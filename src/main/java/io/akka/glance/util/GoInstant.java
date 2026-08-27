package io.akka.glance.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * An instant with the names Go's {@code time.Time} answers to.
 *
 * <p>A template reads {@code .App.CreatedAt.Unix}, and a custom-api template may format or
 * compare a time it parsed, so the value behind those has to carry Go's own method names
 * rather than Java's.
 */
public record GoInstant(Instant instant, ZoneId zone) implements Comparable<GoInstant> {

  public static GoInstant of(Instant instant) {
    return new GoInstant(instant, ZoneOffset.UTC);
  }

  public static GoInstant of(Instant instant, ZoneId zone) {
    return new GoInstant(instant, zone);
  }

  public static GoInstant now() {
    return of(Instant.now());
  }

  public long Unix() {
    return instant.getEpochSecond();
  }

  public long UnixMilli() {
    return instant.toEpochMilli();
  }

  public int Year() {
    return ZonedDateTime.ofInstant(instant, zone).getYear();
  }

  public int Day() {
    return ZonedDateTime.ofInstant(instant, zone).getDayOfMonth();
  }

  public int Hour() {
    return ZonedDateTime.ofInstant(instant, zone).getHour();
  }

  public String Format(String layout) {
    return GoLayout.format(layout, instant, zone);
  }

  public boolean IsZero() {
    return GoTime.isZero(instant);
  }

  public boolean Before(GoInstant other) {
    return instant.isBefore(other.instant);
  }

  public boolean After(GoInstant other) {
    return instant.isAfter(other.instant);
  }

  public GoInstant In(ZoneId other) {
    return new GoInstant(instant, other);
  }

  @Override
  public int compareTo(GoInstant other) {
    return instant.compareTo(other.instant);
  }

  @Override
  public String toString() {
    return GoLayout.format("2006-01-02 15:04:05.999999999 -0700 MST", instant, zone);
  }
}
