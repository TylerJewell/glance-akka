package io.akka.glance.domain;

/**
 * One source inside a widget.
 *
 * @param title overrides the channel name the feed document carries, when set
 * @param limit caps this feed's own contribution before the merge; 0 means no cap
 */
public record FeedSpec(String url, String title, int limit) {}
