package io.akka.glance.domain;

import java.util.List;

/** What one feed's fetch produced. A failed feed carries nothing but its failure. */
public record FeedResult(boolean failed, List<FeedItem> items, String etag, String lastModified) {

  public static FeedResult fetched(List<FeedItem> items, String etag, String lastModified) {
    return new FeedResult(false, items, etag, lastModified);
  }

  public static FeedResult ofFailure() {
    return new FeedResult(true, List.of(), null, null);
  }
}
