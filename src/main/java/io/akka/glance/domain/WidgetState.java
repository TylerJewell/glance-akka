package io.akka.glance.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One widget: what it shows, when it is next due, and how its last refresh went.
 *
 * <p>SPEC-001 §2. {@code error} and {@code notice} are never both set — a refresh sets one and
 * clears the other (R5–R7). {@code contentAvailable} latches: it turns true on the first
 * refresh that loses nothing and no failure of any kind turns it back off, so a widget that
 * has succeeded once shows its last content under a mark for ever after, and only one that
 * has never succeeded shows an error in place of content.
 */
public record WidgetState(
    String title,
    CacheMode cacheMode,
    Duration cacheDuration,
    Instant nextUpdate,
    int updateRetriedTimes,
    List<FeedItem> items,
    String error,
    String notice,
    boolean contentAvailable,
    int limit,
    boolean preserveOrder,
    int collapseAfter,
    Map<String, FeedCacheEntry> feedCache) {

  /** The rss widget's own defaults, from the source's {@code rssWidget.initialize}. */
  public static final Duration DEFAULT_CACHE = Duration.ofHours(2);

  public static final int DEFAULT_LIMIT = 25;
  public static final int DEFAULT_COLLAPSE_AFTER = 5;
  public static final String NO_ITEMS_MESSAGE = "No items were returned from the feeds.";
  public static final String ERROR_NO_CONTENT = "failed to retrieve any content";

  public static WidgetState configured(
      String title,
      CacheMode cacheMode,
      Duration cacheDuration,
      List<FeedItem> items,
      int limit,
      boolean preserveOrder) {
    return new WidgetState(
        title,
        cacheMode,
        cacheDuration,
        null,
        0,
        items,
        null,
        null,
        false,
        limit,
        preserveOrder,
        DEFAULT_COLLAPSE_AFTER,
        Map.of());
  }

  public boolean isDue(Instant now) {
    return CachePolicy.isDue(cacheMode, nextUpdate, now);
  }

  /**
   * Applies a refresh's result. SPEC-001 R5, R6, R7, R8.
   *
   * <p>{@code completedAt} is supplied rather than read, so every deadline this produces is a
   * function of its arguments (SPEC-001 D-5).
   */
  public WidgetState applyOutcome(RefreshOutcome outcome, Instant completedAt) {
    if (outcome.everythingFailed()) {
      var retries = CachePolicy.nextRetryCount(updateRetriedTimes);
      return new WidgetState(
          title,
          cacheMode,
          cacheDuration,
          CachePolicy.retryDeadline(cacheMode, cacheDuration, retries, completedAt),
          retries,
          // Left alone: an error over the last content that arrived is what the source
          // shows, and replacing it would lose the only content there is.
          items,
          ERROR_NO_CONTENT,
          null,
          // Also left alone. A total failure sets the error and says nothing about
          // whether there is content to draw; a widget that has succeeded once still
          // has its items, and drawing an error page over them would throw away the
          // only thing it can show.
          contentAvailable,
          limit,
          preserveOrder,
          collapseAfter,
          mergedCache(outcome));
    }

    if (outcome.somethingFailed()) {
      var retries = CachePolicy.nextRetryCount(updateRetriedTimes);
      return new WidgetState(
          title,
          cacheMode,
          cacheDuration,
          CachePolicy.retryDeadline(cacheMode, cacheDuration, retries, completedAt),
          retries,
          outcome.items(),
          null,
          "failed to retrieve some of the content: missing " + outcome.failed() + " RSS feeds",
          true,
          limit,
          preserveOrder,
          collapseAfter,
          mergedCache(outcome));
    }

    return new WidgetState(
        title,
        cacheMode,
        cacheDuration,
        CachePolicy.ordinaryDeadline(cacheMode, cacheDuration, completedAt),
        CachePolicy.resetRetryCount(),
        outcome.items(),
        null,
        null,
        true,
        limit,
        preserveOrder,
        collapseAfter,
        mergedCache(outcome));
    }

  /**
   * A feed that failed contributes no entry, so its previous validators survive the pass and
   * the next request still asks the conditional question.
   */
  private Map<String, FeedCacheEntry> mergedCache(RefreshOutcome outcome) {
    if (outcome.feedCache().isEmpty()) {
      return feedCache;
    }
    var merged = new java.util.HashMap<>(feedCache);
    merged.putAll(outcome.feedCache());
    return Map.copyOf(merged);
  }
}
