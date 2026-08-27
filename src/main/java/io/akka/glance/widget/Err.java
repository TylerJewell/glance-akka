package io.akka.glance.widget;

/**
 * A widget's error or notice.
 *
 * <p>Two of them are shared and stand for a kind rather than an occasion: nothing at all came
 * back, or some of it did. A wrapped error keeps hold of which kind it is, because whether a
 * widget keeps the content it already has turns on that and not on the message.
 */
public final class Err {

  public static final Err NO_CONTENT = new Err("failed to retrieve any content", null);
  public static final Err PARTIAL_CONTENT =
      new Err("failed to retrieve some of the content", null);

  private final String message;
  private final Err wrapped;

  private Err(String message, Err wrapped) {
    this.message = message;
    this.wrapped = wrapped;
  }

  public static Err of(String message) {
    return new Err(message, null);
  }

  public static Err of(Throwable cause) {
    return new Err(
        cause.getMessage() == null ? cause.toString() : cause.getMessage(), null);
  }

  /** {@code fmt.Errorf("%w: ...", base)} — the kind kept, the message extended. */
  public Err because(String detail) {
    return new Err(message + ": " + detail, this);
  }

  /** The same, where the original's own format string joins the two without a colon. */
  public Err followedBy(String detail) {
    return new Err(message + " " + detail, this);
  }

  /** {@code errors.Is}. */
  public boolean is(Err target) {
    for (var current = this; current != null; current = current.wrapped) {
      if (current == target) {
        return true;
      }
    }
    return false;
  }

  public String message() {
    return message;
  }

  @Override
  public String toString() {
    return message;
  }
}
