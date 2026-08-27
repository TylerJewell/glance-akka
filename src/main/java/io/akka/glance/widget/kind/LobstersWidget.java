package io.akka.glance.widget.kind;

import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.Endpoints;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.util.GoTime;
import io.akka.glance.util.Text;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.ForumPost;
import io.akka.glance.widget.Widget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The front page of Lobsters, or of another instance, or of a tag on one. */
public final class LobstersWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("forum-posts.html", "widget-base.html");

  @Y(skip = true)
  public List<ForumPost> Posts = new ArrayList<>();

  @Y("instance-url")
  public String InstanceURL = "";

  @Y("custom-url")
  public String CustomURL = "";

  @Y("limit")
  public int Limit;

  @Y("collapse-after")
  public int CollapseAfter;

  @Y("sort-by")
  public String SortBy = "";

  @Y("tags")
  public List<String> Tags = new ArrayList<>();

  @Y(skip = true)
  public boolean ShowThumbnails;

  @Override
  public void initialize() {
    withTitle("Lobsters").withCacheDuration(Duration.ofHours(1));
    withTitleURL(InstanceURL.isEmpty() ? "https://lobste.rs" : InstanceURL);
    if (SortBy.isEmpty() || (!SortBy.equals("hot") && !SortBy.equals("new"))) {
      SortBy = "hot";
    }
    if (Limit <= 0) {
      Limit = 15;
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
    var posts = fetched.value();
    if (Limit < posts.size()) {
      posts = new ArrayList<>(posts.subList(0, Limit));
    }
    Posts = posts;
  }

  /** The address of the feed, which the tags and the sort between them decide. */
  public String feedUrl() {
    if (!CustomURL.isEmpty()) {
      return CustomURL;
    }
    String instance =
        InstanceURL.isEmpty()
            ? Endpoints.lobsters
            : trimTrailingSlashes(InstanceURL) + "/";
    if (!Tags.isEmpty()) {
      return instance + "t/" + String.join(",", Tags) + ".json";
    }
    String sort = SortBy.equals("hot") ? "hottest" : SortBy.equals("new") ? "newest" : SortBy;
    return instance + sort + ".json";
  }

  private static String trimTrailingSlashes(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }

  private Fetched<List<ForumPost>> fetch(Instant now) {
    try {
      var feed = Requests.json(HttpClients.standard(), Requests.get(feedUrl()).build());
      var posts = new ArrayList<ForumPost>();
      if (feed != null && feed.isArray()) {
        for (var node : feed) {
          var post = new ForumPost();
          post.Title = node.path("title").asText("");
          post.DiscussionUrl = node.path("comments_url").asText("");
          post.TargetUrl = node.path("url").asText("");
          post.TargetUrlDomain = Text.extractDomainFromUrl(post.TargetUrl);
          post.CommentCount = node.path("comment_count").asInt();
          post.Score = node.path("score").asInt();
          post.TimePosted = parseCreatedAt(node.path("created_at").asText(""));
          var tags = new ArrayList<String>();
          for (var tag : node.path("tags")) {
            tags.add(tag.asText());
          }
          post.Tags = tags;
          posts.add(post);
        }
      }
      if (posts.isEmpty()) {
        return Fetched.failed(Err.NO_CONTENT);
      }
      return Fetched.of(posts);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(e.error());
    }
  }

  /** An unreadable instant is the zero one here, not now: the parse error is discarded. */
  private static Instant parseCreatedAt(String value) {
    try {
      return java.time.OffsetDateTime.parse(value).toInstant();
    } catch (RuntimeException e) {
      return GoTime.ZERO;
    }
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
