package io.akka.glance.widget.kind;

import io.akka.glance.config.ConfigException;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.Widget;
import java.net.URI;

/** Somebody else's page, framed. */
public final class IframeWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("iframe.html", "widget-base.html");

  @Y(skip = true)
  private Safe cachedHTML = Safe.html("");

  @Y("source")
  public String Source = "";

  @Y("height")
  public int Height;

  @Override
  public void initialize() {
    withTitle("IFrame").withError(null);
    if (Source.isEmpty()) {
      throw new ConfigException("source is required");
    }
    try {
      URI.create(Source);
    } catch (IllegalArgumentException e) {
      throw new ConfigException("parsing URL: " + e.getMessage());
    }
    if (Height == 50) {
      Height = 300;
    } else if (Height < 50) {
      Height = 50;
    }
    cachedHTML = renderTemplate(this, TEMPLATE);
  }

  @Override
  public Safe Render() {
    return cachedHTML;
  }
}
