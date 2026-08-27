package io.akka.glance.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.http.HttpResponses;
import akka.javasdk.http.RequestContext;
import io.akka.glance.app.Application;
import io.akka.glance.app.Site;
import io.akka.glance.render.Templates;
import java.nio.charset.StandardCharsets;

/**
 * Drawing one page's shell.
 *
 * <p>Shared rather than sitting in the page endpoint, because three paths the original
 * serves as ordinary pages — {@code /static}, {@code /assets} and {@code /api} — are the
 * first segment of a prefix this rebuild owns, and a router that has claimed the prefix will
 * not offer them back. Each of those endpoints answers its own bare path here instead, so a
 * page named after one of them is served rather than lost.
 */
final class Pages {

  private Pages() {}

  static HttpResponse shell(RequestContext context, String slug) {
    var application = Site.application();
    var page = application.pageBySlug(slug);
    if (page == null) {
      return Requests.notFoundPage();
    }
    var gate = Requests.gate(context, application, Requests.WhenUnauthorized.REDIRECT);
    if (gate.refusal() != null) {
      return gate.refusal();
    }
    var theme = application.themeFor(Requests.cookie(context, "theme"));
    var data =
        new Application.TemplateData(application, page, new Application.RequestData(theme));
    String markup = Templates.of("page.html", "document.html", "footer.html").execute(data);
    return gate.addRenewalTo(
        HttpResponses.of(
            StatusCodes.OK, ContentTypes.TEXT_HTML_UTF8, markup.getBytes(StandardCharsets.UTF_8)));
  }
}
