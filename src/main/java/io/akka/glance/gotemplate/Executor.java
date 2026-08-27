package io.akka.glance.gotemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Walks a parse tree against a value, writing what each node produces. */
final class Executor {

  /** Thrown by {@code {{ break }}} and caught by the enclosing range. */
  private static final class BreakSignal extends RuntimeException {
    BreakSignal() {
      super(null, null, false, false);
    }
  }

  /** Thrown by {@code {{ continue }}} and caught by the enclosing range's body. */
  private static final class ContinueSignal extends RuntimeException {
    ContinueSignal() {
      super(null, null, false, false);
    }
  }

  private final Map<String, Node.ListNode> templates;
  private final Funcs funcs;
  private final StringBuilder out = new StringBuilder(4096);
  private final Escaper escaper = new Escaper();
  private final List<Map<String, Object>> scopes = new ArrayList<>();
  private final String name;
  private int depth;

  Executor(String name, Map<String, Node.ListNode> templates, Funcs funcs) {
    this.name = name;
    this.templates = templates;
    this.funcs = funcs;
  }

  String run(Node.ListNode body, Object data) {
    scopes.add(new HashMap<>(Map.of("$", data == null ? NIL : data)));
    walk(body, data);
    return out.toString();
  }

  /** A placeholder for a null bound to {@code $}, which a map may not hold. */
  private static final Object NIL = new Object();

  private void walk(Node.ListNode body, Object dot) {
    for (var node : body.nodes()) {
      switch (node) {
        case Node.Text text -> out.append(escaper.write(text.text()));
        case Node.Action action -> {
          var value = evaluate(action.pipe(), dot);
          if (!action.pipe().declarations().isEmpty()) {
            break;
          }
          out.append(escaper.escape(value));
        }
        case Node.Branch branch -> branch(branch, dot);
        case Node.TemplateCall call -> templateCall(call, dot);
        case Node.Break ignored -> throw new BreakSignal();
        case Node.Continue ignored -> throw new ContinueSignal();
        case Node.ListNode nested -> walk(nested, dot);
      }
    }
  }

  private void branch(Node.Branch branch, Object dot) {
    switch (branch.kind()) {
      case IF -> {
        var value = evaluate(branch.pipe(), dot);
        if (Values.isTrue(value)) {
          walk(branch.body(), dot);
        } else if (branch.elseBody() != null) {
          walk(branch.elseBody(), dot);
        }
      }
      case WITH -> {
        var value = evaluate(branch.pipe(), dot);
        if (Values.isTrue(value)) {
          pushScope();
          bindDeclarations(branch.pipe(), List.of(value));
          walk(branch.body(), value);
          popScope();
        } else if (branch.elseBody() != null) {
          walk(branch.elseBody(), dot);
        }
      }
      case RANGE -> range(branch, dot);
    }
  }

  private void range(Node.Branch branch, Object dot) {
    var value = evaluate(branch.pipe(), dot);
    var entries = Values.entries(value);
    if (entries.isEmpty()) {
      if (branch.elseBody() != null) {
        walk(branch.elseBody(), dot);
      }
      return;
    }
    try {
      for (var entry : entries) {
        pushScope();
        bindDeclarations(branch.pipe(), List.of(entry.key(), entry.value()));
        try {
          walk(branch.body(), entry.value());
        } catch (ContinueSignal ignored) {
          // the next item
        } finally {
          popScope();
        }
      }
    } catch (BreakSignal ignored) {
      // out of the loop
    }
  }

  /**
   * A range or with declares one or two variables; a range declaring one binds the element
   * rather than the index, which is the opposite way round from the pair.
   */
  private void bindDeclarations(Node.Pipe pipe, List<Object> available) {
    var names = pipe.declarations();
    if (names.isEmpty()) {
      return;
    }
    if (names.size() == 1) {
      set(names.getFirst(), available.size() == 1 ? available.getFirst() : available.get(1));
      return;
    }
    for (int i = 0; i < names.size() && i < available.size(); i++) {
      set(names.get(i), available.get(i));
    }
  }

