package io.akka.glance.domain;

import java.time.Instant;

/** One entry in a feed, as it is shown on the page. */
public record FeedItem(
    String title, String link, String channelName, String channelUrl, Instant publishedAt) {}
