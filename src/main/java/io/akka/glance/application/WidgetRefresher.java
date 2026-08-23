package io.akka.glance.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A widget's own refresh loop. SPEC-001 D-2.
 *
 * <p>The source refreshes a widget only when somebody asks for the page, so a widget's
 * deadline is a thing that gets *noticed* rather than a thing that fires. A page that is
 * already open therefore never sees a refresh. This books the deadline instead, so the state
 * behind an open page moves and the stream has something to send.
 *
 * <p>The timer goes under the widget's own name, so re-booking replaces the pending firing
 * rather than adding to it (question-log row 23), and a refresh whose next deadline is
 * already behind now is booked with a delay in the past, which the runtime accepts and fires
 * promptly (row 25) — the shape a no-cache widget needs.
 */
@Component(id = "widget-refresher")
public class WidgetRefresher extends TimedAction {

  private final PageRefresh pageRefresh;
  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;

  public WidgetRefresher(
      PageRefresh pageRefresh, ComponentClient componentClient, TimerScheduler timerScheduler) {
    this.pageRefresh = pageRefresh;
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
  }

  public static String timerName(String widgetId) {
    return "widget-refresh-" + widgetId;
  }

  /**
   * @param booking the widget, and the token minted when this firing was booked. A
   *     redelivery carries the same token, which is what stops one failed attempt
   *     advancing the backoff twice.
   */
  public record Booking(String widgetId, String token) {}

  public Effect refresh(Booking booking) {
    var state = pageRefresh.refreshNow(booking.widgetId(), Instant.now(), booking.token());
    if (state.nextUpdate() != null) {
      book(booking.widgetId(), Duration.between(Instant.now(), state.nextUpdate()));
    }
    return effects().done();
  }

  private void book(String widgetId, Duration delay) {
    timerScheduler.createSingleTimer(
        timerName(widgetId),
        delay,
        componentClient
            .forTimedAction()
            .method(WidgetRefresher::refresh)
            .deferred(new Booking(widgetId, UUID.randomUUID().toString())));
  }
}
