package io.akka.glance.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The static files, their cache key, and the one style sheet built from them.
 *
 * <p>The key is a digest over every file's contents, so a build that changes any of them
 * changes the path they are served from and nothing stale is kept by a browser. The style
 * sheet is assembled at startup by following the imports, which is what the original does
 * too.
 */
public final class Assets {

  private static final String ROOT = "glance/static";

  private static final Pattern CSS_IMPORT = Pattern.compile("(?m)^@import \"(.*?)\";$");
  private static final Pattern CSS_LINE_COMMENT = Pattern.compile("(?m)^\\s*/\\*.*?\\*/$");

  private static final String HASH = computeHash();
  private static final byte[] BUNDLE = buildBundle();

  private Assets() {}

  public static String hash() {
    return HASH;
  }

  public static byte[] bundledCss() {
    return BUNDLE;
  }

  public static byte[] read(String path) {
    return Resources.bytes(ROOT + "/" + path.replace('\\', '/'));
  }

  public static boolean exists(String path) {
    String normalised = path.replace('\\', '/');
    return !normalised.contains("..") && Resources.exists(ROOT + "/" + normalised);
  }

  private static String computeHash() {
    try {
      var digest = MessageDigest.getInstance("MD5");
      for (var path : Resources.walk(ROOT)) {
        digest.update(Resources.bytes(ROOT + "/" + path));
      }
      return HexFormat.of().formatHex(digest.digest()).substring(0, 10);
    } catch (NoSuchAlgorithmException | RuntimeException e) {
      return String.valueOf(java.time.Instant.now().getEpochSecond());
    }
  }

  private static byte[] buildBundle() {
    String contents = recursiveImports("css/main.css", 0);
    contents = CSS_LINE_COMMENT.matcher(contents).replaceAll("");
    contents = Text.WHITESPACE_AT_LINE_START.matcher(contents).replaceAll("");
    contents = contents.replace("\n", "");
    return contents.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String recursiveImports(String path, int depth) {
    if (depth > 20) {
      throw new IllegalStateException(
          "maximum import depth reached, is one of your imports circular?");
    }
    // Line endings are normalised first, because the import pattern anchors on a line.
    String contents =
        new String(read(path), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
    String directory = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
    var matcher = CSS_IMPORT.matcher(contents);
    var out = new StringBuilder();
    while (matcher.find()) {
      String imported = directory.isEmpty() ? matcher.group(1) : directory + "/" + matcher.group(1);
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(recursiveImports(normalise(imported), depth + 1)));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /** Resolves the {@code ..} segments a relative import may carry. */
  private static String normalise(String path) {
    var segments = new java.util.ArrayDeque<String>();
    for (var segment : path.split("/")) {
      if (segment.equals(".") || segment.isEmpty()) {
        continue;
      }
      if (segment.equals("..")) {
        segments.pollLast();
        continue;
      }
      segments.addLast(segment);
    }
    return String.join("/", segments);
  }
}
