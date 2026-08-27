package io.akka.glance.gotemplate;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * The escaping functions Go's {@code html/template} inserts, one method per escaper.
 *
 * <p>Each takes the raw value rather than a string, because whether escaping happens at all
 * depends on the value's type: a {@link Safe} of the right kind for the context is written
 * through untouched, which is the whole point of those types.
 */
final class Escapers {

  /** What Go substitutes for a URL whose scheme is not one it will emit. */
  private static final String FILTERED_URL = "#ZgotmplZ";

  /** The characters a CSS value may not carry, whatever else it says. */
  private static final String CSS_FORBIDDEN = "\0\"'()/;@[\\]`{}<>";

  private static final char REPLACEMENT = '\uFFFD';

  private Escapers() {}

  /** {@code stringify} — the value as text, with any safe wrapper unwrapped. */
  static String stringify(Object value) {
    if (value instanceof Safe safe) {
      return safe.value();
    }
    if (value == null) {
      return "";
    }
    return GoFormat.value(value);
  }

  /** {@code _html_template_htmlescaper} — markup passes through in element content. */
  static String htmlEscape(Object value) {
    if (value instanceof Safe.Html html) {
      return html.value();
    }
    return htmlEscape(stringify(value));
  }

  /**
   * {@code _html_template_attrescaper} — markup inside an attribute keeps its text and loses
   * its elements, and an ampersand already there is left alone.
   */
  static String attrEscape(Object value) {
    if (value instanceof Safe.Html html) {
      return replace(stripTags(html.value()), true);
    }
    return replace(stringify(value), false);
  }

  static String htmlEscape(String s) {
    return replace(s, false);
  }

