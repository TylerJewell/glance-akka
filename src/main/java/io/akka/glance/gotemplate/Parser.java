package io.akka.glance.gotemplate;

import io.akka.glance.gotemplate.Lexer.Kind;
import io.akka.glance.gotemplate.Lexer.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a parse tree from a template's tokens.
 *
 * <p>A parse produces the named template's own tree plus every {@code define} and {@code
 * block} it contains, all of them going into the same set. That is what makes {@code {{
 * template "widget-content" . }}} in a shared base resolve to whichever file was parsed
 * alongside it.
 */
final class Parser {

  private final String name;
  private final List<Token> tokens;
  private final Map<String, Node.ListNode> associated;
  private int pos;

  private Parser(String name, List<Token> tokens, Map<String, Node.ListNode> associated) {
    this.name = name;
    this.tokens = tokens;
    this.associated = associated;
  }

  /**
   * Parses one file's source into {@code associated}, under {@code name} plus a key for
   * every {@code define} and {@code block} found inside it.
   */
  static void parse(String name, String source, Map<String, Node.ListNode> associated) {
    var parser = new Parser(name, Lexer.lex(name, source), associated);
    var body = parser.parseList(null);
    parser.expect(Kind.EOF);
    associated.put(name, body);
  }

  /**
   * Reads nodes until one of {@code stopWords} is the keyword of the next action, or until
   * the end of input when {@code stopWords} is null. The stop action is not consumed.
   */
  private Node.ListNode parseList(List<String> stopWords) {
    var nodes = new ArrayList<Node>();
    while (true) {
      var token = peek();
      if (token.kind() == Kind.EOF) {
        if (stopWords != null) {
          throw new TemplateException(name + ": unexpected end of input, expected " + stopWords);
        }
        return new Node.ListNode(List.copyOf(nodes));
      }
      if (token.kind() == Kind.TEXT) {
        pos++;
        nodes.add(new Node.Text(token.value()));
        continue;
      }
      int delimiterAt = pos;
      expect(Kind.LEFT_DELIM);
      skipSpace();
      var next = peek();
      if (next.kind() == Kind.KEYWORD && stopWords != null && stopWords.contains(next.value())) {
        pos = delimiterAt; // the closing action belongs to the caller
        return new Node.ListNode(List.copyOf(nodes));
      }
      var node = parseAction();
      if (node != null) {
        nodes.add(node);
      }
    }
  }

  /** Parses the body of one action, having already consumed its left delimiter. */
  private Node parseAction() {
    var token = peek();
    int line = token.line();
    if (token.kind() == Kind.KEYWORD) {
      switch (token.value()) {
        case "if" -> {
          pos++;
          return parseBranch(Node.BranchKind.IF, line);
        }
        case "range" -> {
          pos++;
          return parseBranch(Node.BranchKind.RANGE, line);
        }
        case "with" -> {
          pos++;
          return parseBranch(Node.BranchKind.WITH, line);
        }
        case "template" -> {
          pos++;
          return parseTemplateCall(line);
        }
        case "define" -> {
          pos++;
          parseDefine();
          return null;
        }
        case "block" -> {
          pos++;
          return parseBlock(line);
        }
        case "break" -> {
          pos++;
          skipSpace();
          expect(Kind.RIGHT_DELIM);
          return new Node.Break(line);
        }
        case "continue" -> {
          pos++;
          skipSpace();
          expect(Kind.RIGHT_DELIM);
          return new Node.Continue(line);
        }
        default -> throw new TemplateException(
            name + ":" + line + ": unexpected " + token.value());
      }
    }
    var pipe = parsePipe();
    expect(Kind.RIGHT_DELIM);
    return new Node.Action(pipe, line);
  }

  private Node parseBranch(Node.BranchKind kind, int line) {
    var pipe = parsePipe();
    expect(Kind.RIGHT_DELIM);
    var body = parseList(List.of("else", "end"));
    Node.ListNode elseBody = null;
    expect(Kind.LEFT_DELIM);
    skipSpace();
    var keyword = expect(Kind.KEYWORD);
    if (keyword.value().equals("else")) {
      skipSpace();
      if (peek().kind() == Kind.KEYWORD && peek().value().equals("if")) {
        // "else if" is one nested branch standing in for the whole else body
        pos++;
        var nested = parseBranch(Node.BranchKind.IF, peek().line());
        return new Node.Branch(kind, pipe, body, new Node.ListNode(List.of(nested)), line);
      }
      expect(Kind.RIGHT_DELIM);
      elseBody = parseList(List.of("end"));
      expect(Kind.LEFT_DELIM);
      skipSpace();
      keyword = expect(Kind.KEYWORD);
    }
    if (!keyword.value().equals("end")) {
      throw new TemplateException(name + ":" + line + ": expected end, got " + keyword.value());
    }
    skipSpace();
    expect(Kind.RIGHT_DELIM);
    return new Node.Branch(kind, pipe, body, elseBody, line);
  }

