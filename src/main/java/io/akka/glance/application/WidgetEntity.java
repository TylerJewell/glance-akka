package io.akka.glance.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.akka.glance.domain.CacheMode;
import io.akka.glance.domain.FeedSpec;
import io.akka.glance.domain.RefreshOutcome;
import io.akka.glance.domain.WidgetState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One widget's feeds and its refresh state.
 *
 * <p>Nothing here reaches the network and nothing here reads a clock. The source's
 * {@code rssWidget.update} does both alongside the state transition; SPEC-001 D-1 splits
 * them, and D-5 makes the completion instant an argument, so every deadline this produces
 * is a function of what it was handed.
 */
@Component(id = "widget")
public class WidgetEntity extends KeyValueEntity<WidgetEntity.Widget> {

  /** A widget's configuration alongside the state its refreshes have left behind. */
  public record Widget(
      List<FeedSpec> feeds,
      WidgetState state,
      boolean scheduled,
      List<String> childIds,
      String lastRefreshToken) {

    /**
     * A widget holding other widgets fetches nothing itself; its children do.
     *
     * <p>Kept out of the serialized form: it is derived from {@code childIds}, and a
     * persisted copy of a derived value is a second answer to the same question.
     */
    @JsonIgnore
    public boolean isContainer() {
      return childIds != null && !childIds.isEmpty();
    }
  }

  /**
   * @param cacheSeconds the configured cache span; null leaves the widget type's own default
   * @param scheduled whether this widget refreshes on its own deadline as well as on request
   */
  public record Config(
      String title,
      List<FeedSpec> feeds,
      CacheMode cacheMode,
      Long cacheSeconds,
      Integer limit,
      Boolean preserveOrder,
      boolean scheduled,
      List<String> childIds) {}

  /**
   * The result of one refresh pass, with the instant it finished.
   *
   * @param token identifies the refresh, not the widget. Timers are delivered at least
   *     once, and a redelivered firing carries the token of the one already applied — so
   *     without it a single failed attempt could advance the backoff twice. A refresh
   *     nobody booked a timer for carries no token and is always applied.
   */
  public record Applied(RefreshOutcome outcome, Instant completedAt, String token) {}

  @Override
  public Widget emptyState() {
    return new Widget(
        List.of(),
        WidgetState.configured(
            "", CacheMode.DURATION, WidgetState.DEFAULT_CACHE, List.of(),
            WidgetState.DEFAULT_LIMIT, false),
        false,
        List.of(),
        null);
  }

  public Effect<WidgetState> configure(Config config) {
    var duration =
        config.cacheMode() == CacheMode.DURATION
            ? io.akka.glance.domain.CachePolicy.resolveCacheDuration(
                config.cacheSeconds() == null ? null : Duration.ofSeconds(config.cacheSeconds()),
                WidgetState.DEFAULT_CACHE)
            : null;

    var state =
        WidgetState.configured(
            config.title(),
            config.cacheMode(),
            duration,
            List.of(),
            config.limit() == null ? WidgetState.DEFAULT_LIMIT : config.limit(),
            Boolean.TRUE.equals(config.preserveOrder()));

    return effects()
        .updateState(
            new Widget(
                List.copyOf(config.feeds()),
                state,
                config.scheduled(),
                config.childIds() == null ? List.of() : List.copyOf(config.childIds()),
                null))
        .thenReply(state);
  }

  public ReadOnlyEffect<Widget> get() {
    return effects().reply(currentState());
  }

  public Effect<WidgetState> apply(Applied applied) {
    if (applied.token() != null && applied.token().equals(currentState().lastRefreshToken())) {
      return effects().reply(currentState().state());
    }
    var next = currentState().state().applyOutcome(applied.outcome(), applied.completedAt());
    return effects()
        .updateState(
            new Widget(
                currentState().feeds(),
                next,
                currentState().scheduled(),
                currentState().childIds(),
                applied.token()))
        .thenReply(next);
  }
}
