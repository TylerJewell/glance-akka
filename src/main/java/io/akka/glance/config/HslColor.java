package io.akka.glance.config;

import io.akka.glance.gotemplate.GoFormat;
import io.akka.glance.util.Colors;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.nodes.Node;

/** A colour written as hue, saturation and lightness, with or without the {@code hsl()}. */
public final class HslColor implements Yaml.Decodable {

  private static final Pattern PATTERN =
      Pattern.compile("^(?:hsla?\\()?([\\d.]+)(?: |,)+([\\d.]+)%?(?: |,)+([\\d.]+)%?\\)?$");

  private static final int HUE_MAX = 360;
  private static final int SATURATION_MAX = 100;
  private static final int LIGHTNESS_MAX = 100;

  public double H;
  public double S;
  public double L;

  public HslColor() {}

  public HslColor(double h, double s, double l) {
    this.H = h;
    this.S = s;
    this.L = l;
  }

  /** What the templates write into a style sheet. */
  public String String() {
    return "hsl("
        + GoFormat.fixed(H, 1)
        + ", "
        + GoFormat.fixed(S, 1)
        + "%, "
        + GoFormat.fixed(L, 1)
        + "%)";
  }

  public String ToHex() {
    return Colors.hslToHex(H, S, L);
  }

  public boolean SameAs(HslColor other) {
    if (other == null) {
      return false;
    }
    return H == other.H && S == other.S && L == other.L;
  }

  /** Two colours, either of which may be absent, are the same only when both are. */
  public static boolean same(HslColor a, HslColor b) {
    if (a == null && b == null) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    return a.SameAs(b);
  }

  @Override
  public void decode(Node node) {
    String value = Yaml.scalar(node);
    var matcher = PATTERN.matcher(value);
    if (!matcher.matches()) {
      throw new ConfigException("invalid HSL color format: " + value);
    }
    H = Double.parseDouble(matcher.group(1));
    if (H > HUE_MAX) {
      throw new ConfigException("HSL hue must be between 0 and " + HUE_MAX);
    }
    S = Double.parseDouble(matcher.group(2));
    if (S > SATURATION_MAX) {
      throw new ConfigException("HSL saturation must be between 0 and " + SATURATION_MAX);
    }
    L = Double.parseDouble(matcher.group(3));
    if (L > LIGHTNESS_MAX) {
      throw new ConfigException("HSL lightness must be between 0 and " + LIGHTNESS_MAX);
    }
  }
}
