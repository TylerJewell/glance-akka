package io.akka.glance.util;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/** The string and URL helpers the widgets share. */
public final class Text {

  public static final Pattern SEQUENTIAL_WHITESPACE = Pattern.compile("\\s+");
  public static final Pattern WHITESPACE_AT_LINE_START = Pattern.compile("(?m)^\\s+");
  private static final Pattern URL_SCHEME = Pattern.compile("^[a-z]+://");

  private Text() {}

  /** The host of a URL, lower-cased and without a leading {@code www.}. */
  public static String extractDomainFromUrl(String url) {
    if (url == null || url.isEmpty()) {
      return "";
    }
    try {
      var host = URI.create(url).getHost();
      if (host == null) {
        return "";
      }
      return stripPrefix(host.toLowerCase(Locale.ROOT), "www.");
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  public static String stripUrlScheme(String url) {
    return URL_SCHEME.matcher(url).replaceAll("");
  }

  private static String stripPrefix(String value, String prefix) {
    return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
  }

  /** {@code limitStringLength} — counted in code points, the way Go counts runes. */
  public static Limited limitStringLength(String value, int max) {
    int count = value.codePointCount(0, value.length());
    if (count > max) {
      return new Limited(value.substring(0, value.offsetByCodePoints(0, max)), true);
    }
    return new Limited(value, false);
  }

  public record Limited(String value, boolean wasLimited) {}

  public static String titleToSlug(String title) {
    String slug = title.toLowerCase(Locale.ROOT);
    slug = SEQUENTIAL_WHITESPACE.matcher(slug).replaceAll("-");
    return trim(slug, '-');
  }

  private static String trim(String value, char cut) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == cut) {
      start++;
    }
    while (end > start && value.charAt(end - 1) == cut) {
      end--;
    }
    return value.substring(start, end);
  }

  public static String normalizeVersionFormat(String version) {
    String normalized = version.trim().toLowerCase(Locale.ROOT);
    if (!normalized.isEmpty() && normalized.charAt(0) != 'v') {
      return "v" + normalized;
    }
    return normalized;
  }

  public static String prefixStringLines(String prefix, String value) {
    var lines = value.split("\n", -1);
    var out = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        out.append('\n');
      }
      out.append(prefix).append(lines[i]);
    }
    return out.toString();
  }

  /** {@code parseRFC3339Time} — an unreadable instant becomes now, which is what Go does. */
  public static Instant parseRfc3339(String value, Instant now) {
    try {
      return java.time.OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      return now;
    }
  }

  public static boolean stringToBool(String value) {
    return value.equals("true") || value.equals("yes");
  }

  /** {@code percentChange}. */
  public static double percentChange(double current, double previous) {
    if (previous == 0) {
      return current == 0 ? 0 : 100;
    }
    return (current / previous - 1) * 100;
  }

  /** {@code html.UnescapeString} for the entities a feed actually carries. */
  public static String unescapeHtml(String value) {
    if (value == null || value.indexOf('&') < 0) {
      return value;
    }
    var out = new StringBuilder(value.length());
    int i = 0;
    while (i < value.length()) {
      char c = value.charAt(i);
      if (c != '&') {
        out.append(c);
        i++;
        continue;
      }
      int semicolon = value.indexOf(';', i);
      if (semicolon < 0 || semicolon - i > 32) {
        out.append(c);
        i++;
        continue;
      }
      String entity = value.substring(i + 1, semicolon);
      String replacement = Entities.lookup(entity);
      if (replacement == null) {
        out.append(c);
        i++;
        continue;
      }
      out.append(replacement);
      i = semicolon + 1;
    }
    return out.toString();
  }
}
