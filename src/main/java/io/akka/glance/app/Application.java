package io.akka.glance.app;

import at.favre.lib.crypto.bcrypt.BCrypt;
import io.akka.glance.auth.Sessions;
import io.akka.glance.config.Config;
import io.akka.glance.config.ConfigException;
import io.akka.glance.config.HslColor;
import io.akka.glance.config.OrderedYamlMap;
import io.akka.glance.config.ThemeProperties;
import io.akka.glance.net.Version;
import io.akka.glance.render.Templates;
import io.akka.glance.util.Assets;
import io.akka.glance.util.GoInstant;
import io.akka.glance.util.Text;
import io.akka.glance.widget.Providers;
import io.akka.glance.widget.Widget;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A configuration, made ready to serve.
 *
 * <p>This is where the file stops being a description and becomes a running thing: the
 * accounts get their hashes, the themes get their style sheets, the pages get their slugs, and
 * every widget is given a way to reach an asset.
 */
public final class Application {

  /** Two page names the server uses for itself. */
  private static final List<String> RESERVED_SLUGS = List.of("login", "logout");

  public final String Version;
  public final GoInstant CreatedAt;
  public final Config Config;
  public final boolean RequiresAuth;

  private final Map<String, Config.Page> slugToPage = new LinkedHashMap<>();
  private final Map<Long, Widget> widgetById = new LinkedHashMap<>();
  private final Map<String, String> usernameHashToUsername = new HashMap<>();
  private final byte[] authSecretKey;
  private final String parsedManifest;

  public Application(Config config, Instant createdAt) {
    this.Version = io.akka.glance.net.Version.BUILD;
    this.CreatedAt = GoInstant.of(createdAt);
    this.Config = config;

    byte[] secret = null;
    if (!config.Auth.Users.isEmpty()) {
      try {
        secret = Base64.getDecoder().decode(config.Auth.SecretKey);
      } catch (IllegalArgumentException e) {
        throw new ConfigException("decoding secret-key: illegal base64 data");
      }
      if (secret.length != Sessions.SECRET_KEY_LENGTH) {
        throw new ConfigException(
            "secret-key must be exactly " + Sessions.SECRET_KEY_LENGTH + " bytes");
      }
      for (var entry : config.Auth.Users.entrySet()) {
        var user = entry.getValue();
        usernameHashToUsername.put(
            key(Sessions.usernameHash(entry.getKey(), secret)), entry.getKey());
        if (!user.PasswordHashString.isEmpty()) {
          user.PasswordHash = user.PasswordHashString.getBytes(StandardCharsets.UTF_8);
          user.PasswordHashString = "";
        } else {
          // Cost 10 is bcrypt's own default, which is what the original hashes at.
          user.PasswordHash =
              BCrypt.withDefaults().hash(10, user.Password.getBytes(StandardCharsets.UTF_8));
          user.Password = "";
        }
      }
    }
    this.authSecretKey = secret;
    this.RequiresAuth = secret != null;

    initThemes(config);

    slugToPage.put("", config.Pages.getFirst());
    var providers = new Providers(this::StaticAssetPath);
    for (var page : config.Pages) {
      page.PrimaryColumnIndex = -1;
      if (page.Slug.isEmpty()) {
        page.Slug = Text.titleToSlug(page.Title);
      }
      if (RESERVED_SLUGS.contains(page.Slug)) {
        throw new ConfigException("page slug \"" + page.Slug + "\" is reserved");
      }
      slugToPage.put(page.Slug, page);
      if (page.Width.equals("default")) {
        page.Width = "";
      }
      // The second half of this test can never be false when the first is true; it is the
      // original's own wording and is kept so that the two agree on every configuration.
      if (page.DesktopNavigationWidth.isEmpty()
          && !page.DesktopNavigationWidth.equals("default")) {
        page.DesktopNavigationWidth = page.Width;
      }
      for (var widget : page.HeadWidgets) {
        widgetById.put(widget.GetID(), widget);
        widget.setProviders(providers);
      }
      for (int c = 0; c < page.Columns.size(); c++) {
        var column = page.Columns.get(c);
        if (page.PrimaryColumnIndex == -1 && column.Size.equals("full")) {
          page.PrimaryColumnIndex = c;
        }
        for (var widget : column.Widgets) {
          widgetById.put(widget.GetID(), widget);
          widget.setProviders(providers);
        }
      }
    }

    config.Server.BaseURL = trimTrailingSlashes(config.Server.BaseURL);
    config.Theme.CustomCSSFile = resolveUserDefinedAssetPath(config.Theme.CustomCSSFile);
    config.Branding.LogoURL = resolveUserDefinedAssetPath(config.Branding.LogoURL);
    config.Branding.FaviconURL =
        config.Branding.FaviconURL.isEmpty()
            ? StaticAssetPath("favicon.svg")
            : resolveUserDefinedAssetPath(config.Branding.FaviconURL);
    config.Branding.FaviconType =
        config.Branding.FaviconURL.endsWith(".svg") ? "image/svg+xml" : "image/png";
    if (config.Branding.AppName.isEmpty()) {
      config.Branding.AppName = "Glance";
    }
    if (config.Branding.AppIconURL.isEmpty()) {
      config.Branding.AppIconURL = StaticAssetPath("app-icon.png");
    }
    if (config.Branding.AppBackgroundColor.isEmpty()) {
      config.Branding.AppBackgroundColor = config.Theme.BackgroundColorAsHex;
    }

    this.parsedManifest =
        Templates.of("manifest.json").execute(new TemplateData(this, null, new RequestData(null)));
  }