  private Node parseTemplateCall(int line) {
    skipSpace();
    var nameToken = next();
    if (nameToken.kind() != Kind.STRING && nameToken.kind() != Kind.RAW_STRING) {
      throw new TemplateException(name + ":" + line + ": template name must be a string");
    }
    String target = unquote(nameToken.value());
    skipSpace();
    Node.Pipe pipe = null;
    if (peek().kind() != Kind.RIGHT_DELIM) {
      pipe = parsePipe();
    }
    expect(Kind.RIGHT_DELIM);
    return new Node.TemplateCall(target, pipe, line);
  }

  private void parseDefine() {
    skipSpace();
    var nameToken = next();
    String target = unquote(nameToken.value());
    skipSpace();
    expect(Kind.RIGHT_DELIM);
    var body = parseList(List.of("end"));
    expect(Kind.LEFT_DELIM);
    skipSpace();
    expect(Kind.KEYWORD);
    skipSpace();
    expect(Kind.RIGHT_DELIM);
    associated.put(target, body);
  }

  /**
   * {@code block} defines a template and calls it in the same action, so the definition it
   * leaves behind is only a default: a file parsed alongside this one that defines the same
   * name replaces it.
   */
  private Node parseBlock(int line) {
    skipSpace();
    var nameToken = next();
    String target = unquote(nameToken.value());
    skipSpace();
    Node.Pipe pipe = null;
    if (peek().kind() != Kind.RIGHT_DELIM) {
      pipe = parsePipe();
    }
    expect(Kind.RIGHT_DELIM);
    var body = parseList(List.of("end"));
    expect(Kind.LEFT_DELIM);
    skipSpace();
    expect(Kind.KEYWORD);
    skipSpace();
    expect(Kind.RIGHT_DELIM);
    associated.putIfAbsent(target, body);
    return new Node.TemplateCall(target, pipe, line);
  }

  private Node.Pipe parsePipe() {
    skipSpace();
    var declarations = new ArrayList<String>();
    boolean assign = false;
    // "$x := " and "$x, $y := " open a pipeline; "$x = " assigns to an existing variable
    int save = pos;
    if (peek().kind() == Kind.VARIABLE) {
      var names = new ArrayList<String>();
      while (peek().kind() == Kind.VARIABLE) {
        names.add(next().value());
        skipSpace();
        if (peek().kind() == Kind.COMMA) {
          pos++;
          skipSpace();
          continue;
        }
        break;
      }
      if (peek().kind() == Kind.COLON_EQUALS) {
        pos++;
        declarations.addAll(names);
      } else if (peek().kind() == Kind.EQUALS) {
        pos++;
        declarations.addAll(names);
        assign = true;
      } else {
        pos = save;
      }
    }
    skipSpace();
    var commands = new ArrayList<Node.Command>();
    while (true) {
      commands.add(parseCommand());
      skipSpace();
      if (peek().kind() == Kind.PIPE) {
        pos++;
        skipSpace();
        continue;
      }
      break;
    }
    return new Node.Pipe(List.copyOf(declarations), assign, List.copyOf(commands));
  }

