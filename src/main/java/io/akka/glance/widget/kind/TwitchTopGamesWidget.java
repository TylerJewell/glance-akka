package io.akka.glance.widget.kind;

import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.Endpoints;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** What is being watched most on Twitch. */
public final class TwitchTopGamesWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("twitch-games-list.html", "widget-base.html");

  private static final String REQUEST_BODY =
      """
      [
      {"operationName": "BrowsePage_AllDirectories","variables": {"limit": %d,"options": {"sort": "VIEWER_COUNT","tags": []}},"extensions": {"persistedQuery": {"version": 1,"sha256Hash": "2f67f71ba89f3c0ed26a141ec00da1defecb2303595f5cda4298169549783d9e"}}}
      ]""";

  /** How recently released a game has to be to be marked as new. */
  private static final Duration NEW_FOR = Duration.ofDays(14);

  @Y(skip = true)
  public List<Category> Categories = new ArrayList<>();

  @Y("exclude")
  public List<String> Exclude = new ArrayList<>();

  @Y("limit")
  public int Limit;

  @Y("collapse-after")
  public int CollapseAfter;

  /** One game. */
  public static final class Category {
    public String Slug = "";
    public String Name = "";
    public String AvatarUrl = "";
    public int ViewersCount;
    public List<Tag> Tags = new ArrayList<>();
    public String GameReleaseDate = "";
    public boolean IsNew;
  }

  /** One of the labels a game carries. */
  public static final class Tag {
    public String Name = "";
  }

  @Override
  public void initialize() {
    withTitle("Top games on Twitch")
        .withTitleURL("https://www.twitch.tv/directory?sort=VIEWER_COUNT")
        .withCacheDuration(Duration.ofMinutes(10));
    if (Limit <= 0) {
      Limit = 10;
    }
    if (CollapseAfter == 0 || CollapseAfter < -1) {
      CollapseAfter = 5;
    }
  }

  @Override
  public void update(Instant now) {
    var fetched = fetch(now);
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    Categories = fetched.value();
  }

  private Fetched<List<Category>> fetch(Instant now) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(Endpoints.twitchGql))
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      REQUEST_BODY.formatted(Exclude.size() + Limit)))
              .timeout(HttpClients.DEFAULT_TIMEOUT)
              .header("Client-ID", Endpoints.TWITCH_GQL_CLIENT_ID)
              .build();
      var response = Requests.json(HttpClients.standard(), request);
      if (!response.isArray() || response.isEmpty()) {
        return Fetched.failed(Err.of("no categories could be retrieved"));
      }
      var edges = response.get(0).path("data").path("directoriesWithTags").path("edges");
      var categories = new ArrayList<Category>();
      for (var edge : edges) {
        var node = edge.path("node");
        String slug = node.path("slug").asText("");
        if (Exclude.contains(slug)) {
          continue;
        }
        var category = new Category();
        category.Slug = slug;
        category.Name = node.path("name").asText("");
        // The larger art is what the directory returns; the smaller one is what is shown.
        category.AvatarUrl = node.path("avatarURL").asText("").replaceFirst("285x380", "144x192");
        category.ViewersCount = node.path("viewersCount").asInt();
        for (var tagNode : node.path("tags")) {
          if (category.Tags.size() == 2) {
            break;
          }
          var tag = new Tag();
          tag.Name = tagNode.path("tagName").asText("");
          category.Tags.add(tag);
        }
        category.GameReleaseDate = node.path("originalReleaseDate").asText("");
        try {
          var released = Instant.parse(category.GameReleaseDate);
          category.IsNew = Duration.between(released, now).compareTo(NEW_FOR) < 0;
        } catch (RuntimeException e) {
          // A game with no readable release date is not new.
        }
        categories.add(category);
      }
      if (categories.size() > Limit) {
        categories = new ArrayList<>(categories.subList(0, Limit));
      }
      return Fetched.of(categories);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(e.error());
    }
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
