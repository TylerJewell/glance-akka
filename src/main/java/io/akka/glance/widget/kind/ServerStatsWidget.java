package io.akka.glance.widget.kind;

import io.akka.glance.config.DurationField;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.sysinfo.Sysinfo;
import io.akka.glance.sysinfo.SystemInfo;
import io.akka.glance.sysinfo.SystemInfoRequest;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** How the machine this runs on is doing, and any others that answer the same questions. */
public final class ServerStatsWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("server-stats.html", "widget-base.html");

  @Y("servers")
  public List<Server> Servers = new ArrayList<>();

  /** One machine to report on. */
  public static final class Server {
    @Y(inline = true)
    public SystemInfoRequest SystemInfoRequest = new SystemInfoRequest();

    @Y(skip = true)
    public SystemInfo Info;

    @Y(skip = true)
    public boolean IsReachable;

    @Y(skip = true)
    public String StatusText = "";

    @Y("name")
    public String Name = "";

    @Y("hide-swap")
    public boolean HideSwap;

    @Y("type")
    public String Type = "";

    @Y("url")
    public String URL = "";

    @Y("token")
    public String Token = "";

    @Y("timeout")
    public DurationField Timeout;
  }

  @Override
  public void initialize() {
    withTitle("Server Stats").withCacheDuration(Duration.ofSeconds(15));
    WIP = true;
    if (Servers.isEmpty()) {
      var local = new Server();
      local.Type = "local";
      Servers.add(local);
    }
    for (var server : Servers) {
      server.URL = trimTrailingSlashes(server.URL);
      if (server.Timeout == null || server.Timeout.isZero()) {
        server.Timeout = DurationField.of(Duration.ofSeconds(3));
      }
    }
  }

  @Override
  public void update(Instant now) {
    var running = new ArrayList<java.util.concurrent.Future<?>>();
    for (int i = 0; i < Servers.size(); i++) {
      var server = Servers.get(i);
      if (server.Type.equals("local")) {
        var collected = Sysinfo.collect(server.SystemInfoRequest);
        server.IsReachable = true;
        server.Info = collected.info();
        continue;
      }
      int index = i;
      running.add(
          Fetches.submit(
              () -> {
                try {
                  server.Info = fetchRemote(server);
                  server.IsReachable = true;
                } catch (Fetches.FetchException e) {
                  server.IsReachable = false;
                  var placeholder = new SystemInfo();
                  placeholder.Hostname = "Unnamed server #" + (index + 1);
                  server.Info = placeholder;
                }
              }));
    }
    for (var future : running) {
      try {
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (java.util.concurrent.ExecutionException e) {
        // Each server records its own outcome above.
      }
    }
    withError(null);
    scheduleNextUpdate(now);
  }

  private static SystemInfo fetchRemote(Server server) {
    var builder = Requests.get(server.URL + "/api/sysinfo/all", server.Timeout.duration());
    if (!server.Token.isEmpty()) {
      builder.header("Authorization", "Bearer " + server.Token);
    }
    return Requests.json(HttpClients.standard(), builder.build(), SystemInfo.class);
  }

  private static String trimTrailingSlashes(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
