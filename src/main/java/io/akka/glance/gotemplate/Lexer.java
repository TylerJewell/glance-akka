package io.akka.glance.gotemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a template's source into text runs and the items inside an action.
 *
 * <p>Go's own lexer is a state machine emitting on a channel; this one fills a list, which
 * carries the same sequence. The delimiters are the defaults, which is all glance uses, and
 * the trim markers are handled here rather than in the parser because trimming is defined on
 * the surrounding text rather than on the action.
 */
final class Lexer {

  enum Kind {
    TEXT,
    LEFT_DELIM,
    RIGHT_DELIM,
    IDENTIFIER,
    FIELD,
    VARIABLE,
    STRING,
    RAW_STRING,
    CHAR_CONSTANT,
    NUMBER,
    BOOL,
    NIL,
    DOT,
    PIPE,
    LPAREN,
    RPAREN,
    COLON_EQUALS,
    EQUALS,
    COMMA,
    SPACE,
    KEYWORD,
    EOF
  }

  record Token(Kind kind, String value, int line) {}

  private static final List<String> KEYWORDS =
      List.of(
          "if", "else", "end", "range", "template", "define", "block", "with", "break",
          "continue");

  private final String input;
  private final String name;
  private int pos;
  private int line = 1;
  private boolean pendingTrimRight;
  private final List<Token> tokens = new ArrayList<>();

  private Lexer(String name, String input) {
    this.name = name;
    this.input = input;
  }

  static List<Token> lex(String name, String input) {
    var lexer = new Lexer(name, input);
    lexer.run();
    return lexer.tokens;
  }

  private void run() {
    while (pos < input.length()) {
      int open = input.indexOf("{{", pos);
      if (open < 0) {
        emitText(input.substring(pos), false);
        pos = input.length();
        break;
      }
      boolean trimLeft = open + 2 < input.length() && isTrimMarker(open + 2);
      emitText(input.substring(pos, open), trimLeft);
      pos = open + 2;
      if (trimLeft) {
        pos += 2;
      }
      lexAction();
    }
    if (pendingTrimRight) {
      pendingTrimRight = false;
    }
    tokens.add(new Token(Kind.EOF, "", line));
  }

  /** A minus opens a trim marker only when a space follows it. */
  private boolean isTrimMarker(int at) {
    return input.charAt(at) == '-' && at + 1 < input.length() && isSpace(input.charAt(at + 1));
  }

  private void emitText(String text, boolean trimRight) {
    if (pendingTrimRight) {
      text = stripLeadingSpace(text);
      pendingTrimRight = false;
    }
    if (trimRight) {
      text = stripTrailingSpace(text);
    }
    countLines(text);
    if (!text.isEmpty()) {
      tokens.add(new Token(Kind.TEXT, text, line));
    }
  }

  private void lexAction() {
    tokens.add(new Token(Kind.LEFT_DELIM, "{{", line));
    while (true) {
      if (pos >= input.length()) {
        throw new TemplateException(name + ":" + line + ": unclosed action");
      }
      if (input.startsWith("-}}", pos) && pos > 0 && isSpace(input.charAt(pos - 1))) {
        pos += 3;
        pendingTrimRight = true;
        tokens.add(new Token(Kind.RIGHT_DELIM, "}}", line));
        return;
      }
      if (input.startsWith("}}", pos)) {
        pos += 2;
        tokens.add(new Token(Kind.RIGHT_DELIM, "}}", line));
        return;
      }
      lexInsideAction();
    }
  }

  private void lexInsideAction() {
    char c = input.charAt(pos);
    if (isSpace(c)) {
      while (pos < input.length() && isSpace(input.charAt(pos))) {
        if (input.charAt(pos) == '\n') {
          line++;
        }
        pos++;
      }
      tokens.add(new Token(Kind.SPACE, " ", line));
      return;
    }
    switch (c) {
      case '|' -> {
        pos++;
        tokens.add(new Token(Kind.PIPE, "|", line));
      }
      case '(' -> {
        pos++;
        tokens.add(new Token(Kind.LPAREN, "(", line));
      }
      case ')' -> {
        pos++;
        tokens.add(new Token(Kind.RPAREN, ")", line));
      }
      case '"' -> lexQuoted();
      case '`' -> lexRawQuoted();
      case '\'' -> lexChar();
      case '$' -> lexVariable();
      case '.' -> lexDotOrField();
      case ':' -> {
        if (!input.startsWith(":=", pos)) {
          throw new TemplateException(name + ":" + line + ": expected :=");
        }
        pos += 2;
        tokens.add(new Token(Kind.COLON_EQUALS, ":=", line));
      }
      case '=' -> {
        pos++;
        tokens.add(new Token(Kind.EQUALS, "=", line));
      }
      case ',' -> {
        pos++;
        tokens.add(new Token(Kind.COMMA, ",", line));
      }
      default -> {
        if (c == '+' || c == '-' || Character.isDigit(c)) {
          lexNumber();
        } else if (isAlphaNumeric(c)) {
          lexIdentifier();
        } else {
          throw new TemplateException(name + ":" + line + ": unrecognised character " + c);
        }
      }
    }
  }

