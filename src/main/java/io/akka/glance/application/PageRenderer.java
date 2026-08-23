package io.akka.glance.application;

import io.akka.glance.domain.WidgetRenderer;
import java.util.ArrayList;
import java.util.List;

/**
 * The page's content markup: the column, and the widgets inside it.
 *
 * <p>A transcription of the original's {@code page-content.html}, kept to its whitespace for
 * the same reason {@link WidgetRenderer} is. The blank lines between widgets are what the
 * original's template files leave behind, and they differ by widget type, so they are part
 * of the markup rather than something to tidy.
 */
public final class PageRenderer {

  /** What follows an ordinary widget's markup on the page. */
  private static final String AFTER_WIDGET = "\n\n\n\n";

  /** What follows a container's, whose template nests one level deeper. */
  private static final String AFTER_CONTAINER = "\n\n\n\n\n\n";

  private PageRenderer() {}

  public static String render(List<PageRefresh.Rendered> widgets) {
    var out = new StringBuilder(4096);
    out.append("\n\n\n\n<div class=\"page-columns\">\n");
    out.append("    <div class=\"page-column page-column-full\">");
    for (var widget : widgets) {
      out.append(renderWidget(widget));
      out.append(widget.isContainer() ? AFTER_CONTAINER : AFTER_WIDGET);
    }
    out.append("\n    </div>\n</div>\n");
    return out.toString();
  }

  private static String renderWidget(PageRefresh.Rendered widget) {
    if (!widget.isContainer()) {
      return WidgetRenderer.render(widget.state());
    }
    var children = new ArrayList<WidgetRenderer.GroupChild>();
    for (var child : widget.children()) {
      children.add(new WidgetRenderer.GroupChild(child.id(), child.state()));
    }
    return WidgetRenderer.renderGroup(children);
  }
}
