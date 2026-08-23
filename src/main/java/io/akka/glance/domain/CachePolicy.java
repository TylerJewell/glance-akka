package io.akka.glance.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * When a widget is due, and where its next deadline lands. SPEC-001 R1–R4, R8.
 *
 * <p>Every instant is an argument. Nothing here reads a clock, which is what lets the
 * benchmark run the same workload at two different offsets and require the same verdict.
 */
public final class CachePolicy {

  /** Consecutive failures stop lengthening the wait here; the retrying itself does not stop. */
  public static final int MAX_RETRIES = 5;

  private CachePolicy() {}

  /**
   * Whether a widget wants refreshing at {@code now}.
   *
   * <p>The comparison is strict: a deadline equal to {@code now} has not passed yet.
   */
  public static boolean isDue(CacheMode mode, Instant nextUpdate, Instant now) {
    if (mode == CacheMode.INFINITE) {
      return false;
    }
    return nextUpdate == null || now.isAfter(nextUpdate);
  }

  /** Where the next deadline lands when a refresh went to plan. Null in {@link CacheMode#INFINITE}. */
  public static Instant ordinaryDeadline(CacheMode mode, Duration cacheDuration, Instant completedAt) {
    return switch (mode) {
      case INFINITE -> null;
      case DURATION -> completedAt.plus(cacheDuration);
      // Minute and second go to zero and the sub-second remainder rides along, so a widget
      // refreshed at 12:34:56.789 is next due at 13:00:00.789.
      case ON_THE_HOUR -> completedAt.truncatedTo(ChronoUnit.HOURS)
          .plus(Duration.ofHours(1))
          .plusNanos(completedAt.getNano());
    };
  }

  /**
   * Where the next deadline lands after a failure: sooner than usual, but never later.
   *
   * <p>A widget with no ordinary deadline has none to pull forward either — SPEC-001 D-3.
   */
  public static Instant retryDeadline(
      CacheMode mode, Duration cacheDuration, int retriedTimes, Instant completedAt) {
    var ordinary = ordinaryDeadline(mode, cacheDuration, completedAt);
    if (ordinary == null) {
      return null;
    }
    var early = completedAt.plus(Duration.ofMinutes((long) retriedTimes * retriedTimes));
    return early.isAfter(ordinary) ? ordinary : early;
  }

  public static int nextRetryCount(int retriedTimes) {
    return Math.min(retriedTimes + 1, MAX_RETRIES);
  }

  public static int resetRetryCount() {
    return 0;
  }

  /**
   * How a configured cache span combines with the widget type's own default.
   *
   * <p>A non-positive default is the "no cache" sentinel and wins outright — it is tested
   * before the configured value is looked at, so a configured span on such a widget does
   * nothing.
   */
  public static Duration resolveCacheDuration(Duration configured, Duration widgetDefault) {
    if (widgetDefault.isNegative() || configured == null) {
      return widgetDefault;
    }
    return configured;
  }
}
