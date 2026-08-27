package io.akka.glance.gotemplate;

/**
 * The values Go's {@code html/template} trusts to carry markup of their own.
 *
 * <p>A value of one of these types reaches the page as it is; anything else is escaped for
 * whatever context it lands in. The distinction is the whole of what makes {@code {{
 * .Render }}} write a widget's markup rather than the text of it.
 */
public sealed interface Safe {

  String value();

  /** {@code template.HTML} — a fragment of known-safe markup. */
  record Html(String value) implements Safe {}

  /** {@code template.CSS} — a style sheet, a declaration list, or one property value. */
  record Css(String value) implements Safe {}

  /** {@code template.URL} — a URL that has already been checked. */
  record Url(String value) implements Safe {}

  /** {@code template.HTMLAttr} — one or more attributes, name and value together. */
  record Attr(String value) implements Safe {}

  /** {@code template.JS} — an expression in JavaScript. */
  record Js(String value) implements Safe {}

  /** {@code template.JSStr} — the body of a JavaScript string literal. */
  record JsStr(String value) implements Safe {}

  /** {@code template.Srcset} — a value for the {@code srcset} attribute. */
  record Srcset(String value) implements Safe {}

  static Safe html(String value) {
    return new Html(value);
  }

  static Safe css(String value) {
    return new Css(value);
  }

  static Safe url(String value) {
    return new Url(value);
  }

  static Safe attr(String value) {
    return new Attr(value);
  }
}
