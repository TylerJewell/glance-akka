package io.akka.glance.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Location;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import at.favre.lib.crypto.bcrypt.BCrypt;
import io.akka.glance.app.Application;
import io.akka.glance.app.Site;
import io.akka.glance.auth.Sessions;
import io.akka.glance.render.Templates;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Signing in.
 *
 * <p>A failed attempt is answered slowly and counted, and an address that fails five times
 * inside the window is refused until it closes. Both are the original's own: they cost an
 * attacker time and tell an ordinary user nothing about which half was wrong.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class AuthEndpoint extends AbstractHttpEndpoint {

  /** What one address has got wrong lately. */
  private record FailedAttempts(int attempts, Instant first) {}

  private static final Map<String, FailedAttempts> FAILED = new HashMap<>();
  private static final Object FAILED_LOCK = new Object();
  /** Per-thread, because every request that fails draws from this at once. */
  private static int jitterMillis() {
    return java.util.concurrent.ThreadLocalRandom.current().nextInt(500);
  }

  /** What a caller sends to sign in. */
  public record Credentials(String username, String password) {}

  @Get("/login")
  public HttpResponse loginPage() {
    var application = Site.application();
    if (!application.RequiresAuth) {
      return Requests.notFoundPage();
    }
    var gate = Requests.gate(requestContext(), application, Requests.WhenUnauthorized.REDIRECT);
    if (gate.refusal() == null) {
      return gate.addRenewalTo(
          HttpResponse.create()
              .withStatus(StatusCodes.SEE_OTHER)
              .addHeader(Location.create(application.Config.Server.BaseURL + "/"))
              .withEntity(ContentTypes.TEXT_PLAIN_UTF8, new byte[0]));
    }
    var theme = application.themeFor(Requests.cookie(requestContext(), "theme"));
    var data = new Application.TemplateData(application, null, new Application.RequestData(theme));
    String markup =
        Templates.of("login.html", "document.html", "footer.html").execute(data);
    return HttpResponses.of(
        StatusCodes.OK, ContentTypes.TEXT_HTML_UTF8, markup.getBytes(StandardCharsets.UTF_8));
  }

  @Post("/api/authenticate")
  public HttpResponse authenticate(Credentials credentials) {
    var application = Site.application();
    if (!application.RequiresAuth) {
      return Requests.notFoundPage();
    }
    // The original refuses a body that is not declared as JSON. Here the runtime has
    // already refused anything it could not read as JSON before this is reached, with the
    // same status, so there is nothing left for this to check.
    // Between half a second and a second, so that a failure cannot be timed.
    var waitOnFailure =
        Duration.ofMillis(1000).minus(Duration.ofMillis(jitterMillis()));
    String address = Requests.address(requestContext(), application);

    int retryAfter = registerAttempt(address);
    if (retryAfter > 0) {
      sleep(waitOnFailure);
      return HttpResponse.create()
          .withStatus(StatusCodes.TOO_MANY_REQUESTS)
          .addHeader(RawHeader.create("Retry-After", String.valueOf(retryAfter)));
    }

    if (credentials == null
        || credentials.username() == null
        || credentials.password() == null
        || credentials.username().isEmpty()
        || credentials.password().isEmpty()) {
      sleep(waitOnFailure);
      return HttpResponse.create().withStatus(StatusCodes.UNAUTHORIZED);
    }
    if (credentials.username().length() > 50 || credentials.password().length() > 100) {
      sleep(waitOnFailure);
      return HttpResponse.create().withStatus(StatusCodes.UNAUTHORIZED);
    }
    var user = application.Config.Auth.Users.get(credentials.username());
    if (user == null) {
      sleep(waitOnFailure);
      return HttpResponse.create().withStatus(StatusCodes.UNAUTHORIZED);
    }
    var verified =
        BCrypt.verifyer()
            .verify(credentials.password().getBytes(StandardCharsets.UTF_8), user.PasswordHash);
    if (!verified.verified) {
      sleep(waitOnFailure);
      return HttpResponse.create().withStatus(StatusCodes.UNAUTHORIZED);
    }
    String token;
    try {
      token =
          Sessions.generate(credentials.username(), application.authSecretKey(), Instant.now());
    } catch (RuntimeException e) {
      sleep(waitOnFailure);
      return HttpResponse.create().withStatus(StatusCodes.UNAUTHORIZED);
    }
    synchronized (FAILED_LOCK) {
      FAILED.remove(address);
    }
    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .addHeaders(
            List.of(
                Requests.sessionCookie(
                    requestContext(), application, token, Instant.now().plus(Sessions.TOKEN_VALID_PERIOD))))
        .withEntity(ContentTypes.TEXT_PLAIN_UTF8, new byte[0]);
  }

  /**
   * Counts this attempt.
   *
   * @return how many seconds to wait before trying again, or zero when the attempt may proceed
   */
  private static int registerAttempt(String address) {
    synchronized (FAILED_LOCK) {
      var attempt = FAILED.get(address);
      if (attempt == null) {
        FAILED.put(address, new FailedAttempts(1, Instant.now()));
        return 0;
      }
      var elapsed = Duration.between(attempt.first(), Instant.now());
      if (elapsed.compareTo(Sessions.RATE_LIMIT_WINDOW) < 0
          && attempt.attempts() >= Sessions.RATE_LIMIT_MAX_ATTEMPTS) {
        return (int)
            Math.max(1, Sessions.RATE_LIMIT_WINDOW.getSeconds() - elapsed.getSeconds());
      }
      FAILED.put(address, new FailedAttempts(attempt.attempts() + 1, attempt.first()));
      FAILED.entrySet()
          .removeIf(
              entry ->
                  Duration.between(entry.getValue().first(), Instant.now())
                          .compareTo(Sessions.RATE_LIMIT_WINDOW)
                      > 0);
      return 0;
    }
  }

  /** Only for a test that has just used up an address's attempts. */
  public static void forgetFailedAttempts() {
    synchronized (FAILED_LOCK) {
      FAILED.clear();
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
