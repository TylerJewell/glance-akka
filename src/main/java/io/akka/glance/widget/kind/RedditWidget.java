package io.akka.glance.widget.kind;

import io.akka.glance.config.ConfigException;
import io.akka.glance.config.ProxyOptions;
import io.akka.glance.config.QueryParameters;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.Endpoints;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.RedditAccess;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.util.Text;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.ForumPost;
import io.akka.glance.widget.Widget;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** One subreddit, or a search across one. */
public final class RedditWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template FORUM_POSTS_WIDGET_BASE = Templates.of("forum-posts.html", "widget-base.html");

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template REDDIT_HORIZONTAL_CARDS_WIDGET_BASE = Templates.of("reddit-horizontal-cards.html", "widget-base.html");

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template REDDIT_VERTICAL_CARDS_WIDGET_BASE = Templates.of("reddit-vertical-cards.html", "widget-base.html");

  @Y(skip = true)
  public List<ForumPost> Posts = new ArrayList<>();

  @Y("subreddit")
  public String Subreddit = "";

  @Y("proxy")
  public ProxyOptions Proxy = new ProxyOptions();

  @Y("style")
  public String Style = "";

  @Y("show-thumbnails")
  public boolean ShowThumbnails;

  @Y("show-flairs")
  public boolean ShowFlairs;

  @Y("sort-by")
  public String SortBy = "";

  @Y("top-period")
  public String TopPeriod = "";

  @Y("search")
  public String Search = "";

  @Y("extra-sort-by")
  public String ExtraSortBy = "";

  @Y("comments-url-template")
  public String CommentsURLTemplate = "";

  @Y("limit")
  public int Limit;

  @Y("collapse-after")
  public int CollapseAfter;

  @Y("request-url-template")
  public String RequestURLTemplate = "";

  @Y("app-auth")
  public AppAuth AppAuth = new AppAuth();

  /** A registered application's credentials, which lift reddit's limit on anonymous reads. */
  public static final class AppAuth {
    @Y("name")
    public String Name = "";

    @Y("id")
    public String ID = "";

    @Y("secret")
    public String Secret = "";

    @Y(skip = true)
    boolean enabled;

    @Y(skip = true)
    String accessToken = "";

    @Y(skip = true)
    Instant tokenExpiresAt = Instant.EPOCH;
  }

  @Override
  public void initialize() {
    if (Subreddit.isEmpty()) {
      throw new ConfigException("subreddit is required");
    }
    if (Limit <= 0) {
      Limit = 15;
    }
    if (CollapseAfter == 0 || CollapseAfter < -1) {
      CollapseAfter = 5;
    }
    if (!SortBy.equals("hot")
        && !SortBy.equals("new")
        && !SortBy.equals("top")
        && !SortBy.equals("rising")) {
      SortBy = "hot";
    }
    if (!TopPeriod.equals("hour")
        && !TopPeriod.equals("day")
        && !TopPeriod.equals("week")
        && !TopPeriod.equals("month")
        && !TopPeriod.equals("year")
        && !TopPeriod.equals("all")) {
      TopPeriod = "day";
    }
    if (!RequestURLTemplate.isEmpty() && !RequestURLTemplate.contains("{REQUEST-URL}")) {
      throw new ConfigException("no `{REQUEST-URL}` placeholder specified");
    }
    if (!AppAuth.Name.isEmpty() || !AppAuth.ID.isEmpty() || !AppAuth.Secret.isEmpty()) {
      if (AppAuth.Name.isEmpty() || AppAuth.ID.isEmpty() || AppAuth.Secret.isEmpty()) {
        throw new ConfigException("application name, client ID and client secret are required");
      }
      AppAuth.enabled = true;
    }
    withTitle("r/" + Subreddit)
        .withTitleURL("https://www.reddit.com/r/" + Subreddit + "/")
        .withCacheDuration(Duration.ofMinutes(30));
  }

  @Override
  public void update(Instant now) {
    var fetched = fetch(now);
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    var posts = fetched.value();
    if (posts.size() > Limit) {
      posts = new ArrayList<>(posts.subList(0, Limit));
    }
    if (ExtraSortBy.equals("engagement")) {
      ForumPost.calculateEngagement(posts, now);
      ForumPost.sortByEngagement(posts);
    }
    Posts = posts;
  }

  /** The address the posts are read from, before any request-url-template is applied. */
  public String requestUrl(String baseUrl) {
    var query = new TreeMap<String, String>();
    if (Limit > 25) {
      query.put("limit", String.valueOf(Limit));
    }
    if (!Search.isEmpty()) {
      query.put("q", Search + " subreddit:" + Subreddit);
      query.put("sort", SortBy);
      return baseUrl + "/search.json?" + encode(query);
    }
    if (SortBy.equals("top")) {
      query.put("t", TopPeriod);
    }
    return baseUrl + "/r/" + Subreddit + "/" + SortBy + ".json?" + encode(query);
  }

  private static String encode(TreeMap<String, String> query) {
    var out = new StringBuilder();
    for (var entry : query.entrySet()) {
      if (!out.isEmpty()) {
        out.append('&');
      }
      out.append(QueryParameters.encode(entry.getKey()))
          .append('=')
          .append(QueryParameters.encode(entry.getValue()));
    }
    return out.toString();
  }

  public String customCommentsUrl(String subreddit, String postId, String postPath) {
    return CommentsURLTemplate
        .replace("{SUBREDDIT}", subreddit)
        .replace("{POST-ID}", postId)
        .replace("{POST-PATH}", trimLeadingSlashes(postPath));
  }

  private static String trimLeadingSlashes(String value) {
    int start = 0;
    while (start < value.length() && value.charAt(start) == '/') {
      start++;
    }
    return value.substring(start);
  }

  private Fetched<List<ForumPost>> fetch(Instant now) {
    try {
      var client = HttpClients.standard();
      String baseUrl;
      var builder = HttpRequest.newBuilder().GET().timeout(HttpClients.DEFAULT_TIMEOUT);
      if (!AppAuth.enabled) {
        baseUrl = Endpoints.reddit;
        builder.header("User-Agent", HttpClients.browserUserAgent());
      } else {
        baseUrl = "https://oauth.reddit.com";
        if (AppAuth.accessToken.isEmpty()
            || now.plus(Duration.ofMinutes(1)).isAfter(AppAuth.tokenExpiresAt)) {
          try {
            fetchNewAppAccessToken(now);
          } catch (Fetches.FetchException e) {
            return Fetched.failed(
                Err.of("fetching new app access token: " + e.error().message()));
          }
        }
        builder.header("Authorization", "Bearer " + AppAuth.accessToken);
        builder.header("User-Agent", AppAuth.Name + "/1.0");
      }
      String url = requestUrl(baseUrl);
      if (!RequestURLTemplate.isEmpty()) {
        url = RequestURLTemplate.replace("{REQUEST-URL}", url);
      } else if (Proxy.isConfigured()) {
        client = Proxy.client();
      }
      String loid;
      try {
        loid = RedditAccess.loidCookie();
      } catch (RuntimeException e) {
        return Fetched.failed(Err.of("could not solve reddit challenge"));
      }
      builder.uri(URI.create(url)).header("Cookie", "loid=" + loid);
      var response = Requests.json(client, builder.build());
      var children = response.path("data").path("children");
      if (!children.isArray() || children.isEmpty()) {
        return Fetched.failed(Err.of("no posts found"));
      }
      var posts = new ArrayList<ForumPost>(children.size());
      for (var child : children) {
        var data = child.path("data");
        if (data.path("stickied").asBoolean() || data.path("pinned").asBoolean()) {
          continue;
        }
        var post = new ForumPost();
        String permalink = data.path("permalink").asText("");
        String id = data.path("id").asText("");
        post.Title = Text.unescapeHtml(data.path("title").asText(""));
        post.DiscussionUrl =
            CommentsURLTemplate.isEmpty()
                ? "https://www.reddit.com" + permalink
                : customCommentsUrl(Subreddit, id, permalink);
        post.TargetUrlDomain = data.path("domain").asText("");
        post.CommentCount = data.path("num_comments").asInt();
        post.Score = data.path("ups").asInt();
        post.TimePosted = Instant.ofEpochSecond((long) data.path("created").asDouble());
        String thumbnail = data.path("thumbnail").asText("");
        if (!thumbnail.isEmpty()
            && !thumbnail.equals("self")
            && !thumbnail.equals("default")
            && !thumbnail.equals("nsfw")) {
          post.ThumbnailUrl = Text.unescapeHtml(thumbnail);
        }
        if (!data.path("is_self").asBoolean()) {
          post.TargetUrl = data.path("url").asText("");
        }
        String flair = data.path("link_flair_text").asText("");
        if (ShowFlairs && !flair.isEmpty()) {
          post.Tags.add(flair);
        }
        var parents = data.path("crosspost_parent_list");
        if (parents.isArray() && !parents.isEmpty()) {
          var parent = parents.get(0);
          post.IsCrosspost = true;
          post.TargetUrlDomain = "r/" + parent.path("subreddit").asText("");
          post.TargetUrl =
              CommentsURLTemplate.isEmpty()
                  ? "https://www.reddit.com" + parent.path("permalink").asText("")
                  : customCommentsUrl(
                      parent.path("subreddit").asText(""),
                      parent.path("id").asText(""),
                      parent.path("permalink").asText(""));
        }
        posts.add(post);
      }
      return Fetched.of(posts);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(e.error());
    }
  }

  private void fetchNewAppAccessToken(Instant now) {
    var credentials =
        java.util.Base64.getEncoder()
            .encodeToString((AppAuth.ID + ":" + AppAuth.Secret).getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(URI.create(Endpoints.reddit + "/api/v1/access_token"))
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
            .timeout(HttpClients.DEFAULT_TIMEOUT)
            .header("Authorization", "Basic " + credentials)
            .header("User-Agent", AppAuth.Name + "/1.0")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build();
    var client = Proxy.isConfigured() ? Proxy.client() : HttpClients.standard();
    var response = Requests.json(client, request);
    AppAuth.accessToken = response.path("access_token").asText("");
    AppAuth.tokenExpiresAt = now.plusSeconds(response.path("expires_in").asLong());
  }

  @Override
  public Safe Render() {
    if (Style.equals("horizontal-cards")) {
      return renderTemplate(
          this, REDDIT_HORIZONTAL_CARDS_WIDGET_BASE);
    }
    if (Style.equals("vertical-cards")) {
      return renderTemplate(this, REDDIT_VERTICAL_CARDS_WIDGET_BASE);
    }
    return renderTemplate(this, FORUM_POSTS_WIDGET_BASE);
  }
}
