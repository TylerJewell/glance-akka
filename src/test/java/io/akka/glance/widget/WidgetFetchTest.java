package io.akka.glance.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.akka.glance.config.Config;
import io.akka.glance.config.ConfigLoader;
import io.akka.glance.net.Endpoints;
import io.akka.glance.widget.kind.ChangeDetectionWidget;
import io.akka.glance.widget.kind.DnsStatsWidget;
import io.akka.glance.widget.kind.HackerNewsWidget;
import io.akka.glance.widget.kind.MarketsWidget;
import io.akka.glance.widget.kind.MonitorWidget;
import io.akka.glance.widget.kind.ReleasesWidget;
import io.akka.glance.widget.kind.RepositoryWidget;
import io.akka.glance.widget.kind.RssWidget;
import io.akka.glance.widget.kind.WeatherWidget;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a widget does with what comes back.
 *
 * <p>Nothing here is stood in for but the services themselves: the widgets are the real ones,
 * the requests are real HTTP, and the server answering them is started by this test. The two
 * cases each widget can be in — everything answered, and some of it not — are what these are
 * for, because the difference between them is the whole of the outcome rule.
 */
class WidgetFetchTest {

  private static HttpServer server;
  private static String base;

  /** Which routes are refusing, so a test can put a widget into its partial branch. */
  private static final Map<String, Boolean> broken = new HashMap<>();

  /** How many times each route was asked, so a cache can be shown to have been used. */
  private static final Map<String, AtomicInteger> hits = new HashMap<>();

  private static final String RSS_FEED =
      """
      <?xml version="1.0"?>
      <rss version="2.0"><channel>
      <title>A Channel</title><link>https://channel.test</link>
      <item><title>One</title><link>https://channel.test/1</link>
        <pubDate>Mon, 04 Mar 2024 09:00:00 +0000</pubDate></item>
      <item><title>Two</title><link>https://channel.test/2</link>
        <pubDate>Sat, 02 Mar 2024 09:00:00 +0000</pubDate></item>
      </channel></rss>
      """;

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
    server.createContext("/", WidgetFetchTest::route);
    server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    Endpoints.hackerNews = base + "/hn/";
    Endpoints.github = base + "/github";
    Endpoints.gitlab = base + "/gitlab";
    Endpoints.codeberg = base + "/codeberg";
    Endpoints.dockerHub = base + "/dockerhub";
    Endpoints.openMeteo = base + "/meteo";
    Endpoints.openMeteoGeocoding = base + "/geocoding";
    Endpoints.yahooFinance = base + "/yahoo";
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  @BeforeEach
  void resetRoutes() {
    broken.clear();
    hits.clear();
  }

  private static void route(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    hits.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
    if (Boolean.TRUE.equals(broken.get(path))) {
      respond(exchange, 503, "unavailable", "text/plain");
      return;
    }
    switch (path) {
      case "/feed" -> respond(exchange, 200, RSS_FEED, "application/rss+xml");
      case "/hn/topstories.json" -> respond(exchange, 200, "[1,2]", "application/json");
      case "/hn/item/1.json" -> respond(
          exchange,
          200,
          "{\"id\":1,\"title\":\"First\",\"url\":\"https://a.test/x\",\"descendants\":5,"
              + "\"score\":50,\"time\":1709546400}",
          "application/json");
      case "/hn/item/2.json" -> respond(
          exchange,
          200,
          "{\"id\":2,\"title\":\"Second\",\"descendants\":1,\"score\":5,\"time\":1709460000}",
          "application/json");
      case "/github/repos/owner/project" -> respond(
          exchange,
          200,
          "{\"full_name\":\"owner/project\",\"stargazers_count\":10,\"forks_count\":2}",
          "application/json");
      case "/github/repos/owner/project/releases/latest" -> respond(
          exchange,
          200,
          "{\"tag_name\":\"1.0.0\",\"published_at\":\"2024-03-04T09:00:00Z\","
              + "\"html_url\":\"https://r.test/1\",\"reactions\":{\"-1\":2}}",
          "application/json");
      case "/github/search/issues" -> respond(
          exchange,
          200,
          "{\"total_count\":3,\"items\":[{\"number\":1,\"created_at\":\"2024-03-04T09:00:00Z\","
              + "\"title\":\"A ticket\"}]}",
          "application/json");
      case "/github/repos/owner/project/commits" -> respond(
          exchange,
          200,
          "[{\"sha\":\"abc\",\"commit\":{\"author\":{\"name\":\"A\",\"date\":"
              + "\"2024-03-04T09:00:00Z\"},\"message\":\"Subject\\n\\nBody\"}}]",
          "application/json");
      case "/geocoding/v1/search" -> respond(
          exchange,
          200,
          "{\"results\":[{\"name\":\"London\",\"admin1\":\"England\",\"latitude\":51.5,"
              + "\"longitude\":-0.1,\"timezone\":\"Europe/London\",\"country\":\"UK\"}]}",
          "application/json");
      case "/meteo/v1/forecast" -> respond(exchange, 200, forecast(), "application/json");
      case "/yahoo/v8/finance/chart/AAA" -> respond(
          exchange,
          200,
          "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"USD\",\"regularMarketPrice\":110.0,"
              + "\"shortName\":\"A Company\",\"priceHint\":2},\"indicators\":{\"quote\":[{"
              + "\"close\":[100.0,105.0,110.0]}]}}]}}",
          "application/json");
      case "/cd/api/v1/watch" -> respond(exchange, 200, "{\"aaa\":{}}", "application/json");
      case "/cd/api/v1/watch/aaa" -> respond(
          exchange,
          200,
          "{\"title\":\"\",\"url\":\"https://www.watched.test/one/\",\"last_changed\":1709546400,"
              + "\"date_created\":1709000000,\"previous_md5\":\"0123456789\"}",
          "application/json");
      case "/adguard/control/stats" -> respond(exchange, 200, adguard(), "application/json");
      case "/up" -> respond(exchange, 200, "ok", "text/plain");
      case "/gone" -> respond(exchange, 404, "gone", "text/plain");
      default -> respond(exchange, 404, "no route", "text/plain");
    }
  }