  private void lexQuoted() {
    int start = pos;
    pos++;
    while (true) {
      if (pos >= input.length()) {
        throw new TemplateException(name + ":" + line + ": unterminated quoted string");
      }
      char c = input.charAt(pos);
      if (c == '\\') {
        pos += 2;
        continue;
      }
      pos++;
      if (c == '"') {
        break;
      }
      if (c == '\n') {
        throw new TemplateException(name + ":" + line + ": unterminated quoted string");
      }
    }
    tokens.add(new Token(Kind.STRING, input.substring(start, pos), line));
  }

  private void lexRawQuoted() {
    int start = pos;
    pos++;
    while (pos < input.length() && input.charAt(pos) != '`') {
      if (input.charAt(pos) == '\n') {
        line++;
      }
      pos++;
    }
    pos++;
    tokens.add(new Token(Kind.RAW_STRING, input.substring(start, pos), line));
  }

  private void lexChar() {
    int start = pos;
    pos++;
    while (true) {
      if (pos >= input.length()) {
        throw new TemplateException(name + ":" + line + ": unterminated character constant");
      }
      char c = input.charAt(pos);
      if (c == '\\') {
        pos += 2;
        continue;
      }
      pos++;
      if (c == '\'') {
        break;
      }
    }
    tokens.add(new Token(Kind.CHAR_CONSTANT, input.substring(start, pos), line));
  }

  private void lexVariable() {
    int start = pos;
    pos++;
    while (pos < input.length() && isAlphaNumeric(input.charAt(pos))) {
      pos++;
    }
    tokens.add(new Token(Kind.VARIABLE, input.substring(start, pos), line));
  }

  private void lexDotOrField() {
    if (pos + 1 < input.length() && isAlphaNumeric(input.charAt(pos + 1))) {
      int start = pos;
      pos++;
      while (pos < input.length() && isAlphaNumeric(input.charAt(pos))) {
        pos++;
      }
      tokens.add(new Token(Kind.FIELD, input.substring(start, pos), line));
      return;
    }
    pos++;
    tokens.add(new Token(Kind.DOT, ".", line));
  }

  private void lexNumber() {
    int start = pos;
    if (input.charAt(pos) == '+' || input.charAt(pos) == '-') {
      pos++;
    }
    String digits = "0123456789";
    if (input.startsWith("0x", pos) || input.startsWith("0X", pos)) {
      pos += 2;
      digits = "0123456789abcdefABCDEF";
    }
    while (pos < input.length() && digits.indexOf(input.charAt(pos)) >= 0) {
      pos++;
    }
    if (pos < input.length() && input.charAt(pos) == '.') {
      pos++;
      while (pos < input.length() && digits.indexOf(input.charAt(pos)) >= 0) {
        pos++;
      }
    }
    if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
      pos++;
      if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
        pos++;
      }
      while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
        pos++;
      }
    }
    tokens.add(new Token(Kind.NUMBER, input.substring(start, pos), line));
  }

  private void lexIdentifier() {
    int start = pos;
    while (pos < input.length() && isAlphaNumeric(input.charAt(pos))) {
      pos++;
    }
    String word = input.substring(start, pos);
    Kind kind;
    if (KEYWORDS.contains(word)) {
      kind = Kind.KEYWORD;
    } else if (word.equals("true") || word.equals("false")) {
      kind = Kind.BOOL;
    } else if (word.equals("nil")) {
      kind = Kind.NIL;
    } else {
      kind = Kind.IDENTIFIER;
    }
    tokens.add(new Token(kind, word, line));
  }

  private void countLines(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
  }

  private static boolean isAlphaNumeric(char c) {
    return c == '_' || Character.isLetterOrDigit(c);
  }

  static String stripTrailingSpace(String s) {
    int end = s.length();
    while (end > 0 && isSpace(s.charAt(end - 1))) {
      end--;
    }
    return s.substring(0, end);
  }

  static String stripLeadingSpace(String s) {
    int start = 0;
    while (start < s.length() && isSpace(s.charAt(start))) {
      start++;
    }
    return s.substring(start);
  }
}
