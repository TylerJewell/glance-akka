package io.akka.glance.cli;

import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Version;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Whether this machine can reach everything a dashboard needs.
 *
 * <p>Each check is one request or one name lookup, all of them at once, and each line says
 * what came back and how long it took. It is what somebody pastes into an issue.
 */
public final class Diagnostics {

  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  private Diagnostics() {}

  /** One thing to check. */
  private record Step(String name, Check check) {}

  private interface Check {
    String run();
  }

  private static List<Step> steps() {
    var steps = new ArrayList<Step>();
    steps.add(
        new Step(
            "resolve cloudflare.com through Cloudflare DoH",
            () ->
                request(
                    "GET",
                    "https://1.1.1.1/dns-query?name=cloudflare.com",
                    Map.of("accept", "application/dns-json"),
                    200)));
    steps.add(
        new Step(
            "resolve cloudflare.com through Google DoH",
            () -> request("GET", "https://8.8.8.8/resolve?name=cloudflare.com", Map.of(), 200)));
    steps.add(new Step("resolve github.com", () -> resolve("github.com")));
    steps.add(new Step("resolve reddit.com", () -> resolve("reddit.com")));
    steps.add(new Step("resolve twitch.tv", () -> resolve("twitch.tv")));
    steps.add(
        new Step(
            "fetch data from YouTube RSS feed",
            () ->
                request(
                    "GET",
                    "https://www.youtube.com/feeds/videos.xml?channel_id=UCZU9T1ceaOgwfLRq7OKFU4Q",
                    Map.of(),
                    200)));
    steps.add(
        new Step(
            "fetch data from Twitch.tv GQL",
            // This should always come back with nothing; the status is what matters.
            () -> request("OPTIONS", "https://gql.twitch.tv/gql", Map.of(), 200)));
    steps.add(
        new Step("fetch data from GitHub API", () -> request("GET", "https://api.github.com", Map.of(), 200)));
    steps.add(
        new Step(
            "fetch data from Open-Meteo API",
            () ->
                request(
                    "GET", "https://geocoding-api.open-meteo.com/v1/search?name=London", Map.of(), 200)));
    steps.add(
        new Step(
            "fetch data from Reddit API",
            () ->
                request(
                    "GET",
                    "https://www.reddit.com/search.json",
                    Map.of(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"),
                    200)));
    steps.add(
        new Step(
            "fetch data from Yahoo finance API",
            () ->
                request(
                    "GET",
                    "https://query1.finance.yahoo.com/v8/finance/chart/NVDA",
                    Map.of(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"),
                    200)));
    steps.add(
        new Step(
            "fetch data from Hacker News Firebase API",
            () ->
                request(
                    "GET", "https://hacker-news.firebaseio.com/v0/topstories.json", Map.of(), 200)));
    steps.add(
        new Step(
            "fetch data from Docker Hub API",
            () ->
                request(
                    "GET",
                    "https://hub.docker.com/v2/namespaces/library/repositories/ubuntu/tags/latest",
                    Map.of(),
                    200)));
    return steps;
  }

  public static void run(PrintStream out) {
    out.println("```");
    out.println("Glance version: " + Version.BUILD);
    out.println("Java version: " + System.getProperty("java.version"));
    out.printf(
        "Platform: %s / %s / %d CPUs%n",
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        Runtime.getRuntime().availableProcessors());
    out.println("In Docker container: " + (isInsideDocker() ? "yes" : "no"));
    out.printf(
        "%nChecking network connectivity, this may take up to %d seconds...%n%n",
        TIMEOUT.getSeconds());

    var steps = steps();
    var results = new LinkedHashMap<Step, Result>();
    var running = new ArrayList<java.util.concurrent.Future<Result>>();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      for (var step : steps) {
        running.add(
            executor.submit(
                () -> {
                  long start = System.nanoTime();
                  try {
                    String extra = step.check().run();
                    return new Result(extra, null, Duration.ofNanos(System.nanoTime() - start));
                  } catch (RuntimeException e) {
                    return new Result(
                        e instanceof Failure failure ? failure.extraInfo : "",
                        e.getMessage(),
                        Duration.ofNanos(System.nanoTime() - start));
                  }
                }));
      }
      for (int i = 0; i < steps.size(); i++) {
        try {
          results.put(steps.get(i), running.get(i).get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
          results.put(steps.get(i), new Result("", String.valueOf(e.getCause()), Duration.ZERO));
        }
      }
    }

    for (var step : steps) {
      var result = results.get(step);
      String extra = result.extraInfo().isEmpty() ? "" : "| " + result.extraInfo() + " ";
      out.printf(
          "%s %s %s| %dms%n",
          result.error() == null ? "✓ Can" : "✗ Can't",
          step.name(),
          extra,
          result.elapsed().toMillis());
      if (result.error() != null) {
        out.println("└╴ error: " + result.error());
      }
    }
    out.println("```");
  }

  private record Result(String extraInfo, String error, Duration elapsed) {}

  /** A check that failed but still has something to report about what came back. */
  private static final class Failure extends RuntimeException {
    private final String extraInfo;

    Failure(String extraInfo, String message) {
      super(message);
      this.extraInfo = extraInfo;
    }
  }

  private static String request(
      String method, String url, Map<String, String> headers, int expectedStatus) {
    var builder =
        HttpRequest.newBuilder(URI.create(url))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .timeout(TIMEOUT);
    headers.forEach(builder::header);
    HttpResponse<String> response;
    try {
      response = HttpClients.standard().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new Failure("", e.getMessage() == null ? e.toString() : e.getMessage());
    }
    String body = response.body().replace("\n", "");
    if (body.length() > 50) {
      body = body.substring(0, 50) + "...";
    }
    if (!body.isEmpty()) {
      body = ", " + body;
    }
    String extra = response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " bytes" + body;
    if (response.statusCode() != expectedStatus) {
      throw new Failure(
          extra, "expected status code " + expectedStatus + ", got " + response.statusCode());
    }
    return extra;
  }

  private static String resolve(String domain) {
    try {
      var addresses = InetAddress.getAllByName(domain);
      return java.util.Arrays.stream(addresses)
          .map(InetAddress::getHostAddress)
          .collect(Collectors.joining(", "));
    } catch (Exception e) {
      throw new Failure("", e.getMessage() == null ? e.toString() : e.getMessage());
    }
  }

  static boolean isInsideDocker() {
    return java.nio.file.Files.exists(java.nio.file.Path.of("/.dockerenv"));
  }
}
