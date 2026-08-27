package io.akka.glance.application;

import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import io.akka.glance.app.Site;
import io.akka.glance.config.Includes;
import io.akka.glance.util.Resources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * What the server does before it answers anything: read a configuration.
 *
 * <p>The file is the one named by {@code GLANCE_CONFIG}, or {@code glance.yml} beside the
 * working directory, and a small default is used when neither is there so that a fresh
 * install shows something rather than an error.
 */
@Setup
public class GlanceSetup implements ServiceSetup {

  @Override
  public void onStartup() {
    if (Site.isLoaded()) {
      return;
    }
    Site.load(read(), Instant.now());
  }

  /** The configuration's text, from wherever this instance keeps it. */
  public static String read() {
    String named = System.getenv("GLANCE_CONFIG");
    var path = Path.of(named == null || named.isEmpty() ? "glance.yml" : named);
    if (Files.isRegularFile(path)) {
      return Includes.parse(path).contents();
    }
    return Resources.text("glance/default.yml");
  }
}
