package io.akka.glance.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.akka.glance.domain.FeedCacheEntry;
import io.akka.glance.domain.FeedItem;
import io.akka.glance.domain.FeedSpec;
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

/** SPEC-001 R12, R13. */
class FeedFetcherTest {

  private static final String ETAG = "\"fixture-v1\"";

  private HttpServer server;
  private String base;
  private final AtomicInteger hits = new AtomicInteger();
  private final AtomicInteger notModified = new AtomicInteger();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private FeedFetcher fetcher;

  private static String rss(String channel, String... titles) {
    var items = new StringBuilder();
    var when = Instant.parse("2024-03-04T09:00:00Z");
    for (var i = 0; i < titles.length; i++) {
      items
          .append("<item><title>")
          .append(titles[i])
          .append("</title><link>http://feeds.test/")
          .append(titles[i].replace(' ', '-'))
          .append("</link><pubDate>")
          .append(
              java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                  when.minus(Duration.ofHours(i)).atZone(java.time.ZoneOffset.UTC)))
          .append("</pubDate></item>");
    }
    return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>"
        + channel
        + "</title><link>http://feeds.test/</link>"
        + items
        + "</channel></rss>";
  }

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          var path = exchange.getRequestURI().getPath();
          var forced = statuses.get(path);
          if (forced != null) {
            exchange.sendResponseHeaders(forced, -1);
            exchange.close();
            return;
          }
          if (path.equals("/slow")) {
            try {
              Thread.sleep(300);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          if (ETAG.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            notModified.incrementAndGet();
            exchange.getResponseHeaders().add("ETag", ETAG);
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
          }
          var body = rss("Fixture " + path, "One", "Two").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
          exchange.getResponseHeaders().add("ETag", ETAG);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    // Without its own pool the fixture server answers one request at a time, and a
    // concurrency check against it measures the fixture rather than the fetcher.
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    fetcher = new FeedFetcher(Duration.ofSeconds(5));
  }

  @AfterEach
  void stop() {
    server.stop(0);
    ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    fetcher.close();
  }

  /** R12. A first fetch has nothing to send, and brings the validator back. */
  @Test
  void aFirstFetchStoresTheValidator() {
    var results = fetcher.fetchAll(List.of(new FeedSpec(base + "/a", null, 0)), Map.of());
    assertFalse(results.get(0).failed());
    assertEquals(2, results.get(0).items().size());
    assertEquals(ETAG, results.get(0).etag());
    assertEquals(0, notModified.get());
  }

  /** R12. A held validator is sent, and a 304 is a success answering with the stored items. */
  @Test
  void notModifiedReturnsTheStoredItems() {
    var stored =
        Map.of(
            base + "/a",
            new FeedCacheEntry(
                ETAG,
                null,
                List.of(
                    new FeedItem(
                        "Cached",
                        "http://feeds.test/cached",
                        "Fixture",
                        "http://feeds.test/",
                        Instant.parse("2024-03-04T09:00:00Z")))));

    var results = fetcher.fetchAll(List.of(new FeedSpec(base + "/a", null, 0)), stored);
    assertEquals(1, notModified.get());
    assertFalse(results.get(0).failed());
    assertEquals(List.of("Cached"), results.get(0).items().stream().map(FeedItem::title).toList());
    assertEquals(ETAG, results.get(0).etag());
  }

  /** R12. Every other non-200 is that feed failing, not an empty success. */
  @Test
  void otherStatusesCountAsAFailedFeed() {
    statuses.put("/broken", 500);
    statuses.put("/missing", 404);
    statuses.put("/moved", 302);

    var results =
        fetcher.fetchAll(
            List.of(
                new FeedSpec(base + "/broken", null, 0),
                new FeedSpec(base + "/missing", null, 0),
                new FeedSpec(base + "/moved", null, 0),
                new FeedSpec(base + "/ok", null, 0)),
            Map.of());

    assertEquals(List.of(true, true, true, false), results.stream().map(r -> r.failed()).toList());
  }

  /** R12. A URL that cannot be reached at all is a failed feed, not an exception. */
  @Test
  void anUnreachableHostIsAFailedFeed() {
    var results =
        fetcher.fetchAll(List.of(new FeedSpec("http://127.0.0.1:1/nope", null, 0)), Map.of());
    assertTrue(results.get(0).failed());
  }

  /** R12. Unparseable XML is a failed feed rather than a widget with nothing in it. */
  @Test
  void unparseableContentIsAFailedFeed() throws IOException {
    server.createContext(
        "/garbage",
        exchange -> {
          var body = "not xml at all".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    var results = fetcher.fetchAll(List.of(new FeedSpec(base + "/garbage", null, 0)), Map.of());
    assertTrue(results.get(0).failed());
  }

  /**
   * R13. The feeds of one widget are fetched at the same time, not one after another. Four
   * feeds each answering in about 300 ms finish in well under the 1.2 s a serial pass costs.
   */
  @Test
  void feedsAreFetchedConcurrently() {
    var specs =
        List.of(
            new FeedSpec(base + "/slow", null, 0),
            new FeedSpec(base + "/slow?b", null, 0),
            new FeedSpec(base + "/slow?c", null, 0),
            new FeedSpec(base + "/slow?d", null, 0));
    var started = System.nanoTime();
    var results = fetcher.fetchAll(specs, Map.of());
    var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertEquals(4, results.stream().filter(r -> !r.failed()).count());
    assertTrue(elapsedMillis < 900, "four 300ms feeds took " + elapsedMillis + "ms");
  }

  /** R12. The results come back in the order the feeds were asked for, whatever order they answered in. */
  @Test
  void resultsKeepTheOrderTheFeedsWereGivenIn() {
    statuses.put("/second", 500);
    var results =
        fetcher.fetchAll(
            List.of(
                new FeedSpec(base + "/slow", null, 0),
                new FeedSpec(base + "/second", null, 0),
                new FeedSpec(base + "/third", null, 0)),
            Map.of());
    assertEquals(List.of(false, true, false), results.stream().map(r -> r.failed()).toList());
  }

  /** An Atom document is read as well as an RSS one — both are ordinary feeds to a reader. */
  @Test
  void atomIsReadAsWellAsRss() throws IOException {
    server.createContext(
        "/atom",
        exchange -> {
          var body =
              ("<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\">"
                      + "<title>Atom Fixture</title><link href=\"http://feeds.test/\"/>"
                      + "<entry><title>Atom one</title>"
                      + "<link href=\"http://feeds.test/atom/1\"/>"
                      + "<updated>2024-03-04T09:00:00Z</updated></entry></feed>")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/atom+xml");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    var results = fetcher.fetchAll(List.of(new FeedSpec(base + "/atom", null, 0)), Map.of());
    assertFalse(results.get(0).failed());
    var item = results.get(0).items().get(0);
    assertEquals("Atom one", item.title());
    assertEquals("http://feeds.test/atom/1", item.link());
    assertEquals("Atom Fixture", item.channelName());
    assertEquals(Instant.parse("2024-03-04T09:00:00Z"), item.publishedAt());
  }

  /** A document naming an external entity does not get to read the machine it is parsed on. */
  @Test
  void anExternalEntityIsNotResolved() throws IOException {
    server.createContext(
        "/xxe",
        exchange -> {
          var body =
              ("<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                      + "<rss version=\"2.0\"><channel><title>&x;</title>"
                      + "<link>http://feeds.test/</link></channel></rss>")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    var results = fetcher.fetchAll(List.of(new FeedSpec(base + "/xxe", null, 0)), Map.of());
    assertTrue(results.get(0).failed());
  }
}