  private static String forecast() {
    var temperatures = new StringBuilder();
    var precipitation = new StringBuilder();
    for (int i = 0; i < 24; i++) {
      if (i > 0) {
        temperatures.append(',');
        precipitation.append(',');
      }
      temperatures.append(i);
      precipitation.append(i * 4);
    }
    return "{\"daily\":{\"sunrise\":[1709535600],\"sunset\":[1709575200]},"
        + "\"hourly\":{\"temperature_2m\":["
        + temperatures
        + "],\"precipitation_probability\":["
        + precipitation
        + "]},"
        + "\"current\":{\"temperature_2m\":9.6,\"apparent_temperature\":7.4,\"weather_code\":61}}";
  }

  private static String adguard() {
    var series = new StringBuilder();
    for (int i = 0; i < 24; i++) {
      if (i > 0) {
        series.append(',');
      }
      series.append(100 + i);
    }
    return "{\"num_dns_queries\":1000,\"num_blocked_filtering\":250,\"avg_processing_time\":0.01,"
        + "\"top_blocked_domains\":[{\"ads.test\":100}],\"dns_queries\":["
        + series
        + "],\"blocked_filtering\":["
        + series
        + "]}";
  }

  private static void respond(HttpExchange exchange, int status, String body, String type)
      throws IOException {
    byte[] raw = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", type);
    exchange.sendResponseHeaders(status, raw.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(raw);
    }
  }

  private Widget widgetFrom(String widgetYaml) {
    var config = configFrom(widgetYaml);
    // Built through the application, because that is what gives a widget its way of
    // reaching an asset; the releases widget asks for one while it updates.
    new io.akka.glance.app.Application(config, NOW);
    return config.Pages.getFirst().Columns.getFirst().Widgets.getFirst();
  }

  private Config configFrom(String widgetYaml) {
    return ConfigLoader.fromYaml(
        "pages:\n  - name: Home\n    columns:\n      - size: full\n        widgets:\n"
            + widgetYaml.indent(10));
  }

  private static final Instant NOW = Instant.parse("2026-03-04T12:00:00Z");

  @Test
  void anRssWidgetShowsTheNewestFirst() {
    var widget = (RssWidget) widgetFrom("- type: rss\n  feeds:\n    - url: " + base + "/feed\n");
    widget.update(NOW);
    assertTrue(widget.ContentAvailable);
    assertEquals(2, widget.Items.size());
    assertEquals("One", widget.Items.get(0).Title);
    assertEquals("Two", widget.Items.get(1).Title);
    assertEquals("A Channel", widget.Items.get(0).ChannelName);
  }

  @Test
  void anRssWidgetWithOneFeedDownKeepsTheOtherAndSaysSo() {
    var widget =
        (RssWidget)
            widgetFrom(
                "- type: rss\n  feeds:\n    - url: " + base + "/feed\n    - url: " + base + "/gone\n");
    widget.update(NOW);
    assertTrue(widget.ContentAvailable, "content that did arrive is kept");
    assertNotNull(widget.Notice);
    assertTrue(widget.Notice.is(Err.PARTIAL_CONTENT));
    assertEquals("failed to retrieve some of the content: missing 1 RSS feeds", widget.Notice.message());
    assertEquals(2, widget.Items.size());
  }

