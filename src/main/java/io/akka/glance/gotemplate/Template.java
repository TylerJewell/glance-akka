package io.akka.glance.gotemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A parsed template and everything parsed alongside it.
 *
 * <p>The shape mirrors {@code mustParseTemplate(primary, dependencies...)}: one file is the
 * one executed, and the rest are parsed into the same set so that a {@code define} in any of
 * them answers a {@code template} call in any other. That is how one {@code widget-base.html}
 * serves every widget while each widget's own file supplies {@code widget-content}.
 */
public final class Template {

  private final String name;
  private final Map<String, Node.ListNode> associated = new LinkedHashMap<>();
  private final Funcs funcs;

  private Template(String name, Funcs funcs) {
    this.name = name;
    this.funcs = funcs;
  }

  /** Parses {@code primary} first, then each dependency, into one set. */
  public static Template parse(Funcs funcs, String primaryName, String primarySource) {
    var template = new Template(primaryName, funcs);
    Parser.parse(primaryName, primarySource, template.associated);
    return template;
  }

  public Template associate(String name, String source) {
    Parser.parse(name, source, associated);
    return this;
  }

  public String execute(Object data) {
    var body = associated.get(name);
    if (body == null) {
      throw new TemplateException("no template named " + name);
    }
    return new Executor(name, associated, funcs).run(body, data);
  }

  /** Executes one of the associated definitions rather than the primary file. */
  public String executeTemplate(String which, Object data) {
    var body = associated.get(which);
    if (body == null) {
      throw new TemplateException("no template named " + which);
    }
    return new Executor(which, associated, funcs).run(body, data);
  }

  public boolean defines(String which) {
    return associated.containsKey(which);
  }

  public String name() {
    return name;
  }

  /** Adds one function to a copy of this template's function map. */
  public Template withFunction(String functionName, Funcs.Fn fn) {
    funcs.put(functionName, fn);
    return this;
  }
}
