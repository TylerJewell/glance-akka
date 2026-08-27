package io.akka.glance.widget.kind;

import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.util.Text;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** What a changedetection.io instance has seen change. */
public final class ChangeDetectionWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("change-detection.html", "widget-base.html");

  @Y(skip = true)
  public List<Watch> ChangeDetections = new ArrayList<>();

  @Y("watches")
  public List<String> WatchUUIDs = new ArrayList<>();

  @Y("instance-url")
  public String InstanceURL = "";

  @Y("token")
  public String Token = "";

  @Y("limit")
  public int Limit;

  @Y("collapse-after")
  public int CollapseAfter;

  /** One watched page. */
  public static final class Watch {
    public String Title = "";
    public String URL = "";
    public Instant LastChanged = Instant.EPOCH;
    public String DiffURL = "";
    public String PreviousHash = "";
  }

  @Override
  public void initialize() {
    withTitle("Change Detection").withCacheDuration(Duration.ofHours(1));
    if (Limit <= 0) {
      Limit = 10;
    }
    if (CollapseAfter == 0 || CollapseAfter < -1) {
      CollapseAfter = 5;
    }
    if (InstanceURL.isEmpty()) {
      InstanceURL = "https://www.changedetection.io";
    }
  }

  @Override
  public void update(Instant now) {
    if (WatchUUIDs.isEmpty()) {
      Fetched<List<String>> uuids = fetchWatchUuids();
      if (!canContinueUpdateAfterHandlingErr(uuids.error(), now)) {
        return;
      }
      WatchUUIDs = uuids.value();
    }
    var fetched = fetchWatches();
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    var watches = fetched.value();
    if (watches.size() > Limit) {
      watches = new ArrayList<>(watches.subList(0, Limit));
    }
    ChangeDetections = watches;
  }

  private Fetched<List<String>> fetchWatchUuids() {
    try {
      var builder = Requests.get(InstanceURL + "/api/v1/watch");
      if (!Token.isEmpty()) {
        builder.header("x-api-key", Token);
      }
      var response = Requests.json(HttpClients.standard(), builder.build());
      var uuids = new ArrayList<String>();
      response.fieldNames().forEachRemaining(uuids::add);
      return Fetched.of(uuids);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(Err.of("could not fetch list of watch UUIDs: " + e.error()));
    }
  }

  private Fetched<List<Watch>> fetchWatches() {
    var watches = new ArrayList<Watch>(WatchUUIDs.size());
    if (WatchUUIDs.isEmpty()) {
      return Fetched.of(watches);
    }
    var results =
        Fetches.pool(
            WatchUUIDs,
            15,
            uuid -> {
              var builder = Requests.get(InstanceURL + "/api/v1/watch/" + uuid);
              if (!Token.isEmpty()) {
                builder.header("x-api-key", Token);
              }
              return Requests.json(HttpClients.standard(), builder.build());
            });
    int failed = 0;
    for (int i = 0; i < results.size(); i++) {
      var result = results.get(i);
      if (result.error() != null) {
        failed++;
        continue;
      }
      var node = result.value();
      var watch = new Watch();
      long lastChanged = node.path("last_changed").asLong();
      watch.URL = node.path("url").asText("");
      watch.DiffURL =
          InstanceURL + "/diff/" + WatchUUIDs.get(i) + "?from_version=" + (lastChanged - 1);
      watch.LastChanged =
          Instant.ofEpochSecond(
              lastChanged == 0 ? node.path("date_created").asLong() : lastChanged);
      String title = node.path("title").asText("");
      watch.Title =
          !title.isEmpty()
              ? title
              : stripPrefix(trim(Text.stripUrlScheme(watch.URL), '/'), "www.");
      String hash = node.path("previous_md5").asText("");
      if (!hash.isEmpty()) {
        watch.PreviousHash = hash.substring(0, Math.min(8, hash.length()));
      }
      watches.add(watch);
    }
    if (watches.isEmpty()) {
      return Fetched.failed(Err.NO_CONTENT);
    }
    watches.sort(Comparator.comparing((Watch watch) -> watch.LastChanged).reversed());
    if (failed > 0) {
      return Fetched.of(
          watches, Err.PARTIAL_CONTENT.because("could not get " + failed + " watches"));
    }
    return Fetched.of(watches);
  }

  private static String trim(String value, char cut) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == cut) {
      start++;
    }
    while (end > start && value.charAt(end - 1) == cut) {
      end--;
    }
    return value.substring(start, end);
  }

  private static String stripPrefix(String value, String prefix) {
    return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
