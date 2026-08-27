package io.akka.glance.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Reading a feed, in each of the three shapes one arrives in. */
class FeedTest {

  private static final String RSS =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
      <title>A Channel</title>
      <link>https://channel.test</link>
      <image><url>/logo.png</url></image>
      <item>
        <title>First &amp; foremost</title>
        <link>https://channel.test/1</link>
        <description>&lt;p&gt;Some &lt;b&gt;markup&lt;/b&gt;&lt;/p&gt;</description>
        <category>news</category>
        <category>things</category>
        <media:thumbnail url="https://channel.test/1.png"/>
        <pubDate>Mon, 04 Mar 2024 09:00:00 +0000</pubDate>
      </item>
      <item>
        <title>Second</title>
        <link>/relative/two</link>
        <pubDate>2024-03-02T09:00:00Z</pubDate>
      </item>
      </channel></rss>
      """;

  private static final String ATOM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <feed xmlns="http://www.w3.org/2005/Atom">
      <title>An Atom Feed</title>
      <link rel="alternate" href="https://atom.test"/>
      <entry>
        <title>Atom entry</title>
        <link rel="alternate" href="https://atom.test/1"/>
        <summary>A summary</summary>
        <category term="tagged"/>
        <published>2024-03-04T09:00:00+00:00</published>
      </entry>
      </feed>
      """;

  private static final String RDF =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
      <channel><title>An RDF Feed</title><link>https://rdf.test</link></channel>
      <item>
        <title>RDF entry</title>
        <link>https://rdf.test/1</link>
        <dc:date>2024-03-04T09:00:00Z</dc:date>
      </item>
      </rdf:RDF>
      """;

  @Test
  void anRssFeedIsRead() {
    var feed = Feed.parse(RSS);
    assertEquals("A Channel", feed.Title);
    assertEquals("https://channel.test", feed.Link);
    assertEquals("/logo.png", feed.ImageURL);
    assertEquals(2, feed.Items.size());
    var first = feed.Items.getFirst();
    assertEquals("First & foremost", Text.unescapeHtml(first.Title));
    assertEquals("https://channel.test/1.png", first.ImageURL);
    assertEquals(java.util.List.of("news", "things"), first.Categories);
    assertEquals(Instant.parse("2024-03-04T09:00:00Z"), first.Published);
  }

  @Test
  void anAtomFeedIsRead() {
    var feed = Feed.parse(ATOM);
    assertEquals("An Atom Feed", feed.Title);
    assertEquals("https://atom.test", feed.Link);
    assertEquals("Atom entry", feed.Items.getFirst().Title);
    assertEquals("A summary", feed.Items.getFirst().Description);
    assertEquals(java.util.List.of("tagged"), feed.Items.getFirst().Categories);
  }

  @Test
  void anRdfFeedIsRead() {
    var feed = Feed.parse(RDF);
    assertEquals("An RDF Feed", feed.Title);
    // The items sit beside the channel rather than inside it.
    assertEquals(1, feed.Items.size());
    assertEquals("RDF entry", feed.Items.getFirst().Title);
    assertEquals(Instant.parse("2024-03-04T09:00:00Z"), feed.Items.getFirst().Published);
  }

  @Test
  void anUnreadableDateIsNoDateAtAll() {
    assertNull(Feed.parseDate("not a date"));
    assertNull(Feed.parseDate(""));
  }

  @Test
  void severalWrittenFormsOfADateAreAccepted() {
    var expected = Instant.parse("2024-03-04T09:00:00Z");
    assertEquals(expected, Feed.parseDate("Mon, 04 Mar 2024 09:00:00 +0000"));
    assertEquals(expected, Feed.parseDate("Mon, 04 Mar 2024 09:00:00 GMT"));
    assertEquals(expected, Feed.parseDate("2024-03-04T09:00:00Z"));
    assertEquals(expected, Feed.parseDate("2024-03-04T09:00:00.000Z"));
  }

  @Test
  void entitiesInTextAreUnescaped() {
    assertEquals("a & b", Text.unescapeHtml("a &amp; b"));
    assertEquals("a—b", Text.unescapeHtml("a&mdash;b"));
    assertEquals("aéb", Text.unescapeHtml("a&#233;b"));
    assertEquals("aéb", Text.unescapeHtml("a&#xe9;b"));
    assertEquals("a&notanentity;b", Text.unescapeHtml("a&notanentity;b"));
  }

  @Test
  void aDomainIsTakenFromAUrlWithoutItsPrefix() {
    assertEquals("example.com", Text.extractDomainFromUrl("https://WWW.Example.com/a/b?c=d"));
    assertEquals("", Text.extractDomainFromUrl(""));
    assertEquals("", Text.extractDomainFromUrl("not a url"));
  }

  @Test
  void aTitleBecomesASlug() {
    assertEquals("the-home-page", Text.titleToSlug("  The Home   Page  "));
    assertEquals("home", Text.titleToSlug("Home"));
  }

  @Test
  void aVersionIsNormalisedToCarryItsV() {
    assertEquals("v1.2.3", Text.normalizeVersionFormat(" 1.2.3 "));
    assertEquals("v1.2.3", Text.normalizeVersionFormat("V1.2.3"));
  }

  @Test
  void aStringIsLimitedByCodePointsAndSaysWhetherItWasCut() {
    var limited = Text.limitStringLength("abcdef", 3);
    assertEquals("abc", limited.value());
    assertTrue(limited.wasLimited());
    assertEquals("abc", Text.limitStringLength("abc", 3).value());
    assertTrue(!Text.limitStringLength("abc", 3).wasLimited());
  }
}
