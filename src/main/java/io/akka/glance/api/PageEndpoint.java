package io.akka.glance.api;

import akka.NotUsed;
import akka.http.javadsl.model.ContentType;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpCharsets;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.glance.application.PageEntity;
import io.akka.glance.application.PageRefresh;
import io.akka.glance.application.PageRenderer;
import io.akka.glance.application.SiteEntity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The page, its assets, and the stream that keeps an open page current.
 *
 * <p>The routes are the original's own (SPEC-001 D-6): the same page paths, the same
 * {@code /static/...} prefix, and the same {@code /api/pages/{slug}/content/}. The one that
 * is new is {@code /stream}, which is what {@code page.js} subscribes to instead of asking
 * for the content again.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class PageEndpoint {

  /** How often the stream re-reads the page. RENDERING.md R1.2 wants p95 under 250 ms. */
  private static final Duration TICK = Duration.ofMillis(100);

  /**
   * The asset path prefix the original's markup carries. Its digits are a build hash there;
   * here the shell is vendored, so the same prefix is answered as a constant.
   */
  private static final String ASSET_PREFIX = "f1dcca59e9";

  private final ComponentClient componentClient;
  private final PageRefresh pageRefresh;

  public PageEndpoint(ComponentClient componentClient, PageRefresh pageRefresh) {
    this.componentClient = componentClient;
    this.pageRefresh = pageRefresh;
  }

  /** @param at the instant to refresh at; absent or blank means now */
  public record RefreshRequest(String at) {}

  public record Frame(String content) {}

  /** One widget after a refresh pass: everything SPEC-001's rules decide about it. */
  public record WidgetAnswer(
      String id,
      boolean contentAvailable,
      String error,
      String notice,
      int retries,
      Long dueSeconds,
      String nextUpdate,
      String cacheMode,
      List<String> links) {}

  /** What a refresh pass produced: the page's markup, and each widget's state. */
  public record RefreshAnswer(String content, List<WidgetAnswer> widgets) {}

  /**
   * A refresh pass at an instant the caller names. The clock is an argument here for the
   * same reason it is one in the domain (SPEC-001 D-5): a benchmark comparing two systems
   * has to be able to put the same instant to both.
   */
  @Post("/api/pages/{slug}/refresh")
  public RefreshAnswer refresh(String slug, RefreshRequest request) {
    var at = request == null ? null : request.at();
    var now = at == null || at.isBlank() ? Instant.now() : Instant.parse(at.trim());
    var rendered = pageRefresh.refreshDue(slug, now);

    var widgets = new java.util.ArrayList<WidgetAnswer>();
    collectAnswers(rendered, now, widgets);
    return new RefreshAnswer(PageRenderer.render(rendered), List.copyOf(widgets));
  }

  private static void collectAnswers(
      List<PageRefresh.Rendered> rendered, Instant now, List<WidgetAnswer> into) {
    for (var widget : rendered) {
      if (widget.isContainer()) {
        collectAnswers(widget.children(), now, into);
        continue;
      }
      var state = widget.state();
      Long dueSeconds = null;
      if (state.nextUpdate() != null) {
        var millis = java.time.Duration.between(now, state.nextUpdate()).toMillis();
        // Rounded half away from zero, which is what the original's own rounding does,
        // so a deadline landing on a half second is not a difference between the two.
        dueSeconds = (long) (millis < 0 ? Math.ceil(millis / 1000.0 - 0.5) : Math.floor(millis / 1000.0 + 0.5));
      }
      into.add(
          new WidgetAnswer(
              widget.id(),
              state.contentAvailable(),
              state.error(),
              state.notice(),
              state.updateRetriedTimes(),
              dueSeconds,
              state.nextUpdate() == null ? null : state.nextUpdate().toString(),
              state.cacheMode().name(),
              state.items().stream().map(io.akka.glance.domain.FeedItem::link).toList()));
    }
  }

  @Get("/api/pages/{slug}/content/")
  public HttpResponse content(String slug) {
    return html(PageRenderer.render(pageRefresh.refreshDue(slug, Instant.now())));
  }

  /**
   * RENDERING.md R1. Every frame carries the whole page rather than a change to apply, so a
   * page that reconnects converges on the frame it receives and does not have to know what
   * it missed (SPEC-001 D-4, R17). An unchanged read sends nothing, so an idle page produces
   * no traffic beyond the stream itself (R1.1).
   */
  @Get("/api/pages/{slug}/stream")
  public HttpResponse stream(String slug) {
    Source<Frame, NotUsed> frames =
        Source.tick(Duration.ZERO, TICK, "")
            // Reading the page is a component call, and it blocks. Done inside the
            // stream's own map it would block the thread running every other stream on
            // this node, so one slow read would stall pages it has nothing to do with.
            .mapAsync(
                1,
                ignored ->
                    CompletableFuture.supplyAsync(
                        () -> new Frame(PageRenderer.render(pageRefresh.read(slug))),
                        pageRefresh.readers()))
            .statefulMapConcat(
                () -> {
                  var previous = new Frame[1];
                  return frame -> {
                    if (frame.equals(previous[0])) {
                      return List.of();
                    }
                    previous[0] = frame;
                    return List.of(frame);
                  };
                })
            .mapMaterializedValue(ignored -> NotUsed.getInstance());

    return HttpResponses.serverSentEvents(frames);
  }

  @Get("/{slug}")
  public HttpResponse page(String slug) {
    var page = componentClient.forKeyValueEntity(slug).method(PageEntity::get).invoke();
    var content = PageRenderer.render(pageRefresh.refreshDue(slug, Instant.now()));

    var shell =
        resource("page.html")
            .replace("{{SLUG}}", slug)
            .replace("{{TITLE}}", page.title())
            .replace("{{NAV}}", navigation(slug, page.title()))
            .replace("{{CONTENT}}", content);
    return html(shell);
  }

  /**
   * A link per page, the current one marked. Where no site has been configured the only
   * page anyone can be looking at is this one, so it is the only link.
   */
  private String navigation(String slug, String title) {
    var site = componentClient.forKeyValueEntity(SiteEntity.ID).method(SiteEntity::get).invoke();
    var pages = site.pages().isEmpty() ? List.of(new SiteEntity.Link(slug, title)) : site.pages();

    var nav = new StringBuilder();
    for (var link : pages) {
      if (!nav.isEmpty()) {
        nav.append("\n\n");
      }
      nav.append("<a href=\"/")
          .append(link.slug())
          .append("\" class=\"nav-item")
          .append(link.slug().equals(slug) ? " nav-item-current\" aria-current=\"page" : "")
          .append("\">")
          .append(link.title())
          .append("</a>");
    }
    return nav.toString();
  }

  // ---- the original's assets -----------------------------------------------------

  @Get("/static/" + ASSET_PREFIX + "/{kind}/{name}")
  public HttpResponse asset(String kind, String name) {
    var bytes = resourceBytes("static/" + kind + "/" + name);
    if (bytes == null) {
      return HttpResponses.notFound();
    }
    return HttpResponse.create()
        .withStatus(200)
        .withEntity(HttpEntities.create(contentTypeFor(name), bytes));
  }

  /**
   * The theme picker in the original's shell posts here when somebody chooses a theme. It is
   * page furniture rather than part of this slice, and it is answered so that the shell does
   * not report a failure for a control the port does not implement.
   */
  @Post("/api/set-theme/{key}")
  public String setTheme(String key) {
    return "";
  }

  // ---- plumbing ------------------------------------------------------------------

  private static HttpResponse html(String body) {
    return HttpResponse.create()
        .withStatus(200)
        .withEntity(
            HttpEntities.create(
                ContentTypes.TEXT_HTML_UTF8, body.getBytes(StandardCharsets.UTF_8)));
  }

  private static ContentType contentTypeFor(String name) {
    if (name.endsWith(".css")) {
      return ContentTypes.create(MediaTypes.TEXT_CSS, HttpCharsets.UTF_8);
    }
    if (name.endsWith(".js")) {
      return ContentTypes.create(MediaTypes.APPLICATION_JAVASCRIPT, HttpCharsets.UTF_8);
    }
    if (name.endsWith(".woff2")) {
      return MediaTypes.APPLICATION_FONT_WOFF.toContentType();
    }
    return ContentTypes.APPLICATION_OCTET_STREAM;
  }

  private static String resource(String path) {
    var bytes = resourceBytes(path);
    if (bytes == null) {
      throw new IllegalStateException("missing resource: " + path);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static byte[] resourceBytes(String path) {
    try (InputStream in = PageEndpoint.class.getClassLoader().getResourceAsStream(path)) {
      return in == null ? null : in.readAllBytes();
    } catch (IOException e) {
      return null;
    }
  }
}
