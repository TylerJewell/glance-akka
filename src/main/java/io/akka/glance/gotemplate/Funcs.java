package io.akka.glance.gotemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The functions a template can call.
 *
 * <p>The built-in set is Go's, less the ones no glance template uses. A template set is given
 * further functions by {@link Template#withFunction}, which is where glance's own
 * {@code globalTemplateFunctions} arrive.
 */
public final class Funcs {

  /** A function callable from a template. */
  public interface Fn {
    Object call(List<Object> args);
  }

  private final Map<String, Fn> functions = new HashMap<>();

  public Funcs() {
    functions.put("len", args -> (long) Values.length(args.getFirst()));
    functions.put("index", args -> Values.index(args.getFirst(), args.subList(1, args.size())));
    functions.put("print", args -> concat(args));
    functions.put("println", args -> concat(args) + "\n");
    functions.put(
        "printf",
        args ->
            GoFormat.sprintf(
                Escapers.stringify(args.getFirst()),
                args.subList(1, args.size()).toArray()));
    functions.put("not", args -> !Values.isTrue(args.getFirst()));
    functions.put("and", Funcs::and);
    functions.put("or", Funcs::or);
    functions.put("eq", Funcs::eq);
    functions.put("ne", args -> !Values.equal(args.get(0), args.get(1)));
    functions.put("lt", args -> Values.compare(args.get(0), args.get(1)) < 0);
    functions.put("le", args -> Values.compare(args.get(0), args.get(1)) <= 0);
    functions.put("gt", args -> Values.compare(args.get(0), args.get(1)) > 0);
    functions.put("ge", args -> Values.compare(args.get(0), args.get(1)) >= 0);
    functions.put("html", args -> Escapers.htmlEscape(Escapers.stringify(args.getFirst())));
    functions.put("urlquery", args -> Escapers.urlQueryEscape(Escapers.stringify(args.getFirst())));
    functions.put("js", args -> Escapers.jsString(args.getFirst()));
    functions.put("slice", Funcs::slice);
    functions.put("call", args -> {
      throw new TemplateException("call is not supported");
    });
  }

  public void put(String name, Fn fn) {
    functions.put(name, fn);
  }

  public Fn get(String name) {
    return functions.get(name);
  }

  public boolean has(String name) {
    return functions.containsKey(name);
  }

  public Funcs copy() {
    var out = new Funcs();
    out.functions.putAll(functions);
    return out;
  }

  private static String concat(List<Object> args) {
    var out = new StringBuilder();
    for (var arg : args) {
      out.append(Escapers.stringify(arg));
    }
    return out.toString();
  }

  /** {@code and} returns its first false argument, or its last. */
  private static Object and(List<Object> args) {
    Object last = true;
    for (var arg : args) {
      last = arg;
      if (!Values.isTrue(arg)) {
        return arg;
      }
    }
    return last;
  }

  /** {@code or} returns its first true argument, or its last. */
  private static Object or(List<Object> args) {
    Object last = false;
    for (var arg : args) {
      last = arg;
      if (Values.isTrue(arg)) {
        return arg;
      }
    }
    return last;
  }

  private static Object eq(List<Object> args) {
    var first = args.getFirst();
    for (int i = 1; i < args.size(); i++) {
      if (Values.equal(first, args.get(i))) {
        return true;
      }
    }
    return false;
  }

  private static Object slice(List<Object> args) {
    var value = args.getFirst();
    int from = args.size() > 1 ? (int) GoFormat.toLong(args.get(1)) : 0;
    if (value instanceof String s) {
      int to = args.size() > 2 ? (int) GoFormat.toLong(args.get(2)) : s.length();
      return s.substring(from, to);
    }
    if (value instanceof List<?> list) {
      int to = args.size() > 2 ? (int) GoFormat.toLong(args.get(2)) : list.size();
      return new ArrayList<Object>(list.subList(from, to));
    }
    throw new TemplateException("cannot slice " + (value == null ? "nil" : value.getClass()));
  }
}
