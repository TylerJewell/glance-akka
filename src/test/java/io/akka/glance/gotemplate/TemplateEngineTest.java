package io.akka.glance.gotemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.akka.glance.render.Templates;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The template engine against what Go's {@code html/template} writes for the same input.
 *
 * <p>Every expected string here was produced by running the equivalent template through Go
 * — {@code glance-port/probes/template_probe} — rather than reasoned about, because the
 * question these answer is what Go does rather than what the specification says it does.
 */
class TemplateEngineTest {

  private static String render(String source, Object data) {
    return Template.parse(Templates.functions(), "t", source).execute(data);
  }

  public record Person(String Name, int Age, List<String> Tags) {}

  @Test
  void writesTextAndFieldsInOrder() {
    assertEquals(
        "Hello Ada, 36",
        render("Hello {{ .Name }}, {{ .Age }}", new Person("Ada", 36, List.of())));
  }

  @Test
  void escapesMarkupInText() {
    assertEquals(
        "&lt;b&gt;hi&lt;/b&gt;",
        render("{{ .Name }}", new Person("<b>hi</b>", 0, List.of())));
  }

  @Test
  void passesSafeHtmlThrough() {
    assertEquals("<b>hi</b>", render("{{ .Name | safeHTML }}", new Person("<b>hi</b>", 0, List.of())));
  }

  @Test
  void escapesQuotesInsideAnAttribute() {
    assertEquals(
        "<a title=\"a &#34;b&#34;\">x</a>",
        render("<a title=\"{{ .Name }}\">x</a>", new Person("a \"b\"", 0, List.of())));
  }

  @Test
  void filtersAUrlWithAnUnsafeScheme() {
    assertEquals(
        "<a href=\"#ZgotmplZ\">x</a>",
        render("<a href=\"{{ .Name }}\">x</a>", new Person("javascript:alert(1)", 0, List.of())));
  }

  @Test
  void normalisesASafeUrl() {
    assertEquals(
        "<a href=\"https://example.com/a%20b\">x</a>",
        render("<a href=\"{{ .Name }}\">x</a>", new Person("https://example.com/a b", 0, List.of())));
  }

  @Test
  void escapesTheQueryPartOfAUrlSeparately() {
    assertEquals(
        "<a href=\"/x?q=a%2bb\">y</a>",
        render("<a href=\"/x?q={{ .Name }}\">y</a>", new Person("a+b", 0, List.of())));
  }

  @Test
  void escapesInsideAScriptStringLiteral() {
    assertEquals(
        "<script>var a = \"a\\u003cb\";</script>",
        render("<script>var a = \"{{ .Name }}\";</script>", new Person("a<b", 0, List.of())));
  }

  @Test
  void rangesWithIndexAndValue() {
    assertEquals(
        "0:a 1:b ",
        render("{{ range $i, $t := .Tags }}{{ $i }}:{{ $t }} {{ end }}",
            new Person("", 0, List.of("a", "b"))));
  }

  @Test
  void rangeElseRunsOnAnEmptyList() {
    assertEquals(
        "none",
        render("{{ range .Tags }}x{{ else }}none{{ end }}", new Person("", 0, List.of())));
  }

  @Test
  void trimMarkersRemoveSurroundingWhitespace() {
    assertEquals("ab", render("a\n    {{- .Name -}}   \nb", new Person("", 0, List.of())));
  }

  @Test
  void elseIfChainsPickTheFirstTrueBranch() {
    assertEquals(
        "middle",
        render(
            "{{ if gt .Age 90 }}old{{ else if gt .Age 20 }}middle{{ else }}young{{ end }}",
            new Person("", 36, List.of())));
  }

  @Test
  void aDefineIsCallableFromTheSameSet() {
    assertEquals(
        "[Ada]",
        render("{{ define \"n\" }}[{{ .Name }}]{{ end }}{{ template \"n\" . }}",
            new Person("Ada", 0, List.of())));
  }

  @Test
  void dollarStaysBoundToTheTopLevelInsideARange() {
    assertEquals(
        "Ada:a Ada:b ",
        render("{{ range .Tags }}{{ $.Name }}:{{ . }} {{ end }}",
            new Person("Ada", 0, List.of("a", "b"))));
  }

