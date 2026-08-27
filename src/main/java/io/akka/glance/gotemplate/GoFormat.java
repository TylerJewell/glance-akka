package io.akka.glance.gotemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;

/**
 * How Go's {@code fmt} writes a value, for the verbs glance's templates and code use.
 *
 * <p>Only the shapes that reach a template are covered. The one that is not obvious is a
 * float: Go's {@code %v} is {@code strconv.FormatFloat(f, 'g', -1, 64)}, the shortest
 * decimal that reads back as the same float, which prints {@code 300} where Java's {@code
 * Double.toString} prints {@code 300.0} and {@code 1e+10} where Java prints {@code 1.0E10}.
 */
public final class GoFormat {

  private GoFormat() {}

  /** {@code %v}. */
  public static String value(Object v) {
    if (v == null) {
      return "<nil>";
    }
    if (v instanceof String s) {
      return s;
    }
    if (v instanceof Boolean b) {
      return b ? "true" : "false";
    }
    if (v instanceof Double d) {
      return formatFloat(d);
    }
    if (v instanceof Float f) {
      return formatFloat32(f);
    }
    if (v instanceof Throwable t) {
      return t.getMessage() == null ? t.toString() : t.getMessage();
    }
    if (v instanceof Collection<?> c) {
      var out = new StringBuilder("[");
      boolean first = true;
      for (var item : c) {
        if (!first) {
          out.append(' ');
        }
        first = false;
        out.append(value(item));
      }
      return out.append(']').toString();
    }
    if (v instanceof Map<?, ?> m) {
      var out = new StringBuilder("map[");
      boolean first = true;
      for (var entry : m.entrySet()) {
        if (!first) {
          out.append(' ');
        }
        first = false;
        out.append(value(entry.getKey())).append(':').append(value(entry.getValue()));
      }
      return out.append(']').toString();
    }
    return String.valueOf(v);
  }

  /** {@code strconv.FormatFloat(f, 'g', -1, 64)}. */
  public static String formatFloat(double d) {
    if (Double.isNaN(d)) {
      return "NaN";
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "+Inf" : "-Inf";
    }
    if (d == 0) {
      return (1 / d < 0 ? "-0" : "0");
    }
    String shortest = shortestDigits(d, 17);
    return applyGExponent(shortest, d);
  }

  /** The same, at float32 precision, which is what a Go {@code float32} field prints as. */
  public static String formatFloat32(float f) {
    if (Float.isNaN(f)) {
      return "NaN";
    }
    if (Float.isInfinite(f)) {
      return f > 0 ? "+Inf" : "-Inf";
    }
    if (f == 0) {
      return (1 / f < 0 ? "-0" : "0");
    }
    for (int precision = 1; precision <= 9; precision++) {
      var candidate = new BigDecimal(f, new MathContext(precision, RoundingMode.HALF_EVEN));
      if (candidate.floatValue() == f) {
        return applyGExponent(candidate.round(new MathContext(precision)).toString(), f);
      }
    }
    return applyGExponent(new BigDecimal(f).toString(), f);
  }

  private static String shortestDigits(double d, int maxPrecision) {
    for (int precision = 1; precision <= maxPrecision; precision++) {
      var candidate = new BigDecimal(d, new MathContext(precision, RoundingMode.HALF_EVEN));
      if (candidate.doubleValue() == d) {
        return candidate.toString();
      }
    }
    return new BigDecimal(d).toString();
  }

  /**
   * Go's {@code 'g'} switches to an exponent when the decimal exponent is below -4 or at
   * least the number of significant digits, and writes it as {@code e+07} rather than {@code
   * E7}.
   */
  private static String applyGExponent(String plain, double d) {
    var decimal = new BigDecimal(plain);
    var unscaled = decimal.unscaledValue().abs().toString();
    int digits = unscaled.length();
    // trailing zeros in the unscaled value are not significant for this decision
    int significant = digits;
    while (significant > 1 && unscaled.charAt(significant - 1) == '0') {
      significant--;
    }
    int exponent = digits - decimal.scale() - 1;
    boolean negative = d < 0;
    String mantissaDigits = unscaled.substring(0, significant);
    if (exponent < -4 || exponent >= 21) {
      var out = new StringBuilder();
      if (negative) {
        out.append('-');
      }
      out.append(mantissaDigits.charAt(0));
      if (mantissaDigits.length() > 1) {
        out.append('.').append(mantissaDigits, 1, mantissaDigits.length());
      }
      out.append('e').append(exponent < 0 ? '-' : '+');
      int absolute = Math.abs(exponent);
      if (absolute < 10) {
        out.append('0');
      }
      out.append(absolute);
      return out.toString();
    }
    return decimal.stripTrailingZeros().toPlainString();
  }

