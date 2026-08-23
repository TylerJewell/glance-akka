package io.akka.glance.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import com.sun.net.httpserver.HttpServer;
import io.akka.glance.domain.CacheMode;
import io.akka.glance.domain.FeedSpec;
import io.akka.glance.domain.WidgetState;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R13, R14, and the whole refresh path through a real runtime.
 *
 * <p>Names end in {@code startsARuntime} so the split between test phases stays visible — a
 * class that starts a runtime and one that does not are not interchangeable, and a rename
 * that moves a class between phases is how a test silently stops running.
 */
class PageRefreshIntegrationTest extends TestKitSupport {

  private static final Instant T0 = Instant.parse("2026-08-23T12:00:00Z");

  private HttpServer feeds;
  private String base;
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final AtomicInteger alphaHits = new AtomicInteger();

  private static final String ALPHA =
      "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Alpha Journal</title>"
          + "<link>http://feeds.test/</link>"
          + "<item><title>Alpha one</title><link>http://feeds.test/alpha/1</link>"
          + "<pubDate>Mon, 04 Mar 2024 09:00:00 +0000</pubDate></item>"
          + "<item><title>Alpha two</title><link>http://feeds.test/alpha/2</link>"
          + "<pubDate>Sat, 02 Mar 2024 09:00:00 +0000</pubDate></item>"
          + "</channel></rss>";

