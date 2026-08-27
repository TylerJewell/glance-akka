package io.akka.glance.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The embedded files: the original's templates and its static assets.
 *
 * <p>{@code embed.FS} in the original; the class path here. {@link #walk} exists because the
 * static assets' cache key is a hash over every one of them, which needs the set enumerated
 * rather than opened by name.
 */
public final class Resources {

  private Resources() {}

  public static byte[] bytes(String path) {
    try (InputStream in = Resources.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("no such resource: " + path);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("reading " + path, e);
    }
  }

  public static boolean exists(String path) {
    try (InputStream in = Resources.class.getClassLoader().getResourceAsStream(path)) {
      return in != null;
    } catch (IOException e) {
      return false;
    }
  }

  public static String text(String path) {
    return new String(bytes(path), StandardCharsets.UTF_8);
  }

  /**
   * Every file under {@code root}, as paths relative to it, sorted the way {@code
   * fs.WalkDir} visits them — lexically, which is what makes the hash reproducible.
   */
  public static List<String> walk(String root) {
    var out = new ArrayList<String>();
    try {
      var url = Resources.class.getClassLoader().getResource(root);
      if (url == null) {
        return out;
      }
      URI uri = url.toURI();
      if (uri.getScheme().equals("jar")) {
        try (FileSystem fs = openJar(uri)) {
          collect(fs.getPath("/" + root), out);
        }
      } else {
        collect(Path.of(uri), out);
      }
    } catch (Exception e) {
      throw new IllegalStateException("walking " + root, e);
    }
    Collections.sort(out);
    return out;
  }

  private static FileSystem openJar(URI uri) throws IOException {
    try {
      return FileSystems.getFileSystem(uri);
    } catch (Exception notOpenYet) {
      return FileSystems.newFileSystem(uri, Map.of());
    }
  }

  private static void collect(Path root, List<String> into) throws IOException {
    try (Stream<Path> stream = Files.walk(root)) {
      stream
          .filter(Files::isRegularFile)
          .forEach(path -> into.add(root.relativize(path).toString().replace('\\', '/')));
    }
  }
}
