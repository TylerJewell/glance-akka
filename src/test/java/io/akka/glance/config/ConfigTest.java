package io.akka.glance.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.glance.widget.kind.BookmarksWidget;
import io.akka.glance.widget.kind.ClockWidget;
import io.akka.glance.widget.kind.RedditWidget;
import io.akka.glance.widget.kind.RssWidget;
import io.akka.glance.widget.kind.SearchWidget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a configuration file: what it accepts, what it fills in, and what it refuses. */
class ConfigTest {

  private static final String MINIMAL =
      """
      pages:
        - name: Home
          columns:
            - size: full
              widgets: []
      """;

  @Test
  void aMinimalFileIsAccepted() {
    var config = ConfigLoader.fromYaml(MINIMAL);
    assertEquals(1, config.Pages.size());
    assertEquals("Home", config.Pages.getFirst().Title);
    assertEquals(8080, config.Server.Port);
  }

  @Test
  void aFileWithNoPagesIsRefused() {
    assertEquals(
        "no pages configured", assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml("{}"))
            .getMessage());
  }

  @Test
  void aPageWithNoFullColumnIsRefused() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: small
                widgets: []
        """;
    assertEquals(
        "page 1 must have either 1 or 2 full width columns",
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml)).getMessage());
  }

  @Test
  void aSlimPageMayHaveTwoColumnsAndNoMore() {
    var yaml =
        """
        pages:
          - name: Home
            width: slim
            columns:
              - size: full
                widgets: []
              - size: small
                widgets: []
              - size: small
                widgets: []
        """;
    assertEquals(
        "page 1 is slim and cannot have more than 2 columns",
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml)).getMessage());
  }

  @Test
  void anUnknownWidgetTypeIsRefused() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: nonesuch
        """;
    assertEquals(
        "unknown widget type: nonesuch",
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml)).getMessage());
  }

  @Test
  void aWidgetWithNoTypeIsRefused() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - title: nothing
        """;
    assertEquals(
        "widget 'type' property is empty or not specified",
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml)).getMessage());
  }

  @Test
  void aWidgetsOwnErrorIsPrefixedWithItsType() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: clock
                    hour-format: 36h
        """;
    assertEquals(
        "clock widget: hour-format must be either 12h or 24h",
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml)).getMessage());
  }

  @Test
  void aCacheDurationIsReadInTheOriginalsOwnSpelling() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: rss
                    cache: 90m
                    feeds:
                      - url: http://feeds.test/one
        """;
    var widget = (RssWidget) ConfigLoader.fromYaml(yaml).Pages.getFirst().Columns.getFirst().Widgets.getFirst();
    assertEquals(Duration.ofMinutes(90), widget.cacheDuration());
  }

  @Test
  void anUnknownDurationSpellingIsRefused() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: rss
                    cache: 1m30s
                    feeds: []
        """;
    assertTrue(
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml))
            .getMessage()
            .contains("invalid duration format: 1m30s"));
  }

  @Test
  void aColourIsReadWithOrWithoutItsWrapper() {
    var yaml =
        """
        theme:
          background-color: 240 8 9
          primary-color: hsl(43, 50%, 70%)
        pages:
          - name: Home
            columns:
              - size: full
                widgets: []
        """;
    var config = ConfigLoader.fromYaml(yaml);
    assertEquals("hsl(240.0, 8.0%, 9.0%)", config.Theme.BackgroundColor.String());
    assertEquals("hsl(43.0, 50.0%, 70.0%)", config.Theme.PrimaryColor.String());
    // The same value the original names as its own default background.
    assertEquals("#151519", config.Theme.BackgroundColor.ToHex());
  }

  @Test
  void aColourOutsideItsRangeIsRefused() {
    var yaml =
        """
        theme:
          background-color: 400 8 9
        pages:
          - name: Home
            columns:
              - size: full
                widgets: []
        """;
    assertTrue(
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml))
            .getMessage()
            .contains("HSL hue must be between 0 and 360"));
  }

  @Test
  void presetsKeepTheOrderTheyWereWrittenIn() {
    var yaml =
        """
        theme:
          presets:
            zebra:
              background-color: 0 0 10
            alpha:
              background-color: 0 0 20
        pages:
          - name: Home
            columns:
              - size: full
                widgets: []
        """;
    assertEquals(java.util.List.of("zebra", "alpha"), ConfigLoader.fromYaml(yaml).Theme.Presets.keys());
  }

  @Test
  void aLinkTakesItsGroupsDefaultsUnlessItSaysOtherwise() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: bookmarks
                    groups:
                      - title: Group
                        same-tab: true
                        hide-arrow: true
                        links:
                          - title: Inherits
                            url: https://a.test
                          - title: Overrides
                            url: https://b.test
                            same-tab: false
                            hide-arrow: false
        """;
    var widget =
        (BookmarksWidget)
            ConfigLoader.fromYaml(yaml).Pages.getFirst().Columns.getFirst().Widgets.getFirst();
    var links = widget.Groups.getFirst().Links;
    assertTrue(links.get(0).SameTab);
    assertTrue(links.get(0).HideArrow);
    assertEquals("", links.get(0).Target);
    assertFalse(links.get(1).SameTab);
    assertFalse(links.get(1).HideArrow);
    assertEquals("_blank", links.get(1).Target);
  }

  @Test
  void anIconPrefixPicksItsCollection() {
    assertEquals(
        "https://cdn.jsdelivr.net/npm/simple-icons@latest/icons/github.svg",
        CustomIcon.of("si:github").URL.value());
    assertTrue(CustomIcon.of("si:github").AutoInvert);
    assertEquals(
        "https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/docker.png",
        CustomIcon.of("di:docker.png").URL.value());
    assertFalse(CustomIcon.of("di:docker.png").AutoInvert);
    assertEquals("https://own.test/icon.svg", CustomIcon.of("https://own.test/icon.svg").URL.value());
    assertTrue(CustomIcon.of("auto-invert https://own.test/icon.svg").AutoInvert);
  }

  @Test
  void aSearchPlaceholderReachesTheBrowserWithItsBracesIntact() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: search
                    search-engine: https://own.test/?q={QUERY}
        """;
    var widget =
        (SearchWidget)
            ConfigLoader.fromYaml(yaml).Pages.getFirst().Columns.getFirst().Widgets.getFirst();
    assertEquals("https://own.test/?q=!QUERY!", widget.SearchEngine);
  }

  @Test
  void aRedditWidgetNeedsASubreddit() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: reddit
        """;
    assertEquals(
        "reddit widget: subreddit is required",
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml)).getMessage());
  }

  @Test
  void aRedditRequestTemplateNeedsItsPlaceholder() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: reddit
                    subreddit: test
                    request-url-template: https://proxy.test/
        """;
    assertEquals(
        "reddit widget: no `{REQUEST-URL}` placeholder specified",
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml)).getMessage());
  }

  @Test
  void aRedditWidgetBuildsTheAddressItsSettingsAskFor() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: reddit
                    subreddit: test
                    sort-by: top
                    top-period: week
                    limit: 40
        """;
    var widget =
        (RedditWidget)
            ConfigLoader.fromYaml(yaml).Pages.getFirst().Columns.getFirst().Widgets.getFirst();
    assertEquals(
        "https://base.test/r/test/top.json?limit=40&t=week", widget.requestUrl("https://base.test"));
  }

  @Test
  void aGroupMayNotHoldAnotherGroup() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: group
                    widgets:
                      - type: group
                        widgets: []
        """;
    assertEquals(
        "group widget: nested groups are not supported",
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml)).getMessage());
  }

  @Test
  void anEnvironmentVariableIsSubstituted() {
    ConfigVariables.environment = Map.of("A_TOKEN", "secret-value")::get;
    try {
      var yaml = MINIMAL + "\nbranding:\n  app-name: ${A_TOKEN}\n";
      assertEquals("secret-value", ConfigLoader.fromYaml(yaml).Branding.AppName);
    } finally {
      ConfigVariables.environment = System::getenv;
    }
  }

  @Test
  void anEscapedReferenceIsLeftAsWritten() {
    assertEquals("value: ${NOT_A_VARIABLE}", ConfigVariables.substitute("value: \\${NOT_A_VARIABLE}"));
  }

  @Test
  void aLowerCaseNameIsNotAnEnvironmentVariable() {
    assertEquals("value: ${notAVariable}", ConfigVariables.substitute("value: ${notAVariable}"));
  }

  @Test
  void aMissingEnvironmentVariableIsRefused() {
    ConfigVariables.environment = name -> null;
    try {
      assertEquals(
          "parsing variable: environment variable MISSING not found",
          assertThrows(ConfigException.class, () -> ConfigVariables.substitute("${MISSING}"))
              .getMessage());
    } finally {
      ConfigVariables.environment = System::getenv;
    }
  }

  @Test
  void aSecretIsReadFromItsFile(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("api_key"), "  a-secret\n");
    ConfigVariables.secretsDirectory = directory;
    try {
      assertEquals("key: a-secret", ConfigVariables.substitute("key: ${secret:api_key}"));
    } finally {
      ConfigVariables.secretsDirectory = Path.of("/run/secrets");
    }
  }

  @Test
  void anIncludeIsPastedInAtItsOwnIndentation(@TempDir Path directory) throws Exception {
    Files.writeString(
        directory.resolve("widgets.yml"),
        """
        - type: clock
        - type: calendar
        """);
    Files.writeString(
        directory.resolve("glance.yml"),
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  $include: widgets.yml
        """);
    var resolved = Includes.parse(directory.resolve("glance.yml"));
    var config = ConfigLoader.fromYaml(resolved.contents());
    var widgets = config.Pages.getFirst().Columns.getFirst().Widgets;
    assertEquals(2, widgets.size());
    assertEquals("clock", widgets.get(0).GetType());
    assertEquals("calendar", widgets.get(1).GetType());
    assertEquals(1, resolved.includes().size());
  }

  @Test
  void anIncludeThatIncludesItselfIsRefused(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("loop.yml"), "  $include: loop.yml\n");
    assertTrue(
        assertThrows(ConfigException.class, () -> Includes.parse(directory.resolve("loop.yml")))
            .getMessage()
            .contains("recursion depth limit of 20 reached"));
  }

  @Test
  void aClockNeedsARealTimezone() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: clock
                    timezones:
                      - timezone: Not/APlace
        """;
    assertEquals(
        "clock widget: invalid timezone 'Not/APlace': unknown time zone Not/APlace",
        assertThrows(ConfigException.class, () -> ConfigLoader.fromYaml(yaml)).getMessage());
  }

  @Test
  void aClockFallsBackToTwentyFourHours() {
    var yaml =
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: clock
        """;
    var widget =
        (ClockWidget)
            ConfigLoader.fromYaml(yaml).Pages.getFirst().Columns.getFirst().Widgets.getFirst();
    assertEquals("24h", widget.HourFormat);
  }

  @Test
  void queryParametersAreEncodedWithTheirKeysSorted() {
    var parameters = new QueryParameters();
    parameters.values().put("b", java.util.List.of("2"));
    parameters.values().put("a", java.util.List.of("1", "x y"));
    assertEquals("a=1&a=x+y&b=2", parameters.toQueryString());
  }
}
