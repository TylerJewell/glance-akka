package io.akka.glance.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R5, R6, R7, R8, R9. */
class WidgetStateTest {

  private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

  private static WidgetState hourly() {
    return WidgetState.configured(
        "Healthy", CacheMode.DURATION, Duration.ofHours(1), List.of(), 25, false);
  }

  private static FeedItem item(String link, String published) {
    return new FeedItem("t-" + link, link, "chan", "http://chan", Instant.parse(published));
  }

  /**
   * R5, R6, R7, R9. Question-log row 3 enumerated the class of outcomes rather than sampling
   * it, and row 12 established that the partial/total line is drawn on all-failed rather
   * than any-failed. The check walks the same enumeration and pins every field each outcome
   * touches, because two of the three differ only in which flag moved.
   */
  @Test
  void everyOutcomeShape() {
    var items = List.of(item("a", "2026-08-01T00:00:00Z"));
    record Case(String name, RefreshOutcome outcome) {}
    var cases =
        List.of(
            new Case("nothing failed", new RefreshOutcome(items, 0, 2, Map.of())),
            new Case("one of two failed", new RefreshOutcome(items, 1, 2, Map.of())),
            new Case("one of one failed", new RefreshOutcome(List.of(), 1, 1, Map.of())),
            new Case("two of two failed", new RefreshOutcome(List.of(), 2, 2, Map.of())));

    var answers = new ArrayList<String>();
    for (var c : cases) {
      var after = hourly().applyOutcome(c.outcome(), NOW);
      answers.add(
          c.name()
              + " -> error="
              + after.error()
              + " notice="
              + after.notice()
              + " available="
              + after.contentAvailable()
              + " retries="
              + after.updateRetriedTimes()
              + " deadline="
              + after.nextUpdate());
    }

    assertEquals(
        List.of(
            "nothing failed -> error=null notice=null available=true retries=0 "
                + "deadline=2026-08-23T13:00:00Z",
            "one of two failed -> error=null notice=failed to retrieve some of the content: "
                + "missing 1 RSS feeds available=true retries=1 deadline=2026-08-23T12:01:00Z",
            // Content-available reads false here because these outcomes are put to a
            // widget that has never succeeded. It is not the failure clearing the flag:
            // see theFlagNeverGoesBackOff below.
            "one of one failed -> error=failed to retrieve any content notice=null "
                + "available=false retries=1 deadline=2026-08-23T12:01:00Z",
            "two of two failed -> error=failed to retrieve any content notice=null "
                + "available=false retries=1 deadline=2026-08-23T12:01:00Z"),
        answers);
  }

  /** R6, row 13. A partial refresh replaces the items with what came back. */
  @Test
  void aPartialRefreshReplacesTheItems() {
    var full =
        hourly()
            .applyOutcome(
                new RefreshOutcome(
                    List.of(item("a", "2026-08-02T00:00:00Z"), item("b", "2026-08-01T00:00:00Z")),
                    0,
                    2,
                    Map.of()),
                NOW);
    assertEquals(2, full.items().size());

    var partial =
        full.applyOutcome(
            new RefreshOutcome(List.of(item("a", "2026-08-02T00:00:00Z")), 1, 2, Map.of()), NOW);
    assertEquals(List.of("a"), partial.items().stream().map(FeedItem::link).toList());
  }

  /** R7, row 13. A total failure leaves the items where they were, under an error. */
  @Test
  void aTotalFailureLeavesTheItemsAlone() {
    var full =
        hourly()
            .applyOutcome(
                new RefreshOutcome(
                    List.of(item("a", "2026-08-02T00:00:00Z"), item("b", "2026-08-01T00:00:00Z")),
                    0,
                    2,
                    Map.of()),
                NOW);

    var down = full.applyOutcome(new RefreshOutcome(List.of(), 2, 2, Map.of()), NOW);
    assertEquals(List.of("a", "b"), down.items().stream().map(FeedItem::link).toList());
    assertEquals("failed to retrieve any content", down.error());
    assertTrue(down.contentAvailable());
  }