  @BeforeEach
  void startFeeds() throws IOException {
    // The testkit keeps one instance of this class for every test in it, so the fixture's
    // own state is reset here rather than relying on a fresh instance. A forced status left
    // behind by an earlier test reads as a failing feed in a later one.
    statuses.clear();
    alphaHits.set(0);
    feeds = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    feeds.createContext(
        "/",
        exchange -> {
          var path = exchange.getRequestURI().getPath();
          if (path.equals("/alpha")) {
            alphaHits.incrementAndGet();
          }
          var forced = statuses.get(path);
          if (forced != null) {
            exchange.sendResponseHeaders(forced, -1);
            exchange.close();
            return;
          }
          var body = ALPHA.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    feeds.setExecutor(Executors.newFixedThreadPool(4));
    feeds.start();
    base = "http://127.0.0.1:" + feeds.getAddress().getPort();
  }

  @AfterEach
  void stopFeeds() {
    feeds.stop(0);
  }

  private WidgetState configure(String id, String title, List<String> urls, long cacheSeconds) {
    return componentClient
        .forKeyValueEntity(id)
        .method(WidgetEntity::configure)
        .invoke(
            new WidgetEntity.Config(
                title,
                urls.stream().map(u -> new FeedSpec(base + u, null, 0)).toList(),
                CacheMode.DURATION,
                cacheSeconds,
                25,
                false,
                false,
                List.of()));
  }

  private void container(String id, List<String> childIds) {
    componentClient
        .forKeyValueEntity(id)
        .method(WidgetEntity::configure)
        .invoke(
            new WidgetEntity.Config(
                "", List.of(), CacheMode.DURATION, 3600L, 25, false, false, childIds));
  }

  private void page(String slug, List<String> widgetIds) {
    componentClient
        .forKeyValueEntity(slug)
        .method(PageEntity::configure)
        .invoke(new PageEntity.Page(slug, widgetIds));
  }

  private PageRefresh refresher() {
    return new PageRefresh(componentClient, new FeedFetcher(Duration.ofSeconds(5)));
  }

  /** R13. Every due widget refreshes, and none that are not due. */
  @Test
  void refreshesOnlyTheDueWidgetsAndWaitsForAll_startsARuntime() {
    configure("w-healthy", "Healthy", List.of("/alpha"), 3600);
    configure("w-partial", "Partial", List.of("/alpha", "/broken"), 3600);
    configure("w-down", "Down", List.of("/broken"), 3600);
    statuses.put("/broken", 500);
    page("p1", List.of("w-healthy", "w-partial", "w-down"));

    try (var refresh = refresher()) {
      var first = refresh.refreshDue("p1", T0);
      assertEquals(List.of("w-healthy", "w-partial", "w-down"),
          first.stream().map(PageRefresh.Rendered::id).toList());

      var healthy = first.get(0).state();
      assertEquals(2, healthy.items().size());
      assertTrue(healthy.contentAvailable());
      assertEquals(Instant.parse("2026-08-23T13:00:00Z"), healthy.nextUpdate());

      var partial = first.get(1).state();
      assertEquals("failed to retrieve some of the content: missing 1 RSS feeds", partial.notice());
      assertTrue(partial.contentAvailable());
      assertEquals(Instant.parse("2026-08-23T12:01:00Z"), partial.nextUpdate());

      var down = first.get(2).state();
      assertEquals("failed to retrieve any content", down.error());
      // This one has never succeeded, so the flag has never been turned on.
      assertFalse(down.contentAvailable());

      // R2: a second pass one second later finds nobody due, so nothing is fetched.
      var hitsBefore = alphaHits.get();
      refresh.refreshDue("p1", T0.plusSeconds(1));
      assertEquals(hitsBefore, alphaHits.get());

      // ...and a pass past the failing widgets' pulled-in deadline refreshes those two only.
      refresh.refreshDue("p1", T0.plusSeconds(120));
      assertEquals(hitsBefore + 1, alphaHits.get(), "only the partial widget's good feed refetched");
    }
  }

  /** R14. A container is due when a child is, and only the due child refreshes. */
  @Test
  void aContainerIsDueWhenAChildIs_startsARuntime() {
    configure("c-fresh", "Inner fresh", List.of("/alpha"), 3600);
    configure("c-stale", "Inner stale", List.of("/alpha"), 30);
    container("c-group", List.of("c-fresh", "c-stale"));
    page("p2", List.of("c-group"));

    try (var refresh = refresher()) {
      var first = refresh.refreshDue("p2", T0);
      assertEquals(1, first.size());
      assertTrue(first.get(0).isContainer());
      assertEquals(List.of("c-fresh", "c-stale"),
          first.get(0).children().stream().map(PageRefresh.Rendered::id).toList());
      var hitsAfterFirst = alphaHits.get();
      assertEquals(2, hitsAfterFirst);

      // 40 s on: the 30-second child is due, the hour-long one is not.
      refresh.refreshDue("p2", T0.plusSeconds(40));
      assertEquals(hitsAfterFirst + 1, alphaHits.get());
    }
  }

  /** R7, R13. A widget that loses everything keeps what it was showing. */
  @Test
  void aTotalFailureKeepsTheItemsOnThePage_startsARuntime() {
    configure("w-wobbly", "Wobbly", List.of("/alpha"), 30);
    page("p3", List.of("w-wobbly"));

    try (var refresh = refresher()) {
      var up = refresh.refreshDue("p3", T0).get(0).state();
      assertEquals(2, up.items().size());

      statuses.put("/alpha", 500);
      var down = refresh.refreshDue("p3", T0.plusSeconds(60)).get(0).state();
      assertEquals(2, down.items().size());
      assertEquals("failed to retrieve any content", down.error());
      // R7a: the widget succeeded a moment ago, so it still counts as having content
      // and the page draws it under a mark rather than replacing it with an error.
      assertTrue(down.contentAvailable());
    }
  }

  /** R12. The validator from one refresh is sent by the next, across the entity. */
  @Test
  void theConditionalRequestSurvivesTheEntity_startsARuntime() throws IOException {
    var served = new AtomicInteger();
    var notModified = new AtomicInteger();
    feeds.createContext(
        "/etag",
        exchange -> {
          if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            notModified.incrementAndGet();
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
          }
          served.incrementAndGet();
          var body = ALPHA.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("ETag", "\"v1\"");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    configure("w-etag", "Etag", List.of("/etag"), 30);
    page("p4", List.of("w-etag"));

    try (var refresh = refresher()) {
      assertEquals(2, refresh.refreshDue("p4", T0).get(0).state().items().size());
      var second = refresh.refreshDue("p4", T0.plusSeconds(60)).get(0).state();
      assertEquals(1, served.get());
      assertEquals(1, notModified.get());
      assertEquals(2, second.items().size());
      assertEquals(null, second.error());
    }
  }

  /** R15, and the markup: the page's content is what the original served for it. */
  @Test
  void thePageMarkupMatchesTheOriginal_startsARuntime() {
    configure("m-healthy", "Healthy", List.of("/alpha"), 3600);
    page("p5", List.of("m-healthy"));

    try (var refresh = refresher()) {
      var markup = PageRenderer.render(refresh.refreshDue("p5", T0));
      assertTrue(markup.startsWith("\n\n\n\n<div class=\"page-columns\">\n"));
      assertTrue(markup.contains("<h2 class=\"uppercase\">Healthy</h2>"));
      assertTrue(markup.contains("data-dynamic-relative-time=\"1709542800\""));
      assertTrue(markup.endsWith("\n\n\n\n\n    </div>\n</div>\n"));
    }
  }

  /**
   * N1 from the review pass. Timers are delivered at least once, so a widget's refresh can
   * arrive twice. The second delivery carries the token of the first and must change
   * nothing — otherwise one failed attempt advances the backoff twice and the widget waits
   * four minutes where the source waits one.
   */
  @Test
  void aRedeliveredRefreshIsAppliedOnce_startsARuntime() {
    configure("w-once", "Once", List.of("/broken"), 3600);
    statuses.put("/broken", 500);

    var outcome =
        new io.akka.glance.domain.RefreshOutcome(List.of(), 1, 1, java.util.Map.of());
    var applied =
        componentClient
            .forKeyValueEntity("w-once")
            .method(WidgetEntity::apply)
            .invoke(new WidgetEntity.Applied(outcome, T0, "token-1"));
    assertEquals(1, applied.updateRetriedTimes());
    assertEquals(Instant.parse("2026-08-23T12:01:00Z"), applied.nextUpdate());

    var again =
        componentClient
            .forKeyValueEntity("w-once")
            .method(WidgetEntity::apply)
            .invoke(new WidgetEntity.Applied(outcome, T0.plusSeconds(5), "token-1"));
    assertEquals(1, again.updateRetriedTimes());
    assertEquals(Instant.parse("2026-08-23T12:01:00Z"), again.nextUpdate());

    // A genuinely new attempt carries a new token and does advance the backoff.
    var second =
        componentClient
            .forKeyValueEntity("w-once")
            .method(WidgetEntity::apply)
            .invoke(new WidgetEntity.Applied(outcome, T0.plusSeconds(60), "token-2"));
    assertEquals(2, second.updateRetriedTimes());
    assertEquals(Instant.parse("2026-08-23T12:05:00Z"), second.nextUpdate());
  }
}