  /**
   * The default theme and the presets the picker offers.
   *
   * <p>The two built-in presets are added first so that they come before anything the file
   * declares, and the dark one is left out where the default theme already is it.
   */
  private void initThemes(Config config) {
    if (!config.Theme.DisablePicker) {
      var keys = new ArrayList<String>(2);
      var values = new ArrayList<ThemeProperties>(2);
      var declaredDark = config.Theme.Presets.Get("default-dark");
      if ((declaredDark != null && !config.Theme.SameAs(declaredDark))
          || !config.Theme.SameAs(new ThemeProperties())) {
        keys.add("default-dark");
        values.add(new ThemeProperties());
      }
      keys.add("default-light");
      values.add(
          ThemeProperties.of(
              true,
              new HslColor(240, 13, 95),
              new HslColor(230, 100, 30),
              new HslColor(0, 70, 50),
              1.3f,
              0.5f));
      var builtIn = OrderedYamlMap.of(ThemeProperties.class, keys, values);
      config.Theme.Presets = builtIn.Merge(config.Theme.Presets);
      for (var entry : config.Theme.Presets.Items()) {
        entry.getValue().Key = entry.getKey();
        try {
          entry.getValue().init();
        } catch (RuntimeException e) {
          throw new ConfigException(
              "initializing preset theme " + entry.getKey() + ": " + e.getMessage());
        }
      }
    }
    config.Theme.Key = "default";
    try {
      config.Theme.init();
    } catch (RuntimeException e) {
      throw new ConfigException("initializing default theme: " + e.getMessage());
    }
  }

  // Read from the templates.

  public String StaticAssetPath(String asset) {
    return Config.Server.BaseURL + "/static/" + Assets.hash() + "/" + asset;
  }

  public String VersionedAssetPath(String asset) {
    return Config.Server.BaseURL + asset + "?v=" + CreatedAt.Unix();
  }

  // The rest is what the server needs.

  public Config.Page pageBySlug(String slug) {
    return slugToPage.get(slug);
  }

  public Map<Long, Widget> widgetById() {
    return widgetById;
  }

  public byte[] authSecretKey() {
    return authSecretKey;
  }

  public String usernameForHash(byte[] hash) {
    return usernameHashToUsername.get(key(hash));
  }

  public String manifest() {
    return parsedManifest;
  }

  /** An asset the user supplied is served from this instance; anything else is left alone. */
  public String resolveUserDefinedAssetPath(String path) {
    return path.startsWith("/assets/") ? Config.Server.BaseURL + path : path;
  }

  /** Which theme a request is asking for, which is a cookie the picker sets. */
  public ThemeProperties themeFor(String themeCookie) {
    ThemeProperties theme = Config.Theme;
    if (!Config.Theme.DisablePicker && themeCookie != null && !themeCookie.isEmpty()) {
      var preset = Config.Theme.Presets.Get(themeCookie);
      if (preset != null) {
        theme = preset;
      }
    }
    return theme;
  }

  /**
   * Who a request is from.
   *
   * <p>Behind a proxy this is the rightmost address in {@code X-Forwarded-For}, because that
   * is the one the trusted proxy added; anything to the left of it can be written by whoever
   * is asking.
   */
  public String addressOfRequest(String remoteAddr, String forwardedFor) {
    if (!Config.Server.Proxied) {
      return withoutPort(remoteAddr);
    }
    if (forwardedFor == null || forwardedFor.isEmpty()) {
      return withoutPort(remoteAddr);
    }
    var addresses = forwardedFor.split(",", -1);
    String last = addresses[addresses.length - 1].trim();
    if (last.isEmpty()) {
      return withoutPort(remoteAddr);
    }
    return last;
  }

  private static String withoutPort(String address) {
    if (address == null) {
      return "";
    }
    int colon = address.lastIndexOf(':');
    return colon < 0 ? address : address.substring(0, colon);
  }

  private static String key(byte[] hash) {
    return Base64.getEncoder().encodeToString(hash);
  }

  private static String trimTrailingSlashes(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }

  /** What a page template executes against. */
  public record TemplateData(Application App, Config.Page Page, RequestData Request) {}

  /** What differs between one request for a page and the next. */
  public record RequestData(ThemeProperties Theme) {}
}
