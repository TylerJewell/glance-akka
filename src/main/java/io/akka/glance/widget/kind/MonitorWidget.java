package io.akka.glance.widget.kind;

import io.akka.glance.config.CustomIcon;
import io.akka.glance.config.DurationField;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.HttpClients;
import io.akka.glance.util.GoDuration;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Whether each of several addresses answers, and how quickly. */
public final class MonitorWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template MONITOR_COMPACT_WIDGET_BASE = Templates.of("monitor-compact.html", "widget-base.html");

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template MONITOR_WIDGET_BASE = Templates.of("monitor.html", "widget-base.html");

  /** How long a check waits when the site does not say. */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

  @Y("sites")
  public List<Site> Sites = new ArrayList<>();

  @Y("style")
  public String Style = "";

  @Y("show-failing-only")
  public boolean ShowFailingOnly;

  @Y(skip = true)
  public boolean HasFailing;

  /** One address to check, and what came back. */
  public static final class Site {
    @Y("url")
    public String DefaultURL = "";

    @Y("check-url")
    public String CheckURL = "";

    @Y("allow-insecure")
    public boolean AllowInsecure;

    @Y("timeout")
    public DurationField Timeout;

    @Y("basic-auth")
    public BasicAuth BasicAuth = new BasicAuth();

    @Y(skip = true)
    public SiteStatus Status;

    @Y(skip = true)
    public String URL = "";

    @Y("error-url")
    public String ErrorURL = "";

    @Y("title")
    public String Title = "";

    @Y("icon")
    public CustomIcon Icon = new CustomIcon();

    @Y("same-tab")
    public boolean SameTab;

    @Y(skip = true)
    public String StatusText = "";

    @Y(skip = true)
    public String StatusStyle = "";

    @Y("alt-status-codes")
    public List<Integer> AltStatusCodes = new ArrayList<>();
  }

  /** A user name and password sent with the check. */
  public static final class BasicAuth {
    @Y("username")
    public String Username = "";

    @Y("password")
    public String Password = "";
  }

  /** What one check found. */
  public static final class SiteStatus {
    public int Code;
    public boolean TimedOut;
    public GoDuration ResponseTime = GoDuration.ZERO;
    public Err Error;
  }

  @Override
  public void initialize() {
    withTitle("Monitor").withCacheDuration(Duration.ofMinutes(5));
  }

  @Override
  public void update(Instant now) {
    var results = Fetches.pool(Sites, 20, MonitorWidget::check);
    // Every check reports its own outcome rather than failing, so nothing here can go
    // wrong; the widget is always left with content.
    if (!canContinueUpdateAfterHandlingErr(null, now)) {
      return;
    }
    HasFailing = false;
    for (int i = 0; i < Sites.size(); i++) {
      var site = Sites.get(i);
      var status = results.get(i).value();
      site.Status = status;
      if (!site.AltStatusCodes.contains(status.Code)
          && (status.Code >= 400 || status.Error != null)) {
        HasFailing = true;
      }
      site.URL = status.Error != null && !site.ErrorURL.isEmpty() ? site.ErrorURL : site.DefaultURL;
      site.StatusText = statusCodeToText(status.Code, site.AltStatusCodes);
      site.StatusStyle = statusCodeToStyle(status.Code, site.AltStatusCodes);
    }
  }

  static SiteStatus check(Site site) {
    var status = new SiteStatus();
    String url = site.CheckURL.isEmpty() ? site.DefaultURL : site.CheckURL;
    var timeout =
        site.Timeout != null && !site.Timeout.isZero() ? site.Timeout.duration() : DEFAULT_TIMEOUT;
    HttpRequest request;
    try {
      var builder = HttpRequest.newBuilder(URI.create(url)).GET().timeout(timeout);
      if (!site.BasicAuth.Username.isEmpty() || !site.BasicAuth.Password.isEmpty()) {
        var credentials =
            Base64.getEncoder()
                .encodeToString(
                    (site.BasicAuth.Username + ":" + site.BasicAuth.Password)
                        .getBytes(StandardCharsets.UTF_8));
        builder.header("Authorization", "Basic " + credentials);
      }
      request = builder.build();
    } catch (RuntimeException e) {
      status.Error = Err.of(e.getMessage());
      return status;
    }
    var client = site.AllowInsecure ? HttpClients.insecure() : HttpClients.standard();
    long sentAt = System.nanoTime();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.discarding());
      status.ResponseTime = GoDuration.ofNanos(System.nanoTime() - sentAt);
      status.Code = response.statusCode();
      return status;
    } catch (java.net.http.HttpTimeoutException e) {
      status.ResponseTime = GoDuration.ofNanos(System.nanoTime() - sentAt);
      status.TimedOut = true;
      status.Error = Err.of("Get \"" + url + "\": context deadline exceeded");
      return status;
    } catch (IOException e) {
      status.ResponseTime = GoDuration.ofNanos(System.nanoTime() - sentAt);
      status.Error = Err.of("Get \"" + url + "\": " + e.getMessage());
      return status;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      status.Error = Err.of("interrupted");
      return status;
    }
  }

  public static String statusCodeToText(int status, List<Integer> altStatusCodes) {
    if (status == 200 || altStatusCodes.contains(status)) {
      return "OK";
    }
    if (status == 404) {
      return "Not Found";
    }
    if (status == 403) {
      return "Forbidden";
    }
    if (status == 401) {
      return "Unauthorized";
    }
    if (status >= 500) {
      return "Server Error";
    }
    if (status >= 400) {
      return "Client Error";
    }
    return String.valueOf(status);
  }

  public static String statusCodeToStyle(int status, List<Integer> altStatusCodes) {
    return status == 200 || altStatusCodes.contains(status) ? "ok" : "error";
  }

  @Override
  public Safe Render() {
    if (Style.equals("compact")) {
      return renderTemplate(this, MONITOR_COMPACT_WIDGET_BASE);
    }
    return renderTemplate(this, MONITOR_WIDGET_BASE);
  }
}
