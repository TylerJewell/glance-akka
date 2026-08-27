package io.akka.glance.config;

import io.akka.glance.gotemplate.Safe;
import io.akka.glance.widget.Widget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole configuration file, in the shape the templates read it in.
 *
 * <p>Field names are the original's own, because the original's template files are shipped
 * unchanged and address them by those names.
 */
public final class Config {

  @Y("server")
  public Server Server = new Server();

  @Y("auth")
  public Auth Auth = new Auth();

  @Y("document")
  public Document Document = new Document();

  @Y("theme")
  public Theme Theme = new Theme();

  @Y("branding")
  public Branding Branding = new Branding();

  @Y("pages")
  public List<Page> Pages = new ArrayList<>();

  /** Where the server listens, and what it is behind. */
  public static final class Server {
    @Y("host")
    public String Host = "";

    @Y("port")
    public int Port = 8080;

    @Y("proxied")
    public boolean Proxied;

    @Y("assets-path")
    public String AssetsPath = "";

    @Y("base-url")
    public String BaseURL = "";
  }

  /** Who may see the pages. Empty means everybody. */
  public static final class Auth {
    @Y("secret-key")
    public String SecretKey = "";

    @Y("users")
    public Map<String, User> Users = new LinkedHashMap<>();
  }

  /** One account. A password is hashed at startup; a password-hash is used as it stands. */
  public static final class User {
    @Y("password")
    public String Password = "";

    @Y("password-hash")
    public String PasswordHashString = "";

    @Y(skip = true)
    public byte[] PasswordHash;
  }

  /** Markup added to every page's head. */
  public static final class Document {
    @Y("head")
    public String HeadSource = "";

    /** Typed as markup, which is what makes it reach the page rather than its text. */
    public Safe Head() {
      return Safe.html(HeadSource);
    }
  }

  /** The default theme, the presets the picker offers, and any style sheet of one's own. */
  public static final class Theme extends ThemeProperties {
    @Y("custom-css-file")
    public String CustomCSSFile = "";

    @Y("disable-picker")
    public boolean DisablePicker;

    @Y("presets")
    public OrderedYamlMap<ThemeProperties> Presets = new OrderedYamlMap<>(ThemeProperties.class);
  }

  /** The name, the marks and the footer. */
  public static final class Branding {
    @Y("hide-footer")
    public boolean HideFooter;

    @Y("custom-footer")
    public String CustomFooterSource = "";

    @Y("logo-text")
    public String LogoText = "";

    @Y("logo-url")
    public String LogoURL = "";

    @Y("favicon-url")
    public String FaviconURL = "";

    @Y(skip = true)
    public String FaviconType = "";

    @Y("app-name")
    public String AppName = "";

    @Y("app-icon-url")
    public String AppIconURL = "";

    @Y("app-background-color")
    public String AppBackgroundColor = "";

    public Safe CustomFooter() {
      return Safe.html(CustomFooterSource);
    }
  }

  /** One page: its columns, and everything about how it is laid out. */
  public static final class Page {
    @Y("name")
    public String Title = "";

    @Y("slug")
    public String Slug = "";

    @Y("width")
    public String Width = "";

    @Y("desktop-navigation-width")
    public String DesktopNavigationWidth = "";

    @Y("show-mobile-header")
    public boolean ShowMobileHeader;

    @Y("hide-desktop-navigation")
    public boolean HideDesktopNavigation;

    @Y("center-vertically")
    public boolean CenterVertically;

    @Y("head-widgets")
    public Widgets HeadWidgets = new Widgets();

    @Y("columns")
    public List<Column> Columns = new ArrayList<>();

    @Y(skip = true)
    public int PrimaryColumnIndex = -1;
  }

  /** One column of a page. */
  public static final class Column {
    @Y("size")
    public String Size = "";

    @Y("widgets")
    public Widgets Widgets = new Widgets();
  }

  /** A list of widgets, which decides each one's type as it reads it. */
  public static final class Widgets extends ArrayList<Widget> implements Yaml.Decodable {

    @Override
    public void decode(org.yaml.snakeyaml.nodes.Node node) {
      if (!(node instanceof org.yaml.snakeyaml.nodes.SequenceNode sequence)) {
        throw new ConfigException("expected a list of widgets");
      }
      for (var item : sequence.getValue()) {
        var type = new WidgetType();
        Yaml.decodeInto(item, type);
        var widget = io.akka.glance.widget.WidgetFactory.create(type.type);
        Yaml.decodeInto(item, widget);
        add(widget);
      }
    }
  }

  /** Only the type, read first so that the rest can be read into the right class. */
  public static final class WidgetType {
    @Y("type")
    public String type = "";
  }
}
