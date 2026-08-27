package io.akka.glance.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.akka.glance.config.Config;
import io.akka.glance.config.ConfigLoader;
import io.akka.glance.widget.Widget;
import io.akka.glance.widget.kind.RssWidget;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs the benchmark's workloads against the rebuild.
 *
 * <p>The counterpart of {@code glance-port/probes/source_probe/bench_complete.go}: the same
 * workload file, the same fixture server and the same shape of answer, so the two sequences
 * can be compared step by step.
 *
 * <p>Every widget's own code runs. The clock is the real one, as it is on the source side,
 * and a deadline is recorded relative to the instant of the pass rather than as an absolute
 * instant, so nothing in an answer moves with when the run happened.
 */
public final class BenchDriver {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private BenchDriver() {}

  /** One step's answer, in the shape the sequence probe reads. */
  public record Answer(int step, String order, String outcome) {}

  public static void main(String[] args) throws Exception {
    Path workloadsPath = Path.of(args[0]);
    Path out = Path.of(args[1]);
    String fixtures = args.length > 2 ? args[2] : "http://127.0.0.1:8390";
    CompleteDriver.pointAt(fixtures);

    var workloads = MAPPER.readTree(Files.readString(workloadsPath));
    var answers = new LinkedHashMap<String, List<Answer>>();

    for (var workload : workloads) {
      String name = workload.path("name").asText();
      defineFeeds(fixtures, workload.path("feeds"));
      if (workload.path("sequence").isTextual()
          && workload.path("sequence").asText().equals("arrival-orders")) {
        answers.put(name, runOrders(fixtures, workload));
        continue;
      }
      answers.put(name, runSteps(fixtures, workload));
    }

    Files.writeString(out, MAPPER.writeValueAsString(Map.of("answers", answers)));
  }

  private static void defineFeeds(String fixtures, JsonNode feeds) throws IOException {
    if (!feeds.isObject()) {
      return;
    }
    var names = feeds.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      put(fixtures + "/dyn/" + name, feeds.get(name).toString());
    }
  }

  private static List<Answer> runSteps(String fixtures, JsonNode workload) throws Exception {
    var pages = load(workload.path("config").asText());
    var out = new ArrayList<Answer>();
    var steps = workload.path("sequence");
    for (int i = 0; i < steps.size(); i++) {
      var step = steps.get(i);
      applyControl(fixtures, step.path("control"));
      int sleep = step.path("sleepMillis").asInt();
      if (sleep > 0) {
        Thread.sleep(sleep);
      }
      var at = Instant.now();
      refresh(pages, at);
      out.add(new Answer(i, null, outcome(pages, at)));
    }
    return out;
  }

  private static List<Answer> runOrders(String fixtures, JsonNode workload) throws Exception {
    var rows = workload.path("rows");
    var out = new ArrayList<Answer>();
    for (var order : workload.path("orders")) {
      var items = MAPPER.createArrayNode();
      var labels = new ArrayList<String>();
      for (var index : order) {
        items.add(rows.get(index.asInt()));
        labels.add(String.valueOf(index.asInt()));
      }
      var feed = MAPPER.createObjectNode();
      feed.put("title", "Ordered");
      feed.set("items", items);
      put(fixtures + "/dyn/ordered", feed.toString());

      var pages = load(workload.path("config").asText());
      var at = Instant.now();
      refresh(pages, at);
      out.add(new Answer(0, String.join(",", labels), outcome(pages, at)));
    }
    return out;
  }

  /**
   * Every page the configuration holds, not only the first: a workload may put a whole
   * dashboard to both systems rather than one widget.
   */
  private static List<Config.Page> load(String yaml) {
    var config = ConfigLoader.fromYaml(yaml);
    // Built through the application, because that is what gives a widget its providers and
    // fills in the page's own defaults.
    new io.akka.glance.app.Application(config, Instant.now());
    return List.copyOf(config.Pages);
  }

  /** One page pass: every widget that is due, refreshed at once, and the pass waits. */
  private static void refresh(List<Config.Page> pages, Instant at) throws Exception {
    var running = new ArrayList<java.util.concurrent.Future<?>>();
    for (var page : pages) {
      for (var widget : page.HeadWidgets) {
        if (widget.requiresUpdate(at)) {
          running.add(io.akka.glance.widget.Fetches.submit(() -> widget.update(at)));
        }
      }
      for (var column : page.Columns) {
        for (var widget : column.Widgets) {
          if (widget.requiresUpdate(at)) {
            running.add(io.akka.glance.widget.Fetches.submit(() -> widget.update(at)));
          }
        }
      }
    }
    for (var future : running) {
      future.get();
    }
  }

  /** The same line the source's probe writes, field for field. */
  private static String outcome(List<Config.Page> pages, Instant at) {
    var parts = new ArrayList<String>();
    for (var page : pages) {
      for (var widget : page.HeadWidgets) {
        parts.add(describe(widget, at));
      }
      for (var column : page.Columns) {
        for (var widget : column.Widgets) {
          parts.add(describe(widget, at));
        }
      }
    }
    return String.join(" ;; ", parts);
  }

  private static String describe(Widget widget, Instant at) {
    String due = "none";
    if (widget.nextUpdate() != null
        && widget.nextUpdate().atZone(java.time.ZoneId.systemDefault()).getMinute() == 0
        && widget.nextUpdate().atZone(java.time.ZoneId.systemDefault()).getSecond() == 0) {
      // An absolute deadline rather than one measured from this pass: the countdown to it
      // depends on when the run happened, and the deadline itself does not. The fraction of
      // a second is carried over from the instant of the refresh, so only the minute and the
      // second say whether a deadline is on the hour.
      due = "top-of-hour";
    } else if (widget.nextUpdate() != null) {
      // Rounded to the nearest second, half away from zero, which is what Go's own
      // duration rounding does — a deadline landing on a half second is then not a
      // difference between the two.
      long millis = Duration.between(at, widget.nextUpdate()).toMillis();
      long seconds =
          millis < 0
              ? (long) Math.ceil(millis / 1000.0 - 0.5)
              : (long) Math.floor(millis / 1000.0 + 0.5);
      due = String.valueOf(seconds);
    }
    var links = new ArrayList<String>();
    if (widget instanceof RssWidget rss) {
      for (var item : rss.Items) {
        links.add(item.Link);
      }
    }
    return widget.Title
        + "|available="
        + widget.ContentAvailable
        + "|error="
        + (widget.Error == null ? "" : widget.Error.message())
        + "|notice="
        + (widget.Notice == null ? "" : widget.Notice.message())
        + "|retries="
        + widget.updateRetriedTimes()
        + "|due="
        + due
        + "|links="
        + String.join(",", links);
  }

  private static void applyControl(String fixtures, JsonNode control) throws IOException {
    if (!control.isObject()) {
      return;
    }
    // Sorted, because a step that breaks one feed and repairs another has to do both in
    // the same order on both sides.
    var ordered = new TreeMap<String, String>();
    control.fields().forEachRemaining(entry -> ordered.put(entry.getKey(), entry.getValue().asText()));
    for (var entry : ordered.entrySet()) {
      get(fixtures + "/control/" + entry.getKey() + "/" + entry.getValue());
    }
  }

  private static void get(String url) throws IOException {
    try {
      CLIENT.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
          HttpResponse.BodyHandlers.discarding());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void put(String url, String body) throws IOException {
    try {
      CLIENT.send(
          HttpRequest.newBuilder(URI.create(url))
              .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .header("Content-Type", "application/json")
              .build(),
          HttpResponse.BodyHandlers.discarding());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
