package io.akka.glance.widget.kind;

import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.Endpoints;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.util.Xml;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The most recent uploads from a set of YouTube channels and playlists. */
public final class VideosWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template VIDEOS_GRID_WIDGET_BASE_VIDEO_CARD_CONTENTS = Templates.of("videos-grid.html", "widget-base.html", "video-card-contents.html");

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template VIDEOS_VERTICAL_LIST_WIDGET_BASE = Templates.of("videos-vertical-list.html", "widget-base.html");

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template VIDEOS_WIDGET_BASE_VIDEO_CARD_CONTENTS = Templates.of("videos.html", "widget-base.html", "video-card-contents.html");

  private static final String PLAYLIST_PREFIX = "playlist:";

  @Y(skip = true)
  public List<Video> Videos = new ArrayList<>();

  @Y("video-url-template")
  public String VideoUrlTemplate = "";

  @Y("style")
  public String Style = "";

  @Y("collapse-after")
  public int CollapseAfter;

  @Y("collapse-after-rows")
  public int CollapseAfterRows;

  @Y("channels")
  public List<String> Channels = new ArrayList<>();

  @Y("playlists")
  public List<String> Playlists = new ArrayList<>();

  @Y("limit")
  public int Limit;

  @Y("include-shorts")
  public boolean IncludeShorts;

  /** The last good list for each channel, which is what a failed refetch falls back to. */
  @Y(skip = true)
  private final Map<String, Cached> cachedVideoLists = new ConcurrentHashMap<>();

  private record Cached(List<Video> value, Instant timestamp) {}

  /** One upload. */
  public static final class Video {
    public String ThumbnailUrl = "";
    public String Title = "";
    public String Url = "";
    public String Author = "";
    public String AuthorUrl = "";
    public Instant TimePosted = Instant.EPOCH;
  }

  @Override
  public void initialize() {
    withTitle("Videos").withCacheDuration(Duration.ofHours(1));
    if (Limit <= 0) {
      Limit = 25;
    }
    if (CollapseAfterRows == 0 || CollapseAfterRows < -1) {
      CollapseAfterRows = 4;
    }
    if (CollapseAfter == 0 || CollapseAfter < -1) {
      CollapseAfter = 7;
    }
    // Playlists are a separate setting for the reader's sake; the fetching treats them as
    // channels carrying a prefix.
    for (var playlist : Playlists) {
      Channels.add(PLAYLIST_PREFIX + playlist);
    }
  }

  @Override
  public void update(Instant now) {
    var fetched = fetch(now);
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    var videos = fetched.value();
    if (videos.size() > Limit) {
      videos = new ArrayList<>(videos.subList(0, Limit));
    }
    Videos = videos;
  }

  /** The feed a channel or playlist identifier reads from. */
  public String feedUrl(String id) {
    if (id.startsWith(PLAYLIST_PREFIX)) {
      return Endpoints.youtube
          + "/feeds/videos.xml?playlist_id="
          + id.substring(PLAYLIST_PREFIX.length());
    }
    if (!IncludeShorts && id.startsWith("UC")) {
      // The uploads playlist whose identifier starts UULF holds the long videos only.
      return Endpoints.youtube + "/feeds/videos.xml?playlist_id=" + id.replaceFirst("UC", "UULF");
    }
    return Endpoints.youtube + "/feeds/videos.xml?channel_id=" + id;
  }

  private Fetched<List<Video>> fetch(Instant now) {
    var results = Fetches.pool(Channels, 30, id -> fetchOne(id, now));
    var videos = new ArrayList<Video>();
    int failed = 0;
    for (int i = 0; i < results.size(); i++) {
      var result = results.get(i);
      if (result.error() != null) {
        failed++;
        // A failed channel still contributes whatever was cached for it.
        var cached = cachedVideoLists.get(Channels.get(i));
        if (cached != null) {
          videos.addAll(cached.value());
        }
        continue;
      }
      videos.addAll(result.value());
    }
    if (videos.isEmpty()) {
      return Fetched.failed(Err.NO_CONTENT);
    }
    videos.sort(Comparator.comparing((Video video) -> video.TimePosted).reversed());
    if (failed > 0) {
      return Fetched.of(
          videos, Err.PARTIAL_CONTENT.because("missing videos from " + failed + " channels"));
    }
    return Fetched.of(videos);
  }

  private List<Video> fetchOne(String id, Instant now) {
    var cached = cachedVideoLists.get(id);
    if (cached != null && Duration.between(cached.timestamp(), now).compareTo(cacheDuration) < 0) {
      return cached.value();
    }
    var response =
        Requests.send(HttpClients.standard(), Requests.get(feedUrl(id)).build()).body();
    var feed = Xml.parse(response);
    var list = new ArrayList<Video>(15);
    var author = feed.child("author");
    String channel = author == null ? "" : author.text("name");
    String channelLink = author == null ? "" : author.text("uri");
    for (var entry : feed.children("entry")) {
      var video = new Video();
      var group = entry.child("group");
      var thumbnail = group == null ? null : group.child("thumbnail");
      video.ThumbnailUrl = thumbnail == null ? "" : thumbnail.attribute("url");
      video.Title = entry.text("title");
      var link = entry.child("link");
      String href = link == null ? "" : link.attribute("href");
      video.Url = VideoUrlTemplate.isEmpty() ? href : templatedUrl(href);
      video.Author = channel;
      video.AuthorUrl = channelLink + "/videos";
      video.TimePosted = parsePublished(entry.text("published"), now);
      list.add(video);
    }
    cachedVideoLists.put(id, new Cached(list, now));
    return list;
  }

  private String templatedUrl(String href) {
    try {
      var uri = URI.create(href);
      String id = "";
      String query = uri.getQuery();
      if (query != null) {
        for (var pair : query.split("&")) {
          int equals = pair.indexOf('=');
          if (equals > 0 && pair.substring(0, equals).equals("v")) {
            id = pair.substring(equals + 1);
            break;
          }
        }
      }
      return VideoUrlTemplate.replace("{VIDEO-ID}", id);
    } catch (IllegalArgumentException e) {
      return "#";
    }
  }

  private static Instant parsePublished(String value, Instant now) {
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (RuntimeException e) {
      return now;
    }
  }

  @Override
  public Safe Render() {
    return switch (Style) {
      case "grid-cards" ->
          renderTemplate(
              this,
              VIDEOS_GRID_WIDGET_BASE_VIDEO_CARD_CONTENTS);
      case "vertical-list" ->
          renderTemplate(this, VIDEOS_VERTICAL_LIST_WIDGET_BASE);
      default ->
          renderTemplate(
              this, VIDEOS_WIDGET_BASE_VIDEO_CARD_CONTENTS);
    };
  }
}
