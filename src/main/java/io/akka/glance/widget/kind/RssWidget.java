package io.akka.glance.widget.kind;

import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.util.Feed;
import io.akka.glance.util.Text;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Entries from any number of syndication feeds, in one list. */
public final class RssWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template RSS_DETAILED_LIST_WIDGET_BASE = Templates.of("rss-detailed-list.html", "widget-base.html");

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template RSS_HORIZONTAL_CARDS_2_WIDGET_BASE = Templates.of("rss-horizontal-cards-2.html", "widget-base.html");

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template RSS_HORIZONTAL_CARDS_WIDGET_BASE = Templates.of("rss-horizontal-cards.html", "widget-base.html");

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template RSS_LIST_WIDGET_BASE = Templates.of("rss-list.html", "widget-base.html");

  /** A tag with any attributes it carries, which a description is stripped of. */
  private static final Pattern HTML_TAG =
      Pattern.compile("</?[a-zA-Z0-9-]+ *(?:[a-zA-Z-]+=(?:\"|').*?(?:\"|') ?)* */?>");

  @Y("feeds")
  public List<FeedRequest> FeedRequests = new ArrayList<>();

  @Y("style")
  public String Style = "";

  @Y("thumbnail-height")
  public double ThumbnailHeight;

  @Y("card-height")
  public double CardHeight;

  @Y("limit")
  public int Limit;

  @Y("collapse-after")
  public int CollapseAfter;

  @Y("single-line-titles")
  public boolean SingleLineTitles;

  @Y("preserve-order")
  public boolean PreserveOrder;

  @Y(skip = true)
  public List<Item> Items = new ArrayList<>();

  @Y(skip = true)
  public String NoItemsMessage = "";

  /** What each feed answered last, so that an unchanged one need not be read again. */
  @Y(skip = true)
  private final Map<String, Cached> cachedFeeds = new ConcurrentHashMap<>();

  private record Cached(String etag, String lastModified, List<Item> items) {}

  /** One feed to read. */
  public static final class FeedRequest {
    @Y("url")
    public String URL = "";

    @Y("title")
    public String Title = "";

    @Y("hide-categories")
    public boolean HideCategories;

    @Y("hide-description")
    public boolean HideDescription;

    @Y("limit")
    public int Limit;

    @Y("item-link-prefix")
    public String ItemLinkPrefix = "";

    @Y("headers")
    public Map<String, String> Headers = new LinkedHashMap<>();

    @Y(skip = true)
    public boolean IsDetailed;
  }

  /** One entry, as the templates show it. */
  public static final class Item {
    public String ChannelName = "";
    public String ChannelURL = "";
    public String Title = "";
    public String Link = "";
    public String ImageURL = "";
    public List<String> Categories = new ArrayList<>();
    public String Description = "";
    public Instant PublishedAt = Instant.EPOCH;
  }

  @Override
  public void initialize() {
    withTitle("RSS Feed").withCacheDuration(Duration.ofHours(2));
    if (Limit <= 0) {
      Limit = 25;
    }
    if (CollapseAfter == 0 || CollapseAfter < -1) {
      CollapseAfter = 5;
    }
    if (ThumbnailHeight < 0) {
      ThumbnailHeight = 0;
    }
    if (CardHeight < 0) {
      CardHeight = 0;
    }
    if (Style.equals("detailed-list")) {
      for (var request : FeedRequests) {
        request.IsDetailed = true;
      }
    }
    NoItemsMessage = "No items were returned from the feeds.";
  }

  @Override
  public void update(Instant now) {
    var fetched = fetch(now);
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    var items = fetched.value();
    if (!PreserveOrder) {
      items.sort(Comparator.comparing((Item item) -> item.PublishedAt).reversed());
    }
    if (items.size() > Limit) {
      items = new ArrayList<>(items.subList(0, Limit));
    }
    Items = items;
  }

  private Fetched<List<Item>> fetch(Instant now) {
    var results = Fetches.pool(FeedRequests, 30, request -> fetchOne(request, now));
    int failed = 0;
    var entries = new ArrayList<Item>();
    var seen = new LinkedHashSet<String>();
    for (var result : results) {
      if (result.error() != null) {
        failed++;
        continue;
      }
      for (var item : result.value()) {
        if (seen.add(item.Link)) {
          entries.add(item);
        }
      }
    }
    if (failed == FeedRequests.size()) {
      return Fetched.failed(Err.NO_CONTENT);
    }
    if (failed > 0) {
      return Fetched.of(
          entries, Err.PARTIAL_CONTENT.because("missing " + failed + " RSS feeds"));
    }
    return Fetched.of(entries);
  }

  private List<Item> fetchOne(FeedRequest request, Instant now) {
    var builder = Requests.get(request.URL).header("User-Agent", Requests.USER_AGENT);
    var cached = cachedFeeds.get(request.URL);
    if (cached != null) {
      if (!cached.etag().isEmpty()) {
        builder.header("If-None-Match", cached.etag());
      }
      if (!cached.lastModified().isEmpty()) {
        builder.header("If-Modified-Since", cached.lastModified());
      }
    }
    Requests.withHeaders(builder, request.Headers);
    var response = Requests.sendRaw(HttpClients.standard(), builder.build());
    if (response.statusCode() == 304 && cached != null) {
      return cached.items();
    }
    if (response.statusCode() != 200) {
      throw new Fetches.FetchException(
          "unexpected status code " + response.statusCode() + " from " + request.URL);
    }
    var feed = Feed.parse(response.body());
    var feedItems = feed.Items;
    if (request.Limit > 0 && feedItems.size() > request.Limit) {
      feedItems = feedItems.subList(0, request.Limit);
    }
    var items = new ArrayList<Item>(feedItems.size());
    for (var source : feedItems) {
      var item = new Item();
      item.ChannelURL = feed.Link;
      item.Link = resolveLink(request, feed, source.Link);
      item.Title =
          source.Title.isEmpty()
              ? shortenDescription(source.Description, 100)
              : Text.unescapeHtml(source.Title);
      if (request.IsDetailed) {
        if (!request.HideDescription
            && !source.Description.isEmpty()
            && !source.Title.isEmpty()) {
          item.Description = shortenDescription(source.Description, 200);
        }
        if (!request.HideCategories) {
          var categories = new ArrayList<String>(6);
          for (var category : source.Categories) {
            if (categories.size() == 6) {
              break;
            }
            if (category.isEmpty() || category.length() > 30) {
              continue;
            }
            categories.add(category);
          }
          item.Categories = categories;
        }
      }
      item.ChannelName = request.Title.isEmpty() ? feed.Title : request.Title;
      if (!source.ImageURL.isEmpty()) {
        item.ImageURL = source.ImageURL;
      } else if (!feed.ImageURL.isEmpty()) {
        item.ImageURL =
            feed.ImageURL.charAt(0) == '/'
                ? trimTrailingSlashes(feed.Link) + feed.ImageURL
                : feed.ImageURL;
      }
      item.PublishedAt = source.Published == null ? now : source.Published;
      items.add(item);
    }
    String etag = response.headers().firstValue("ETag").orElse("");
    String lastModified = response.headers().firstValue("Last-Modified").orElse("");
    if (!etag.isEmpty() || !lastModified.isEmpty()) {
      cachedFeeds.put(request.URL, new Cached(etag, lastModified, items));
    }
    return items;
  }

  /**
   * An entry's own address, made absolute against the feed's when it is not already one and
   * no prefix was configured.
   */
  private static String resolveLink(FeedRequest request, Feed feed, String link) {
    if (!request.ItemLinkPrefix.isEmpty()) {
      return request.ItemLinkPrefix + link;
    }
    if (link.startsWith("http://") || link.startsWith("https://")) {
      return link;
    }
    URI base = tryParse(feed.Link);
    if (base == null) {
      base = tryParse(request.URL);
    }
    if (base == null || base.getScheme() == null || base.getHost() == null) {
      return "";
    }
    String path = !link.isEmpty() && link.charAt(0) == '/' ? link : "/" + link;
    return base.getScheme() + "://" + base.getHost() + path;
  }

  private static URI tryParse(String value) {
    try {
      return URI.create(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String trimTrailingSlashes(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }

  /** A description as plain text, cut to length, with an ellipsis when it was cut. */
  public static String shortenDescription(String description, int max) {
    String limited = Text.limitStringLength(description, 1000).value();
    String cleaned = sanitize(limited);
    var result = Text.limitStringLength(cleaned, max);
    return result.wasLimited() ? result.value() + "…" : result.value();
  }

  public static String sanitize(String description) {
    if (description.isEmpty()) {
      return "";
    }
    String out = description.replace("\n", " ");
    out = HTML_TAG.matcher(out).replaceAll("");
    out = Text.SEQUENTIAL_WHITESPACE.matcher(out).replaceAll(" ");
    out = out.trim();
    return Text.unescapeHtml(out);
  }

  @Override
  public Safe Render() {
    return switch (Style) {
      case "horizontal-cards" ->
          renderTemplate(this, RSS_HORIZONTAL_CARDS_WIDGET_BASE);
      case "horizontal-cards-2" ->
          renderTemplate(this, RSS_HORIZONTAL_CARDS_2_WIDGET_BASE);
      case "detailed-list" ->
          renderTemplate(this, RSS_DETAILED_LIST_WIDGET_BASE);
      default -> renderTemplate(this, RSS_LIST_WIDGET_BASE);
    };
  }
}
