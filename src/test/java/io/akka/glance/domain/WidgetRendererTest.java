package io.akka.glance.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R15.
 *
 * <p>The three fixtures under {@code src/test/resources/original/} were cut out of the
 * running original's own response, not written by hand — the widget markup glance served
 * for a page with one healthy, one partial and one failed rss widget. Asserting against a
 * hand-written expectation would automate the assertion rather than the verification, which
 * is the mistake `docs/question-log.md` records for baselines generally.
 */
class WidgetRendererTest {

  private static final Instant PUBLISHED_BASE = Instant.parse("2024-03-04T09:00:00Z");

  private static String fixture(String name) {
    try (var in = WidgetRendererTest.class.getResourceAsStream("/original/" + name + ".html")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static FeedItem alpha(String title, String link, String published) {
    return new FeedItem(
        title, link, "Alpha Journal", "http://feeds.test/", Instant.parse(published));
  }

  private static FeedItem beta(String title, String link, String published) {
    return new FeedItem(title, link, "Beta Weekly", "http://feeds.test/", Instant.parse(published));
  }

  private static WidgetState with(
      String title, List<FeedItem> items, String error, String notice, boolean available) {
    return new WidgetState(
        title,
        CacheMode.DURATION,
        Duration.ofHours(1),
        null,
        0,
        items,
        error,
        notice,
        available,
        25,
        false,
        5,
        Map.of());
  }

  /** R15, all three states, against the original's own markup. */
  @Test
  void rendersEachStateLikeTheOriginal() {
    var healthy =
        with(
            "Healthy",
            List.of(
                alpha("Alpha one", "http://feeds.test/alpha/1", "2024-03-04T09:00:00Z"),
                beta("Beta one", "http://feeds.test/beta/1", "2024-03-03T09:00:00Z"),
                alpha("Alpha two", "http://feeds.test/alpha/2", "2024-03-02T09:00:00Z"),
                beta("Beta two", "http://feeds.test/beta/2", "2024-03-01T09:00:00Z"),
                alpha("Alpha three", "http://feeds.test/alpha/3", "2024-02-29T09:00:00Z")),
            null,
            null,
            true);
    assertEquals(fixture("healthy"), WidgetRenderer.render(healthy));

    var partial =
        with(
            "Partial",
            List.of(
                alpha("Alpha one", "http://feeds.test/alpha/1", "2024-03-04T09:00:00Z"),
                alpha("Alpha two", "http://feeds.test/alpha/2", "2024-03-02T09:00:00Z"),
                alpha("Alpha three", "http://feeds.test/alpha/3", "2024-02-29T09:00:00Z")),
            null,
            "failed to retrieve some of the content: missing 1 RSS feeds",
            true);
    assertEquals(fixture("partial"), WidgetRenderer.render(partial));

    var down = with("Down", List.of(), "failed to retrieve any content", null, false);
    assertEquals(fixture("down"), WidgetRenderer.render(down));
  }

  /**
   * R15. Content available with an error is the third header state — a major mark rather
   * than the minor one — and the original's page never produced it in the fixture run,
   * because an rss widget clears the error whenever it has content. The markup it would
   * emit is the same template branch, so it is pinned here rather than left unchecked.
   */
  @Test
  void contentWithAnErrorGetsTheMajorMark() {
    var state =
        with(
            "Wobbly",
            List.of(alpha("Alpha one", "http://feeds.test/alpha/1", "2024-03-04T09:00:00Z")),
            "something went wrong",
            null,
            true);
    var html = WidgetRenderer.render(state);
    assertEquals(
        true,
        html.contains(
            "<div class=\"notice-icon notice-icon-major\" title=\"something went wrong\"></div>"));
    assertEquals(false, html.contains("notice-icon-minor"));
  }

  /** R15. Text reaching the markup is escaped, whichever field it arrived in. */
  @Test
  void titlesAndMessagesAreEscaped() {
    var state =
        with(
            "A & B",
            List.of(
                new FeedItem(
                    "<script>x</script>",
                    "http://feeds.test/a?x=1&y=2",
                    "Ch \"quoted\"",
                    "http://feeds.test/",
                    PUBLISHED_BASE)),
            null,
            null,
            true);
    var html = WidgetRenderer.render(state);
    assertEquals(true, html.contains("A &amp; B"));
    assertEquals(true, html.contains("&lt;script&gt;x&lt;/script&gt;"));
    assertEquals(true, html.contains("http://feeds.test/a?x=1&amp;y=2"));
    assertEquals(false, html.contains("<script>x</script>"));
  }

  /** R15. A widget with content available and nothing in it says so, as the original does. */
  @Test
  void anEmptyButAvailableWidgetShowsTheNoItemsMessage() {
    var html = WidgetRenderer.render(with("Empty", List.of(), null, null, true));
    assertEquals(true, html.contains("No items were returned from the feeds."));
  }

  /**
   * R14, R15. A container draws its children as tabs, its own header hidden and theirs with
   * it. The fixture is the running original's markup for a group holding one healthy rss
   * widget and one that lost its only feed.
   */
  @Test
  void rendersAContainerLikeTheOriginal() {
    var healthy =
        with(
            "Inner healthy",
            List.of(
                alpha("Alpha one", "http://feeds.test/alpha/1", "2024-03-04T09:00:00Z"),
                alpha("Alpha two", "http://feeds.test/alpha/2", "2024-03-02T09:00:00Z"),
                alpha("Alpha three", "http://feeds.test/alpha/3", "2024-02-29T09:00:00Z")),
            null,
            null,
            true);
    var down = with("Inner down", List.of(), "failed to retrieve any content", null, false);

    var rendered =
        WidgetRenderer.renderGroup(
            List.of(
                new WidgetRenderer.GroupChild("8", healthy),
                new WidgetRenderer.GroupChild("9", down)));

    assertEquals(fixture("group"), rendered);
  }
}
