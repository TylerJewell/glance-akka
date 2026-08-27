package io.akka.glance.config;

import io.akka.glance.gotemplate.Safe;
import org.yaml.snakeyaml.nodes.Node;

/**
 * An icon, either a URL of its own or a short name into one of four icon collections.
 *
 * <p>{@code AutoInvert} is what the two monochrome collections need on a dark background,
 * and the {@code auto-invert } prefix asks for it on any of them.
 */
public final class CustomIcon implements Yaml.Decodable {

  private static final String AUTO_INVERT_PREFIX = "auto-invert ";

  /** Typed as a URL so that a collection's address is not filtered by the escaper. */
  public Safe URL = Safe.url("");

  public boolean AutoInvert;

  public CustomIcon() {}

  public static CustomIcon of(String value) {
    var icon = new CustomIcon();
    icon.set(value);
    return icon;
  }

  private void set(String value) {
    if (value.startsWith(AUTO_INVERT_PREFIX)) {
      AutoInvert = true;
      value = value.substring(AUTO_INVERT_PREFIX.length());
    }
    int colon = value.indexOf(':');
    if (colon < 0) {
      URL = Safe.url(value);
      return;
    }
    String prefix = value.substring(0, colon);
    String icon = value.substring(colon + 1);
    int dot = icon.indexOf('.');
    String basename;
    String extension;
    if (dot < 0) {
      basename = icon;
      extension = "svg";
    } else {
      basename = icon.substring(0, dot);
      extension = icon.substring(dot + 1);
    }
    if (!extension.equals("svg") && !extension.equals("png")) {
      extension = "svg";
    }
    switch (prefix) {
      case "si" -> {
        AutoInvert = true;
        URL =
            Safe.url(
                "https://cdn.jsdelivr.net/npm/simple-icons@latest/icons/" + basename + ".svg");
      }
      case "di" ->
          URL =
              Safe.url(
                  "https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/"
                      + extension
                      + "/"
                      + basename
                      + "."
                      + extension);
      case "mdi" -> {
        AutoInvert = true;
        URL = Safe.url("https://cdn.jsdelivr.net/npm/@mdi/svg@latest/svg/" + basename + ".svg");
      }
      case "sh" ->
          URL =
              Safe.url(
                  "https://cdn.jsdelivr.net/gh/selfhst/icons/"
                      + extension
                      + "/"
                      + basename
                      + "."
                      + extension);
      default -> URL = Safe.url(value);
    }
  }

  @Override
  public void decode(Node node) {
    set(Yaml.scalar(node));
  }
}
