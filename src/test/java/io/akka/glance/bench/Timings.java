package io.akka.glance.bench;

import io.akka.glance.domain.CacheMode;
import io.akka.glance.domain.FeedItem;
import io.akka.glance.domain.FeedResult;
import io.akka.glance.domain.FeedSpec;
import io.akka.glance.domain.FeedMerge;
import io.akka.glance.domain.WidgetRenderer;
import io.akka.glance.domain.WidgetState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The port's half of the benchmark's timing.
 *
 * <p>What is timed is the decision path: a refresh outcome merged, applied to a widget, and
 * rendered. No network, no entity, no runtime — the same three things the source's own
 * timing harness runs, so the two figures are about the rules rather than about the two
 * architectures around them. What each architecture costs is timed separately, over HTTP,
 * and reported beside this rather than folded into it.
 *
 * <p>Windows are sized from a pilot so that each one runs for tens of milliseconds, and
 * five of them are run and the median taken: a single sized window still moves by enough
 * between runs to reorder a table.
 *
 * <p>This is a measuring instrument rather than a check: it asserts nothing and it writes a
 * file. It has a {@code main} instead of a {@code @Test} for that reason — a class that
 * only runs when somebody names it on the command line reads, in a build's totals,
 * exactly like a test that passed.
 *
 * <pre>
 * mvn -o test-compile
 * mvn -o dependency:build-classpath -Dmdep.outputFile=target/test-cp.txt -Dmdep.includeScope=test
 * java -cp "target/test-classes;target/classes;$(cat target/test-cp.txt)"  *     io.akka.glance.bench.Timings
 * </pre>
 */
public final class Timings {

  private static final int WINDOWS = 5;
  private static final long TARGET_WINDOW_NANOS = 50_000_000L;
  private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

  private static FeedItem item(String feed, int i, Instant at) {
    return new FeedItem(
        feed + " " + i, "http://feeds.test/" + feed + "/" + i, feed, "http://feeds.test/", at);
  }

  private static List<FeedItem> itemsFor(String feed, int count) {
    var out = new ArrayList<FeedItem>();
    for (var i = 0; i < count; i++) {
      out.add(item(feed, i, Instant.parse("2024-03-04T09:00:00Z").minus(Duration.ofHours(i))));
    }
    return out;
  }

  private static WidgetState fresh() {
    return WidgetState.configured(
        "Timed", CacheMode.DURATION, Duration.ofHours(1), List.of(), 25, false);
  }

  /** One unit of the decision path, exactly as the source's harness defines it. */
  private static int decideAndRender(
      List<FeedSpec> specs, List<FeedResult> results, WidgetState state) {
    return WidgetRenderer.render(decideOnly(specs, results, state)).length();
  }

  /**
   * The same unit with the rendering taken out and the real code left around it. The two
   * systems draw the same markup by different means — a Go template against a string
   * builder — so a ratio quoted over decideAndRender is partly a ratio between two
   * template engines. Timing both says how much.
   */
  private static WidgetState decideOnly(
      List<FeedSpec> specs, List<FeedResult> results, WidgetState state) {
    var outcome = FeedMerge.merge(specs, results, false, state.limit());
    return state.applyOutcome(outcome, NOW);
  }

  private record Case(String name, List<FeedSpec> specs, List<FeedResult> results, boolean render) {}

  public static void main(String[] args) throws IOException {
    new Timings().writeTimings();
  }

  void writeTimings() throws IOException {
    var twoFeeds =
        List.of(new FeedSpec("http://a", null, 0), new FeedSpec("http://b", null, 0));
    var nothingFailed =
        List.of(
            FeedResult.fetched(itemsFor("alpha", 12), "\"v\"", null),
            FeedResult.fetched(itemsFor("beta", 12), "\"v\"", null));
    var oneFailed =
        List.of(FeedResult.fetched(itemsFor("alpha", 12), "\"v\"", null), FeedResult.ofFailure());
    var allFailed = List.of(FeedResult.ofFailure(), FeedResult.ofFailure());

    var cases =
        List.of(
            new Case("decide-and-render/nothing-failed", twoFeeds, nothingFailed, true),
            new Case("decide-and-render/one-of-two-failed", twoFeeds, oneFailed, true),
            new Case("decide-and-render/every-feed-failed", twoFeeds, allFailed, true),
            new Case("decide-only/nothing-failed", twoFeeds, nothingFailed, false),
            new Case("decide-only/one-of-two-failed", twoFeeds, oneFailed, false),
            new Case("decide-only/every-feed-failed", twoFeeds, allFailed, false));

    var rows = new java.util.LinkedHashMap<String, Object>();
    for (var testCase : cases) {
      rows.put(testCase.name(), measure(testCase));
    }

    var json = new StringBuilder("{\n  \"timing\": {\n");
    var first = true;
    for (var entry : rows.entrySet()) {
      if (!first) {
        json.append(",\n");
      }
      first = false;
      json.append("    \"").append(entry.getKey()).append("\": ").append(entry.getValue());
    }
    json.append("\n  }\n}\n");

    var out = Path.of("..", "glance-port", "bench", "port-timings.json");
    Files.writeString(out, json.toString());
    System.out.println("wrote " + out.toAbsolutePath().normalize() + "\n" + json);
  }

  private String measure(Case testCase) {
    var state = fresh();
    java.util.function.IntSupplier run =
        testCase.render()
            ? () -> decideAndRender(testCase.specs(), testCase.results(), state)
            : () -> decideOnly(testCase.specs(), testCase.results(), state).items().size();

    // Warm the JIT before anything is read off the clock.
    for (var i = 0; i < 20_000; i++) {
      run.getAsInt();
    }

    // A pilot says how many repetitions a window of tens of milliseconds needs. A window
    // shorter than the platform clock's resolution reports the clock.
    // The pilot grows until the clock registers it. A pilot that measures zero is a clock
    // that could not see the work, not work that took no time.
    var pilotRuns = 2_000;
    var pilotNanos = 0L;
    for (var attempt = 0; attempt < 8 && pilotNanos <= 0; attempt++) {
      var pilotStart = System.nanoTime();
      for (var i = 0; i < pilotRuns; i++) {
        run.getAsInt();
      }
      pilotNanos = System.nanoTime() - pilotStart;
      if (pilotNanos <= 0) {
        pilotRuns *= 10;
      }
    }
    if (pilotNanos <= 0) {
      throw new IllegalStateException("the pilot measured nothing: " + testCase.name());
    }
    var perRun = (double) pilotNanos / pilotRuns;
    var repetitions = (int) Math.max(1000, Math.ceil(TARGET_WINDOW_NANOS / perRun));

    var readings = new ArrayList<Long>();
    for (var window = 0; window < WINDOWS; window++) {
      var started = System.nanoTime();
      var sink = 0;
      for (var i = 0; i < repetitions; i++) {
        sink += run.getAsInt();
      }
      var elapsed = System.nanoTime() - started;
      if (sink == Integer.MIN_VALUE) {
        throw new IllegalStateException("unreachable, and enough to keep the work");
      }
      readings.add(elapsed);
    }
    readings.sort(Long::compare);
    // The median, never the minimum: a minimum picks the window the clock under-reported.
    var median = readings.get(readings.size() / 2);

    return String.format(
        "{\"repetitions\": %d, \"windows\": %d, \"windowNanos\": %d, \"nanosPerRun\": %.1f}",
        repetitions, WINDOWS, median, (double) median / repetitions);
  }
}