  /** {@code fmt.Sprintf} for the verbs glance uses: {@code %v %s %d %t %f %T} and widths. */
  public static String sprintf(String format, Object... args) {
    var out = new StringBuilder();
    int argIndex = 0;
    int i = 0;
    while (i < format.length()) {
      char c = format.charAt(i);
      if (c != '%') {
        out.append(c);
        i++;
        continue;
      }
      i++;
      if (i < format.length() && format.charAt(i) == '%') {
        out.append('%');
        i++;
        continue;
      }
      var flags = new StringBuilder();
      while (i < format.length() && "+-# 0".indexOf(format.charAt(i)) >= 0) {
        flags.append(format.charAt(i));
        i++;
      }
      var width = new StringBuilder();
      while (i < format.length() && Character.isDigit(format.charAt(i))) {
        width.append(format.charAt(i));
        i++;
      }
      var precision = new StringBuilder();
      boolean hasPrecision = false;
      if (i < format.length() && format.charAt(i) == '.') {
        hasPrecision = true;
        i++;
        while (i < format.length() && Character.isDigit(format.charAt(i))) {
          precision.append(format.charAt(i));
          i++;
        }
      }
      if (i >= format.length()) {
        out.append('%').append('!');
        break;
      }
      char verb = format.charAt(i);
      i++;
      Object arg = argIndex < args.length ? args[argIndex++] : null;
      out.append(
          one(
              verb,
              flags.toString(),
              width.isEmpty() ? -1 : Integer.parseInt(width.toString()),
              hasPrecision ? (precision.isEmpty() ? 0 : Integer.parseInt(precision.toString())) : -1,
              arg));
    }
    return out.toString();
  }

  private static String one(char verb, String flags, int width, int precision, Object arg) {
    String body =
        switch (verb) {
          case 'v', 's' -> value(arg);
          case 'd' -> String.valueOf(toLong(arg));
          case 't' -> String.valueOf(Boolean.TRUE.equals(arg));
          case 'f' -> fixed(toDouble(arg), precision < 0 ? 6 : precision);
          case 'T' -> arg == null ? "<nil>" : arg.getClass().getSimpleName();
          case 'q' -> "\"" + value(arg) + "\"";
          case 'x' -> Long.toHexString(toLong(arg));
          default -> "%!" + verb + "(" + value(arg) + ")";
        };
    if (flags.indexOf('+') >= 0 && (verb == 'f' || verb == 'd') && !body.startsWith("-")) {
      body = "+" + body;
    }
    if (width > 0 && body.length() < width) {
      int missing = width - body.length();
      if (flags.indexOf('-') >= 0) {
        body = body + " ".repeat(missing);
      } else if (flags.indexOf('0') >= 0) {
        String sign = body.startsWith("-") || body.startsWith("+") ? body.substring(0, 1) : "";
        body = sign + "0".repeat(missing) + body.substring(sign.length());
      } else {
        body = " ".repeat(missing) + body;
      }
    }
    return body;
  }

  /**
   * {@code %.Nf}.
   *
   * <p>Rounded half to even, on the value the double actually holds rather than on the
   * decimal it was written as. {@code 0.625} is exactly representable and comes out as
   * {@code 0.62}; {@code 2.675} is not, and the value underneath it is already below the
   * halfway point, so it comes out as {@code 2.67} whichever way halves are broken.
   */
  public static String fixed(double d, int precision) {
    if (Double.isNaN(d)) {
      return "NaN";
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "+Inf" : "-Inf";
    }
    return new BigDecimal(d).setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
  }

  /** {@code math.Round} — half away from zero, which Java's own rounding is not. */
  public static long round(double value) {
    return value < 0 ? -Math.round(-value) : Math.round(value);
  }

  /** The same widening the formatter does, for callers outside this package. */
  public static long toLongPublic(Object v) {
    return toLong(v);
  }

  static long toLong(Object v) {
    if (v instanceof Number n) {
      return n.longValue();
    }
    if (v instanceof Boolean b) {
      return b ? 1 : 0;
    }
    return 0;
  }

  static double toDouble(Object v) {
    if (v instanceof Number n) {
      return n.doubleValue();
    }
    return 0;
  }
}
