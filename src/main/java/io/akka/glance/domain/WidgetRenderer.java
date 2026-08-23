package io.akka.glance.domain;

import java.util.List;

/**
 * The widget's markup. SPEC-001 R15, D-7.
 *
 * <p>A transcription of the original's {@code widget-base.html}, {@code rss-list.html} and
 * {@code group.html}, which are Go templates and do not run here. The markup is what the port
 * reuses, not the engine: the same classes, the same nesting and the same whitespace, so the
 * original's own stylesheet lays it out unchanged and a screenshot comparison has something
 * to compare. {@code WidgetRendererTest} holds it to fragments cut out of the running
 * original's own response.
 */
public final class WidgetRenderer {

  /** One child of a container, with the id the container's tab markup addresses it by. */
  public record GroupChild(String id, WidgetState state) {}

  private WidgetRenderer() {}

  public static String render(WidgetState state) {
    return render(state, false);
  }

  /**
   * A container hides its children's headers, because their titles become its tabs — so the
   * same widget draws differently inside one than it does directly on the page.
   */
  public static String render(WidgetState state, boolean hideHeader) {
    var out = new StringBuilder(512);
    out.append("<div class=\"widget widget-type-rss\">\n");

    if (!hideHeader) {
      out.append("    <div class=\"widget-header\">\n");
      out.append("        <h2 class=\"uppercase\">").append(escape(state.title())).append("</h2>\n");
      if (state.error() != null && state.contentAvailable()) {
        out.append("        <div class=\"notice-icon notice-icon-major\" title=\"")
            .append(escape(state.error()))
            .append("\"></div>\n");
      } else if (state.notice() != null) {
        out.append("        <div class=\"notice-icon notice-icon-minor\" title=\"")
            .append(escape(state.notice()))
            .append("\"></div>\n");
      }
      out.append("    </div>\n");
    }

    if (state.contentAvailable()) {
      // The trailing space is the original's: the class list is built from a template block
      // that is empty for this widget type, and the space in front of it survives.
      out.append("    <div class=\"widget-content \">\n");
      out.append("        \n");
      renderList(out, state);
      out.append("\n    </div>\n");
    } else {
      out.append("    <div class=\"widget-content\">\n");
      out.append("            <div class=\"widget-error-header\">\n");
      out.append("                <div class=\"color-negative size-h3\">ERROR</div>\n");
      out.append(
          "                <svg class=\"widget-error-icon\" xmlns=\"http://www.w3.org/2000/svg\""
              + " fill=\"none\" viewBox=\"0 0 24 24\" stroke-width=\"1.5\">\n");
      out.append(
          "                    <path stroke-linecap=\"round\" stroke-linejoin=\"round\""
              + " d=\"M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0"
              + " 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898"
              + " 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z\" />\n");
      out.append("                </svg>\n");
      out.append("            </div>\n");
      out.append("            <p class=\"break-all\">")
          .append(escape(state.error() == null ? "No error information provided" : state.error()))
          .append("</p>\n");
      out.append("    </div>\n");
    }

    out.append("</div>");
    return out.toString();
  }

  /** A container's markup: a tab strip over its children's titles, then the children. */
  public static String renderGroup(List<GroupChild> children) {
    var out = new StringBuilder(1024);
    out.append("<div class=\"widget widget-type-group\">\n");
    out.append("    <div class=\"widget-content widget-content-frameless\">\n");
    out.append("        \n");
    out.append("<div class=\"widget-group-header\">\n");
    out.append("    <div class=\"widget-header gap-20\" role=\"tablist\">\n");
    for (var i = 0; i < children.size(); i++) {
      var child = children.get(i);
      out.append("        <button class=\"widget-group-title")
          .append(i == 0 ? " widget-group-title-current" : "")
          .append("\" aria-selected=\"")
          .append(i == 0)
          .append("\" arial-level=\"2\" role=\"tab\" aria-controls=\"widget-")
          .append(escape(child.id()))
          .append("-tabpanel-")
          .append(i)
          .append("\" id=\"widget-")
          .append(escape(child.id()))
          .append("-tab-")
          .append(i)
          .append("\">")
          .append(escape(child.state().title()))
          .append("</button>\n");
    }
    out.append("    </div>\n");
    out.append("</div>\n");
    out.append("\n");
    out.append("<div class=\"widget-group-contents\">\n");
    for (var i = 0; i < children.size(); i++) {
      var child = children.get(i);
      out.append("    <div class=\"widget-group-content")
          .append(i == 0 ? " widget-group-content-current" : "")
          .append("\" id=\"widget-")
          .append(escape(child.id()))
          .append("-tabpanel-")
          .append(i)
          .append("\" role=\"tabpanel\" aria-labelledby=\"widget-")
          .append(escape(child.id()))
          .append("-tab-")
          .append(i)
          .append("\" aria-hidden=\"")
          .append(i != 0)
          .append("\">")
          .append(render(child.state(), true))
          .append("\n\n\n\n</div>\n");
    }
    out.append("</div>\n");
    out.append("\n    </div>\n");
    out.append("</div>");
    return out.toString();
  }

  private static void renderList(StringBuilder out, WidgetState state) {
    out.append("<ul class=\"list list-gap-14 collapsible-container\" data-collapse-after=\"")
        .append(state.collapseAfter())
        .append("\">\n");

    if (state.items().isEmpty()) {
      out.append("    \n    <li>")
          .append(escape(WidgetState.NO_ITEMS_MESSAGE))
          .append("</li>\n    \n");
    } else {
      for (var item : state.items()) {
        out.append("    \n");
        out.append("    <li>\n");
        out.append(
                "        <a class=\"title size-title-dynamic color-primary-if-not-visited\" href=\"")
            .append(escape(item.link()))
            .append("\" target=\"_blank\" rel=\"noreferrer\">")
            .append(escape(item.title()))
            .append("</a>\n");
        out.append("        <ul class=\"list-horizontal-text flex-nowrap\">\n");
        out.append("            <li data-dynamic-relative-time=\"")
            .append(item.publishedAt().getEpochSecond())
            .append("\"></li>\n");
        out.append("            <li class=\"min-width-0\">\n");
        out.append("                <a class=\"block text-truncate\" href=\"")
            .append(escape(item.channelUrl()))
            .append("\" target=\"_blank\" rel=\"noreferrer\">")
            .append(escape(item.channelName()))
            .append("</a>\n");
        out.append("            </li>\n");
        out.append("        </ul>\n");
        out.append("    </li>\n");
      }
      out.append("    \n");
    }
    out.append("</ul>\n");
  }

  /** The five substitutions Go's html/template makes in a text or attribute context. */
  private static String escape(String raw) {
    if (raw == null) {
      return "";
    }
    var out = new StringBuilder(raw.length() + 16);
    for (var i = 0; i < raw.length(); i++) {
      var c = raw.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&#34;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