  private void templateCall(Node.TemplateCall call, Object dot) {
    var body = templates.get(call.name());
    if (body == null) {
      throw new TemplateException(name + ":" + call.line() + ": no template " + call.name());
    }
    if (depth > 100) {
      throw new TemplateException(name + ": template recursion too deep at " + call.name());
    }
    Object argument = call.pipe() == null ? null : evaluate(call.pipe(), dot);
    depth++;
    pushScope();
    set("$", argument == null ? NIL : argument);
    walk(body, argument);
    popScope();
    depth--;
  }

  private Object evaluate(Node.Pipe pipe, Object dot) {
    Object value = null;
    boolean first = true;
    for (var command : pipe.commands()) {
      value = command(command, dot, first ? null : value, !first);
      first = false;
    }
    if (!pipe.declarations().isEmpty()) {
      if (pipe.assign()) {
        assign(pipe.declarations().getFirst(), value);
      } else {
        set(pipe.declarations().getFirst(), value);
      }
    }
    return value;
  }

  private Object command(Node.Command command, Object dot, Object piped, boolean hasPiped) {
    var args = command.args();
    var head = args.getFirst();
    var rest = new ArrayList<Object>();
    for (int i = 1; i < args.size(); i++) {
      rest.add(argument(args.get(i), dot));
    }
    if (hasPiped) {
      rest.add(piped);
    }
    return switch (head) {
      case Node.Arg.Identifier identifier -> {
        var fn = funcs.get(identifier.name());
        if (fn == null) {
          throw new TemplateException(name + ": function " + identifier.name() + " not defined");
        }
        yield fn.call(rest);
      }
      case Node.Arg.Field field -> chain(dot, field.idents(), rest);
      case Node.Arg.Variable variable -> {
        var base = lookup(variable.name());
        yield chain(base, variable.fields(), rest);
      }
      case Node.Arg.Dot ignored -> {
        if (!rest.isEmpty()) {
          throw new TemplateException(name + ": cannot call .");
        }
        yield dot;
      }
      case Node.Arg.Nested nested -> {
        var value = evaluate(nested.pipe(), dot);
        yield chain(value, nested.fields(), rest);
      }
      case Node.Arg.Constant constant -> {
        if (!rest.isEmpty()) {
          throw new TemplateException(name + ": cannot call a literal");
        }
        yield constant.value();
      }
    };
  }

  /** Follows a field chain, passing any arguments to the last name in it. */
  private Object chain(Object base, List<String> fields, List<Object> args) {
    Object current = base;
    for (int i = 0; i < fields.size(); i++) {
      boolean last = i == fields.size() - 1;
      current = Values.resolve(current, fields.get(i), last ? args : List.of());
    }
    if (fields.isEmpty() && !args.isEmpty()) {
      throw new TemplateException(name + ": cannot call a value with no name");
    }
    return current;
  }

  private Object argument(Node.Arg arg, Object dot) {
    return switch (arg) {
      case Node.Arg.Dot ignored -> dot;
      case Node.Arg.Constant constant -> constant.value();
      case Node.Arg.Field field -> chain(dot, field.idents(), List.of());
      case Node.Arg.Variable variable -> chain(lookup(variable.name()), variable.fields(), List.of());
      case Node.Arg.Nested nested -> chain(evaluate(nested.pipe(), dot), nested.fields(), List.of());
      case Node.Arg.Identifier identifier -> {
        var fn = funcs.get(identifier.name());
        if (fn == null) {
          throw new TemplateException(name + ": function " + identifier.name() + " not defined");
        }
        yield fn.call(List.of());
      }
    };
  }

  private void pushScope() {
    scopes.add(new HashMap<>());
  }

  private void popScope() {
    scopes.removeLast();
  }

  private void set(String variable, Object value) {
    scopes.getLast().put(variable, value == null ? NIL : value);
  }

  private void assign(String variable, Object value) {
    for (int i = scopes.size() - 1; i >= 0; i--) {
      if (scopes.get(i).containsKey(variable)) {
        scopes.get(i).put(variable, value == null ? NIL : value);
        return;
      }
    }
    set(variable, value);
  }

  private Object lookup(String variable) {
    for (int i = scopes.size() - 1; i >= 0; i--) {
      var scope = scopes.get(i);
      if (scope.containsKey(variable)) {
        var value = scope.get(variable);
        return value == NIL ? null : value;
      }
    }
    throw new TemplateException(name + ": undefined variable " + variable);
  }
}
