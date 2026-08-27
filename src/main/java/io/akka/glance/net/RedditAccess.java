package io.akka.glance.net;

import io.akka.glance.util.Singleflight;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetches;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * The cookie reddit's JSON endpoints want, and the challenge that hands it over.
 *
 * <p>Reddit's front page carries a small script whose answer is one string written twice.
 * Asking for the page again with that answer sets a {@code loid} cookie, and every widget on
 * a dashboard shares one: the flow is asked for once at a time and the answer kept for six
 * hours, so a page of subreddits does not walk through it repeatedly.
 */
public final class RedditAccess {

  private static final Pattern CHALLENGE =
      Pattern.compile("await\\(async \\w+\\s*=>\\s*\\w+\\s*\\+\\s*\\w+\\)\\(\"([^\"]+)\"\\)");
  private static final Pattern TOKEN = Pattern.compile("name=\"token\"\\s+value=\"([^\"]+)\"");

  private static final Duration CACHE_FOR = Duration.ofHours(6);

  private static Instant lastUpdate;
  private static String cached = "";

  private static final Singleflight<String> FLIGHT = new Singleflight<>(RedditAccess::refresh);

  private RedditAccess() {}

  public static String loidCookie() {
    return FLIGHT.get();
  }

  /** Only for a test, which needs the next call to go through the flow again. */
  public static void forget() {
    lastUpdate = null;
    cached = "";
  }

  private static String refresh() {
    if (lastUpdate != null
        && Duration.between(lastUpdate, Instant.now()).compareTo(CACHE_FOR) < 0
        && !cached.isEmpty()) {
      return cached;
    }
    String loid;
    try {
      loid = fetch();
    } catch (RuntimeException e) {
      if (!cached.isEmpty()) {
        return cached;
      }
      throw e;
    }
    lastUpdate = Instant.now();
    cached = loid;
    return loid;
  }

  private static String fetch() {
    var page =
        Requests.sendRaw(
            HttpClients.standard(),
            Requests.get(Endpoints.reddit + "/")
                .header("User-Agent", HttpClients.browserUserAgent())
                .build());
    if (page.statusCode() != 200) {
      throw new Fetches.FetchException(
          "unexpected status code " + page.statusCode() + " when requesting challenge page");
    }
    var challenge = CHALLENGE.matcher(page.body());
    if (!challenge.find()) {
      throw new Fetches.FetchException("no JS challenge found");
    }
    var token = TOKEN.matcher(page.body());
    if (!token.find()) {
      throw new Fetches.FetchException("no token found in challenge page");
    }
    // The page's own script adds the string to itself; the answer is it, twice.
    String solution = challenge.group(1) + challenge.group(1);
    String query =
        "js_challenge=1"
            + "&solution="
            + io.akka.glance.config.QueryParameters.encode(solution)
            + "&token="
            + io.akka.glance.config.QueryParameters.encode(token.group(1));
    var answered =
        Requests.sendRaw(
            HttpClients.standard(),
            HttpRequest.newBuilder(URI.create(Endpoints.reddit + "/?" + query))
                .GET()
                .timeout(HttpClients.DEFAULT_TIMEOUT)
                .header("User-Agent", HttpClients.browserUserAgent())
                .build());
    if (answered.statusCode() != 200) {
      throw new Fetches.FetchException(
          "unexpected status code "
              + answered.statusCode()
              + " when submitting challenge solution");
    }
    for (var header : answered.headers().allValues("set-cookie")) {
      int equals = header.indexOf('=');
      if (equals < 0) {
        continue;
      }
      if (!header.substring(0, equals).trim().equals("loid")) {
        continue;
      }
      int semicolon = header.indexOf(';', equals);
      return semicolon < 0
          ? header.substring(equals + 1)
          : header.substring(equals + 1, semicolon);
    }
    throw new Fetches.FetchException(Err.of("no loid cookie found"));
  }
}
