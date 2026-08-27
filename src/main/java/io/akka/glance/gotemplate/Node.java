package io.akka.glance.gotemplate;

import java.util.List;

/**
 * The parse tree.
 *
 * <p>One variant per node kind Go's {@code text/template/parse} produces, less the ones no
 * glance template uses. {@link Command} is a pipeline stage: the first argument is what is
 * called, the rest are its arguments, and a stage after the first receives the previous
 * stage's value appended to its own arguments.
 */
public sealed interface Node {

  /** Literal text between actions. */
  record Text(String text) implements Node {}

  /** A sequence of nodes; the body of a template, a branch, or a loop. */
  record ListNode(List<Node> nodes) implements Node {}

  /** {@code {{ pipeline }}} — evaluate and write. */
  record Action(Pipe pipe, int line) implements Node {}

  /** {@code {{ if }}}, {@code {{ range }}} and {@code {{ with }}} share this shape. */
  record Branch(BranchKind kind, Pipe pipe, ListNode body, ListNode elseBody, int line)
      implements Node {}

  enum BranchKind {
    IF,
    RANGE,
    WITH
  }

  /** {@code {{ template "name" pipeline }}}. A block is parsed into one of these. */
  record TemplateCall(String name, Pipe pipe, int line) implements Node {}

  /** {@code {{ break }}} inside a range. */
  record Break(int line) implements Node {}

  /** {@code {{ continue }}} inside a range. */
  record Continue(int line) implements Node {}

  /** A pipeline: declarations, then one or more commands separated by {@code |}. */
  record Pipe(List<String> declarations, boolean assign, List<Command> commands) {}

  /** One stage of a pipeline. */
  record Command(List<Arg> args) {}

  /** An argument to a command. */
  sealed interface Arg {

    /** {@code .} — the value the template is currently executing against. */
    record Dot() implements Arg {}

    /** {@code .A.B.C}, or a chain hung off another argument. */
    record Field(List<String> idents) implements Arg {}

    /** {@code $x}, {@code $x.A.B}, or the bare {@code $}. */
    record Variable(String name, List<String> fields) implements Arg {}

    /** A function name looked up in the function map. */
    record Identifier(String name) implements Arg {}

    /** A string, number, boolean or nil literal. */
    record Constant(Object value) implements Arg {}

    /** A parenthesised pipeline, with any field chain applied to its result. */
    record Nested(Pipe pipe, List<String> fields) implements Arg {}
  }
}
