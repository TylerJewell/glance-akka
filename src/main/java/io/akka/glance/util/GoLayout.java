package io.akka.glance.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;

/**
 * Go's reference-time layouts, as patterns Java can use.
 *
 * <p>Go writes a layout by spelling out {@code Mon Jan 2 15:04:05 MST 2006} in whatever shape
 * the value should take. Each of those pieces is replaced here by the Java pattern letters
 * that mean the same thing, longest piece first so that {@code 2006} is not read as {@code 2}
 * followed by {@code 006}.
 */
public final class GoLayout {

  /** One piece of the reference time, and what it becomes. */
  private record Piece(String go, String java) {}

  private static final List<Piece> PIECES =
      List.of(
          new Piece("2006", "uuuu"),
          new Piece("01", "MM"),
          new Piece("02", "dd"),
          new Piece("15", "HH"),
          new Piece("04", "mm"),
          new Piece("05", "ss"),
          new Piece("Monday", "EEEE"),
          new Piece("Mon", "EEE"),
          new Piece("January", "MMMM"),
          new Piece("Jan", "MMM"),
          new Piece(".000000000", ".SSSSSSSSS"),
          new Piece(".000000", ".SSSSSS"),
          new Piece(".000", ".SSS"),
          new Piece("Z07:00", "XXX"),
          new Piece("Z0700", "XX"),
          new Piece("-07:00", "xxx"),
          new Piece("-0700", "xx"),
          new Piece("-07", "x"),
          new Piece("MST", "zzz"),
          new Piece("PM", "a"),
          new Piece("pm", "a"),
          new Piece("03", "hh"),
          new Piece("06", "uu"),
          new Piece("_2", "ppd"),
          new Piece("2", "d"),
          new Piece("1", "M"),
          new Piece("3", "h"),
          new Piece("4", "m"),
          new Piece("5", "s"));

  private GoLayout() {}

  /** The named layouts Go's {@code time} package ships, which the widget accepts by name. */
  public static String named(String layout) {
    return switch (layout.toLowerCase(java.util.Locale.ROOT)) {
      case "rfc3339" -> "2006-01-02T15:04:05Z07:00";
      case "rfc3339nano" -> "2006-01-02T15:04:05.999999999Z07:00";
      case "datetime" -> "2006-01-02 15:04:05";
      case "dateonly" -> "2006-01-02";
      case "timeonly" -> "15:04:05";
      default -> layout;
    };
  }

  /** Translates one Go layout into a Java pattern. */
  public static String toJavaPattern(String layout) {
    var out = new StringBuilder();
    int i = 0;
    outer:
    while (i < layout.length()) {
      // ".999999999" is Go's optional fraction; Java has no direct spelling, so it is
      // dropped and the parser is given an optional fraction instead.
      if (layout.startsWith(".999999999", i)) {
        i += 10;
        continue;
      }
      if (layout.startsWith(".999999", i)) {
        i += 7;
        continue;
      }
      if (layout.startsWith(".999", i)) {
        i += 4;
        continue;
      }
      for (var piece : PIECES) {
        if (layout.startsWith(piece.go(), i)) {
          out.append(piece.java());
          i += piece.go().length();
          continue outer;
        }
      }
      char c = layout.charAt(i);
      if (Character.isLetter(c)) {
        out.append('\'').append(c).append('\'');
      } else {
        out.append(c);
      }
      i++;
    }
    return out.toString();
  }

  private static DateTimeFormatter formatter(String layout) {
    boolean optionalFraction =
        layout.contains(".999999999") || layout.contains(".999999") || layout.contains(".999");
    var builder = new DateTimeFormatterBuilder().appendPattern(toJavaPattern(layout));
    if (optionalFraction) {
      builder.appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true);
    }
    return builder.toFormatter(java.util.Locale.ENGLISH);
  }

  /** {@code time.ParseInLocation}, with the unreadable answer Go gives on a failure. */
  public static Instant parse(String layout, String value, ZoneId zone) {
    String resolved = named(layout);
    if (layout.equalsIgnoreCase("unix")) {
      try {
        return Instant.ofEpochSecond(Long.parseLong(value.trim()));
      } catch (NumberFormatException e) {
        return Instant.EPOCH;
      }
    }
    try {
      var pattern = formatter(resolved);
      if (resolved.contains("Z07") || resolved.contains("-07")) {
        return ZonedDateTime.parse(value, pattern).toInstant();
      }
      return LocalDateTime.parse(value, pattern).atZone(zone).toInstant();
    } catch (RuntimeException e) {
      return Instant.EPOCH;
    }
  }

  /** {@code time.Time.Format}. */
  public static String format(String layout, Instant instant, ZoneId zone) {
    if (layout.equalsIgnoreCase("unix")) {
      return String.valueOf(instant.getEpochSecond());
    }
    String resolved = named(layout);
    return formatter(resolved).format(ZonedDateTime.ofInstant(instant, zone));
  }
}
