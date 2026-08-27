package io.akka.glance.widget.kind;

import io.akka.glance.config.ConfigException;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.ContainerWidget;

/** Several widgets in one frame, shown one at a time behind tabs. */
public final class GroupWidget extends ContainerWidget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("group.html", "widget-base.html");

  @Override
  public void initialize() {
    withError(null);
    HideHeader = true;
    for (var widget : Widgets) {
      widget.setHideHeader(true);
      if (widget.GetType().equals("group")) {
        throw new ConfigException("nested groups are not supported");
      }
      if (widget.GetType().equals("split-column")) {
        throw new ConfigException("split columns inside of groups are not supported");
      }
    }
    initializeWidgets();
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
