package io.akka.glance.config;

import io.akka.glance.gotemplate.Safe;
import io.akka.glance.render.Templates;
import java.util.regex.Pattern;

/**
 * One theme: the colours, the two multipliers, and the style sheet and preview built from
 * them.
 *
 * <p>{@link #init} is what turns the declared colours into the {@code CSS} the document
 * carries and the {@code PreviewHTML} the picker shows, both by rendering the original's own
 * templates.
 */
public class ThemeProperties {

  /** Leading whitespace, which the compiled style sheet does not carry. */
  private static final Pattern LEADING_WHITESPACE = Pattern.compile("(?m)^\\s+");

  @Y("background-color")
  public HslColor BackgroundColor;

  @Y("primary-color")
  public HslColor PrimaryColor;

  @Y("positive-color")
  public HslColor PositiveColor;

  @Y("negative-color")
  public HslColor NegativeColor;

  @Y("light")
  public boolean Light;

  @Y("contrast-multiplier")
  public float ContrastMultiplier;

  @Y("text-saturation-multiplier")
  public float TextSaturationMultiplier;

  @Y(skip = true)
  public String Key = "";

  @Y(skip = true)
  public Safe CSS = Safe.css("");

  @Y(skip = true)
  public Safe PreviewHTML = Safe.html("");

  @Y(skip = true)
  public String BackgroundColorAsHex = "";

  public ThemeProperties() {}

  public static ThemeProperties of(
      boolean light,
      HslColor background,
      HslColor primary,
      HslColor negative,
      float contrast,
      float saturation) {
    var theme = new ThemeProperties();
    theme.Light = light;
    theme.BackgroundColor = background;
    theme.PrimaryColor = primary;
    theme.NegativeColor = negative;
    theme.ContrastMultiplier = contrast;
    theme.TextSaturationMultiplier = saturation;
    return theme;
  }

  /** Builds the style sheet and the preview. */
  public void init() {
    var style = Templates.of("theme-style.gotmpl").execute(this);
    CSS = Safe.css(LEADING_WHITESPACE.matcher(style).replaceAll(""));
    PreviewHTML = Safe.html(Templates.of("theme-preset-preview.html").execute(this));
    BackgroundColorAsHex = BackgroundColor != null ? BackgroundColor.ToHex() : "#151519";
  }

  public boolean SameAs(ThemeProperties other) {
    if (other == null) {
      return false;
    }
    return Light == other.Light
        && ContrastMultiplier == other.ContrastMultiplier
        && TextSaturationMultiplier == other.TextSaturationMultiplier
        && HslColor.same(BackgroundColor, other.BackgroundColor)
        && HslColor.same(PrimaryColor, other.PrimaryColor)
        && HslColor.same(PositiveColor, other.PositiveColor)
        && HslColor.same(NegativeColor, other.NegativeColor);
  }
}
