package io.akka.glance.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The session token, against what the original's own {@code auth_test.go} asserts about it.
 *
 * <p>The tampering case is the one that matters: every single byte of the token is part of what
 * the signature covers, so changing any one of them has to make it unreadable.
 */
class SessionsTest {

  @Test
  void aTokenIsGeneratedAndReadBack() {
    var secret = Base64.getDecoder().decode(Sessions.makeSecretKey(Sessions.SECRET_KEY_LENGTH));
    assertEquals(Sessions.SECRET_KEY_LENGTH, secret.length);

    var now = Instant.now();
    String token = Sessions.generate("admin", secret, now);

    var verified = Sessions.verify(token, secret, now);
    assertFalse(
        verified.shouldRegenerate(),
        "a token should not need replacing the moment it was made");
    assertArrayEquals(Sessions.usernameHash("admin", secret), verified.usernameHash());
  }

  @Test
  void aTokenNearingItsEndIsMarkedForReplacement() {
    var secret = Base64.getDecoder().decode(Sessions.makeSecretKey(Sessions.SECRET_KEY_LENGTH));
    var now = Instant.now();
    String token = Sessions.generate("admin", secret, now);

    var justInsideTheWindow =
        now.plus(Sessions.TOKEN_VALID_PERIOD)
            .minus(Sessions.TOKEN_REGEN_BEFORE)
            .plus(Duration.ofSeconds(2));
    assertTrue(Sessions.verify(token, secret, justInsideTheWindow).shouldRegenerate());
  }

  @Test
  void anExpiredTokenIsRefused() {
    var secret = Base64.getDecoder().decode(Sessions.makeSecretKey(Sessions.SECRET_KEY_LENGTH));
    var now = Instant.now();
    String token = Sessions.generate("admin", secret, now);
    var afterExpiry = now.plus(Sessions.TOKEN_VALID_PERIOD).plus(Duration.ofSeconds(2));
    assertThrows(
        Sessions.AuthException.class, () -> Sessions.verify(token, secret, afterExpiry));
  }

  @Test
  void changingAnyByteOfATokenMakesItUnreadable() {
    var secret = Base64.getDecoder().decode(Sessions.makeSecretKey(Sessions.SECRET_KEY_LENGTH));
    var now = Instant.now();
    String token = Sessions.generate("admin", secret, now);
    byte[] decoded = Base64.getDecoder().decode(token);

    for (int i = 0; i < decoded.length; i++) {
      byte[] tampered = decoded.clone();
      tampered[i] += 1;
      String encoded = Base64.getEncoder().encodeToString(tampered);
      int at = i;
      assertThrows(
          Sessions.AuthException.class,
          () -> Sessions.verify(encoded, secret, now),
          "a token with byte " + at + " changed was accepted");
    }
  }
}
