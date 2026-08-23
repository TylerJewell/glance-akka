package io.akka.glance.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Turning a feed's worth of results into the one list a widget shows. SPEC-001 R9–R12. */
public final class FeedMerge {

  private FeedMerge() {}

  /**
   * @param preserveOrder keeps feed-then-document order instead of sorting newest first
   * @param limit caps the merged list; 0 or less means no cap
   */
  public static RefreshOutcome merge(
      List<FeedSpec> specs, List<FeedResult> results, boolean preserveOrder, int limit) {
    var items = new ArrayList<FeedItem>();
    var seen = new HashSet<String>();
    var cache = new HashMap<String, FeedCacheEntry>();
    var failed = 0;

    for (var i = 0; i < specs.size(); i++) {
      var spec = specs.get(i);
      var result = results.get(i);
      if (result.failed()) {
        failed++;
        continue;
      }

      var fromFeed = result.items();
      if (spec.limit() > 0 && fromFeed.size() > spec.limit()) {
        fromFeed = fromFeed.subList(0, spec.limit());
      }

      var kept = new ArrayList<FeedItem>(fromFeed.size());
      for (var item : fromFeed) {
        var named =
            spec.title() == null || spec.title().isEmpty()
                ? item
                : new FeedItem(
                    item.title(), item.link(), spec.title(), item.channelUrl(), item.publishedAt());
        kept.add(named);
        // Dropped here rather than after the sort: which copy of a repeated link survives is
        // decided by feed order, and sorting first would decide it by recency instead.
        if (seen.add(named.link())) {
          items.add(named);
        }
      }
      // Only as many items as could ever reach the page are kept: the merge truncates
      // to the widget's limit, so a feed's items past that can never be shown, and
      // storing them would grow the widget's state with the feed rather than with the
      // page.
      var cached = limit > 0 && kept.size() > limit ? kept.subList(0, limit) : kept;
      cache.put(
          spec.url(),
          new FeedCacheEntry(result.etag(), result.lastModified(), List.copyOf(cached)));
    }

    if (!preserveOrder) {
      items.sort(Comparator.comparing(FeedItem::publishedAt).reversed());
    }
    if (limit > 0 && items.size() > limit) {
      items = new ArrayList<>(items.subList(0, limit));
    }

    return new RefreshOutcome(List.copyOf(items), failed, specs.size(), Map.copyOf(cache));
  }
}
