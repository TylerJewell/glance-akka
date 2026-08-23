package io.akka.glance.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R1, R2, R3, R4, R8. */
class CachePolicyTest {

  private static final Instant NOW = Instant.parse("2026-08-23T12:34:56.789Z");

  /**
   * R1, R2. Question-log row 1 answered this over the whole cross product rather than over a
   * few examples, so the check is the same cross product: three cache modes against a
   * deadline that is absent, behind, exactly equal, and ahead.
   */
  @Test
  void dueOverEveryCacheModeAndDeadlinePosition() {
    record Position(String name, Instant deadline) {}
    var positions =
        List.of(
            new Position("absent", null),
            new Position("past", NOW.minusSeconds(1)),
            new Position("exactly-now", NOW),
            new Position("future", NOW.plusSeconds(1)));

    var answers = new ArrayList<String>();
    for (var mode : CacheMode.values()) {
      for (var position : positions) {
        answers.add(
            mode + "/" + position.name() + "=" + CachePolicy.isDue(mode, position.deadline(), NOW));
      }
    }

    assertEquals(
        List.of(
            "INFINITE/absent=false",
            "INFINITE/past=false",
            "INFINITE/exactly-now=false",
            "INFINITE/future=false",
            "DURATION/absent=true",
            "DURATION/past=true",
            "DURATION/exactly-now=false",
            "DURATION/future=false",
            "ON_THE_HOUR/absent=true",
            "ON_THE_HOUR/past=true",
            "ON_THE_HOUR/exactly-now=false",
            "ON_THE_HOUR/future=false"),
        answers);
  }

  /** R3. The deadline keeps sub-second precision, which a coarser clock would lose. */
  @Test
  void durationDeadlineIsCompletionPlusDuration() {
    assertEquals(
        NOW.plus(Duration.ofHours(2)),
        CachePolicy.ordinaryDeadline(CacheMode.DURATION, Duration.ofHours(2), NOW));
    assertEquals(
        NOW.plusSeconds(30),
        CachePolicy.ordinaryDeadline(CacheMode.DURATION, Duration.ofSeconds(30), NOW));
  }

  /**
   * R3, row 10. A negative duration is not a separate mode: it is a deadline that has always
   * already passed, so the widget is due on the next question.
   */
  @Test
  void aNegativeDurationIsAlreadyExpired() {
    var deadline = CachePolicy.ordinaryDeadline(CacheMode.DURATION, Duration.ofNanos(-1), NOW);
    assertTrue(deadline.isBefore(NOW));
    assertTrue(CachePolicy.isDue(CacheMode.DURATION, deadline, NOW));
  }

  /** R4. Minute and second go to zero; the sub-second remainder is carried across. */
  @Test
  void onTheHourDeadlineIsTheNextTopOfTheHour() {
    var deadline = CachePolicy.ordinaryDeadline(CacheMode.ON_THE_HOUR, null, NOW);
    assertEquals(Instant.parse("2026-08-23T13:00:00.789Z"), deadline);
  }

  /** R4. An instant already on the hour gets the following hour, not itself. */
  @Test
  void onTheHourAtTheTopOfTheHourGoesToTheNextOne() {
    var onTheHour = Instant.parse("2026-08-23T12:00:00Z");
    assertEquals(
        Instant.parse("2026-08-23T13:00:00Z"),
        CachePolicy.ordinaryDeadline(CacheMode.ON_THE_HOUR, null, onTheHour));
  }

  /** R1, D-3. An infinite-cache widget has no deadline at all. */
  @Test
  void infiniteHasNoDeadline() {
    assertNull(CachePolicy.ordinaryDeadline(CacheMode.INFINITE, Duration.ofHours(1), NOW));
  }

  /** R8, row 4. One, four, nine, sixteen, twenty-five minutes — then twenty-five for ever. */
  @Test
  void retryDelayIsSquaredMinutesCappedAtFive() {
    var delays = new ArrayList<Long>();
    var retries = 0;
    for (var call = 1; call <= 8; call++) {
      retries = CachePolicy.nextRetryCount(retries);
      var deadline =
          CachePolicy.retryDeadline(CacheMode.DURATION, Duration.ofHours(24), retries, NOW);
      delays.add(Duration.between(NOW, deadline).toMinutes());
    }
    assertEquals(List.of(1L, 4L, 9L, 16L, 25L, 25L, 25L, 25L), delays);
    assertEquals(5, retries);
  }

  /** R8, row 5. The early deadline is taken only when it is genuinely earlier. */
  @Test
  void retryNeverOvershootsTheOrdinaryDeadline() {
    var deadline =
        CachePolicy.retryDeadline(CacheMode.DURATION, Duration.ofSeconds(30), 1, NOW);
    assertEquals(NOW.plusSeconds(30), deadline);
  }

  /**
   * R8, D-3. The source would put the epoch here, but only along a path it cannot take
   * (row 8), so the port leaves an infinite-cache widget without a deadline instead of
   * copying the artefact.
   */
  @Test
  void anInfiniteWidgetGetsNoRetryDeadline() {
    assertNull(CachePolicy.retryDeadline(CacheMode.INFINITE, Duration.ofHours(1), 3, NOW));
  }

  /** R5, row 6. A success puts the retry count back to where it started. */
  @Test
  void successResetsTheRetryCount() {
    assertEquals(0, CachePolicy.resetRetryCount());
    var retries = 0;
    for (var i = 0; i < 5; i++) {
      retries = CachePolicy.nextRetryCount(retries);
    }
    assertEquals(5, retries);
    assertEquals(0, CachePolicy.resetRetryCount());
  }

  /** R9. Configured cache duration against a widget's own default, per row 9. */
  @Test
  void configuredCacheReplacesTheDefaultExceptWhenTheDefaultIsNoCache() {
    assertEquals(
        Duration.ofHours(2), CachePolicy.resolveCacheDuration(null, Duration.ofHours(2)));
    assertEquals(
        Duration.ofMinutes(10),
        CachePolicy.resolveCacheDuration(Duration.ofMinutes(10), Duration.ofHours(2)));
    // The source tests its no-cache sentinel before it looks at the configured value, so a
    // configured value on such a widget does nothing.
    assertEquals(
        Duration.ofNanos(-1),
        CachePolicy.resolveCacheDuration(Duration.ofMinutes(5), Duration.ofNanos(-1)));
    assertFalse(
        CachePolicy.resolveCacheDuration(Duration.ofMinutes(5), Duration.ofNanos(-1)).isPositive());
  }
}
