package io.akka.glance.util;

import io.akka.glance.gotemplate.GoFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The number formats the templates ask for.
 *
 * <p>{@code formatNumber} and {@code formatPrice} go through Go's {@code
 * golang.org/x/text/message} printer for English, which groups the integer part in threes
 * with a comma. Nothing else about that package's behaviour reaches a template, so the
 * grouping is all that is reproduced here.
 */
public final class Numbers {

  private Numbers() {}

  /** {@code formatApproxNumber}. */
  public static String approx(long count) {
    if (count < 1_000) {
      return String.valueOf(count);
    }
    if (count < 10_000) {
      return GoFormat.fixed(count / 1_000.0, 1) + "k";
    }
    if (count < 1_000_000) {
      return (count / 1_000) + "k";
    }
    return GoFormat.fixed(count / 1_000_000.0, 1) + "m";
  }

  /** {@code formatNumber} — {@code message.Printer.Sprint} for English. */
  public static String grouped(Object value) {
    if (value instanceof Double || value instanceof Float) {
      return group(GoFormat.value(value));
    }
    return group(String.valueOf(GoFormat.toLongPublic(value)));
  }

  /** {@code formatPriceWithPrecision} — grouped, with a fixed number of decimals. */
  public static String price(int precision, double value) {
    return group(new BigDecimal(value).setScale(precision, RoundingMode.HALF_UP).toPlainString());
  }

  /** Puts a comma between each group of three digits before the decimal point. */
  public static String group(String number) {
    boolean negative = number.startsWith("-");
    if (negative) {
      number = number.substring(1);
    }
    int dot = number.indexOf('.');
    String whole = dot < 0 ? number : number.substring(0, dot);
    String rest = dot < 0 ? "" : number.substring(dot);
    var out = new StringBuilder();
    int digitsSeen = 0;
    for (int i = whole.length() - 1; i >= 0; i--) {
      out.append(whole.charAt(i));
      digitsSeen++;
      if (digitsSeen % 3 == 0 && i > 0) {
        out.append(',');
      }
    }
    return (negative ? "-" : "") + out.reverse() + rest;
  }

  /** {@code formatServerMegabytes} — the value, and the unit as its own element. */
  public static String serverMegabytes(long megabytes) {
    String value;
    String label;
    if (megabytes < 1_000) {
      value = String.valueOf(megabytes);
      label = "MB";
    } else if (megabytes < 1_000_000) {
      if (megabytes < 10_000) {
        value = GoFormat.fixed(megabytes / 1_000.0, 1);
      } else {
        value = String.valueOf(megabytes / 1_000);
      }
      label = "GB";
    } else {
      value = GoFormat.fixed(megabytes / 1_000_000.0, 1);
      label = "TB";
    }
    return value + " <span class=\"color-base size-h5\">" + label + "</span>";
  }
}
