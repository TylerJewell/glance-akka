package io.akka.glance.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.akka.glance.app.Application;
import io.akka.glance.config.Config;
import io.akka.glance.config.ConfigLoader;
import io.akka.glance.config.Includes;
import io.akka.glance.render.Templates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Times the rebuild at the two things a request costs: building an application from a
 * configuration, and rendering a page once every widget is fresh.
 *
 * <p>The counterpart of {@code probes/source_probe/timing_complete.go}, measured the same
 * way: a pilot doubles a repetition count until one window runs for at least fifty
 * milliseconds, five windows are taken, and the figure is the median divided by what was in
 * it.
 *
 * <p>Two things a Java figure needs and a Go one does not. The result of every timed call is
 * read into a value checked afterwards, so the call cannot be proven dead and removed; and
 * the loop cycles over four different pages rather than repeating one, so the call cannot be
 * proven constant and hoisted out. Either alone leaves a loop the compiler may delete, which
 * reports as a flat zero.
 */
public final class TimingDriver {

  /** What a window aims for, in nanoseconds. */
  private static final long TARGET_WINDOW = 50_000_000L;

  /**
   * How many windows one figure is the median of. Nine rather than five: on a warmed
   * virtual machine the spread between two runs of the same work was about a third of the
   * figure at five, which is larger than some of the gaps the table exists to show.
   */
  private static final int WINDOWS = 9;

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private TimingDriver() {}

  /** One figure, and how it was arrived at. */
  public record Row(long repetitions, int windows, long windowNanos, double nanosPerRun) {}

  private static long sink;

  public static void main(String[] args) throws Exception {
    Path configPath = Path.of(args[0]);
    Path out = Path.of(args[1]);
    String fixtures = args.length > 2 ? args[2] : "http://127.0.0.1:8390";
    CompleteDriver.pointAt(fixtures);

    String contents = Includes.parse(configPath).contents();

    var config = ConfigLoader.fromYaml(contents);
    var application = new Application(config, Instant.now());
    var now = Instant.now();
    for (var page : config.Pages) {
      io.akka.glance.app.Site.refresh(page, now);
    }

    var rows = new LinkedHashMap<String, Row>();

    rows.put(
        "build-application",
        measure(
            repetitions -> {
              for (long i = 0; i < repetitions; i++) {
                var built = ConfigLoader.fromYaml(contents);
                sink += built.Pages.size();
              }
            }));

    var pages = List.copyOf(config.Pages);
    var contentTemplate = Templates.of("page-content.html");
    rows.put(
        "render-page-contents",
        measure(
            repetitions -> {
              for (long i = 0; i < repetitions; i++) {
                var page = pages.get((int) (i % pages.size()));
                String markup =
                    contentTemplate.execute(
                        new Application.TemplateData(null, page, new Application.RequestData(null)));
                sink += markup.length();
              }
            }));

    var shellTemplate = Templates.of("page.html", "document.html", "footer.html");
    rows.put(
        "render-page-shell",
        measure(
            repetitions -> {
              for (long i = 0; i < repetitions; i++) {
                var page = pages.get((int) (i % pages.size()));
                String markup =
                    shellTemplate.execute(
                        new Application.TemplateData(
                            application, page, new Application.RequestData(config.Theme)));
                sink += markup.length();
              }
            }));

    if (sink == 0) {
      throw new IllegalStateException("nothing was read from the timed work");
    }

    Files.writeString(out, MAPPER.writeValueAsString(Map.of("timing", rows)));
  }

  private static Row measure(LongConsumer run) {
    // Warmed before the window is sized. A pilot run against cold code sizes the window
    // for an interpreter and then measures a compiled one, which comes back well under
    // the target and makes the clock's own resolution a larger share of the figure.
    warm(run);
    long repetitions = windowSize(run);
    var windows = new ArrayList<Long>(WINDOWS);
    for (int i = 0; i < WINDOWS; i++) {
      long start = System.nanoTime();
      run.accept(repetitions);
      windows.add(System.nanoTime() - start);
    }
    windows.sort(Long::compare);
    long middle = windows.get(windows.size() / 2);
    return new Row(repetitions, WINDOWS, middle, (double) middle / repetitions);
  }

  /** Runs the work until it has been executed enough for the compiler to have settled. */
  private static void warm(LongConsumer run) {
    long deadline = System.nanoTime() + 500_000_000L;
    long repetitions = 1;
    while (System.nanoTime() < deadline) {
      run.accept(repetitions);
      repetitions = Math.min(repetitions * 2, 4096);
    }
  }

  /**
   * Doubles a repetition count until one window runs for the target. What it reached is
   * reported rather than a zero when the ceiling is hit: a fast operation is a figure, and a
   * zero is a stopped clock.
   */
  private static long windowSize(LongConsumer run) {
    long repetitions = 1;
    for (int i = 0; i < 32; i++) {
      long start = System.nanoTime();
      run.accept(repetitions);
      long elapsed = System.nanoTime() - start;
      if (elapsed >= TARGET_WINDOW) {
        return repetitions;
      }
      if (elapsed <= 0) {
        repetitions *= 8;
        continue;
      }
      long next = repetitions * (TARGET_WINDOW / elapsed + 1);
      repetitions = next <= repetitions ? repetitions * 2 : next;
    }
    return repetitions;
  }
}
