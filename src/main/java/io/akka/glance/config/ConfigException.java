package io.akka.glance.config;

/** A configuration file that could not be read, or that says something contradictory. */
public class ConfigException extends RuntimeException {

  public ConfigException(String message) {
    super(message);
  }

  public ConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
