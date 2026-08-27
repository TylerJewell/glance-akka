package io.akka.glance.widget.kind;

import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.widget.Widget;

/** Markup written straight into the page. It has no frame and no heading of its own. */
public final class HtmlWidget extends Widget {

  @Y("source")
  public String SourceText = "";

  @Override
  public void initialize() {
    withTitle("").withError(null);
  }

  public Safe Source() {
    return Safe.html(SourceText);
  }

  @Override
  public Safe Render() {
    return Source();
  }
}
