package io.akka.glance.widget;

/**
 * What a fetch produced and what went wrong with it, together.
 *
 * <p>Both halves at once, because partial content is exactly the case where there is
 * something to show and something to report.
 */
public record Fetched<T>(T value, Err error) {

  public static <T> Fetched<T> of(T value) {
    return new Fetched<>(value, null);
  }

  public static <T> Fetched<T> of(T value, Err error) {
    return new Fetched<>(value, error);
  }

  public static <T> Fetched<T> failed(Err error) {
    return new Fetched<>(null, error);
  }
}
