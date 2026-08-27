package io.akka.glance.widget.kind;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.glance.config.ConfigException;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.util.GoLayout;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

/** What a DNS filter has been asked for, and how much of it was refused. */
public final class DnsStatsWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("dns-stats.html", "widget-base.html");

  /** How many bars the graph draws, and how much time each covers. */
  private static final int BARS = 8;

  private static final int HOURS_SPAN = 24;
  private static final int HOURS_PER_BAR = HOURS_SPAN / BARS;

  private static final String ADGUARD = "adguard";
  private static final String PIHOLE = "pihole";
  private static final String TECHNITIUM = "technitium";
  private static final String PIHOLE_V6 = "pihole-v6";

  @Y(skip = true)
  public List<String> TimeLabels = new ArrayList<>();

  @Y(skip = true)
  public Stats Stats;

  @Y(skip = true)
  private String piholeSessionID = "";

  @Y("hour-format")
  public String HourFormat = "";

  @Y("hide-graph")
  public boolean HideGraph;

  @Y("hide-top-domains")
  public boolean HideTopDomains;

  @Y("service")
  public String Service = "";

  @Y("allow-insecure")
  public boolean AllowInsecure;

  @Y("url")
  public String URL = "";

  @Y("token")
  public String Token = "";

  @Y("username")
  public String Username = "";

  @Y("password")
  public String Password = "";

  /** The totals, the graph and the domains refused most. */
  public static final class Stats {
    public int TotalQueries;
    public int BlockedQueries;
    public int BlockedPercent;
    public int ResponseTime;
    public int DomainsBlocked;
    public List<Series> Series = new ArrayList<>();
    public List<BlockedDomain> TopBlockedDomains = new ArrayList<>();

    Stats() {
      for (int i = 0; i < BARS; i++) {
        Series.add(new Series());
      }
    }
  }

  /** One bar of the graph. */
  public static final class Series {
    public int Queries;
    public int Blocked;
    public int PercentTotal;
    public int PercentBlocked;
  }

  /** One refused domain. */
  public static final class BlockedDomain {
    public String Domain = "";
    public int PercentBlocked;
  }

  @Override
  public void initialize() {
    String titleUrl = trimTrailingSlashes(URL);
    if (Service.equals(PIHOLE) || Service.equals(PIHOLE_V6)) {
      titleUrl = titleUrl + "/admin";
    }
    withTitle("DNS Stats").withTitleURL(titleUrl).withCacheDuration(Duration.ofMinutes(10));
    if (!Service.equals(ADGUARD)
        && !Service.equals(PIHOLE_V6)
        && !Service.equals(PIHOLE)
        && !Service.equals(TECHNITIUM)) {
      throw new ConfigException(
          "service must be one of: " + ADGUARD + ", " + PIHOLE + ", " + PIHOLE_V6 + ", "
              + TECHNITIUM);
    }
  }

  @Override
  public void update(Instant now) {
    Fetched<Stats> fetched =
        switch (Service) {
          case ADGUARD -> fetchAdguard();
          case PIHOLE -> fetchPihole5();
          case TECHNITIUM -> fetchTechnitium();
          case PIHOLE_V6 -> fetchPiholeV6();
          default -> Fetched.of(null);
        };
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    TimeLabels = timeLabels(HourFormat.equals("24h") ? "15:00" : "3PM", now);
    Stats = fetched.value();
  }

  /** The label under each bar: the hour that bar starts at, in lower case. */
  public static List<String> timeLabels(String layout, Instant now) {
    var labels = new ArrayList<String>(BARS);
    for (int i = 0; i < BARS; i++) {
      labels.add("");
    }
    for (int h = HOURS_SPAN; h > 0; h -= HOURS_PER_BAR) {
      labels.set(
          7 - (h / 3 - 1),
          GoLayout.format(layout, now.minus(Duration.ofHours(h)), ZoneId.systemDefault())
              .toLowerCase(Locale.ROOT));
    }
    return labels;
  }

  private HttpClient client() {
    return AllowInsecure ? HttpClients.insecure() : HttpClients.standard();
  }

  private static String trimTrailingSlashes(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }

  private Fetched<Stats> fetchAdguard() {
    try {
      var credentials =
          Base64.getEncoder()
              .encodeToString((Username + ":" + Password).getBytes(StandardCharsets.UTF_8));
      var response =
          Requests.json(
              client(),
              Requests.get(trimTrailingSlashes(URL) + "/control/stats")
                  .header("Authorization", "Basic " + credentials)
                  .build());
      var stats = new Stats();
      stats.TotalQueries = response.path("num_dns_queries").asInt();
      stats.BlockedQueries = response.path("num_blocked_filtering").asInt();
      stats.ResponseTime = (int) (response.path("avg_processing_time").asDouble() * 1000);
      var top = response.path("top_blocked_domains");
      int topCount = Math.min(top.size(), 5);
      if (stats.TotalQueries <= 0) {
        return Fetched.of(stats);
      }
      stats.BlockedPercent = (int) ((double) stats.BlockedQueries / stats.TotalQueries * 100);
      for (int i = 0; i < topCount; i++) {
        var entry = top.get(i);
        var names = entry.fieldNames();
        if (!names.hasNext()) {
          continue;
        }
        String name = names.next();
        var domain = new BlockedDomain();
        domain.Domain = name;
        if (stats.BlockedQueries > 0) {
          domain.PercentBlocked =
              (int) ((double) entry.path(name).asInt() / stats.BlockedQueries * 100);
        }
        stats.TopBlockedDomains.add(domain);
      }
      if (HideGraph) {
        return Fetched.of(stats);
      }
      fillSeries(stats, ints(response.path("dns_queries")), ints(response.path("blocked_filtering")));
      return Fetched.of(stats);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(e.error());
    }
  }

  private Fetched<Stats> fetchPihole5() {
    if (Token.isEmpty()) {
      return Fetched.failed(Err.of("missing API token"));
    }
    try {
      var response =
          Requests.json(
              client(),
              Requests.get(
                      trimTrailingSlashes(URL)
                          + "/admin/api.php?summaryRaw&topItems&overTimeData10mins&auth="
                          + Token)
                  .build());
      var stats = new Stats();
      stats.TotalQueries = response.path("dns_queries_today").asInt();
      stats.BlockedQueries = response.path("ads_blocked_today").asInt();
      stats.BlockedPercent = (int) response.path("ads_percentage_today").asDouble();
      stats.DomainsBlocked = response.path("domains_being_blocked").asInt();
      var top = response.path("top_ads");
      if (top.isObject() && !top.isEmpty()) {
        var domains = new ArrayList<BlockedDomain>();
        top.fields()
            .forEachRemaining(
                entry -> {
                  var domain = new BlockedDomain();
                  domain.Domain = entry.getKey();
                  domain.PercentBlocked =
                      (int) ((double) entry.getValue().asInt() / stats.BlockedQueries * 100);
                  domains.add(domain);
                });
        domains.sort(Comparator.comparingInt((BlockedDomain d) -> d.PercentBlocked).reversed());
        stats.TopBlockedDomains = new ArrayList<>(domains.subList(0, Math.min(domains.size(), 5)));
      }
      if (HideGraph) {
        return Fetched.of(stats);
      }
      var queries = response.path("domains_over_time");
      var blocked = response.path("ads_over_time");
      // The instance should answer with a day's worth at ten-minute intervals.
      if (!queries.isObject() || queries.size() != 144 || !blocked.isObject() || blocked.size() != 144) {
        return Fetched.of(stats);
      }
      long lowest = 0;
      var names = queries.fieldNames();
      while (names.hasNext()) {
        long timestamp = Long.parseLong(names.next());
        if (lowest == 0 || timestamp < lowest) {
          lowest = timestamp;
        }
      }
      int maxQueries = 0;
      for (int i = 0; i < BARS; i++) {
        int barQueries = 0;
        int barBlocked = 0;
        for (int j = 0; j < 18; j++) {
          String index = String.valueOf(lowest + i * 10800L + j * 600L);
          barQueries += queries.path(index).asInt();
          barBlocked += blocked.path(index).asInt();
        }
        maxQueries = Math.max(maxQueries, barQueries);
        var series = stats.Series.get(i);
        series.Queries = barQueries;
        series.Blocked = barBlocked;
        if (barQueries > 0) {
          series.PercentBlocked = (int) ((double) barBlocked / barQueries * 100);
        }
      }
      applyPercentTotal(stats, maxQueries);
      return Fetched.of(stats);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(e.error());
    }
  }

  private Fetched<Stats> fetchTechnitium() {
    if (Token.isEmpty()) {
      return Fetched.failed(Err.of("missing API token"));
    }
    try {
      var response =
          Requests.json(
              client(),
              Requests.get(
                      trimTrailingSlashes(URL)
                          + "/api/dashboard/stats/get?token="
                          + Token
                          + "&type=LastDay")
                  .build());
      var inner = response.path("response");
      var summary = inner.path("stats");
      var stats = new Stats();
      stats.TotalQueries = summary.path("totalQueries").asInt();
      stats.BlockedQueries = summary.path("totalBlocked").asInt();
      stats.DomainsBlocked =
          summary.path("blockedZones").asInt() + summary.path("blockListZones").asInt();
      var top = inner.path("TopBlockedDomains");
      if (top.isMissingNode()) {
        top = inner.path("topBlockedDomains");
      }
      int topCount = Math.min(top.size(), 5);
      if (stats.TotalQueries <= 0) {
        return Fetched.of(stats);
      }
      stats.BlockedPercent = (int) ((double) stats.BlockedQueries / stats.TotalQueries * 100);
      for (int i = 0; i < topCount; i++) {
        var entry = top.get(i);
        String name = entry.path("name").asText("");
        if (name.isEmpty()) {
          continue;
        }
        var domain = new BlockedDomain();
        domain.Domain = name;
        if (stats.BlockedQueries > 0) {
          domain.PercentBlocked =
              (int) ((double) entry.path("hits").asInt() / stats.BlockedQueries * 100);
        }
        stats.TopBlockedDomains.add(domain);
      }
      if (HideGraph) {
        return Fetched.of(stats);
      }
      List<Integer> queries = List.of();
      List<Integer> blocked = List.of();
      for (var dataset : inner.path("mainChartData").path("datasets")) {
        String label = dataset.path("label").asText("");
        if (label.equals("Total")) {
          queries = ints(dataset.path("data"));
        } else if (label.equals("Blocked")) {
          blocked = ints(dataset.path("data"));
        }
      }
      fillSeries(stats, queries, blocked);
      return Fetched.of(stats);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(e.error());
    }
  }

  private Fetched<Stats> fetchPiholeV6() {
    String instance = trimTrailingSlashes(URL);
    var client = client();
    try {
      if (piholeSessionID.isEmpty()) {
        try {
          piholeSessionID = newSessionId(instance, client);
        } catch (Fetches.FetchException e) {
          return Fetched.failed(Err.of("fetching session ID: " + e.error()));
        }
      } else {
        boolean valid;
        try {
          valid = sessionIdIsValid(instance, client, piholeSessionID);
        } catch (Fetches.FetchException e) {
          return Fetched.failed(Err.of("checking session ID: " + e.error()));
        }
        if (!valid) {
          try {
            piholeSessionID = newSessionId(instance, client);
          } catch (Fetches.FetchException e) {
            return Fetched.failed(Err.of("renewing session ID: " + e.error()));
          }
        }
      }
      String session = piholeSessionID;
      Future<JsonNode> summary =
          Fetches.submitCall(
              () ->
                  Requests.json(
                      client,
                      Requests.get(instance + "/api/stats/summary")
                          .header("x-ftl-sid", session)
                          .build()));
      Future<JsonNode> history =
          HideGraph
              ? null
              : Fetches.submitCall(
                  () ->
                      Requests.json(
                          client,
                          Requests.get(instance + "/api/history")
                              .header("x-ftl-sid", session)
                              .build()));
      Future<JsonNode> topDomains =
          HideTopDomains
              ? null
              : Fetches.submitCall(
                  () ->
                      Requests.json(
                          client,
                          Requests.get(instance + "/api/stats/top_domains?blocked=true")
                              .header("x-ftl-sid", session)
                              .build()));

      var summaryResult = Fetches.await(summary);
      if (summaryResult.error() != null) {
        return Fetched.failed(Err.of("fetching stats: " + summaryResult.error()));
      }
      boolean partial = false;
      var stats = new Stats();
      var queriesNode = summaryResult.value().path("queries");
      stats.TotalQueries = queriesNode.path("total").asInt();
      stats.BlockedQueries = queriesNode.path("blocked").asInt();
      stats.BlockedPercent = (int) queriesNode.path("percent_blocked").asDouble();
      stats.DomainsBlocked =
          summaryResult.value().path("gravity").path("domains_being_blocked").asInt();

      if (history != null) {
        var result = Fetches.await(history);
        if (result.error() != null) {
          partial = true;
        } else {
          var points = result.value().path("history");
          if (points.size() != 145) {
            partial = true;
          } else {
            // The oldest point is dropped: a day at ten-minute intervals is 144, and the
            // instance answers with one more than that.
            final int interval = 10;
            final int perBar = HOURS_PER_BAR * (60 / interval);
            int maxQueries = 0;
            for (int i = 0; i < BARS; i++) {
              int barQueries = 0;
              int barBlocked = 0;
              for (int j = 0; j < perBar; j++) {
                var point = points.get(1 + i * perBar + j);
                barQueries += point.path("total").asInt();
                barBlocked += point.path("blocked").asInt();
              }
              maxQueries = Math.max(maxQueries, barQueries);
              var series = stats.Series.get(i);
              series.Queries = barQueries;
              series.Blocked = barBlocked;
              if (barQueries > 0) {
                series.PercentBlocked = (int) ((double) barBlocked / barQueries * 100);
              }
            }
            applyPercentTotal(stats, maxQueries);
          }
        }
      }

      if (topDomains != null) {
        var result = Fetches.await(topDomains);
        if (result.error() != null) {
          partial = true;
        } else {
          var listed = result.value().path("domains");
          if (listed.isArray() && !listed.isEmpty()) {
            var domains = new ArrayList<BlockedDomain>();
            for (var node : listed) {
              var domain = new BlockedDomain();
              domain.Domain = node.path("domain").asText("");
              domain.PercentBlocked =
                  (int) ((double) node.path("count").asInt() / stats.BlockedQueries * 100);
              domains.add(domain);
            }
            domains.sort(
                Comparator.comparingInt((BlockedDomain domain) -> domain.PercentBlocked).reversed());
            stats.TopBlockedDomains =
                new ArrayList<>(domains.subList(0, Math.min(domains.size(), 5)));
          }
        }
      }
      return Fetched.of(stats, partial ? Err.PARTIAL_CONTENT : null);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(e.error());
    }
  }

  private String newSessionId(String instance, HttpClient client) {
    var request =
        HttpRequest.newBuilder(URI.create(instance + "/api/auth"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + Password + "\"}"))
            .timeout(HttpClients.DEFAULT_TIMEOUT)
            .header("Content-Type", "application/json")
            .build();
    var response = Requests.sendRaw(client, request);
    JsonNode parsed;
    try {
      parsed = Requests.parse(response.body());
    } catch (Fetches.FetchException e) {
      throw new Fetches.FetchException("parsing authentication response: " + e.error());
    }
    String message = parsed.path("session").path("message").asText("");
    if (response.statusCode() != 200) {
      throw new Fetches.FetchException(
          "authentication request returned status "
              + response.statusCode()
              + " with message '"
              + message
              + "'");
    }
    String sid = parsed.path("session").path("sid").asText("");
    if (sid.isEmpty()) {
      throw new Fetches.FetchException(
          "authentication response returned empty session ID, status code "
              + response.statusCode()
              + ", message '"
              + message
              + "'");
    }
    return sid;
  }

  private static boolean sessionIdIsValid(String instance, HttpClient client, String sessionId) {
    var response =
        Requests.sendRaw(
            client,
            Requests.get(instance + "/api/auth").header("x-ftl-sid", sessionId).build());
    if (response.statusCode() != 200 && response.statusCode() != 401) {
      throw new Fetches.FetchException(
          "session ID check request returned status " + response.statusCode());
    }
    return response.statusCode() == 200;
  }

  /** Folds an hourly series into the graph's bars. */
  private static void fillSeries(Stats stats, List<Integer> queries, List<Integer> blocked) {
    var alignedQueries = alignTo(queries, HOURS_SPAN);
    var alignedBlocked = alignTo(blocked, HOURS_SPAN);
    int maxQueries = 0;
    for (int i = 0; i < BARS; i++) {
      int barQueries = 0;
      int barBlocked = 0;
      for (int j = 0; j < HOURS_PER_BAR; j++) {
        barQueries += alignedQueries.get(i * HOURS_PER_BAR + j);
        barBlocked += alignedBlocked.get(i * HOURS_PER_BAR + j);
      }
      var series = stats.Series.get(i);
      series.Queries = barQueries;
      series.Blocked = barBlocked;
      if (barQueries > 0) {
        series.PercentBlocked = (int) ((double) barBlocked / barQueries * 100);
      }
      maxQueries = Math.max(maxQueries, barQueries);
    }
    applyPercentTotal(stats, maxQueries);
  }

  /**
   * The height of each bar against the tallest.
   *
   * <p>A set of bars that are all zero has no tallest, and the division the original does
   * there is not defined for an integer; every bar is flat, which is the only reading of a
   * graph with nothing in it.
   */
  private static void applyPercentTotal(Stats stats, int maxQueries) {
    if (maxQueries == 0) {
      return;
    }
    for (var series : stats.Series) {
      series.PercentTotal = (int) ((double) series.Queries / maxQueries * 100);
    }
  }

  /** Keeps the last {@code size} readings, padding the front with zeros where there are fewer. */
  private static List<Integer> alignTo(List<Integer> values, int size) {
    if (values.size() > size) {
      return values.subList(values.size() - size, values.size());
    }
    if (values.size() < size) {
      var out = new ArrayList<Integer>(size);
      for (int i = 0; i < size - values.size(); i++) {
        out.add(0);
      }
      out.addAll(values);
      return out;
    }
    return values;
  }

  private static List<Integer> ints(JsonNode node) {
    var out = new ArrayList<Integer>();
    if (node.isArray()) {
      for (var item : node) {
        out.add(item.asInt());
      }
    }
    return out;
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