  private Node.Command parseCommand() {
    var args = new ArrayList<Node.Arg>();
    while (true) {
      skipSpace();
      var token = peek();
      switch (token.kind()) {
        case RIGHT_DELIM, PIPE, RPAREN, COMMA, EOF -> {
          if (args.isEmpty()) {
            throw new TemplateException(name + ":" + token.line() + ": empty command");
          }
          return new Node.Command(List.copyOf(args));
        }
        case DOT -> {
          pos++;
          args.add(new Node.Arg.Dot());
        }
        case FIELD -> {
          pos++;
          // The lexer stops a field at the next dot, so ".A.B" arrives as two items.
          var idents = new ArrayList<>(splitFields(token.value()));
          while (peek().kind() == Kind.FIELD) {
            idents.addAll(splitFields(next().value()));
          }
          args.add(new Node.Arg.Field(List.copyOf(idents)));
        }
        case VARIABLE -> {
          pos++;
          var fields = new ArrayList<String>();
          // The lexer stops a variable at the dot, so a chain arrives as separate items.
          while (peek().kind() == Kind.FIELD) {
            fields.addAll(splitFields(next().value()));
          }
          args.add(new Node.Arg.Variable(token.value(), List.copyOf(fields)));
        }
        case IDENTIFIER -> {
          pos++;
          args.add(new Node.Arg.Identifier(token.value()));
        }
        case STRING -> {
          pos++;
          args.add(new Node.Arg.Constant(unquote(token.value())));
        }
        case RAW_STRING -> {
          pos++;
          args.add(new Node.Arg.Constant(token.value().substring(1, token.value().length() - 1)));
        }
        case CHAR_CONSTANT -> {
          pos++;
          args.add(new Node.Arg.Constant((long) unquoteChar(token.value())));
        }
        case NUMBER -> {
          pos++;
          args.add(new Node.Arg.Constant(parseNumber(token.value())));
        }
        case BOOL -> {
          pos++;
          args.add(new Node.Arg.Constant(Boolean.parseBoolean(token.value())));
        }
        case NIL -> {
          pos++;
          args.add(new Node.Arg.Constant(null));
        }
        case LPAREN -> {
          pos++;
          var nested = parsePipe();
          expect(Kind.RPAREN);
          var fields = new ArrayList<String>();
          while (peek().kind() == Kind.FIELD) {
            fields.addAll(splitFields(next().value()));
          }
          args.add(new Node.Arg.Nested(nested, List.copyOf(fields)));
        }
        default -> throw new TemplateException(
            name + ":" + token.line() + ": unexpected " + token.kind() + " " + token.value());
      }
      // Arguments are separated by space; anything else ends this command.
      if (peek().kind() != Kind.SPACE) {
        return new Node.Command(List.copyOf(args));
      }
    }
  }

  private static List<String> splitFields(String raw) {
    var out = new ArrayList<String>();
    for (var part : raw.split("\\.")) {
      if (!part.isEmpty()) {
        out.add(part);
      }
    }
    return List.copyOf(out);
  }

  private static Object parseNumber(String text) {
    if (text.contains(".") || text.contains("e") || text.contains("E")) {
      return Double.parseDouble(text);
    }
    if (text.startsWith("0x") || text.startsWith("0X")) {
      return Long.parseLong(text.substring(2), 16);
    }
    return Long.parseLong(text);
  }

  static String unquote(String quoted) {
    if (quoted.startsWith("`")) {
      return quoted.substring(1, quoted.length() - 1);
    }
    var out = new StringBuilder();
    for (int i = 1; i < quoted.length() - 1; i++) {
      char c = quoted.charAt(i);
      if (c != '\\') {
        out.append(c);
        continue;
      }
      i++;
      char escape = quoted.charAt(i);
      switch (escape) {
        case 'n' -> out.append('\n');
        case 't' -> out.append('\t');
        case 'r' -> out.append('\r');
        case '\\' -> out.append('\\');
        case '"' -> out.append('"');
        case '\'' -> out.append('\'');
        case 'a' -> out.append('\u0007');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'v' -> out.append('\u000B');
        case '0' -> out.append('\0');
        case 'x' -> {
          out.append((char) Integer.parseInt(quoted.substring(i + 1, i + 3), 16));
          i += 2;
        }
        case 'u' -> {
          out.append((char) Integer.parseInt(quoted.substring(i + 1, i + 5), 16));
          i += 4;
        }
        default -> out.append(escape);
      }
    }
    return out.toString();
  }

  private static char unquoteChar(String quoted) {
    String inner = unquote("\"" + quoted.substring(1, quoted.length() - 1) + "\"");
    return inner.isEmpty() ? 0 : inner.charAt(0);
  }

  private Token peek() {
    return tokens.get(pos);
  }

  private Token next() {
    return tokens.get(pos++);
  }

  private void skipSpace() {
    while (tokens.get(pos).kind() == Kind.SPACE) {
      pos++;
    }
  }

  private Token expect(Kind kind) {
    var token = tokens.get(pos);
    if (token.kind() != kind) {
      throw new TemplateException(
          name + ":" + token.line() + ": expected " + kind + ", got " + token.kind());
    }
    pos++;
    return token;
  }
}
