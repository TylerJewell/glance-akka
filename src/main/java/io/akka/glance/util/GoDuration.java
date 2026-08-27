package io.akka.glance.util;

import java.time.Duration;

/**
 * A span of time with the names Go's {@code time.Duration} answers to.
 *
 * <p>The monitor's template writes {@code .Status.ResponseTime.Milliseconds}, so the value
 * behind that field has to answer to that name rather than to Java's own.
 */
public record GoDuration(long nanos) {

  public static final GoDuration ZERO = new GoDuration(0);

  public static GoDuration of(Duration duration) {
    return new GoDuration(duration.toNanos());
  }

  public static GoDuration ofNanos(long nanos) {
    return new GoDuration(nanos);
  }

  public long Milliseconds() {
    return nanos / 1_000_000;
  }

  public long Seconds() {
    return nanos / 1_000_000_000;
  }

  public Duration duration() {
    return Duration.ofNanos(nanos);
  }
}
