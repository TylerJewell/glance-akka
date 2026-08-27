package io.akka.glance.config;

import io.akka.glance.widget.Widget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * Reads a configuration file into the shape the application runs on.
 *
 * <p>Three passes: the references to the surroundings are substituted, the YAML is decoded,
 * and what it says is checked against itself. Every widget is then initialised, which is where
 * a widget's own settings are checked.
 */
public final class ConfigLoader {

  private ConfigLoader() {}

  public static Config fromYaml(String contents) {
    String substituted = ConfigVariables.substitute(contents);
    var config = new Config();
    var node = Yaml.compose(substituted);
    if (node != null) {
      Yaml.decodeInto(node, config);
    }
    validate(config);
    for (var page : config.Pages) {
      for (var widget : page.HeadWidgets) {
        initialise(widget);
      }
      for (var column : page.Columns) {
        for (var widget : column.Widgets) {
          initialise(widget);
        }
      }
    }
    return config;
  }

  private static void initialise(Widget widget) {
    try {
      widget.initialize();
    } catch (ConfigException e) {
      throw new ConfigException(widget.GetType() + " widget: " + e.getMessage());
    }
  }

  /**
   * What the file says about itself, before anything is changed.
   *
   * <p>The rest of the checking happens while the application is built, which is also where
   * the values get adjusted; this half only reads.
   */
  static void validate(Config config) {
    if (config.Pages.isEmpty()) {
      throw new ConfigException("no pages configured");
    }
    if (!config.Auth.Users.isEmpty() && config.Auth.SecretKey.isEmpty()) {
      throw new ConfigException("secret-key must be set when users are configured");
    }
    for (var entry : config.Auth.Users.entrySet()) {
      String username = entry.getKey();
      if (username.isEmpty()) {
        throw new ConfigException("user has no name");
      }
      if (username.length() < 3) {
        throw new ConfigException("usernames must be at least 3 characters");
      }
      var user = entry.getValue();
      if (user.Password.isEmpty()) {
        if (user.PasswordHashString.isEmpty()) {
          throw new ConfigException(
              "user " + username + " must have a password or a password-hash set");
        }
      } else if (user.Password.length() < 6) {
        throw new ConfigException("the password for " + username + " must be at least 6 characters");
      }
    }
    if (!config.Server.AssetsPath.isEmpty() && !Files.exists(Path.of(config.Server.AssetsPath))) {
      throw new ConfigException("assets directory does not exist: " + config.Server.AssetsPath);
    }
    for (int i = 0; i < config.Pages.size(); i++) {
      var page = config.Pages.get(i);
      int number = i + 1;
      if (page.Title.isEmpty()) {
        throw new ConfigException("page " + number + " has no name");
      }
      if (!page.Width.isEmpty()
          && !page.Width.equals("wide")
          && !page.Width.equals("slim")
          && !page.Width.equals("default")) {
        throw new ConfigException("page " + number + ": width can only be either wide or slim");
      }
      if (!page.DesktopNavigationWidth.isEmpty()
          && !page.DesktopNavigationWidth.equals("wide")
          && !page.DesktopNavigationWidth.equals("slim")
          && !page.DesktopNavigationWidth.equals("default")) {
        throw new ConfigException(
            "page " + number + ": desktop-navigation-width can only be either wide or slim");
      }
      if (page.Columns.isEmpty()) {
        throw new ConfigException("page " + number + " has no columns");
      }
      if (page.Width.equals("slim")) {
        if (page.Columns.size() > 2) {
          throw new ConfigException(
              "page " + number + " is slim and cannot have more than 2 columns");
        }
      } else if (page.Columns.size() > 3) {
        throw new ConfigException("page " + number + " has more than 3 columns");
      }
      var sizes = new LinkedHashMap<String, Integer>();
      for (int c = 0; c < page.Columns.size(); c++) {
        var column = page.Columns.get(c);
        if (!column.Size.equals("small") && !column.Size.equals("full")) {
          throw new ConfigException(
              "column " + (c + 1) + " of page " + number + ": size can only be either small or full");
        }
        sizes.merge(column.Size, 1, Integer::sum);
      }
      int full = sizes.getOrDefault("full", 0);
      if (full > 2 || full == 0) {
        throw new ConfigException("page " + number + " must have either 1 or 2 full width columns");
      }
    }
  }
}
