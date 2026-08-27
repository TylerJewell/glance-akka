package io.akka.glance.util;

import java.time.Instant;

/**
 * The instant a Go {@code time.Time} holds before anything is put in it.
 *
 * <p>Not the epoch: the first of January in year one. It reaches a page whenever a widget
 * shows a time it could not read, because the template writes the value's {@code Unix()}
 * whatever it is.
 */
public final class GoTime {

  public static final Instant ZERO = Instant.parse("0001-01-01T00:00:00Z");

  private GoTime() {}

  public static boolean isZero(Instant instant) {
    return instant == null || instant.equals(ZERO);
  }
}
