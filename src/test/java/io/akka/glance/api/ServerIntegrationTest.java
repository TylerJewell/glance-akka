package io.akka.glance.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.glance.auth.Sessions;
import io.akka.glance.util.Assets;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The server's own surface, driven the way a browser drives it.
 *
 * <p>These go through the HTTP layer rather than calling the code behind it, because the
 * routes, the cookies, the statuses and the content types are what a caller sees and none of
 * them is exercised by asking the components directly.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerIntegrationTest extends TestKitSupport {

  static {
    // These drive the server through the configuration route, which an instance offers only
    // when it was told to.
    System.setProperty("glance.config-api", "on");
  }

  private static final String OPEN_CONFIG =
      """
      branding:
        app-name: Fixture
      pages:
        - name: Home
          columns:
            - size: full
              widgets:
                - type: html
                  source: '<p id="marker">Hello</p>'
        - name: Second Page
          columns:
            - size: full
              widgets: []
      """;

  /** A configuration with one account. The hash is bcrypt's for {@code correct-horse}. */
  private static final String CLOSED_CONFIG =
      """
      auth:
        secret-key: %s
        users:
          admin:
            password: correct-horse
      pages:
        - name: Home
          columns:
            - size: full
              widgets:
                - type: html
                  source: '<p id="marker">Hello</p>'
      """;

  /**
   * The response, with its body read as text.
   *
   * <p>Read through the raw parser rather than as a typed body, because a typed body turns a
   * failing status into an exception and several of these are about what a failure says.
   */
  private static akka.javasdk.http.StrictResponse<String> text(
      akka.javasdk.http.RequestBuilder<?> request) {
    return request
        .parseResponseBody(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
        .invoke();
  }

  private void load(String yaml) {
    var response =
        httpClient.PUT("/api/config/").withRequestBody(new ConfigEndpoint.Load(yaml, null)).invoke();
    assertEquals(200, response.status().intValue());
  }

  @Test
  @Order(1)
  void thePageIsServedAtTheRootAndAtItsSlug() {
    load(OPEN_CONFIG);
    var root = text(httpClient.GET("/"));
    assertEquals(200, root.status().intValue());
    assertTrue(root.body().contains("<title>Home</title>"));
    assertTrue(
        root.body().contains("apple-mobile-web-app-title\" content=\"Fixture\""));

    var second = text(httpClient.GET("/second-page"));
    assertEquals(200, second.status().intValue());
    assertTrue(second.body().contains("<title>Second Page</title>"));
  }

  @Test
  @Order(2)
  void aPageThatIsNotThereSaysSo() {
    load(OPEN_CONFIG);
    var response = text(httpClient.GET("/nonesuch"));
    assertEquals(404, response.status().intValue());
    assertEquals("Page not found", response.body());
  }

  @Test
  @Order(3)
  void aPagesContentsAreServedSeparatelyFromItsShell() {
    load(OPEN_CONFIG);
    var response = text(httpClient.GET("/api/pages/home/content/"));
    assertEquals(200, response.status().intValue());
    assertTrue(response.body().contains("<p id=\"marker\">Hello</p>"));
    assertFalse(response.body().contains("<title>"), "the shell is not repeated");
  }

  @Test
  @Order(4)
  void healthIsAnswered() {
    assertEquals(200, httpClient.GET("/api/healthz").invoke().status().intValue());
  }

  @Test
  @Order(5)
  void theManifestNamesTheApplication() {
    load(OPEN_CONFIG);
    var response = text(httpClient.GET("/manifest.json"));
    assertEquals(200, response.status().intValue());
    assertTrue(response.body().contains("\"name\": \"Fixture\""));
  }

  @Test
  @Order(6)
  void aStaticAssetIsServedFromThePathCarryingItsDigest() {
    load(OPEN_CONFIG);
    var response = text(httpClient.GET("/static/" + Assets.hash() + "/js/page.js"));
    assertEquals(200, response.status().intValue());
    assertTrue(response.body().contains("function setupPage"));

    var wrongDigest = text(httpClient.GET("/static/0000000000/js/page.js"));
    assertEquals(404, wrongDigest.status().intValue());
  }

  @Test
  @Order(7)
  void theStyleSheetIsBuiltFromEveryPartOfIt() {
    load(OPEN_CONFIG);
    var response = text(httpClient.GET("/static/" + Assets.hash() + "/css/bundle.css"));
    assertEquals(200, response.status().intValue());
    String css = response.body();
    assertTrue(css.contains(":root {"), "the main sheet");
    assertTrue(css.contains(".widget-header"), "a sheet it imports");
    assertFalse(css.contains("@import"), "nothing is left to fetch");
  }

  @Test
  @Order(8)
  void aThemeMayBeChosenAndComesBackAsAStyleSheet() {
    load(OPEN_CONFIG);
    var response = text(httpClient.POST("/api/set-theme/default-light"));
    assertEquals(200, response.status().intValue());
    assertEquals("light", response.httpResponse().getHeader("X-Scheme").get().value());
    assertTrue(response.body().contains("--bgh:"));
    assertTrue(
        response.httpResponse().getHeader("Set-Cookie").get().value().startsWith("theme=default-light"));
  }

  @Test
  @Order(9)
  void aThemeThatIsNotThereIsNotFound() {
    load(OPEN_CONFIG);
    assertEquals(404, httpClient.POST("/api/set-theme/nonesuch").invoke().status().intValue());
  }

  @Test
  @Order(10)
  void aPerWidgetRequestIsAnsweredTheWayTheOriginalAnswersIt() {
    load(OPEN_CONFIG);
    assertEquals(501, httpClient.GET("/api/widgets/1/anything").invoke().status().intValue());
  }

  @Test
  @Order(11)
  void withAnAccountConfiguredAPageRedirectsToTheLogin() {
    load(CLOSED_CONFIG.formatted(Sessions.makeSecretKey(Sessions.SECRET_KEY_LENGTH)));
    var response = text(httpClient.GET("/"));
    assertEquals(303, response.status().intValue());
    assertEquals("/login", response.httpResponse().getHeader("Location").get().value());
  }

  @Test
  @Order(12)
  void withAnAccountConfiguredTheContentsAnswerWithJson() {
    load(CLOSED_CONFIG.formatted(Sessions.makeSecretKey(Sessions.SECRET_KEY_LENGTH)));
    var response = text(httpClient.GET("/api/pages/home/content/"));
    assertEquals(401, response.status().intValue());
    assertTrue(response.body().contains("Unauthorized"));
  }

  @Test
  @Order(13)
  void theWrongPasswordIsRefusedAndTheRightOneSetsACookie() {
    load(CLOSED_CONFIG.formatted(Sessions.makeSecretKey(Sessions.SECRET_KEY_LENGTH)));
    AuthEndpoint.forgetFailedAttempts();

    var refused =
        httpClient
            .POST("/api/authenticate")
            .withRequestBody(new AuthEndpoint.Credentials("admin", "wrong-password"))
            .invoke();
    assertEquals(401, refused.status().intValue());

    var accepted =
        httpClient
            .POST("/api/authenticate")
            .withRequestBody(new AuthEndpoint.Credentials("admin", "correct-horse"))
            .invoke();
    assertEquals(200, accepted.status().intValue());
    String cookie = accepted.httpResponse().getHeader("Set-Cookie").get().value();
    assertTrue(cookie.startsWith(Sessions.COOKIE_NAME + "="));
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Lax"));
  }

  @Test
  @Order(14)
  void anUnknownUserIsRefused() {
    load(CLOSED_CONFIG.formatted(Sessions.makeSecretKey(Sessions.SECRET_KEY_LENGTH)));
    AuthEndpoint.forgetFailedAttempts();
    var response =
        httpClient
            .POST("/api/authenticate")
            .withRequestBody(new AuthEndpoint.Credentials("nobody", "correct-horse"))
            .invoke();
    assertEquals(401, response.status().intValue());
  }

  @Test
  @Order(15)
  void aConfigurationThatContradictsItselfIsRefusedWithItsReason() {
    var response =
        httpClient
            .PUT("/api/config/")
            .withRequestBody(new ConfigEndpoint.Load("pages: []\n", null))
            .parseResponseBody(
                bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
            .invoke();
    assertEquals(400, response.status().intValue());
    assertTrue(response.body().contains("no pages configured"));
  }

  @Test
  @Order(16)
  void aLoadInstantThatIsNotAnInstantIsRefusedRatherThanRaised() {
    var response =
        httpClient
            .PUT("/api/config/")
            .withRequestBody(new ConfigEndpoint.Load(OPEN_CONFIG, "not-a-time"))
            .parseResponseBody(
                bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
            .invoke();
    assertEquals(400, response.status().intValue());
    assertTrue(response.body().contains("ISO-8601"));
  }

  @Test
  @Order(17)
  void theConfigurationThatWasLoadedCanBeReadBack() {
    load(OPEN_CONFIG);
    var response = text(httpClient.GET("/api/config/"));
    assertEquals(200, response.status().intValue());
    assertTrue(response.body().contains("app-name: Fixture"));
  }

  @Test
  @Order(18)
  void aSessionCloseToExpiryIsReplacedOnTheNextPageItAsksFor() {
    String key = Sessions.makeSecretKey(Sessions.SECRET_KEY_LENGTH);
    load(CLOSED_CONFIG.formatted(key));
    var secret = java.util.Base64.getDecoder().decode(key);

    // Issued eight days ago, so it has six days left and is inside the week that asks for a
    // replacement.
    String ageing =
        Sessions.generate("admin", secret, java.time.Instant.now().minus(java.time.Duration.ofDays(8)));
    var response =
        text(httpClient.GET("/").addHeader("Cookie", Sessions.COOKIE_NAME + "=" + ageing));

    assertEquals(200, response.status().intValue());
    String cookie = response.httpResponse().getHeader("Set-Cookie").get().value();
    assertTrue(cookie.startsWith(Sessions.COOKIE_NAME + "="));
    assertFalse(cookie.contains("=" + ageing), "the token that came back is a new one");
  }

  @Test
  @Order(19)
  void anInstanceThatWasNotToldToOfferTheConfigurationRouteDoesNotHaveIt() {
    load(OPEN_CONFIG);
    System.clearProperty("glance.config-api");
    try {
      var response = text(httpClient.GET("/api/config/"));
      assertEquals(404, response.status().intValue());
      assertEquals("Page not found", response.body());
    } finally {
      System.setProperty("glance.config-api", "on");
    }
  }
}
