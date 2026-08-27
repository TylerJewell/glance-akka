package io.akka.glance.gotemplate;

/** A template that could not be parsed, or an action that could not be executed. */
public class TemplateException extends RuntimeException {

  public TemplateException(String message) {
    super(message);
  }

  public TemplateException(String message, Throwable cause) {
    super(message, cause);
  }
}
