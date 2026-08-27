package io.akka.glance.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A syndication feed, whichever of the three shapes it arrives in.
 *
 * <p>RSS, Atom and RDF differ in the names of their elements rather than in what they carry,
 * so the reading is one pass over the document with the right names for whichever it is.
 */
public final class Feed {

  public String Title = "";
  public String Link = "";
  public String ImageURL = "";
  public List<Item> Items = new ArrayList<>();

  /** One entry. */
  public static final class Item {
    public String Title = "";
    public String Link = "";
    public String Description = "";
    public String ImageURL = "";
    public List<String> Categories = new ArrayList<>();
    public Instant Published;
  }

  /** Every date format the three shapes are written with, in the order they are tried. */
  private static final List<DateTimeFormatter> DATE_FORMATS =
      List.of(
          DateTimeFormatter.RFC_1123_DATE_TIME,
          DateTimeFormatter.ISO_OFFSET_DATE_TIME,
          DateTimeFormatter.ISO_INSTANT,
          DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm zzz", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH),
          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH));

  private Feed() {}

  public static Feed parse(String source) {
    var root = Xml.parse(source);
    var feed = new Feed();
    if (root.name().equals("feed")) {
      readAtom(root, feed);
    } else {
      // Both RSS and RDF put the feed's own details in a channel element.
      var channel = root.child("channel");
      readRss(channel == null ? root : channel, root, feed);
    }
    return feed;
  }

  private static void readAtom(Xml root, Feed feed) {
    feed.Title = root.text("title");
    for (var link : root.children("link")) {
      String relation = link.attribute("rel");
      if (relation.isEmpty() || relation.equals("alternate")) {
        feed.Link = link.attribute("href");
        break;
      }
    }
    var logo = root.child("logo");
    feed.ImageURL = logo == null ? "" : logo.text();
    for (var entry : root.children("entry")) {
      var item = new Item();
      item.Title = entry.text("title");
      for (var link : entry.children("link")) {
        String relation = link.attribute("rel");
        if (relation.isEmpty() || relation.equals("alternate")) {
          item.Link = link.attribute("href");
          break;
        }
      }
      var summary = entry.child("summary");
      var content = entry.child("content");
      item.Description = summary != null ? summary.text() : content == null ? "" : content.text();
      for (var category : entry.children("category")) {
        String term = category.attribute("term");
        item.Categories.add(term.isEmpty() ? category.text() : term);
      }
      item.ImageURL = thumbnail(entry);
      String published = entry.text("published");
      if (published.isEmpty()) {
        published = entry.text("updated");
      }
      item.Published = parseDate(published);
      feed.Items.add(item);
    }
  }

  private static void readRss(Xml channel, Xml root, Feed feed) {
    feed.Title = channel.text("title");
    feed.Link = channel.text("link");
    var image = channel.child("image");
    if (image != null) {
      String url = image.text("url");
      feed.ImageURL = url.isEmpty() ? image.attribute("href") : url;
    }
    var items = new ArrayList<Xml>(channel.children("item"));
    // RDF puts its items beside the channel rather than inside it.
    if (items.isEmpty()) {
      items.addAll(root.children("item"));
    }
    for (var node : items) {
      var item = new Item();
      item.Title = node.text("title");
      item.Link = node.text("link");
      item.Description = node.text("description");
      if (item.Description.isEmpty()) {
        item.Description = node.text("encoded");
      }
      for (var category : node.children("category")) {
        item.Categories.add(category.text());
      }
      item.ImageURL = thumbnail(node);
      String published = node.text("pubDate");
      if (published.isEmpty()) {
        published = node.text("date");
      }
      item.Published = parseDate(published);
      feed.Items.add(item);
    }
  }

  /**
   * The picture an entry carries, wherever it put it: a media thumbnail or image at any depth,
   * or an enclosure of an image kind.
   */
  private static String thumbnail(Xml node) {
    var found = recursiveThumbnail(node);
    if (!found.isEmpty()) {
      return found;
    }
    for (var enclosure : node.children("enclosure")) {
      if (enclosure.attribute("type").startsWith("image/")) {
        return enclosure.attribute("url");
      }
    }
    return "";
  }

  private static String recursiveThumbnail(Xml node) {
    for (var child : node.children()) {
      if (child.name().equals("thumbnail") || child.name().equals("image")) {
        String url = child.attribute("url");
        if (!url.isEmpty()) {
          return url;
        }
      }
      String nested = recursiveThumbnail(child);
      if (!nested.isEmpty()) {
        return nested;
      }
    }
    return "";
  }

  /** An instant, or nothing when none of the shapes a feed uses reads. */
  public static Instant parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    for (var format : DATE_FORMATS) {
      try {
        return ZonedDateTime.parse(trimmed, format).toInstant();
      } catch (RuntimeException ignored) {
        // the next shape
      }
      try {
        return OffsetDateTime.parse(trimmed, format).toInstant();
      } catch (RuntimeException ignored) {
        // the next shape
      }
      try {
        return java.time.LocalDateTime.parse(trimmed, format).toInstant(ZoneOffset.UTC);
      } catch (RuntimeException ignored) {
        // the next shape
      }
    }
    return null;
  }
}
