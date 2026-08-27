package io.akka.glance.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Location;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.glance.app.Site;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * The pages themselves.
 *
 * <p>The routes are the original's: the first page at the root, every page at its own slug,
 * and each page's contents on their own path so that the shell can be served before anything
 * has been fetched.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class PageEndpoint extends AbstractHttpEndpoint {

  @Get("/")
  public HttpResponse root() {
    return page("");
  }

  @Get("/{slug}")
  public HttpResponse page(String slug) {
    return Pages.shell(requestContext(), slug);
  }

  @Get("/api/pages/{slug}/content/")
  public HttpResponse content(String slug) {
    var application = Site.application();
    var page = application.pageBySlug(slug);
    if (page == null) {
      return Requests.notFoundPage();
    }
    var gate = Requests.gate(requestContext(), application, Requests.WhenUnauthorized.JSON);
    if (gate.refusal() != null) {
      return gate.refusal();
    }
    String markup = Site.content(page, Instant.now());
    return gate.addRenewalTo(
        HttpResponses.of(
            StatusCodes.OK,
            ContentTypes.TEXT_HTML_UTF8,
            markup.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * The original's own logout route: the cookie is cleared and the browser sent to the login
   * page.
   */
  @Get("/logout")
  public HttpResponse logout() {
    var application = Site.application();
    if (!application.RequiresAuth) {
      return Requests.notFoundPage();
    }
    return HttpResponse.create()
        .withStatus(StatusCodes.SEE_OTHER)
        .addHeaders(
            List.of(
                Location.create(application.Config.Server.BaseURL + "/login"),
                Requests.sessionCookie(requestContext(), application, "", Instant.EPOCH)))
        .withEntity(ContentTypes.TEXT_PLAIN_UTF8, new byte[0]);
  }
}