  @Test
  void printfMatchesGosVerbs() {
    // The plus is escaped because html/template's table covers it, like the angle brackets.
    assertEquals("&#43;1.50", render("{{ printf \"%+.2f\" 1.5 }}", null));
    assertEquals("007", render("{{ printf \"%03d\" 7 }}", null));
  }

  @Test
  void safeCssPassesThroughAndAnythingElseIsFiltered() {
    assertEquals(
        "<style>a{color:hsl(1, 2%, 3%)}</style>",
        render("<style>a{color:{{ .Name | safeCSS }}}</style>",
            new Person("hsl(1, 2%, 3%)", 0, List.of())));
    assertEquals(
        "<style>a{color:ZgotmplZ}</style>",
        render("<style>a{color:{{ .Name }}}</style>",
            new Person("hsl(1, 2%, 3%)", 0, List.of())));
  }

  @Test
  void anUnquotedAttributeValueKeepsItsOwnDelimiterOut() {
    assertEquals(
        "<div class=a&#32;b>x</div>",
        render("<div class={{ .Name }}>x</div>", new Person("a b", 0, List.of())));
  }

  @Test
  void anHtmlCommentDoesNotReachThePage() {
    assertEquals("x", render("<!-- {{ .Name }} -->x", new Person("hi", 0, List.of())));
  }

  @Test
  void markupInsideAnAttributeLosesItsElements() {
    assertEquals(
        "<div title=\"\">x</div>",
        render("<div title=\"{{ .Name | safeHTML }}\">x</div>", new Person("<b>", 0, List.of())));
  }

  @Test
  void safeAttributesAreWrittenIntoTheTag() {
    assertEquals(
        "<div data-dynamic-relative-time=\"36\">x</div>",
        render("<div {{ dynamicRelativeTimeAttrs .Age }}>x</div>",
            new Person("", 36, List.of())));
  }

  @Test
  void aValueInAScriptExpressionIsMarshalled() {
    assertEquals(
        "<script>var a = \"a\\u003cb\";</script>",
        render("<script>var a = {{ .Name }};</script>", new Person("a<b", 0, List.of())));
    assertEquals(
        "<script>var a = [\"a\",\"b\"];</script>",
        render("<script>var a = {{ .Tags }};</script>", new Person("", 0, List.of("a", "b"))));
  }

  @Test
  void anAmpersandInAUrlIsEscapedForTheAttribute() {
    assertEquals(
        "<a href=\"https://a.b/c?d=1&amp;e=2\">x</a>",
        render("<a href=\"{{ .Name }}\">x</a>",
            new Person("https://a.b/c?d=1&e=2", 0, List.of())));
  }

  @Test
  void aFloatPrintsTheShortestFormThatReadsBack() {
    assertEquals("1.3", render("{{ . }}", 1.3d));
    assertEquals("300", render("{{ . }}", 300.0d));
  }

  @Test
  void indexReadsAListAndAMap() {
    assertEquals("b", render("{{ index . 1 }}", List.of("a", "b")));
    assertEquals("2", render("{{ index . \"k\" }}", Map.of("k", 2L)));
  }

  @Test
  void aMissingFunctionIsAnError() {
    assertThrows(TemplateException.class, () -> render("{{ nope . }}", null));
  }

  @Test
  void aBlockIsADefaultThatALaterDefineDoesNotReplace() {
    var template = Template.parse(Templates.functions(), "a", "{{ define \"x\" }}real{{ end }}");
    template.associate("b", "[{{ block \"x\" . }}{{ end }}]");
    assertEquals("[real]", template.executeTemplate("b", null));
  }

  @Test
  void aBlocksOwnBodyIsUsedWhenNothingElseDefinesIt() {
    var template = Template.parse(Templates.functions(), "b", "[{{ block \"x\" . }}default{{ end }}]");
    assertEquals("[default]", template.execute(null));
  }

  @Test
  void everyShippedTemplateParses() {
    for (var name : io.akka.glance.util.Resources.walk("glance/templates")) {
      var source = io.akka.glance.util.Resources.text("glance/templates/" + name);
      Template.parse(Templates.functions(), name, source);
    }
  }
}
