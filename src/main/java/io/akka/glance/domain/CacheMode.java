package io.akka.glance.domain;

/** How long a widget's content stays fresh. SPEC-001 §2, question-log rows 1 and 2. */
public enum CacheMode {
  /** Never refreshed after the first pass. */
  INFINITE,
  /** Fresh for a fixed span after each refresh. */
  DURATION,
  /** Fresh until the next exact top of the hour. */
  ON_THE_HOUR
}
