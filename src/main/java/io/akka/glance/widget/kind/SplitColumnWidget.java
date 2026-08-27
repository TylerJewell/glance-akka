package io.akka.glance.widget.kind;

import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.ContainerWidget;

/** One column's worth of space, laid out as several narrower ones. */
public final class SplitColumnWidget extends ContainerWidget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("split-column.html", "widget-base.html");

  @Y("max-columns")
  public int MaxColumns;

  @Override
  public void initialize() {
    withError(null).withTitle("Split Column");
    setHideHeader(true);
    initializeWidgets();
    if (MaxColumns < 2) {
      MaxColumns = 2;
    }
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
