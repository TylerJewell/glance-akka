package io.akka.glance.net;

/**
 * What this build calls itself.
 *
 * <p>{@code dev} unless a build sets it, which is the original's default too, and the footer
 * reads it to decide whether to link to a release.
 */
public final class Version {

  public static final String BUILD =
      System.getProperty("glance.version", System.getenv().getOrDefault("GLANCE_VERSION", "dev"));

  private Version() {}
}
