package io.akka.glance.config;

import io.akka.glance.util.Text;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A configuration file, with everything it includes pasted into it.
 *
 * <p>An include line is replaced by the file it names, indented to where the line sat, so
 * that a fragment can stand for one item of a list or one branch of a mapping.
 */
public final class Includes {

  private static final int DEPTH_LIMIT = 20;

  private static final Pattern INCLUDE =
      Pattern.compile("(?m)^([ \\t]*)(?:-[ \\t]*)?(?:!|\\$)include:[ \\t]*(.+)$");

  private Includes() {}

  /** The whole file, and every path that went into it. */
  public record Resolved(String contents, Set<String> includes) {}

  public static Resolved parse(Path mainFile) {
    var includes = new LinkedHashSet<String>();
    String contents = recursive(mainFile, includes, 0);
    return new Resolved(contents, includes);
  }

  private static String recursive(Path file, Set<String> includes, int depth) {
    if (depth > DEPTH_LIMIT) {
      throw new ConfigException("recursion depth limit of " + DEPTH_LIMIT + " reached");
    }
    String contents;
    try {
      contents = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ConfigException(
          "reading " + file + ": open " + file + ": no such file or directory");
    }
    Path directory = file.toAbsolutePath().getParent();
    var matcher = INCLUDE.matcher(contents);
    var out = new StringBuilder();
    while (matcher.find()) {
      String indent = matcher.group(1);
      String named = matcher.group(2).trim();
      Path included = Path.of(named);
      if (!included.isAbsolute()) {
        included = directory.resolve(named);
      }
      includes.add(included.toAbsolutePath().toString());
      String body = recursive(included, includes, depth + 1);
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(Text.prefixStringLines(indent, body)));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}
