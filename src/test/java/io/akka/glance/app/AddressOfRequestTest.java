package io.akka.glance.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.akka.glance.config.ConfigLoader;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Which address a request is counted against, from the original's own {@code address_test.go}.
 *
 * <p>The rightmost value is the one the trusted proxy added; anything to the left of it can be
 * written by whoever is asking, so it decides nothing.
 */
class AddressOfRequestTest {

  private static final String CONFIG =
      """
      pages:
        - name: Home
          columns:
            - size: full
              widgets: []
      """;

  private static Application applicationWith(boolean proxied) {
    var config = ConfigLoader.fromYaml(CONFIG);
    config.Server.Proxied = proxied;
    return new Application(config, Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void notProxiedUsesTheRemoteAddress() {
    assertEquals("1.2.3.4", applicationWith(false).addressOfRequest("1.2.3.4:5678", ""));
  }

  @Test
  void proxiedWithNoForwardedForFallsBack() {
    assertEquals("1.2.3.4", applicationWith(true).addressOfRequest("1.2.3.4:5678", ""));
  }

  @Test
  void proxiedWithOneAddressUsesIt() {
    assertEquals("5.6.7.8", applicationWith(true).addressOfRequest("1.2.3.4:5678", "5.6.7.8"));
  }

  @Test
  void proxiedWithSeveralUsesTheLast() {
    assertEquals(
        "5.6.7.8", applicationWith(true).addressOfRequest("1.2.3.4:5678", "1.2.3.4, 5.6.7.8"));
  }

  @Test
  void aSpoofedFirstAddressIsIgnored() {
    assertEquals(
        "1.2.3.4",
        applicationWith(true).addressOfRequest("1.2.3.4:5678", "99.99.99.99, 1.2.3.4"));
  }

  @Test
  void aTrailingCommaFallsBackToTheRemoteAddress() {
    assertEquals("1.2.3.4", applicationWith(true).addressOfRequest("1.2.3.4:5678", "5.6.7.8,"));
  }

  @Test
  void whitespaceAroundAnAddressIsTrimmed() {
    assertEquals(
        "5.6.7.8", applicationWith(true).addressOfRequest("1.2.3.4:5678", "  5.6.7.8  "));
  }
}
