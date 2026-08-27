package io.akka.glance.gotemplate;

import java.util.List;
import java.util.Locale;

/**
 * The context an action lands in, and the escaping that context asks for.
 *
 * <p>Go decides this when it compiles a template, by walking the literal text and rewriting
 * each action to call the escapers its context needs. This walks the same literal text while
 * the template runs, which reaches the same answer for a template whose branches all leave
 * the same context — the only kind Go accepts.
 *
 * <p>Only literal text moves the context. What an action writes does not, which is Go's rule
 * too: a URL split across an action and a literal {@code ?} is in its query part after the
 * literal, not after whatever the action produced.
 */
final class Escaper {

  enum State {
    TEXT,
    TAG_NAME,
    TAG,
    ATTR_NAME,
    AFTER_NAME,
    BEFORE_VALUE,
    ATTR,
    HTML_COMMENT,
    RCDATA,
    SCRIPT,
    SCRIPT_DQ_STRING,
    SCRIPT_SQ_STRING,
    SCRIPT_TEMPLATE_STRING,
    SCRIPT_LINE_COMMENT,
    SCRIPT_BLOCK_COMMENT,
    CSS,
    CSS_DQ_STRING,
    CSS_SQ_STRING,
    CSS_LINE_COMMENT,
    CSS_BLOCK_COMMENT,
    CSS_URL
  }

  /** What kind of thing an attribute's value is, which decides how a value in it is escaped. */
  enum AttrKind {
    NONE,
    PLAIN,
    URL,
    SRCSET,
    SCRIPT,
    STYLE
  }

  /** Where inside a URL the context sits, which Go tracks separately from the state. */
  enum UrlPart {
    NONE,
    PRE_QUERY,
    QUERY_OR_FRAGMENT
  }

  private static final List<String> URL_ATTRIBUTES =
      List.of(
          "action", "archive", "background", "cite", "classid", "codebase", "data", "formaction",
          "href", "icon", "longdesc", "manifest", "poster", "profile", "src", "usemap", "xmlns");

  private static final List<String> RCDATA_ELEMENTS = List.of("textarea", "title");

  State state = State.TEXT;
  AttrKind attr = AttrKind.NONE;
  UrlPart urlPart = UrlPart.NONE;
  /** The quote closing the current attribute value: {@code "}, {@code '} or 0 when bare. */
  char delimiter;

  private final StringBuilder pending = new StringBuilder();
  private String element = "";

  /**
   * How much of the block comment being read has been seen, so that its own opener cannot
   * close it. Counted rather than indexed because a comment may span the text on either side
   * of an action, which arrives as two separate runs.
   */
  private int commentChars;

  /** The character before the one being read, wherever it came from. */
  private char previousChar;

  /** Advances the context over one run of literal template text. */
  void advance(String text) {
    for (int i = 0; i < text.length(); i++) {
      step(text.charAt(i), text, i);
    }
  }

