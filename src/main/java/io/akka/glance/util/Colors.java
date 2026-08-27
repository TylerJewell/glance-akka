package io.akka.glance.util;

/** Colour arithmetic: the one conversion the original does, from HSL to a hex triplet. */
public final class Colors {

  private Colors() {}

  public static String hslToHex(double h, double s, double l) {
    s /= 100.0;
    l /= 100.0;
    double r;
    double g;
    double b;
    if (s == 0) {
      r = l;
      g = l;
      b = l;
    } else {
      double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
      double p = 2 * l - q;
      double hue = h / 360.0;
      r = hueToRgb(p, q, hue + 1.0 / 3.0);
      g = hueToRgb(p, q, hue);
      b = hueToRgb(p, q, hue - 1.0 / 3.0);
    }
    return String.format("#%02x%02x%02x", channel(r), channel(g), channel(b));
  }

  private static int channel(double value) {
    long rounded = Math.round(value * 255.0);
    return (int) Math.max(0, Math.min(255, rounded));
  }

  private static double hueToRgb(double p, double q, double t) {
    if (t < 0) {
      t += 1;
    }
    if (t > 1) {
      t -= 1;
    }
    if (t < 1.0 / 6.0) {
      return p + (q - p) * 6.0 * t;
    }
    if (t < 1.0 / 2.0) {
      return q;
    }
    if (t < 2.0 / 3.0) {
      return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    }
    return p;
  }
}
