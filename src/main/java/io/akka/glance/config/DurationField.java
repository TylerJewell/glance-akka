package io.akka.glance.config;

import java.time.Duration;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.nodes.Node;

/**
 * A span written as a whole number and one of {@code s}, {@code m}, {@code h} or {@code d}.
 *
 * <p>Not Go's own duration syntax: {@code 90s} is accepted and {@code 1m30s} is not.
 */
public final class DurationField implements Yaml.Decodable {

  private static final Pattern PATTERN = Pattern.compile("^(\\d+)([smhd])$");

  private long nanos;

  public DurationField() {}

  public DurationField(Duration duration) {
    this.nanos = duration.toNanos();
  }

  public static DurationField of(Duration duration) {
    return new DurationField(duration);
  }

  public long nanos() {
    return nanos;
  }

  public Duration duration() {
    return Duration.ofNanos(nanos);
  }

  public boolean isZero() {
    return nanos == 0;
  }

  @Override
  public void decode(Node node) {
    String value = Yaml.scalar(node);
    var matcher = PATTERN.matcher(value);
    if (!matcher.matches()) {
      throw new ConfigException("invalid duration format: " + value);
    }
    long amount = Long.parseLong(matcher.group(1));
    nanos =
        switch (matcher.group(2)) {
          case "s" -> Duration.ofSeconds(amount).toNanos();
          case "m" -> Duration.ofMinutes(amount).toNanos();
          case "h" -> Duration.ofHours(amount).toNanos();
          default -> Duration.ofDays(amount).toNanos();
        };
  }
}
