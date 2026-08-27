package io.akka.glance.widget.kind;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.Endpoints;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
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

/** The front page of Hacker News. */
public final class HackerNewsWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("forum-posts.html", "widget-base.html");

  /** How many posts are fetched before the widget's own limit is applied. */
  private static final int FETCH_LIMIT = 40;

  @Y(skip = true)
  public List<ForumPost> Posts = new ArrayList<>();

  @Y("limit")
  public int Limit;

  @Y("sort-by")
  public String SortBy = "";

  @Y("extra-sort-by")
  public String ExtraSortBy = "";

  @Y("collapse-after")
  public int CollapseAfter;

  @Y("comments-url-template")
  public String CommentsUrlTemplate = "";

  @Y(skip = true)
  public boolean ShowThumbnails;

  @Override
  public void initialize() {
    withTitle("Hacker News")
        .withTitleURL("https://news.ycombinator.com/")
        .withCacheDuration(Duration.ofMinutes(30));
    if (Limit <= 0) {
      Limit = 15;
    }
    if (CollapseAfter == 0 || CollapseAfter < -1) {
      CollapseAfter = 5;
    }
    if (!SortBy.equals("top") && !SortBy.equals("new") && !SortBy.equals("best")) {
      SortBy = "top";
    }
  }

  @Override
  public void update(Instant now) {
    var fetched = fetch(SortBy, FETCH_LIMIT, CommentsUrlTemplate);
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    var posts = fetched.value();
    if (ExtraSortBy.equals("engagement")) {
      ForumPost.calculateEngagement(posts, now);
      ForumPost.sortByEngagement(posts);
    }
    if (Limit < posts.size()) {
      posts = new ArrayList<>(posts.subList(0, Limit));
    }
    Posts = posts;
  }

  private Fetched<List<ForumPost>> fetch(String sort, int limit, String template) {
    List<Long> ids;
    try {
      ids = fetchPostIds(sort);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(e.error());
    }
    if (ids.size() > limit) {
      ids = ids.subList(0, limit);
    }
    return fetchPostsFromIds(ids, template);
  }

  private List<Long> fetchPostIds(String sort) {
    JsonNode response;
    try {
      response =
          Requests.json(HttpClients.standard(), Requests.get(Endpoints.hackerNews + sort + "stories.json").build());
    } catch (Fetches.FetchException e) {
      throw new Fetches.FetchException(Err.NO_CONTENT.because("could not fetch list of post IDs"));
    }
    var out = new ArrayList<Long>();
    if (response != null && response.isArray()) {
      for (var item : response) {
        out.add(item.asLong());
      }
    }
    return out;
  }

  private Fetched<List<ForumPost>> fetchPostsFromIds(List<Long> ids, String template) {
    var results =
        Fetches.pool(
            ids,
            30,
            id ->
                Requests.json(
                    HttpClients.standard(), Requests.get(Endpoints.hackerNews + "item/" + id + ".json").build()));
    var posts = new ArrayList<ForumPost>(ids.size());
    for (var result : results) {
      if (result.error() != null || result.value() == null || result.value().isNull()) {
        continue;
      }
      posts.add(toPost(result.value(), template));
    }
    if (posts.isEmpty()) {
      return Fetched.failed(Err.NO_CONTENT);
    }
    if (posts.size() != ids.size()) {
      return Fetched.of(
          posts, Err.PARTIAL_CONTENT.followedBy("could not fetch some hacker news posts"));
    }
    return Fetched.of(posts);
  }

  private static ForumPost toPost(JsonNode node, String template) {
    var post = new ForumPost();
    long id = node.path("id").asLong();
    post.Title = node.path("title").asText("");
    post.DiscussionUrl =
        template.isEmpty()
            ? "https://news.ycombinator.com/item?id=" + id
            : template.replace("{POST-ID}", String.valueOf(id));
    post.TargetUrl = node.path("url").asText("");
    post.TargetUrlDomain = Text.extractDomainFromUrl(post.TargetUrl);
    post.CommentCount = node.path("descendants").asInt();
    post.Score = node.path("score").asInt();
    post.TimePosted = Instant.ofEpochSecond(node.path("time").asLong());
    return post;
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
