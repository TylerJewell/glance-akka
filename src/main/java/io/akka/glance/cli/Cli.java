package io.akka.glance.cli;

import at.favre.lib.crypto.bcrypt.BCrypt;
import io.akka.glance.auth.Sessions;
import io.akka.glance.config.ConfigException;
import io.akka.glance.config.ConfigLoader;
import io.akka.glance.config.Includes;
import io.akka.glance.net.Version;
import io.akka.glance.sysinfo.Sysinfo;
import io.akka.glance.gotemplate.GoFormat;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The commands the program answers to on the command line.
 *
 * <p>Everything but serving: checking a configuration, printing one with its includes pasted
 * in, hashing a password, making a secret key, listing sensors and mount points, and the
 * network diagnostic.
 */
public final class Cli {

  /** What the caller asked for. */
  public enum Intent {
    VERSION_PRINT,
    SERVE,
    CONFIG_VALIDATE,
    CONFIG_PRINT,
    DIAGNOSE,
    SENSORS_PRINT,
    MOUNTPOINT_INFO,
    SECRET_MAKE,
    PASSWORD_HASH
  }

  /** The parsed command line. */
  public record Options(Intent intent, String configPath, List<String> args) {}

  private Cli() {}

  public static int main(String[] argv, PrintStream out) {
    Options options;
    try {
      options = parse(argv);
    } catch (ConfigException e) {
      out.println(e.getMessage());
      return 1;
    }
    switch (options.intent()) {
      case VERSION_PRINT -> out.println(Version.BUILD);
      case SERVE -> {
        out.println("Serving is started by the runtime, not by this command.");
        return 1;
      }
      case CONFIG_VALIDATE -> {
        String contents;
        try {
          contents = Includes.parse(Path.of(options.configPath())).contents();
        } catch (ConfigException e) {
          out.println("Could not parse config file: " + e.getMessage());
          return 1;
        }
        try {
          ConfigLoader.fromYaml(contents);
        } catch (RuntimeException e) {
          out.println("Config file is invalid: " + e.getMessage());
          return 1;
        }
      }
      case CONFIG_PRINT -> {
        try {
          out.println(Includes.parse(Path.of(options.configPath())).contents());
        } catch (ConfigException e) {
          out.println("Could not parse config file: " + e.getMessage());
          return 1;
        }
      }
      case SENSORS_PRINT -> {
        return sensorsPrint(out);
      }
      case MOUNTPOINT_INFO -> {
        return mountpointInfo(options.args().get(1), out);
      }
      case DIAGNOSE -> Diagnostics.run(out);
      case SECRET_MAKE -> out.println(Sessions.makeSecretKey(Sessions.SECRET_KEY_LENGTH));
      case PASSWORD_HASH -> {
        String password = options.args().get(1);
        if (password.isEmpty()) {
          out.println("Password cannot be empty");
          return 1;
        }
        if (password.length() < 6) {
          out.println("Password must be at least 6 characters long");
          return 1;
        }
        out.println(
            new String(
                BCrypt.withDefaults().hash(10, password.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
      }
    }
    return 0;
  }

  /** What the usage text says, printed when the arguments make no sense. */
  public static String usage() {
    return """
        Usage: glance [options] command

        Options:
          -config string
            \tSet config path (default "glance.yml")

        Commands:
          config:validate       Validate the config file
          config:print          Print the parsed config file with embedded includes
          password:hash <pwd>   Hash a password
          secret:make           Generate a random secret key
          sensors:print         List all sensors
          mountpoint:info       Print information about a given mountpoint path
          diagnose              Run diagnostic checks""";
  }

  public static Options parse(String[] argv) {
    var all = List.of(argv);
    if (all.size() == 1
        && (all.getFirst().equals("--version")
            || all.getFirst().equals("-v")
            || all.getFirst().equals("version"))) {
      return new Options(Intent.VERSION_PRINT, "glance.yml", List.of());
    }
    String configPath = "glance.yml";
    var args = new ArrayList<String>();
    for (int i = 0; i < all.size(); i++) {
      String argument = all.get(i);
      if (argument.equals("-config") || argument.equals("--config")) {
        if (i + 1 >= all.size()) {
          throw new ConfigException("flag needs an argument: -config");
        }
        configPath = all.get(i + 1);
        i++;
        continue;
      }
      if (argument.startsWith("-config=") || argument.startsWith("--config=")) {
        configPath = argument.substring(argument.indexOf('=') + 1);
        continue;
      }
      args.add(argument);
    }
    var unknown = new ConfigException("unknown command: " + String.join(" ", args));
    Intent intent;
    if (args.isEmpty()) {
      intent = Intent.SERVE;
    } else if (args.size() == 1) {
      intent =
          switch (args.getFirst()) {
            case "config:validate" -> Intent.CONFIG_VALIDATE;
            case "config:print" -> Intent.CONFIG_PRINT;
            case "sensors:print" -> Intent.SENSORS_PRINT;
            case "diagnose" -> Intent.DIAGNOSE;
            case "secret:make" -> Intent.SECRET_MAKE;
            default -> throw unknown;
          };
    } else if (args.size() == 2) {
      intent =
          switch (args.getFirst()) {
            case "password:hash" -> Intent.PASSWORD_HASH;
            case "mountpoint:info" -> Intent.MOUNTPOINT_INFO;
            default -> throw unknown;
          };
    } else {
      throw unknown;
    }
    return new Options(intent, configPath, List.copyOf(args));
  }

  private static int sensorsPrint(PrintStream out) {
    var readings = Sysinfo.temperatures();
    if (readings.isEmpty()) {
      out.println("No sensors found");
      return 0;
    }
    out.println("Sensors found:");
    for (var reading : readings) {
      out.println(" " + reading.key() + ": " + GoFormat.fixed(reading.celsius(), 1) + "°C");
    }
    return 0;
  }

  private static int mountpointInfo(String path, PrintStream out) {
    var file = new java.io.File(path);
    if (!file.exists()) {
      out.println(
          "Failed to retrieve info for path " + path + ": no such file or directory");
      return 1;
    }
    long total = file.getTotalSpace();
    long used = total - file.getUsableSpace();
    out.println("Path: " + path);
    out.println("FS type: " + filesystemType(path));
    out.println(
        "Used percent: "
            + GoFormat.fixed(total == 0 ? 0 : (double) used / total * 100, 1)
            + "%");
    return 0;
  }

  private static String filesystemType(String path) {
    try {
      var store = java.nio.file.Files.getFileStore(Path.of(path));
      String type = store.type();
      return type == null || type.isEmpty() ? "unknown" : type;
    } catch (Exception e) {
      return "unknown";
    }
  }
}
