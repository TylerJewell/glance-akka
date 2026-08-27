package io.akka.glance.api;

import akka.NotUsed;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.glance.app.Site;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The rest of the surface: health, the theme picker, the per-widget route the original has not
 * implemented, and the stream an open page follows.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class ApiEndpoint extends AbstractHttpEndpoint {

  /** How often the stream looks for a change. RENDERING.md R1.2 wants p95 under 250 ms. */
  private static final Duration TICK = Duration.ofMillis(100);

  /** What a tick produces once the configuration this stream opened against is gone. */
  private static final Site.Update GONE = new Site.Update(-1, null);

  /** How long a theme choice is remembered. */
  private static final Duration THEME_COOKIE_LIFETIME = Duration.ofDays(2 * 365);

  /** A page whose slug is {@code api}; this endpoint owns the prefix. See {@link Pages}. */
  @Get("/api")
  public HttpResponse apiPage() {
    return Pages.shell(requestContext(), "api");
  }

  @Get("/api/healthz")
  public HttpResponse healthz() {
    return HttpResponses.ok();
  }

  @Post("/api/set-theme/{key}")
  public HttpResponse setTheme(String key) {
    var application = Site.application();
    if (application.Config.Theme.DisablePicker) {
      return HttpResponses.notFound();
    }
    var properties = application.Config.Theme.Presets.Get(key);
    if (properties == null && !key.equals("default")) {
      return HttpResponse.create().withStatus(StatusCodes.NOT_FOUND);
    }
    if (key.equals("default")) {
      properties = application.Config.Theme;
    }
    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .addHeaders(
            List.copyOf(
                Requests.themeCookie(
                    application, key, Instant.now().plus(THEME_COOKIE_LIFETIME))))
        .addHeader(
            akka.http.javadsl.model.headers.RawHeader.create(
                "X-Scheme", properties.Light ? "light" : "dark"))
        .withEntity(
            // Without a charset: the style sheet is the theme's own custom properties,
            // ASCII throughout, and the original names the type bare.
            MediaTypes.TEXT_CSS.toContentTypeWithMissingCharset(),
            properties.CSS.value().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The per-widget route.
   *
   * <p>The original answers every request here with {@code 501}: refreshing one widget needs
   * the page's lock to be held per widget rather than per page, which it does not do. The same
   * answer is given here, because a caller cannot tell the difference between a route that
   * does nothing and one that is not there, and the original's own clients rely on the code.
   */
  @Get("/api/widgets/{widget}/{path}")
  public HttpResponse widget(String widget, String path) {
    return HttpResponse.create().withStatus(StatusCodes.NOT_IMPLEMENTED);
  }

  /**
   * What an open page follows instead of asking again.
   *
   * <p>RENDERING.md R1: a view of server-owned state subscribes rather than polls. Each frame
   * carries the page's markup, and one is sent whenever a refresh changed it — plus one at the
   * start, so that a page that has just reconnected is current without a request of its own.
   */
  @Get("/api/pages/{slug}/stream")
  public HttpResponse stream(String slug) {
    var application = Site.application();
    var page = application.pageBySlug(slug);
    if (page == null) {
      return HttpResponses.notFound();
    }
    var gate = Requests.gate(requestContext(), application, Requests.WhenUnauthorized.JSON);
    if (gate.refusal() != null) {
      return gate.refusal();
    }
    var first = page;
    var sent = new java.util.concurrent.atomic.AtomicLong(-1);
    Source<Frame, NotUsed> frames =
        Source.tick(Duration.ZERO, TICK, NotUsed.getInstance())
            // On the fetch pool rather than the stream's own thread: a refresh waits on the
            // network, and waiting here would hold a dispatcher thread that every other open
            // page shares.
            .mapAsync(1, ignored -> java.util.concurrent.CompletableFuture.supplyAsync(
                () -> {
                  // Resolved again each tick: a configuration loaded since this opened holds
                  // different page objects, and refreshing the ones this captured would feed
                  // an open browser a dashboard the server no longer runs.
                  var now = Site.application().pageBySlug(slug);
                  return now == first
                      ? Site.updated(now, Instant.now(), sent.get())
                      : GONE;
                },
                io.akka.glance.widget.Fetches.executor()))
            // Ending the stream rather than sending the new page down the old one: the
            // browser reconnects on its own, and a reconnect is already how it catches up.
            .takeWhile(update -> update != GONE)
            // A tick that changed nothing produces no element at all rather than a null one,
            // which a stream may not carry.
            .mapConcat(
                update -> {
                  if (update.content() == null) {
                    return List.of();
                  }
                  sent.set(update.revision());
                  return List.of(new Frame(update.content(), update.revision()));
                })
            .mapMaterializedValue(ignored -> NotUsed.getInstance());
    return gate.addRenewalTo(HttpResponses.serverSentEvents(frames));
  }

  /** One frame of the stream: the page's markup, and which revision it is. */
  public record Frame(String content, long revision) {}
}
