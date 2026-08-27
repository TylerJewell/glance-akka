package io.akka.glance.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The values a configuration file asks the surroundings for.
 *
 * <p>Three kinds: an environment variable, a file under {@code /run/secrets}, and a file whose
 * path an environment variable holds. A backslash before the opening brace escapes the whole
 * thing, which is how a literal is written.
 */
public final class ConfigVariables {

  private static final Pattern ENV_NAME = Pattern.compile("^[A-Z0-9_]+$");

  /**
   * The character before the reference is captured too, so that an escape can be recognised
   * and removed.
   */
  private static final Pattern REFERENCE =
      Pattern.compile("(^|.)\\$\\{(?:([a-zA-Z]+):)?([a-zA-Z0-9_-]+)}");

  private static final String TYPE_ENV = "env";
  private static final String TYPE_SECRET = "secret";
  private static final String TYPE_FILE_FROM_ENV = "readFileFromEnv";

  /** Where a secret lives, as a directory so that a test can point it elsewhere. */
  public static Path secretsDirectory = Path.of("/run/secrets");

  /** Where an environment variable is read from, so that a test can supply its own. */
  public static Function<String, String> environment = System::getenv;

  private ConfigVariables() {}

  public static String substitute(String contents) {
    var matcher = REFERENCE.matcher(contents);
    var out = new StringBuilder();
    while (matcher.find()) {
      String prefix = matcher.group(1);
      if (prefix.equals("\\")) {
        // Escaped: the reference stands as written, less the backslash.
        matcher.appendReplacement(
            out, Matcher.quoteReplacement(matcher.group().substring(1)));
        continue;
      }
      String type = matcher.group(2) == null || matcher.group(2).isEmpty()
          ? TYPE_ENV
          : matcher.group(2);
      String name = matcher.group(3);
      var resolved = resolve(type, name);
      if (resolved == null) {
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
        continue;
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(prefix + resolved));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /** The value, or nothing at all when the reference is not one this understands. */
  private static String resolve(String type, String name) {
    switch (type) {
      case TYPE_ENV -> {
        if (!ENV_NAME.matcher(name).matches()) {
          return null;
        }
        var value = environment.apply(name);
        if (value == null) {
          throw new ConfigException(
              "parsing variable: environment variable " + name + " not found");
        }
        return value;
      }
      case TYPE_SECRET -> {
        var path = secretsDirectory.resolve(name);
        try {
          return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
          throw new ConfigException(
              "parsing variable: reading secret file: open " + path + ": " + reason(e));
        }
      }
      case TYPE_FILE_FROM_ENV -> {
        if (!ENV_NAME.matcher(name).matches()) {
          return null;
        }
        var filePath = environment.apply(name);
        if (filePath == null) {
          throw new ConfigException(
              "parsing variable: readFileFromEnv: environment variable " + name + " not found");
        }
        if (!Path.of(filePath).isAbsolute()) {
          throw new ConfigException(
              "parsing variable: readFileFromEnv: file path " + filePath + " is not absolute");
        }
        try {
          return Files.readString(Path.of(filePath), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
          throw new ConfigException(
              "parsing variable: readFileFromEnv: reading file from " + name + ": " + reason(e));
        }
      }
      default -> {
        return null;
      }
    }
  }

  private static String reason(IOException e) {
    return e instanceof java.nio.file.NoSuchFileException
        ? "no such file or directory"
        : e.getMessage();
  }
}
