package io.akka.glance.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;

/**
 * The configuration this instance is running, kept where a restart can find it.
 *
 * <p>The original reads a file and holds the result in memory; a restart re-reads the file. A
 * configuration set through the API has nowhere to be re-read from, so it is kept here.
 */
@Component(id = "site")
public class SiteEntity extends KeyValueEntity<SiteEntity.State> {

  /** The only identifier this entity has: there is one configuration. */
  public static final String ID = "current";

  /** The configuration's own text, and when it was put there. */
  public record State(String yaml, String loadedAt) {}

  public SiteEntity(KeyValueEntityContext context) {}

  @Override
  public State emptyState() {
    return new State("", "");
  }

  public Effect<Done> set(State state) {
    return effects().updateState(state).thenReply(Done.INSTANCE);
  }

  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  /** What a command that only writes replies with. */
  public enum Done {
    INSTANCE
  }
}
