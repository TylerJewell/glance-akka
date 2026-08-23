package io.akka.glance.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.glance.application.PageEntity;
import io.akka.glance.application.SiteEntity;
import io.akka.glance.application.WidgetEntity;
import io.akka.glance.application.WidgetRefresher;
import io.akka.glance.domain.CacheMode;
import io.akka.glance.domain.FeedSpec;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * How the port is told what to draw.
 *
 * <p>The original reads all of this out of a YAML file it is started with, so it has no
 * equivalent surface at all. Keeping it on its own class rather than beside the page's
 * routes is what makes the difference in reach explicit: this is the half a caller can
 * change the service with, and its access rule is one annotation rather than something to
 * find among the read routes.
 *
 * <p>It is open to anything that can reach the service, which matches how the original's
 * configuration file is protected — by the machine it sits on rather than by the
 * application. A deployment that wants the page public and its configuration not changes
 * the matcher here to {@code service = "*"}.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class ConfigEndpoint {

  /** How a widget is set up; the source reads the same fields out of its YAML. */
  public record WidgetRequest(
      String id,
      String title,
      List<FeedSpec> feeds,
      CacheMode cacheMode,
      Long cacheSeconds,
      Integer limit,
      Boolean preserveOrder,
      boolean scheduled,
      List<String> childIds) {}

  public record PageRequest(String title, List<String> widgetIds) {}

  /** What a caller gets back: what was configured, not the widget's internal state. */
  public record WidgetConfigured(String id, String title, int feeds, boolean scheduled) {}

  public record PageConfigured(String slug, String title, List<String> widgetIds) {}

  public record SiteConfigured(List<String> slugs) {}

  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;

  public ConfigEndpoint(ComponentClient componentClient, TimerScheduler timerScheduler) {
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
  }

  @Post("/api/widgets")
  public WidgetConfigured configureWidget(WidgetRequest request) {
    var feeds = request.feeds() == null ? List.<FeedSpec>of() : request.feeds();
    componentClient
        .forKeyValueEntity(request.id())
        .method(WidgetEntity::configure)
        .invoke(
            new WidgetEntity.Config(
                request.title(),
                feeds,
                request.cacheMode() == null ? CacheMode.DURATION : request.cacheMode(),
                request.cacheSeconds(),
                request.limit(),
                request.preserveOrder(),
                request.scheduled(),
                request.childIds()));

    if (request.scheduled()) {
      // A widget that has never refreshed is due now, so its loop starts immediately.
      timerScheduler.createSingleTimer(
          WidgetRefresher.timerName(request.id()),
          Duration.ZERO,
          componentClient
              .forTimedAction()
              .method(WidgetRefresher::refresh)
              .deferred(
                  new WidgetRefresher.Booking(request.id(), UUID.randomUUID().toString())));
    }
    return new WidgetConfigured(
        request.id(), request.title(), feeds.size(), request.scheduled());
  }

  @Post("/api/pages/{slug}")
  public PageConfigured configurePage(String slug, PageRequest request) {
    var page =
        componentClient
            .forKeyValueEntity(slug)
            .method(PageEntity::configure)
            .invoke(new PageEntity.Page(request.title(), request.widgetIds()));
    return new PageConfigured(slug, page.title(), page.widgetIds());
  }

  @Post("/api/site")
  public SiteConfigured configureSite(SiteEntity.Site site) {
    var configured =
        componentClient
            .forKeyValueEntity(SiteEntity.ID)
            .method(SiteEntity::configure)
            .invoke(site);
    return new SiteConfigured(configured.pages().stream().map(SiteEntity.Link::slug).toList());
  }
}