  @Test
  void anRssWidgetWithEveryFeedDownHasNothingToShow() {
    var widget = (RssWidget) widgetFrom("- type: rss\n  feeds:\n    - url: " + base + "/gone\n");
    widget.update(NOW);
    assertFalse(widget.ContentAvailable);
    assertEquals("failed to retrieve any content", widget.Error.message());
    assertEquals(1, widget.updateRetriedTimes(), "a failure is retried sooner");
  }

  @Test
  void aFailedUpdateIsRetriedSoonerAndSoonerLess() {
    var widget = (RssWidget) widgetFrom("- type: rss\n  feeds:\n    - url: " + base + "/gone\n");
    widget.update(NOW);
    // One minute after the first failure, four after the second, nine after the third.
    assertEquals(NOW.plus(Duration.ofMinutes(1)), widget.nextUpdate());
    widget.update(NOW);
    assertEquals(NOW.plus(Duration.ofMinutes(4)), widget.nextUpdate());
    widget.update(NOW);
    assertEquals(NOW.plus(Duration.ofMinutes(9)), widget.nextUpdate());
  }

  @Test
  void anEarlyRetryNeverOutlastsTheOrdinaryDeadline() {
    var widget =
        (RssWidget)
            widgetFrom("- type: rss\n  cache: 30s\n  feeds:\n    - url: " + base + "/gone\n");
    widget.update(NOW);
    assertEquals(
        NOW.plus(Duration.ofSeconds(30)),
        widget.nextUpdate(),
        "a minute is longer than the widget's own half minute, so the half minute wins");
  }

  @Test
  void aSuccessfulUpdateSchedulesTheOrdinaryDeadlineAndForgetsTheRetries() {
    var widget = (RssWidget) widgetFrom("- type: rss\n  feeds:\n    - url: " + base + "/gone\n");
    widget.update(NOW);
    broken.clear();
    var working = (RssWidget) widgetFrom("- type: rss\n  feeds:\n    - url: " + base + "/feed\n");
    working.update(NOW);
    assertEquals(NOW.plus(Duration.ofHours(2)), working.nextUpdate());
    assertEquals(0, working.updateRetriedTimes());
  }

  @Test
  void aWidgetIsDueOnlyOnceItsDeadlineHasPassed() {
    var widget = (RssWidget) widgetFrom("- type: rss\n  feeds:\n    - url: " + base + "/feed\n");
    assertTrue(widget.requiresUpdate(NOW), "a widget that has never run is due");
    widget.update(NOW);
    assertFalse(widget.requiresUpdate(NOW.plus(Duration.ofHours(1))));
    assertFalse(widget.requiresUpdate(NOW.plus(Duration.ofHours(2))), "the deadline itself is not past");
    assertTrue(widget.requiresUpdate(NOW.plus(Duration.ofHours(2)).plusSeconds(1)));
  }

  @Test
  void aHackerNewsWidgetReadsItsPostsById() {
    var widget = (HackerNewsWidget) widgetFrom("- type: hacker-news\n");
    widget.update(NOW);
    assertTrue(widget.ContentAvailable);
    assertEquals(2, widget.Posts.size());
    assertEquals("First", widget.Posts.getFirst().Title);
    assertEquals("a.test", widget.Posts.getFirst().TargetUrlDomain);
    assertEquals(
        "https://news.ycombinator.com/item?id=1", widget.Posts.getFirst().DiscussionUrl);
  }

  @Test
  void aRepositoryWidgetKeepsWhatItGotAndReportsWhatItDidNot() {
    var widget =
        (RepositoryWidget) widgetFrom("- type: repository\n  repository: owner/project\n  commits-limit: 3\n");
    widget.update(NOW);
    assertTrue(widget.ContentAvailable);
    assertEquals("owner/project", widget.Repository.Name);
    assertEquals(10, widget.Repository.Stars);
    assertEquals(3, widget.Repository.OpenIssues);
    assertEquals("Subject", widget.Repository.Commits.getFirst().Message);
  }

  @Test
  void aRepositoryWithNoDetailsHasNothingToShow() {
    broken.put("/github/repos/owner/project", true);
    var widget =
        (RepositoryWidget) widgetFrom("- type: repository\n  repository: owner/project\n");
    widget.update(NOW);
    assertFalse(widget.ContentAvailable);
    assertTrue(widget.Error.message().startsWith("failed to retrieve any content: could not get repository details"));
  }

  @Test
  void aReleasesWidgetReportsHowManyItCouldNotGet() {
    var widget =
        (ReleasesWidget)
            widgetFrom("- type: releases\n  repositories:\n    - owner/project\n    - owner/missing\n");
    widget.update(NOW);
    assertTrue(widget.ContentAvailable);
    assertEquals(1, widget.Releases.size());
    assertEquals("v1.0.0", widget.Releases.getFirst().Version);
    assertEquals(2, widget.Releases.getFirst().Downvotes);
    assertEquals("failed to retrieve some of the content: could not get 1 releases", widget.Notice.message());
  }

