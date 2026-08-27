package io.akka.glance.auth;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The token a signed-in browser carries.
 *
 * <p>Its bytes are a hash of the user's name, the instant it stops being valid, and a
 * signature over both. Nothing about the user is readable from it, and nothing in it can be
 * changed without the secret key.
 */
public final class Sessions {

  public static final String COOKIE_NAME = "session_token";

  public static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(5);
  public static final int RATE_LIMIT_MAX_ATTEMPTS = 5;

  public static final int TOKEN_SECRET_LENGTH = 32;
  public static final int USERNAME_HASH_LENGTH = 32;
  public static final int SECRET_KEY_LENGTH = TOKEN_SECRET_LENGTH + USERNAME_HASH_LENGTH;

  /** Four bytes, holding the expiry as a count of seconds. */
  public static final int TIMESTAMP_LENGTH = 4;

  public static final int TOKEN_DATA_LENGTH = USERNAME_HASH_LENGTH + TIMESTAMP_LENGTH;

  public static final Duration TOKEN_VALID_PERIOD = Duration.ofDays(14);

  /** How long a token has left before a request is answered with a fresh one. */
  public static final Duration TOKEN_REGEN_BEFORE = Duration.ofDays(7);

  private Sessions() {}

  /** A token for {@code username}, valid for the usual period from {@code now}. */
  public static String generate(String username, byte[] secret, Instant now) {
    if (secret.length != SECRET_KEY_LENGTH) {
      throw new AuthException("secret key length is not " + SECRET_KEY_LENGTH + " bytes");
    }
    byte[] usernameHash = usernameHash(username, secret);
    var data = ByteBuffer.allocate(TOKEN_DATA_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
    data.put(usernameHash);
    data.putInt((int) now.plus(TOKEN_VALID_PERIOD).getEpochSecond());
    byte[] signed = hmac(Arrays.copyOfRange(secret, 0, TOKEN_SECRET_LENGTH), data.array());
    var token = new byte[TOKEN_DATA_LENGTH + signed.length];
    System.arraycopy(data.array(), 0, token, 0, TOKEN_DATA_LENGTH);
    System.arraycopy(signed, 0, token, TOKEN_DATA_LENGTH, signed.length);
    return Base64.getEncoder().encodeToString(token);
  }

  public static byte[] usernameHash(String username, byte[] secret) {
    if (secret.length != SECRET_KEY_LENGTH) {
      throw new AuthException("secret key length is not " + SECRET_KEY_LENGTH + " bytes");
    }
    return hmac(
        Arrays.copyOfRange(secret, TOKEN_SECRET_LENGTH, secret.length),
        username.getBytes(StandardCharsets.UTF_8));
  }

  /** Which user a token names, and whether it is close enough to expiry to be replaced. */
  public record Verified(byte[] usernameHash, boolean shouldRegenerate) {}

  public static Verified verify(String token, byte[] secret, Instant now) {
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(token);
    } catch (IllegalArgumentException e) {
      throw new AuthException("illegal base64 data");
    }
    if (bytes.length != TOKEN_DATA_LENGTH + 32) {
      throw new AuthException("token length is invalid");
    }
    if (secret.length != SECRET_KEY_LENGTH) {
      throw new AuthException("secret key length is not " + SECRET_KEY_LENGTH + " bytes");
    }
    byte[] usernameHash = Arrays.copyOfRange(bytes, 0, USERNAME_HASH_LENGTH);
    byte[] provided = Arrays.copyOfRange(bytes, TOKEN_DATA_LENGTH, bytes.length);
    byte[] expected =
        hmac(
            Arrays.copyOfRange(secret, 0, TOKEN_SECRET_LENGTH),
            Arrays.copyOfRange(bytes, 0, TOKEN_DATA_LENGTH));
    if (!MessageDigest.isEqual(expected, provided)) {
      throw new AuthException("signature does not match");
    }
    long expires =
        Integer.toUnsignedLong(
            ByteBuffer.wrap(bytes, USERNAME_HASH_LENGTH, TIMESTAMP_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt());
    if (now.getEpochSecond() > expires) {
      throw new AuthException("token has expired");
    }
    boolean shouldRegenerate =
        Instant.ofEpochSecond(expires).minus(TOKEN_REGEN_BEFORE).isBefore(now);
    return new Verified(usernameHash, shouldRegenerate);
  }

  /** A new secret key, as the {@code secret:make} command prints it. */
  public static String makeSecretKey(int length) {
    var key = new byte[length];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (Exception e) {
      throw new AuthException("computing signature: " + e.getMessage());
    }
  }

  /** A token or key that could not be read. */
  public static class AuthException extends RuntimeException {
    public AuthException(String message) {
      super(message);
    }
  }
}
