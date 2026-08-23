package io.akka.glance.domain;

import java.util.List;
import java.util.Map;

/**
 * What one pass over every feed produced.
 *
 * <p>{@code failed} is carried against {@code attempted} rather than as a verdict, because the
 * line between a partial refresh and a total one is drawn on the two being equal (SPEC-001 R9)
 * and the widget is the thing entitled to draw it.
 */
public record RefreshOutcome(
    List<FeedItem> items, int failed, int attempted, Map<String, FeedCacheEntry> feedCache) {

  public boolean everythingFailed() {
    return attempted > 0 && failed == attempted;
  }

  public boolean somethingFailed() {
    return failed > 0;
  }
}