  @Test
  void aMarketsWidgetWorksOutTheChangeAgainstTheDayBefore() {
    var widget = (MarketsWidget) widgetFrom("- type: markets\n  markets:\n    - symbol: AAA\n");
    widget.update(NOW);
    assertTrue(widget.ContentAvailable);
    var market = widget.Markets.getFirst();
    assertEquals("A Company", market.Name);
    assertEquals("$", market.CurrencySymbol);
    // 110 against the previous close of 105.
    assertEquals(4.7619, market.PercentChange, 1e-4);
    assertEquals("0.00,49.00 50.00,25.00 100.00,1.00", market.SvgChartPoints);
  }

  @Test
  void aMonitorWidgetReportsEachSiteAndWhetherAnyIsFailing() {
    var widget =
        (MonitorWidget)
            widgetFrom(
                "- type: monitor\n  sites:\n    - title: Up\n      url: "
                    + base
                    + "/up\n    - title: Gone\n      url: "
                    + base
                    + "/gone\n");
    widget.update(NOW);
    assertTrue(widget.ContentAvailable);
    assertEquals("OK", widget.Sites.get(0).StatusText);
    assertEquals("ok", widget.Sites.get(0).StatusStyle);
    assertEquals("Not Found", widget.Sites.get(1).StatusText);
    assertEquals("error", widget.Sites.get(1).StatusStyle);
    assertTrue(widget.HasFailing);
  }

  @Test
  void aStatusCodeTheSiteDeclaresAcceptableIsNotAFailure() {
    var widget =
        (MonitorWidget)
            widgetFrom(
                "- type: monitor\n  sites:\n    - title: Gone\n      url: "
                    + base
                    + "/gone\n      alt-status-codes: [404]\n");
    widget.update(NOW);
    assertEquals("OK", widget.Sites.getFirst().StatusText);
    assertFalse(widget.HasFailing);
  }

  @Test
  void aWeatherWidgetFoldsTwentyFourHoursIntoTwelveColumns() {
    var widget =
        (WeatherWidget)
            widgetFrom("- type: weather\n  location: London, England, UK\n  hour-format: 24h\n");
    widget.update(NOW);
    assertTrue(widget.ContentAvailable);
    assertEquals("London", widget.Place.Name);
    assertEquals(12, widget.Weather.Columns.size());
    assertEquals(9, widget.Weather.Temperature, "the current reading is truncated, not rounded");
    assertEquals("Rain", widget.Weather.WeatherCodeAsString());
    // A pair of hours whose average chance of rain is over 75 marks the column.
    assertTrue(widget.Weather.Columns.get(11).HasPrecipitation);
    assertFalse(widget.Weather.Columns.getFirst().HasPrecipitation);
  }

  @Test
  void aChangeDetectionWidgetNamesAWatchAfterItsAddressWhenItHasNoTitle() {
    var widget =
        (ChangeDetectionWidget) widgetFrom("- type: change-detection\n  instance-url: " + base + "/cd\n");
    widget.update(NOW);
    assertTrue(widget.ContentAvailable);
    assertEquals("watched.test/one", widget.ChangeDetections.getFirst().Title);
    assertEquals("01234567", widget.ChangeDetections.getFirst().PreviousHash);
  }

  @Test
  void aDnsWidgetSharesOutADaysQueriesAcrossEightBars() {
    var widget =
        (DnsStatsWidget)
            widgetFrom("- type: dns-stats\n  service: adguard\n  url: " + base + "/adguard\n");
    widget.update(NOW);
    assertTrue(widget.ContentAvailable);
    assertEquals(1000, widget.Stats.TotalQueries);
    assertEquals(25, widget.Stats.BlockedPercent);
    assertEquals(10, widget.Stats.ResponseTime);
    assertEquals(8, widget.Stats.Series.size());
    assertEquals(100 + 101 + 102, widget.Stats.Series.getFirst().Queries);
    assertEquals(100, widget.Stats.Series.getLast().PercentTotal, "the tallest bar is the last");
    assertEquals(8, widget.TimeLabels.size());
  }

  @Test
  void aFeedThatHasNotChangedIsNotReadAgain() {
    var widget = (RssWidget) widgetFrom("- type: rss\n  feeds:\n    - url: " + base + "/feed\n");
    widget.update(NOW);
    int first = hits.get("/feed").get();
    widget.update(NOW.plus(Duration.ofHours(3)));
    assertEquals(first + 1, hits.get("/feed").get(), "it is asked again");
    assertEquals(2, widget.Items.size());
  }
}
