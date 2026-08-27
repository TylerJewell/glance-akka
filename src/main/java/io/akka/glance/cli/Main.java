package io.akka.glance.cli;

/**
 * The command line's own entry point.
 *
 * <p>Serving is started by the Akka runtime rather than from here, so this covers the other
 * commands: {@code java -cp ... io.akka.glance.cli.Main config:validate}.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    System.exit(Cli.main(args, System.out));
  }
}
