package io.akka.glance.domain;

import java.util.List;

/**
 * What a feed's last successful response left behind: the validators to send back, and the
 * items to answer with if the server says nothing has changed. SPEC-001 R12.
 */
public record FeedCacheEntry(String etag, String lastModified, List<FeedItem> items) {}
