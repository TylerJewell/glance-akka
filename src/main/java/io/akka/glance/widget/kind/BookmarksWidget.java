package io.akka.glance.widget.kind;

import io.akka.glance.config.CustomIcon;
import io.akka.glance.config.HslColor;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.Widget;
import java.util.ArrayList;
import java.util.List;

/** Links, in groups. Everything it shows is settled when the configuration is read. */
public final class BookmarksWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("bookmarks.html", "widget-base.html");

  @Y(skip = true)
  private Safe cachedHTML = Safe.html("");

  @Y("groups")
  public List<Group> Groups = new ArrayList<>();

  /** One group of links, and the defaults its links inherit. */
  public static final class Group {
    @Y("title")
    public String Title = "";

    @Y("color")
    public HslColor Color;

    @Y("same-tab")
    public boolean SameTab;

    @Y("hide-arrow")
    public boolean HideArrow;

    @Y("target")
    public String Target = "";

    @Y("links")
    public List<Link> Links = new ArrayList<>();
  }

  /**
   * One link.
   *
   * <p>{@code SameTabRaw} and {@code HideArrowRaw} are separate from the fields the template
   * reads because absent and false are different answers here: absent takes the group's, and
   * a template cannot tell an unset pointer from a false one.
   */
  public static final class Link {
    @Y("title")
    public String Title = "";

    @Y("url")
    public String URL = "";

    @Y("description")
    public String Description = "";

    @Y("icon")
    public CustomIcon Icon = new CustomIcon();

    @Y("same-tab")
    public Boolean SameTabRaw;

    @Y(skip = true)
    public boolean SameTab;

    @Y("hide-arrow")
    public Boolean HideArrowRaw;

    @Y(skip = true)
    public boolean HideArrow;

    @Y("target")
    public String Target = "";
  }

  @Override
  public void initialize() {
    withTitle("Bookmarks").withError(null);
    for (var group : Groups) {
      for (var link : group.Links) {
        link.SameTab = link.SameTabRaw == null ? group.SameTab : link.SameTabRaw;
        link.HideArrow = link.HideArrowRaw == null ? group.HideArrow : link.HideArrowRaw;
        if (link.Target.isEmpty()) {
          if (!group.Target.isEmpty()) {
            link.Target = group.Target;
          } else {
            link.Target = link.SameTab ? "" : "_blank";
          }
        }
      }
    }
    cachedHTML = renderTemplate(this, TEMPLATE);
  }

  @Override
  public Safe Render() {
    return cachedHTML;
  }
}
