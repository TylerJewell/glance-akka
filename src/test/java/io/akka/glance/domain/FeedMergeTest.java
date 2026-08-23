package io.akka.glance.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R10, R11, R9. */
class FeedMergeTest {

  private static FeedItem item(String link, String published) {
    return new FeedItem("title-" + link, link, "chan", "http://chan", Instant.parse(published));
  }

  private static FeedResult ok(List<FeedItem> items) {
    return FeedResult.fetched(items, null, null);
  }

  private static List<String> links(RefreshOutcome outcome) {
    return outcome.items().stream().map(FeedItem::link).toList();
  }

  /** R10. Newest first once the duplicates are gone. */
  @Test
  void itemsAcrossFeedsAreOrderedNewestFirst() {
    var outcome =
        FeedMerge.merge(
            List.of(new FeedSpec("http://a", null, 0), new FeedSpec("http://b", null, 0)),
            List.of(
                ok(List.of(item("a1", "2026-03-04T09:00:00Z"), item("a2", "2026-03-02T09:00:00Z"))),
                ok(List.of(item("b1", "2026-03-03T09:00:00Z"), item("b2", "2026-03-01T09:00:00Z")))),
            false,
            25);
    assertEquals(List.of("a1", "b1", "a2", "b2"), links(outcome));
  }

  /**
   * R10. De-duplication happens before the sort, so the copy that survives a repeated link is
   * the one from the earlier feed — not the newer one. Row 14: the two are different answers,
   * and this fixture puts the newer copy in the later feed so they cannot both be right.
   */
  @Test
  void aRepeatedLinkKeepsTheCopyFromTheEarlierFeed() {
    var outcome =
        FeedMerge.merge(
            List.of(new FeedSpec("http://a", null, 0), new FeedSpec("http://b", null, 0)),
            List.of(
                ok(List.of(item("shared", "2026-03-01T09:00:00Z"))),
                ok(List.of(item("shared", "2026-03-09T09:00:00Z"), item("b2", "2026-03-02T09:00:00Z")))),
            false,
            25);
    assertEquals(List.of("b2", "shared"), links(outcome));
    assertEquals(
        Instant.parse("2026-03-01T09:00:00Z"),
        outcome.items().stream().filter(i -> i.link().equals("shared")).findFirst().get()
            .publishedAt());
  }

  /** R10. Preserving order skips the sort but not the de-duplication. */
  @Test
  void preservingOrderKeepsFeedOrderAndStillDeduplicates() {
    var outcome =
        FeedMerge.merge(
            List.of(new FeedSpec("http://a", null, 0), new FeedSpec("http://b", null, 0)),
            List.of(
                ok(List.of(item("a1", "2026-03-01T09:00:00Z"))),
                ok(List.of(item("a1", "2026-03-09T09:00:00Z"), item("b1", "2026-03-08T09:00:00Z")))),
            true,
            25);
    assertEquals(List.of("a1", "b1"), links(outcome));
  }

  /** R11. A per-feed limit cuts that feed before the merge; the widget's cuts after it. */
  @Test
  void perFeedLimitAppliesBeforeTheMergeAndTheWidgetLimitAfter() {
    var perFeed =
        FeedMerge.merge(
            List.of(new FeedSpec("http://a", null, 1), new FeedSpec("http://b", null, 0)),
            List.of(
                ok(List.of(item("a1", "2026-03-04T09:00:00Z"), item("a2", "2026-03-03T09:00:00Z"))),
                ok(List.of(item("b1", "2026-03-02T09:00:00Z")))),
            false,
            25);
    assertEquals(List.of("a1", "b1"), links(perFeed));

    var widgetLimit =
        FeedMerge.merge(
            List.of(new FeedSpec("http://a", null, 0)),
            List.of(
                ok(List.of(item("a1", "2026-03-04T09:00:00Z"), item("a2", "2026-03-03T09:00:00Z")))),
            false,
            1);
    assertEquals(List.of("a1"), links(widgetLimit));
  }

  /** R9. One failing out of one is total; one out of two is partial. */
  @Test
  void theFailedCountIsCarriedAgainstTheNumberAttempted() {
    var oneOfOne =
        FeedMerge.merge(
            List.of(new FeedSpec("http://a", null, 0)), List.of(FeedResult.ofFailure()), false, 25);
    assertEquals(1, oneOfOne.failed());
    assertEquals(1, oneOfOne.attempted());

    var oneOfTwo =
        FeedMerge.merge(
            List.of(new FeedSpec("http://a", null, 0), new FeedSpec("http://b", null, 0)),
            List.of(ok(List.of(item("a1", "2026-03-04T09:00:00Z"))), FeedResult.ofFailure()),
            false,
            25);
    assertEquals(1, oneOfTwo.failed());
    assertEquals(2, oneOfTwo.attempted());
  }

  /** R12. Only a feed that answered contributes validators to carry forward. */
  @Test
  void onlySuccessfulFeedsContributeValidators() {
    var outcome =
        FeedMerge.merge(
            List.of(new FeedSpec("http://a", null, 0), new FeedSpec("http://b", null, 0)),
            List.of(
                FeedResult.fetched(List.of(item("a1", "2026-03-04T09:00:00Z")), "\"v1\"", "then"),
                FeedResult.ofFailure()),
            false,
            25);
    assertEquals(Map.of("http://a", new FeedCacheEntry("\"v1\"", "then",
        List.of(item("a1", "2026-03-04T09:00:00Z")))), outcome.feedCache());
  }

  /** R10. A feed's own title overrides the channel name the feed document carries. */
  @Test
  void aConfiguredTitleOverridesTheChannelName() {
    var outcome =
        FeedMerge.merge(
            List.of(new FeedSpec("http://a", "My Title", 0)),
            List.of(ok(List.of(item("a1", "2026-03-04T09:00:00Z")))),
            false,
            25);
    assertEquals("My Title", outcome.items().get(0).channelName());
  }

  /**
   * G2, Q3 from the review pass. The stored copy of a feed is capped at the widget's own
   * limit, because the merge truncates there and a feed's items past it can never be
   * shown — otherwise a widget's state grows with the feed rather than with the page.
   */
  @Test
  void theStoredFeedIsCappedAtTheWidgetLimit() {
    var many =
        java.util.stream.IntStream.range(0, 40)
            .mapToObj(i -> item("i" + i, "2026-03-01T09:00:00Z"))
            .toList();
    var outcome =
        FeedMerge.merge(
            List.of(new FeedSpec("http://a", null, 0)),
            List.of(FeedResult.fetched(many, "\"v\"", null)),
            false,
            25);
    assertEquals(25, outcome.items().size());
    assertEquals(25, outcome.feedCache().get("http://a").items().size());
  }
}