  /**
   * Advances over one run of literal text and returns what of it reaches the page. An HTML
   * comment does not: Go drops comments while it escapes, so a template's comments are not in
   * its output.
   */
  String write(String text) {
    var out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      boolean wasComment = isComment(state);
      State before = state;
      step(c, text, i);
      boolean nowComment = isComment(state);
      if (!wasComment && nowComment) {
        // A comment in a script or a style sheet leaves a space behind, so that the tokens
        // on either side of it stay apart; one in the markup leaves nothing.
        if (state != State.HTML_COMMENT) {
          out.append(' ');
        }
        continue;
      }
      if (wasComment && nowComment) {
        continue;
      }
      if (wasComment) {
        // The character that ends a line comment is the line's own terminator and is kept;
        // the one that ends a block or markup comment is part of it and is not.
        if (before == State.SCRIPT_LINE_COMMENT || before == State.CSS_LINE_COMMENT) {
          out.append(c);
        }
        continue;
      }
      out.append(c);
    }
    return out.toString();
  }

  private static boolean isComment(State state) {
    return state == State.HTML_COMMENT
        || state == State.SCRIPT_LINE_COMMENT
        || state == State.SCRIPT_BLOCK_COMMENT
        || state == State.CSS_LINE_COMMENT
        || state == State.CSS_BLOCK_COMMENT;
  }

  private void step(char c, String text, int index) {
    if (isComment(state)) {
      commentChars++;
    }
    try {
      stepInner(c, text, index);
    } finally {
      previousChar = c;
    }
  }

  private void stepInner(char c, String text, int index) {
    switch (state) {
      case TEXT -> {
        if (c == '<') {
          if (text.startsWith("<!--", index)) {
            state = State.HTML_COMMENT;
          } else {
            state = State.TAG_NAME;
            pending.setLength(0);
          }
        }
      }
      case TAG_NAME -> {
        if (isSpace(c) || c == '>' || c == '/') {
          element = pending.toString().toLowerCase(Locale.ROOT);
          if (element.startsWith("/")) {
            element = "";
          }
          state = c == '>' ? enterElement() : State.TAG;
        } else {
          pending.append(c);
        }
      }
      case TAG -> {
        if (c == '>') {
          state = enterElement();
        } else if (!isSpace(c) && c != '/') {
          state = State.ATTR_NAME;
          pending.setLength(0);
          pending.append(c);
        }
      }
      case ATTR_NAME -> {
        if (c == '=') {
          attr = classify(pending.toString());
          state = State.BEFORE_VALUE;
        } else if (isSpace(c)) {
          attr = classify(pending.toString());
          state = State.AFTER_NAME;
        } else if (c == '>') {
          attr = AttrKind.NONE;
          state = enterElement();
        } else {
          pending.append(c);
        }
      }
      case AFTER_NAME -> {
        if (c == '=') {
          state = State.BEFORE_VALUE;
        } else if (c == '>') {
          attr = AttrKind.NONE;
          state = enterElement();
        } else if (!isSpace(c)) {
          state = State.ATTR_NAME;
          attr = AttrKind.NONE;
          pending.setLength(0);
          pending.append(c);
        }
      }
      case BEFORE_VALUE -> {
        if (isSpace(c)) {
          return;
        }
        if (c == '"' || c == '\'') {
          delimiter = c;
          state = State.ATTR;
          urlPart = UrlPart.NONE;
        } else if (c == '>') {
          attr = AttrKind.NONE;
          state = enterElement();
        } else {
          delimiter = 0;
          state = State.ATTR;
          urlPart = UrlPart.NONE;
          stepInsideAttr(c);
        }
      }
      case ATTR -> {
        if ((delimiter != 0 && c == delimiter) || (delimiter == 0 && isSpace(c))) {
          state = State.TAG;
          attr = AttrKind.NONE;
          urlPart = UrlPart.NONE;
        } else if (delimiter == 0 && c == '>') {
          attr = AttrKind.NONE;
          urlPart = UrlPart.NONE;
          state = enterElement();
        } else {
          stepInsideAttr(c);
        }
      }
      case HTML_COMMENT -> {
        if (c == '>' && index >= 2 && text.startsWith("--", index - 2)) {
          state = State.TEXT;
        }
      }
      case RCDATA -> {
        if (c == '<' && text.regionMatches(true, index, "</" + element, 0, element.length() + 2)) {
          state = State.TAG_NAME;
          pending.setLength(0);
        }
      }
      case SCRIPT -> stepScript(c, text, index);
      case SCRIPT_DQ_STRING -> {
        if (c == '\\') {
          skipNext = true;
        } else if (c == '"' && !consumeSkip()) {
          state = State.SCRIPT;
        } else {
          consumeSkip();
        }
      }
      case SCRIPT_SQ_STRING -> {
        if (c == '\\') {
          skipNext = true;
        } else if (c == '\'' && !consumeSkip()) {
          state = State.SCRIPT;
        } else {
          consumeSkip();
        }
      }
      case SCRIPT_TEMPLATE_STRING -> {
        if (c == '\\') {
          skipNext = true;
        } else if (c == '`' && !consumeSkip()) {
          state = State.SCRIPT;
        } else {
          consumeSkip();
        }
      }
      case SCRIPT_LINE_COMMENT -> {
        if (c == '\n') {
          state = State.SCRIPT;
        }
      }
      case SCRIPT_BLOCK_COMMENT -> {
        if (c == '/' && commentChars >= 4 && previousChar == '*') {
          state = State.SCRIPT;
        }
      }
      case CSS -> stepCss(c, text, index);
      case CSS_DQ_STRING -> {
        if (c == '\\') {
          skipNext = true;
        } else if (c == '"' && !consumeSkip()) {
          state = State.CSS;
        } else {
          consumeSkip();
        }
      }
      case CSS_SQ_STRING -> {
        if (c == '\\') {
          skipNext = true;
        } else if (c == '\'' && !consumeSkip()) {
          state = State.CSS;
        } else {
          consumeSkip();
        }
      }
      case CSS_LINE_COMMENT -> {
        if (c == '\n') {
          state = State.CSS;
        }
      }
      case CSS_BLOCK_COMMENT -> {
        if (c == '/' && commentChars >= 4 && previousChar == '*') {
          state = State.CSS;
        }
      }
      case CSS_URL -> {
        if (c == ')') {
          state = State.CSS;
          urlPart = UrlPart.NONE;
        } else if (c == '?' || c == '#') {
          urlPart = UrlPart.QUERY_OR_FRAGMENT;
        }
      }
    }
  }

  private boolean skipNext;

  private boolean consumeSkip() {
    boolean was = skipNext;
    skipNext = false;
    return was;
  }

  private void stepInsideAttr(char c) {
    if (attr == AttrKind.URL || attr == AttrKind.SRCSET) {
      if (urlPart == UrlPart.NONE) {
        urlPart = UrlPart.PRE_QUERY;
      }
      if (c == '?' || c == '#') {
        urlPart = UrlPart.QUERY_OR_FRAGMENT;
      }
    }
  }

  private void stepScript(char c, String text, int index) {
    if (c == '"') {
      state = State.SCRIPT_DQ_STRING;
    } else if (c == '\'') {
      state = State.SCRIPT_SQ_STRING;
    } else if (c == '`') {
      state = State.SCRIPT_TEMPLATE_STRING;
    } else if (c == '/' && index + 1 < text.length() && text.charAt(index + 1) == '/') {
      state = State.SCRIPT_LINE_COMMENT;
    } else if (c == '/' && index + 1 < text.length() && text.charAt(index + 1) == '*') {
      state = State.SCRIPT_BLOCK_COMMENT;
      commentChars = 1;
    } else if (c == '<' && text.regionMatches(true, index, "</script", 0, 8)) {
      state = State.TAG_NAME;
      pending.setLength(0);
    }
  }

  private void stepCss(char c, String text, int index) {
    if (c == '"') {
      state = State.CSS_DQ_STRING;
    } else if (c == '\'') {
      state = State.CSS_SQ_STRING;
    } else if (c == '/' && index + 1 < text.length() && text.charAt(index + 1) == '/') {
      state = State.CSS_LINE_COMMENT;
    } else if (c == '/' && index + 1 < text.length() && text.charAt(index + 1) == '*') {
      state = State.CSS_BLOCK_COMMENT;
      commentChars = 1;
    } else if (c == '(' && index >= 3 && text.regionMatches(true, index - 3, "url(", 0, 4)) {
      state = State.CSS_URL;
      urlPart = UrlPart.NONE;
    } else if (c == '<' && text.regionMatches(true, index, "</style", 0, 7)) {
      state = State.TAG_NAME;
      pending.setLength(0);
    }
  }

  private State enterElement() {
    if (element.equals("script")) {
      return State.SCRIPT;
    }
    if (element.equals("style")) {
      return State.CSS;
    }
    if (RCDATA_ELEMENTS.contains(element)) {
      return State.RCDATA;
    }
    return State.TEXT;
  }

  private static AttrKind classify(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.startsWith("on")) {
      return AttrKind.SCRIPT;
    }
    if (lower.equals("style")) {
      return AttrKind.STYLE;
    }
    if (lower.equals("srcset")) {
      return AttrKind.SRCSET;
    }
    if (URL_ATTRIBUTES.contains(lower) || lower.startsWith("xlink:") && lower.endsWith("href")) {
      return AttrKind.URL;
    }
    return AttrKind.PLAIN;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
  }

  /** Escapes one action's value for wherever the context currently sits. */
  String escape(Object value) {
    return switch (state) {
      case CSS -> Escapers.cssValueFilter(value);
      case CSS_DQ_STRING, CSS_SQ_STRING -> Escapers.cssEscape(value);
      case CSS_URL -> Escapers.htmlEscape(url(value));
      case SCRIPT -> Escapers.jsValue(value);
      case SCRIPT_DQ_STRING, SCRIPT_SQ_STRING, SCRIPT_TEMPLATE_STRING -> Escapers.jsString(value);
      case SCRIPT_LINE_COMMENT, SCRIPT_BLOCK_COMMENT, CSS_LINE_COMMENT, CSS_BLOCK_COMMENT,
              HTML_COMMENT ->
          "";
      case TAG, ATTR_NAME, AFTER_NAME -> Escapers.htmlName(value);
      case RCDATA -> Escapers.rcdataEscape(value);
      case ATTR, BEFORE_VALUE -> delimit(escapeInAttribute(value));
      default -> Escapers.htmlEscape(value);
    };
  }

  /** An unquoted attribute value also has to keep whatever ends it out of the value. */
  private String delimit(String escaped) {
    return delimiter == 0 ? Escapers.noSpaceEscape(escaped) : escaped;
  }

  private String escapeInAttribute(Object value) {
    return switch (attr) {
      case URL, SRCSET -> Escapers.htmlEscape(url(value));
      case SCRIPT -> Escapers.htmlEscape(Escapers.jsValue(value));
      case STYLE -> Escapers.htmlEscape(Escapers.cssValueFilter(value));
      default -> Escapers.attrEscape(value);
    };
  }

  private String url(Object value) {
    return switch (urlPart) {
      case NONE -> Escapers.urlNormalize(Escapers.urlFilter(value));
      case PRE_QUERY -> Escapers.urlNormalize(Escapers.stringify(value));
      case QUERY_OR_FRAGMENT -> Escapers.urlQueryEscape(Escapers.stringify(value));
    };
  }
}
