package io.akka.glance.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.akka.glance.app.Application;
import io.akka.glance.config.Config;
import io.akka.glance.config.ConfigLoader;
import io.akka.glance.config.Includes;
import io.akka.glance.net.Endpoints;
import io.akka.glance.render.Templates;
import io.akka.glance.util.Assets;
import io.akka.glance.widget.ContainerWidget;
import io.akka.glance.widget.Widget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the rebuild's whole page pipeline over one configuration and prints what it drew.
 *
 * <p>The counterpart of {@code glance-port/probes/source_probe/complete_source.go}: the same
 * configuration, the same fixture server, the same instant, and the same shape of answer, so
 * the two can be compared byte for byte.
 */
public final class CompleteDriver {

  private CompleteDriver() {}

  /** What to drive: a configuration, where the services are, and where to write the answer. */
  public record Spec(String configPath, String fixtures, String out, long createdAtUnix) {}

  /** One page, as both sides describe it. */
  public record Page(String slug, String title, String shell, String content) {}

  /** One widget, as both sides describe it. */
  public record WidgetAnswer(
      String type,
      String title,
      String titleUrl,
      boolean contentAvailable,
      String error,
      String notice,
      String cacheMode,
      long cacheSeconds,
      int retries,
      String markup) {}

  /** Everything one run produced. */
  public record Answers(
      long createdAtUnix,
      String staticHash,
      String manifest,
      String themeCss,
      List<String> themePreviews,
      int bundleLength,
      List<Page> pages,
      List<WidgetAnswer> widgets,
      String error) {}

  public static void main(String[] args) throws Exception {
    var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    var spec = mapper.readValue(Path.of(args[0]).toFile(), Spec.class);
    pointAt(spec.fixtures());
    var answers = run(spec);
    Files.writeString(Path.of(spec.out()), mapper.writeValueAsString(answers));
  }

  /** Sends every service this reads from to the fixture server, under the host's own path. */
  public static void pointAt(String fixtures) {
    Endpoints.hackerNews = fixtures + "/svc/hacker-news.firebaseio.com/v0/";
    Endpoints.lobsters = fixtures + "/svc/lobste.rs/";
    Endpoints.reddit = fixtures + "/svc/www.reddit.com";
    Endpoints.twitchGql = fixtures + "/svc/gql.twitch.tv/gql";
    Endpoints.github = fixtures + "/svc/api.github.com";
    Endpoints.dockerHub = fixtures + "/svc/hub.docker.com";
    Endpoints.gitlab = fixtures + "/svc/gitlab.com";
    Endpoints.codeberg = fixtures + "/svc/codeberg.org";
    Endpoints.openMeteo = fixtures + "/svc/api.open-meteo.com";
    Endpoints.openMeteoGeocoding = fixtures + "/svc/geocoding-api.open-meteo.com";
    Endpoints.yahooFinance = fixtures + "/svc/query1.finance.yahoo.com";
    Endpoints.youtube = fixtures + "/svc/www.youtube.com";
  }

  public static Answers run(Spec spec) {
    Config config;
    Application application;
    try {
      String contents = Includes.parse(Path.of(spec.configPath())).contents();
      config = ConfigLoader.fromYaml(contents);
      application = new Application(config, Instant.ofEpochSecond(spec.createdAtUnix()));
    } catch (RuntimeException e) {
      return new Answers(
          0, "", "", "", List.of(), 0, List.of(), List.of(), String.valueOf(e.getMessage()));
    }

    var previews = new ArrayList<String>();
    for (var entry : config.Theme.Presets.Items()) {
      previews.add(entry.getKey() + "\n" + entry.getValue().PreviewHTML.value());
    }

    var pages = new ArrayList<Page>();
    var widgets = new ArrayList<WidgetAnswer>();
    var now = Instant.now();
    for (var page : config.Pages) {
      var data =
          new Application.TemplateData(
              application, page, new Application.RequestData(config.Theme));
      String shell = Templates.of("page.html", "document.html", "footer.html").execute(data);
      String content = io.akka.glance.app.Site.content(page, now);
      pages.add(new Page(page.Slug, page.Title, shell, content));
      for (var widget : page.HeadWidgets) {
        describe(widget, widgets);
      }
      for (var column : page.Columns) {
        for (var widget : column.Widgets) {
          describe(widget, widgets);
        }
      }
    }

    return new Answers(
        spec.createdAtUnix(),
        Assets.hash(),
        application.manifest(),
        config.Theme.CSS.value(),
        previews,
        Assets.bundledCss().length,
        pages,
        widgets,
        "");
  }

  private static void describe(Widget widget, List<WidgetAnswer> into) {
    into.add(
        new WidgetAnswer(
            widget.GetType(),
            widget.Title,
            widget.TitleURL,
            widget.ContentAvailable,
            widget.Error == null ? "" : widget.Error.message(),
            widget.Notice == null ? "" : widget.Notice.message(),
            widget.cacheType().name(),
            widget.cacheDuration().getSeconds(),
            widget.updateRetriedTimes(),
            widget.Render().value()));
    if (widget instanceof ContainerWidget container) {
      for (var child : container.children()) {
        describe(child, into);
      }
    }
  }
}
