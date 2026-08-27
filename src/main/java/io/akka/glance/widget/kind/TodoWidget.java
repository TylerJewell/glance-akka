package io.akka.glance.widget.kind;

import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.Widget;

/** A list of tasks. The list itself lives in the browser; the widget only names it. */
public final class TodoWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("todo.html", "widget-base.html");

  @Y(skip = true)
  private Safe cachedHTML = Safe.html("");

  @Y("id")
  public String TodoID = "";

  @Override
  public void initialize() {
    withTitle("To-do").withError(null);
    cachedHTML = renderTemplate(this, TEMPLATE);
  }

  @Override
  public Safe Render() {
    return cachedHTML;
  }
}
