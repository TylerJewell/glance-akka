package io.akka.glance.application;

import io.akka.glance.domain.FeedCacheEntry;
import io.akka.glance.domain.FeedItem;
import io.akka.glance.domain.FeedResult;
import io.akka.glance.domain.FeedSpec;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Fetches every feed of one widget. SPEC-001 R12, R13.
 *
 * <p>Feeds run at the same time and the results come back in the order they were asked for,
 * so a widget's items do not depend on which server answered first. A feed that fails in any
 * way — unreachable, a status other than 200 or 304, or a document that will not parse —
 * comes back as a failed result rather than as an exception, because it is the widget that
 * decides what one failure out of several means (SPEC-001 R9).
 */
public final class FeedFetcher implements AutoCloseable {

  /** The source's own per-request timeout. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private static final String USER_AGENT = "glance-akka +https://github.com/glanceapp/glance";

  private final HttpClient http;
  private final ExecutorService workers;
  private final Duration timeout;

  public FeedFetcher(Duration timeout) {
    this.timeout = timeout;
    this.workers = Executors.newVirtualThreadPerTaskExecutor();
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            // The source treats a redirect as a status other than 200, so a feed that moves
            // is a failed feed rather than a followed one.
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(workers)
            .build();
  }

  public List<FeedResult> fetchAll(List<FeedSpec> specs, Map<String, FeedCacheEntry> cache) {
    var pending = new ArrayList<CompletableFuture<FeedResult>>(specs.size());
    for (var spec : specs) {
      pending.add(CompletableFuture.supplyAsync(() -> fetchOne(spec, cache.get(spec.url())), workers));
    }
    var results = new ArrayList<FeedResult>(specs.size());
    for (var future : pending) {
      try {
        results.add(future.join());
      } catch (RuntimeException e) {
          results.add(FeedResult.ofFailure());
      }
    }
    return List.copyOf(results);
  }

  private FeedResult fetchOne(FeedSpec spec, FeedCacheEntry cached) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(spec.url()))
              .timeout(timeout)
              .header("User-Agent", USER_AGENT);
      if (cached != null) {
        if (cached.etag() != null && !cached.etag().isEmpty()) {
          request.header("If-None-Match", cached.etag());
        }
        if (cached.lastModified() != null && !cached.lastModified().isEmpty()) {
          request.header("If-Modified-Since", cached.lastModified());
        }
      }

      var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());

      if (response.statusCode() == 304 && cached != null) {
        return FeedResult.fetched(cached.items(), cached.etag(), cached.lastModified());
      }
      if (response.statusCode() != 200) {
        return FeedResult.ofFailure();
      }

      var items = parse(response.body(), spec.url());
      return FeedResult.fetched(
          items,
          response.headers().firstValue("ETag").orElse(null),
          response.headers().firstValue("Last-Modified").orElse(null));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return FeedResult.ofFailure();
    } catch (Exception e) {
      return FeedResult.ofFailure();
    }
  }

  private static List<FeedItem> parse(byte[] body, String requestUrl) throws Exception {
    // Built per call: a DocumentBuilderFactory is not safe to share across threads, and a
    // widget's feeds are parsed at the same time.
    var factory = DocumentBuilderFactory.newInstance();
    // A feed is somebody else's document. Refusing a doctype declaration outright is what
    // stops one naming a file on the machine that parses it.
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setNamespaceAware(false);
    factory.setExpandEntityReferences(false);

    var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
    var root = document.getDocumentElement();

    if ("feed".equalsIgnoreCase(root.getTagName())) {
      return parseAtom(root, requestUrl);
    }
    return parseRss(root, requestUrl);
  }

  private static List<FeedItem> parseRss(Element root, String requestUrl) {
    var channel = firstChild(root, "channel");
    if (channel == null) {
      return List.of();
    }
    var channelName = text(firstChild(channel, "title"), "");
    var channelUrl = text(firstChild(channel, "link"), requestUrl);

    var items = new ArrayList<FeedItem>();
    for (var item : children(channel, "item")) {
      var link = text(firstChild(item, "link"), "");
      items.add(
          new FeedItem(
              text(firstChild(item, "title"), ""),
              absolute(link, channelUrl, requestUrl),
              channelName,
              channelUrl,
              parseInstant(text(firstChild(item, "pubDate"), null))));
    }
    return List.copyOf(items);
  }

  private static List<FeedItem> parseAtom(Element root, String requestUrl) {
    var channelName = text(firstChild(root, "title"), "");
    var channelUrl = hrefOf(firstChild(root, "link"), requestUrl);

    var items = new ArrayList<FeedItem>();
    for (var entry : children(root, "entry")) {
      var published = text(firstChild(entry, "published"), null);
      if (published == null) {
        published = text(firstChild(entry, "updated"), null);
      }
      items.add(
          new FeedItem(
              text(firstChild(entry, "title"), ""),
              absolute(hrefOf(firstChild(entry, "link"), ""), channelUrl, requestUrl),
              channelName,
              channelUrl,
              parseInstant(published)));
    }
    return List.copyOf(items);
  }

  private static String absolute(String link, String channelUrl, String requestUrl) {
    if (link == null || link.isEmpty()) {
      return "";
    }
    if (link.startsWith("http://") || link.startsWith("https://")) {
      return link;
    }
    // A relative link is resolved against the channel's own address, and against the
    // address it was fetched from when the channel does not give one.
    var basis = channelUrl == null || channelUrl.isEmpty() ? requestUrl : channelUrl;
    try {
      return URI.create(basis).resolve(link).toString();
    } catch (IllegalArgumentException e) {
      return link;
    }
  }

  /**
   * A missing or unreadable publication instant becomes the epoch rather than an error: the
   * item still exists, and the sort has to put it somewhere.
   */
  private static Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) {
      return Instant.EPOCH;
    }
    var trimmed = raw.trim();
    for (var format :
        List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME)) {
      try {
        return ZonedDateTime.parse(trimmed, format).toInstant();
      } catch (RuntimeException ignored) {
        // Try the next shape.
      }
    }
    try {
      return Instant.parse(trimmed);
    } catch (RuntimeException e) {
      return Instant.EPOCH;
    }
  }

  private static Element firstChild(Element parent, String name) {
    var found = children(parent, name);
    return found.isEmpty() ? null : found.get(0);
  }

  private static List<Element> children(Element parent, String name) {
    var out = new ArrayList<Element>();
    NodeList nodes = parent.getChildNodes();
    for (var i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node instanceof Element element && localName(element).equalsIgnoreCase(name)) {
        out.add(element);
      }
    }
    return out;
  }

  private static String localName(Element element) {
    var tag = element.getTagName();
    var colon = tag.indexOf(':');
    return colon < 0 ? tag : tag.substring(colon + 1);
  }

  private static String text(Element element, String fallback) {
    if (element == null) {
      return fallback;
    }
    var content = element.getTextContent();
    return content == null || content.isBlank() ? fallback : content.trim();
  }

  private static String hrefOf(Element link, String fallback) {
    if (link == null) {
      return fallback;
    }
    var href = link.getAttribute("href");
    return href == null || href.isEmpty() ? text(link, fallback) : href;
  }

  @Override
  public void close() {
    http.close();
    workers.shutdown();
  }
}