  /**
   * R5, R7, question-log row 3b. The flag turns on once and never turns off. Found by
   * running the two systems side by side: every unit check here used a fresh widget, and
   * from a standing start a total failure and a latched flag look identical.
   */
  @Test
  void theFlagNeverGoesBackOff() {
    var fresh = hourly();
    assertFalse(fresh.contentAvailable());

    // Never succeeded, so a total failure leaves it off and the widget draws an error.
    var neverUp = fresh.applyOutcome(new RefreshOutcome(List.of(), 1, 1, Map.of()), NOW);
    assertFalse(neverUp.contentAvailable());

    var up =
        neverUp.applyOutcome(
            new RefreshOutcome(List.of(item("a", "2026-08-01T00:00:00Z")), 0, 1, Map.of()), NOW);
    assertTrue(up.contentAvailable());

    // Succeeded once, so every kind of failure after it leaves the flag on.
    var partial =
        up.applyOutcome(
            new RefreshOutcome(List.of(item("a", "2026-08-01T00:00:00Z")), 1, 2, Map.of()), NOW);
    assertTrue(partial.contentAvailable());

    var total = partial.applyOutcome(new RefreshOutcome(List.of(), 2, 2, Map.of()), NOW);
    assertTrue(total.contentAvailable());
    assertEquals("failed to retrieve any content", total.error());

    var stillTotal = total.applyOutcome(new RefreshOutcome(List.of(), 1, 1, Map.of()), NOW);
    assertTrue(stillTotal.contentAvailable());
  }

  /**
   * R5, row 11. Content-available latches on the first success. A widget that has succeeded
   * once and then only partially fails still counts as having content, which is what keeps
   * the notice a notice rather than an error page.
   */
  @Test
  void contentAvailableLatchesOnTheFirstSuccess() {
    var fresh = hourly();
    assertFalse(fresh.contentAvailable());

    var down = fresh.applyOutcome(new RefreshOutcome(List.of(), 1, 1, Map.of()), NOW);
    assertFalse(down.contentAvailable());

    var up =
        down.applyOutcome(
            new RefreshOutcome(List.of(item("a", "2026-08-01T00:00:00Z")), 0, 1, Map.of()), NOW);
    assertTrue(up.contentAvailable());

    var wobbly =
        up.applyOutcome(
            new RefreshOutcome(List.of(item("a", "2026-08-01T00:00:00Z")), 1, 2, Map.of()), NOW);
    assertTrue(wobbly.contentAvailable());
  }

  /** R8, row 6. Consecutive failures grow the wait; one success starts it over. */
  @Test
  void theBackoffGrowsAcrossFailuresAndResetsOnSuccess() {
    var state = hourly();
    var deadlines = new ArrayList<String>();
    for (var i = 0; i < 3; i++) {
      state = state.applyOutcome(new RefreshOutcome(List.of(), 1, 1, Map.of()), NOW);
      deadlines.add(state.nextUpdate().toString());
    }
    assertEquals(
        List.of("2026-08-23T12:01:00Z", "2026-08-23T12:04:00Z", "2026-08-23T12:09:00Z"),
        deadlines);

    state =
        state.applyOutcome(
            new RefreshOutcome(List.of(item("a", "2026-08-01T00:00:00Z")), 0, 1, Map.of()), NOW);
    assertEquals(0, state.updateRetriedTimes());

    state = state.applyOutcome(new RefreshOutcome(List.of(), 1, 1, Map.of()), NOW);
    assertEquals(Instant.parse("2026-08-23T12:01:00Z"), state.nextUpdate());
  }

  /** D-3. An infinite-cache widget never gets a deadline, whichever way its refresh went. */
  @Test
  void anInfiniteWidgetKeepsNoDeadlineEitherWay() {
    var infinite =
        WidgetState.configured("Static", CacheMode.INFINITE, null, List.of(), 25, false);
    assertNull(
        infinite
            .applyOutcome(
                new RefreshOutcome(List.of(item("a", "2026-08-01T00:00:00Z")), 0, 1, Map.of()),
                NOW)
            .nextUpdate());
    assertNull(
        infinite.applyOutcome(new RefreshOutcome(List.of(), 1, 1, Map.of()), NOW).nextUpdate());
  }

  /** R12. The validators a refresh brought back are what the next request will send. */
  @Test
  void theOutcomeCarriesTheFeedValidatorsForward() {
    var cache = Map.of("http://f/1", new FeedCacheEntry("\"v1\"", null, List.of()));
    var after =
        hourly()
            .applyOutcome(
                new RefreshOutcome(List.of(item("a", "2026-08-01T00:00:00Z")), 0, 1, cache), NOW);
    assertEquals("\"v1\"", after.feedCache().get("http://f/1").etag());
  }
}
