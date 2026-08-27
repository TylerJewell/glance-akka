package io.akka.glance.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.HttpCookie;
import akka.http.javadsl.model.headers.Location;
import akka.http.javadsl.model.headers.SetCookie;
import akka.javasdk.http.RequestContext;
import io.akka.glance.app.Application;
import io.akka.glance.auth.Sessions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** What every route needs: the cookies on a request, and who it is from. */
final class Requests {

  /** What an unauthorised request is answered with, which differs by what asked. */
  enum WhenUnauthorized {
    REDIRECT,
    JSON
  }

  private Requests() {}

  static String cookie(RequestContext context, String name) {
    var header = context.requestHeader("Cookie");
    if (header.isEmpty()) {
      return "";
    }
    for (var pair : header.get().value().split(";")) {
      int equals = pair.indexOf('=');
      if (equals < 0) {
        continue;
      }
      if (pair.substring(0, equals).trim().equals(name)) {
        return pair.substring(equals + 1).trim();
      }
    }
    return "";
  }

  static String header(RequestContext context, String name) {
    return context.requestHeader(name).map(h -> h.value()).orElse("");
  }

  /** Where the request came from, as the rate limiter counts it. */
  static String address(RequestContext context, Application application) {
    return application.addressOfRequest(
        header(context, "Remote-Address"), header(context, "X-Forwarded-For"));
  }

  static HttpResponse notFoundPage() {
    return HttpResponse.create()
        .withStatus(StatusCodes.NOT_FOUND)
        .withEntity(ContentTypes.TEXT_PLAIN_UTF8, "Page not found".getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Whether this request may proceed.
   *
   * <p>A session with under a week left is replaced here, and the replacement rides back on
   * whatever the caller answers with — the original does the same, which is what keeps a
   * user who visits every day signed in indefinitely.
   */
  static Gate gate(RequestContext context, Application application, WhenUnauthorized fallback) {
    var authorisation = authorise(context, application);
    if (authorisation.authorised()) {
      var renewal = authorisation.replacementToken();
      return new Gate(
          null,
          renewal == null
              ? null
              : sessionCookie(
                  context, application, renewal, Instant.now().plus(Sessions.TOKEN_VALID_PERIOD)));
    }
    if (fallback == WhenUnauthorized.JSON) {
      return new Gate(
          HttpResponse.create()
              .withStatus(StatusCodes.UNAUTHORIZED)
              .withEntity(
                  ContentTypes.APPLICATION_JSON,
                  "{\"error\": \"Unauthorized\"}".getBytes(StandardCharsets.UTF_8)),
          null);
    }
    return new Gate(
        HttpResponse.create()
            .withStatus(StatusCodes.SEE_OTHER)
            .addHeader(Location.create(application.Config.Server.BaseURL + "/login"))
            .withEntity(ContentTypes.TEXT_PLAIN_UTF8, new byte[0]),
        null);
  }

  /**
   * What the gate decided: the response to send instead of doing the work, or the cookie to
   * add to the work's own response. At most one of the two is ever set.
   */
  record Gate(HttpResponse refusal, SetCookie renewal) {

    HttpResponse addRenewalTo(HttpResponse response) {
      return renewal == null ? response : response.addHeader(renewal);
    }
  }

  /** Whether a request carries a valid session, and a replacement token when one is due. */
  record Authorisation(boolean authorised, String replacementToken) {}

  static Authorisation authorise(RequestContext context, Application application) {
    if (!application.RequiresAuth) {
      return new Authorisation(true, null);
    }
    String token = cookie(context, Sessions.COOKIE_NAME);
    if (token.isEmpty()) {
      return new Authorisation(false, null);
    }
    Sessions.Verified verified;
    try {
      verified = Sessions.verify(token, application.authSecretKey(), Instant.now());
    } catch (RuntimeException e) {
      return new Authorisation(false, null);
    }
    String username = application.usernameForHash(verified.usernameHash());
    if (username == null || !application.Config.Auth.Users.containsKey(username)) {
      return new Authorisation(false, null);
    }
    if (!verified.shouldRegenerate()) {
      return new Authorisation(true, null);
    }
    try {
      return new Authorisation(
          true, Sessions.generate(username, application.authSecretKey(), Instant.now()));
    } catch (RuntimeException e) {
      return new Authorisation(false, null);
    }
  }

  /** The session cookie, with the flags the original sets on it. */
  static SetCookie sessionCookie(
      RequestContext context, Application application, String token, Instant expires) {
    boolean secure = header(context, "X-Forwarded-Proto").equalsIgnoreCase("https");
    var cookie =
        HttpCookie.create(Sessions.COOKIE_NAME, token)
            .withPath(application.Config.Server.BaseURL + "/")
            .withHttpOnly(true)
            .withSecure(secure)
            .withExpires(akka.http.javadsl.model.DateTime.create(expires.toEpochMilli()));
    return SetCookie.create(withSameSiteLax(cookie));
  }

  /**
   * {@code SameSite=Lax}, which the Java model spells through a raw extension rather than a
   * flag of its own.
   */
  private static HttpCookie withSameSiteLax(HttpCookie cookie) {
    return cookie.withExtension("SameSite=Lax");
  }

  static List<SetCookie> themeCookie(Application application, String key, Instant expires) {
    var cookie =
        HttpCookie.create("theme", key)
            .withPath(application.Config.Server.BaseURL + "/")
            .withExpires(akka.http.javadsl.model.DateTime.create(expires.toEpochMilli()));
    return List.of(SetCookie.create(withSameSiteLax(cookie)));
  }
}