  private static String replace(String s, boolean normalizeAmpersand) {
    var out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '&' -> out.append(normalizeAmpersand ? "&" : "&amp;");
        case '\'' -> out.append("&#39;");
        case '"' -> out.append("&#34;");
        case '+' -> out.append("&#43;");
        case '\0' -> out.append(REPLACEMENT);
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  /** What {@code stripTags} leaves of a fragment: its text, without its elements. */
  private static String stripTags(String s) {
    var out = new StringBuilder(s.length());
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c != '<') {
        out.append(c);
        i++;
        continue;
      }
      if (s.startsWith("<!--", i)) {
        int close = s.indexOf("-->", i + 4);
        i = close < 0 ? s.length() : close + 3;
        continue;
      }
      int close = s.indexOf('>', i);
      i = close < 0 ? s.length() : close + 1;
    }
    return out.toString();
  }

  /** {@code _html_template_rcdataescaper}. */
  static String rcdataEscape(Object value) {
    return replace(stringify(value), false);
  }

  /** {@code _html_template_nospaceescaper} — for a value ending an unquoted attribute. */
  static String noSpaceEscape(String s) {
    var out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\t' -> out.append("&#9;");
        case '\n' -> out.append("&#10;");
        case '\u000B' -> out.append("&#11;");
        case '\f' -> out.append("&#12;");
        case '\r' -> out.append("&#13;");
        case ' ' -> out.append("&#32;");
        case '=' -> out.append("&#61;");
        case '`' -> out.append("&#96;");
        case REPLACEMENT -> out.append("&#xfffd;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  /** {@code _html_template_htmlnamefilter}. */
  static String htmlName(Object value) {
    if (value instanceof Safe.Attr attr) {
      return attr.value();
    }
    String s = stringify(value);
    if (s.isEmpty()) {
      return FILTERED_URL;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean allowed =
          (c >= '0' && c <= '9')
              || (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || c == '-'
              || c == '_'
              || c == ':';
      if (!allowed) {
        return FILTERED_URL;
      }
    }
    String lower = s.toLowerCase(Locale.ROOT);
    if (lower.startsWith("on") || lower.equals("srcdoc")) {
      return FILTERED_URL;
    }
    return lower;
  }

  /** {@code _html_template_urlfilter} — a scheme this will not emit becomes the marker. */
  static String urlFilter(Object value) {
    if (value instanceof Safe.Url url) {
      return url.value();
    }
    String s = stringify(value);
    int colon = s.indexOf(':');
    if (colon >= 0 && s.lastIndexOf('/', colon) < 0) {
      String scheme = s.substring(0, colon).toLowerCase(Locale.ROOT);
      if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")) {
        return FILTERED_URL;
      }
    }
    return s;
  }

  /**
   * {@code _html_template_urlnormalizer} — percent-escapes what a URL may not carry, and
   * leaves an escape that is already there alone.
   */
  static String urlNormalize(String s) {
    return urlProcess(s, true);
  }

  /** {@code _html_template_urlescaper} — escapes for one query or fragment component. */
  static String urlQueryEscape(String s) {
    return urlProcess(s, false);
  }

  private static String urlProcess(String s, boolean normalize) {
    if (s.equals(FILTERED_URL)) {
      return s;
    }
    var bytes = s.getBytes(StandardCharsets.UTF_8);
    var out = new StringBuilder(bytes.length);
    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 0xFF;
      char c = (char) b;
      if (normalize
          && c == '%'
          && i + 2 < bytes.length
          && isHex(bytes[i + 1])
          && isHex(bytes[i + 2])) {
        out.append('%').append((char) bytes[i + 1]).append((char) bytes[i + 2]);
        i += 2;
        continue;
      }
      if (isUrlSafe(c, normalize)) {
        out.append(c);
      } else {
        out.append('%').append(HEX[b >>> 4]).append(HEX[b & 0xF]);
      }
    }
    return out.toString();
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static boolean isHex(byte b) {
    char c = (char) (b & 0xFF);
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean isUrlSafe(char c, boolean normalize) {
    if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
      return true;
    }
    return switch (c) {
      case '-', '.', '_', '~' -> true;
      case '!', '#', '$', '&', '*', '+', ',', '/', ':', ';', '=', '?', '@', '[', ']', '\'', '(',
              ')' ->
          normalize;
      default -> false;
    };
  }

  /** {@code _html_template_cssvaluefilter}. */
  static String cssValueFilter(Object value) {
    if (value instanceof Safe.Css css) {
      return css.value();
    }
    String s = stringify(value);
    if (s.isEmpty()) {
      return "";
    }
    for (int i = 0; i < s.length(); i++) {
      if (CSS_FORBIDDEN.indexOf(s.charAt(i)) >= 0) {
        return "ZgotmplZ";
      }
    }
    String lower = s.toLowerCase(Locale.ROOT);
    if (lower.contains("expression") || lower.contains("mozbinding")) {
      return "ZgotmplZ";
    }
    return s;
  }

  /** {@code _html_template_cssescaper}. */
  static String cssEscape(Object value) {
    String s = stringify(value);
    var out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean safe =
          (c >= '0' && c <= '9')
              || (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || c == '-'
              || c == '_'
              || c == '.'
              || c == ' '
              || c == '#'
              || c == '%'
              || c == ',';
      if (safe) {
        out.append(c);
      } else {
        out.append('\\').append(Integer.toHexString(c)).append(' ');
      }
    }
    return out.toString();
  }

  /** {@code _html_template_jsvalescaper} — the value as a JavaScript expression. */
  static String jsValue(Object value) {
    if (value instanceof Safe.Js js) {
      return js.value();
    }
    Object raw = value instanceof Safe safe ? safe.value() : value;
    if (raw == null) {
      return " null ";
    }
    if (raw instanceof Boolean b) {
      return b ? " true " : " false ";
    }
    if (raw instanceof Number n) {
      return GoFormat.value(n);
    }
    var out = new StringBuilder();
    json(raw, out);
    return out.toString();
  }

  /** {@code encoding/json} for the shapes that reach a script, with Go's own escaping. */
  private static void json(Object value, StringBuilder out) {
    switch (value) {
      case null -> out.append("null");
      case Boolean b -> out.append(b);
      case Number n -> out.append(GoFormat.value(n));
      case Collection<?> c -> {
        out.append('[');
        boolean first = true;
        for (var item : c) {
          if (!first) {
            out.append(',');
          }
          first = false;
          json(item, out);
        }
        out.append(']');
      }
      case Map<?, ?> m -> {
        out.append('{');
        boolean first = true;
        for (var entry : m.entrySet()) {
          if (!first) {
            out.append(',');
          }
          first = false;
          json(String.valueOf(entry.getKey()), out);
          out.append(':');
          json(entry.getValue(), out);
        }
        out.append('}');
      }
      default -> out.append('"').append(jsStringBody(stringify(value))).append('"');
    }
  }

  /** {@code _html_template_jsstrescaper} — the body of a JavaScript string literal. */
  static String jsString(Object value) {
    if (value instanceof Safe.JsStr js) {
      return js.value();
    }
    return jsStringBody(stringify(value));
  }

  private static String jsStringBody(String s) {
    var out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\0' -> out.append("\\u0000");
        case '\t' -> out.append("\\t");
        case '\n' -> out.append("\\n");
        case '\u000B' -> out.append("\\u000b");
        case '\f' -> out.append("\\f");
        case '\r' -> out.append("\\r");
        case '"' -> out.append("\\\"");
        case '&' -> out.append("\\u0026");
        case '\'' -> out.append("\\'");
        case '+' -> out.append("\\u002b");
        case '/' -> out.append("\\/");
        case '<' -> out.append("\\u003c");
        case '=' -> out.append("\\u003d");
        case '>' -> out.append("\\u003e");
        case '\\' -> out.append("\\\\");
        case '\u0085' -> out.append("\\u0085");
        case '\u2028' -> out.append("\\u2028");
        case '\u2029' -> out.append("\\u2029");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
