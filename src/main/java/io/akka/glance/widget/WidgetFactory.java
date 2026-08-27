package io.akka.glance.widget;

import io.akka.glance.config.ConfigException;
import io.akka.glance.widget.kind.BookmarksWidget;
import io.akka.glance.widget.kind.CalendarWidget;
import io.akka.glance.widget.kind.ChangeDetectionWidget;
import io.akka.glance.widget.kind.ClockWidget;
import io.akka.glance.widget.kind.CustomApiWidget;
import io.akka.glance.widget.kind.DnsStatsWidget;
import io.akka.glance.widget.kind.DockerContainersWidget;
import io.akka.glance.widget.kind.ExtensionWidget;
import io.akka.glance.widget.kind.GroupWidget;
import io.akka.glance.widget.kind.HackerNewsWidget;
import io.akka.glance.widget.kind.HtmlWidget;
import io.akka.glance.widget.kind.IframeWidget;
import io.akka.glance.widget.kind.LobstersWidget;
import io.akka.glance.widget.kind.MarketsWidget;
import io.akka.glance.widget.kind.MonitorWidget;
import io.akka.glance.widget.kind.OldCalendarWidget;
import io.akka.glance.widget.kind.RedditWidget;
import io.akka.glance.widget.kind.ReleasesWidget;
import io.akka.glance.widget.kind.RepositoryWidget;
import io.akka.glance.widget.kind.RssWidget;
import io.akka.glance.widget.kind.SearchWidget;
import io.akka.glance.widget.kind.ServerStatsWidget;
import io.akka.glance.widget.kind.SplitColumnWidget;
import io.akka.glance.widget.kind.TodoWidget;
import io.akka.glance.widget.kind.TwitchChannelsWidget;
import io.akka.glance.widget.kind.TwitchTopGamesWidget;
import io.akka.glance.widget.kind.VideosWidget;
import io.akka.glance.widget.kind.WeatherWidget;
import java.util.function.Supplier;

/** Turns the {@code type} in the file into the widget it names. */
public final class WidgetFactory {

  private WidgetFactory() {}

  public static Widget create(String type) {
    if (type.isEmpty()) {
      throw new ConfigException("widget 'type' property is empty or not specified");
    }
    Supplier<Widget> supplier =
        switch (type) {
          case "calendar" -> CalendarWidget::new;
          case "calendar-legacy" -> OldCalendarWidget::new;
          case "clock" -> ClockWidget::new;
          case "weather" -> WeatherWidget::new;
          case "bookmarks" -> BookmarksWidget::new;
          case "iframe" -> IframeWidget::new;
          case "html" -> HtmlWidget::new;
          case "hacker-news" -> HackerNewsWidget::new;
          case "releases" -> ReleasesWidget::new;
          case "videos" -> VideosWidget::new;
          case "markets", "stocks" -> MarketsWidget::new;
          case "reddit" -> RedditWidget::new;
          case "rss" -> RssWidget::new;
          case "monitor" -> MonitorWidget::new;
          case "twitch-top-games" -> TwitchTopGamesWidget::new;
          case "twitch-channels" -> TwitchChannelsWidget::new;
          case "lobsters" -> LobstersWidget::new;
          case "change-detection" -> ChangeDetectionWidget::new;
          case "repository" -> RepositoryWidget::new;
          case "search" -> SearchWidget::new;
          case "extension" -> ExtensionWidget::new;
          case "group" -> GroupWidget::new;
          case "dns-stats" -> DnsStatsWidget::new;
          case "split-column" -> SplitColumnWidget::new;
          case "custom-api" -> CustomApiWidget::new;
          case "docker-containers" -> DockerContainersWidget::new;
          case "server-stats" -> ServerStatsWidget::new;
          case "to-do" -> TodoWidget::new;
          default -> null;
        };
    if (supplier == null) {
      throw new ConfigException("unknown widget type: " + type);
    }
    return supplier.get();
  }
}
