package io.akka.glance.widget.kind;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.Endpoints;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.util.GoTime;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Which of a set of Twitch channels is live, and what each is showing. */
public final class TwitchChannelsWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("twitch-channels.html", "widget-base.html");

  /**
   * Two queries in one request: the channel's own details, and what it is streaming. Both are
   * asked for by the hash Twitch's own web player uses, which is what makes them answerable
   * without a key.
   */
  private static final String REQUEST_BODY =
      """
      [
      {"operationName":"ChannelShell","variables":{"login":"%s"},"extensions":{"persistedQuery":{"version":1,"sha256Hash":"580ab410bcd0c1ad194224957ae2241e5d252b2c5173d8e0cce9d32d5bb14efe"}}},
      {"operationName":"StreamMetadata","variables":{"channelLogin":"%s"},"extensions":{"persistedQuery":{"version":1,"sha256Hash":"676ee2f834ede42eb4514cdb432b3134fefc12590080c9a2c9bb44a2a4a63266"}}}
      ]""";

  @Y("channels")
  public List<String> ChannelsRequest = new ArrayList<>();

  @Y(skip = true)
  public List<Channel> Channels = new ArrayList<>();

  @Y("collapse-after")
  public int CollapseAfter;

  @Y("sort-by")
  public String SortBy = "";

  /** One channel. */
  public static final class Channel {
    public String Login = "";
    public boolean Exists;
    public String Name = "";
    public String StreamTitle = "";
    public String AvatarUrl = "";
    public boolean IsLive;
    public Instant LiveSince = GoTime.ZERO;
    public String Category = "";
    public String CategorySlug = "";
    public int ViewersCount;
  }

  @Override
  public void initialize() {
    withTitle("Twitch Channels")
        .withTitleURL("https://www.twitch.tv/directory/following")
        .withCacheDuration(Duration.ofMinutes(10));
    if (CollapseAfter == 0 || CollapseAfter < -1) {
      CollapseAfter = 5;
    }
    if (!SortBy.equals("viewers") && !SortBy.equals("live")) {
      SortBy = "viewers";
    }
  }

  @Override
  public void update(Instant now) {
    var fetched = fetch();
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    var channels = fetched.value();
    if (SortBy.equals("viewers")) {
      channels.sort(Comparator.comparingInt((Channel channel) -> channel.ViewersCount).reversed());
    } else if (SortBy.equals("live")) {
      channels.sort(Comparator.comparing((Channel channel) -> !channel.IsLive));
    }
    Channels = channels;
  }

  private Fetched<List<Channel>> fetch() {
    var results = Fetches.pool(ChannelsRequest, 10, TwitchChannelsWidget::fetchOne);
    var channels = new ArrayList<Channel>(ChannelsRequest.size());
    int failed = 0;
    for (var result : results) {
      if (result.error() != null) {
        failed++;
        continue;
      }
      channels.add(result.value());
    }
    if (failed == ChannelsRequest.size()) {
      return Fetched.of(channels, Err.NO_CONTENT);
    }
    if (failed > 0) {
      return Fetched.of(
          channels, Err.PARTIAL_CONTENT.because("failed to fetch " + failed + " channels"));
    }
    return Fetched.of(channels);
  }

  static Channel fetchOne(String login) {
    var channel = new Channel();
    channel.Login = login.toLowerCase(Locale.ROOT);
    var request =
        HttpRequest.newBuilder(URI.create(Endpoints.twitchGql))
            .POST(HttpRequest.BodyPublishers.ofString(REQUEST_BODY.formatted(login, login)))
            .timeout(HttpClients.DEFAULT_TIMEOUT)
            .header("Client-ID", Endpoints.TWITCH_GQL_CLIENT_ID)
            .build();
    var response = Requests.json(HttpClients.standard(), request);
    if (!response.isArray() || response.size() != 2) {
      throw new Fetches.FetchException(
          "expected 2 operation responses, got " + (response.isArray() ? response.size() : 0));
    }
    JsonNode shell = null;
    JsonNode metadata = null;
    for (var operation : response) {
      String name = operation.path("extensions").path("operationName").asText("");
      switch (name) {
        case "ChannelShell" -> shell = operation.path("Data").isMissingNode()
            ? operation.path("data")
            : operation.path("Data");
        case "StreamMetadata" -> metadata = operation.path("Data").isMissingNode()
            ? operation.path("data")
            : operation.path("Data");
        default -> throw new Fetches.FetchException("unknown operation name: " + name);
      }
    }
    var userOrError = shell == null ? null : shell.path("userOrError");
    if (userOrError == null || !userOrError.path("__typename").asText("").equals("User")) {
      channel.Name = channel.Login;
      return channel;
    }
    channel.Exists = true;
    channel.Name = userOrError.path("displayName").asText("");
    channel.AvatarUrl = userOrError.path("profileImageURL").asText("");
    var stream = userOrError.path("stream");
    if (!stream.isNull() && !stream.isMissingNode()) {
      channel.IsLive = true;
      channel.ViewersCount = stream.path("viewersCount").asInt();
      var user = metadata == null ? null : metadata.path("user");
      if (user != null && !user.isNull() && !user.isMissingNode()) {
        var liveStream = user.path("stream");
        if (!liveStream.isNull() && !liveStream.isMissingNode()) {
          var lastBroadcast = user.path("lastBroadcast");
          if (!lastBroadcast.isNull() && !lastBroadcast.isMissingNode()) {
            channel.StreamTitle = lastBroadcast.path("title").asText("");
          }
          var game = liveStream.path("game");
          if (!game.isNull() && !game.isMissingNode()) {
            channel.Category = game.path("name").asText("");
            channel.CategorySlug = game.path("slug").asText("");
          }
          try {
            channel.LiveSince = Instant.parse(liveStream.path("createdAt").asText(""));
          } catch (RuntimeException e) {
            // An unreadable start time leaves the channel's own zero instant in place.
          }
        }
      }
    } else {
      // A live channel with nobody watching must still sort above an offline one.
      channel.ViewersCount = -1;
    }
    return channel;
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
