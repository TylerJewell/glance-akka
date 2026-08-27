package io.akka.glance.widget.kind;

import io.akka.glance.config.ConfigException;
import io.akka.glance.config.QueryParameters;
import io.akka.glance.config.Y;
import io.akka.glance.config.Yaml;
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
import io.akka.glance.widget.Widget;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.yaml.snakeyaml.nodes.Node;

/** The newest release of each of several repositories, from four kinds of host. */
public final class ReleasesWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("releases.html", "widget-base.html");

  @Y(skip = true)
  public List<AppRelease> Releases = new ArrayList<>();

  @Y("repositories")
  public List<ReleaseRequest> Repositories = new ArrayList<>();

  @Y("token")
  public String Token = "";

  @Y("gitlab-token")
  public String GitLabToken = "";

  @Y("limit")
  public int Limit;

  @Y("collapse-after")
  public int CollapseAfter;

  @Y("show-source-icon")
  public boolean ShowSourceIcon;

  /** One release, as the template shows it. */
  public static final class AppRelease {
    public String Source = "";
    public String SourceIconURL = "";
    public String Name = "";
    public String Version = "";
    public String NotesUrl = "";
    public Instant TimeReleased = Instant.EPOCH;
    public int Downvotes;
  }

  /**
   * One repository to watch, written either as a name or as a mapping.
   *
   * <p>The host is a prefix on the name — {@code gitlab:group/project} — and github when
   * there is none.
   */
  public static final class ReleaseRequest implements Yaml.Decodable {

    @Y("include-prereleases")
    public boolean IncludePrereleases;

    @Y("repository")
    public String Repository = "";

    @Y(skip = true)
    String source = "github";

    @Y(skip = true)
    String token;

    @Override
    public void decode(Node node) {
      String repository = "";
      if (Yaml.isScalar(node)) {
        repository = Yaml.scalar(node);
      } else {
        Yaml.decodeInto(node, this);
      }
      if (Repository.isEmpty()) {
        if (repository.isEmpty()) {
          throw new ConfigException("repository is required");
        }
        Repository = repository;
      }
      // The prefix is read from what the scalar form carried, which is why a mapping
      // form always names a github repository.
      int colon = repository.indexOf(':');
      if (colon < 0) {
        source = "github";
        return;
      }
      Repository = repository.substring(colon + 1);
      source = repository.substring(0, colon);
      if (!source.equals("github")
          && !source.equals("gitlab")
          && !source.equals("dockerhub")
          && !source.equals("codeberg")) {
        throw new ConfigException("invalid source");
      }
    }
  }

  @Override
  public void initialize() {
    withTitle("Releases").withCacheDuration(Duration.ofHours(2));
    if (Limit <= 0) {
      Limit = 10;
    }
    if (CollapseAfter == 0 || CollapseAfter < -1) {
      CollapseAfter = 5;
    }
    for (var repository : Repositories) {
      if (repository.source.equals("github") && !Token.isEmpty()) {
        repository.token = Token;
      } else if (repository.source.equals("gitlab") && !GitLabToken.isEmpty()) {
        repository.token = GitLabToken;
      }
    }
  }

  @Override
  public void update(Instant now) {
    var fetched = fetch(now);
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    var releases = fetched.value();
    if (releases.size() > Limit) {
      releases = new ArrayList<>(releases.subList(0, Limit));
    }
    for (var release : releases) {
      release.SourceIconURL =
          Providers.assetResolver().apply("icons/" + release.Source + ".svg");
    }
    Releases = releases;
  }

  private Fetched<List<AppRelease>> fetch(Instant now) {
    var results = Fetches.pool(Repositories, 20, request -> fetchOne(request, now));
    int failed = 0;
    var releases = new ArrayList<AppRelease>(Repositories.size());
    for (var result : results) {
      if (result.error() != null) {
        failed++;
        continue;
      }
      releases.add(result.value());
    }
    if (failed == Repositories.size()) {
      return Fetched.failed(Err.NO_CONTENT);
    }
    releases.sort(Comparator.comparing((AppRelease release) -> release.TimeReleased).reversed());
    if (failed > 0) {
      return Fetched.of(
          releases, Err.PARTIAL_CONTENT.because("could not get " + failed + " releases"));
    }
    return Fetched.of(releases);
  }

  private AppRelease fetchOne(ReleaseRequest request, Instant now) {
    return switch (request.source) {
      case "codeberg" -> fromCodeberg(request, now);
      case "github" -> fromGithub(request, now);
      case "gitlab" -> fromGitlab(request, now);
      case "dockerhub" -> fromDockerHub(request, now);
      default -> throw new Fetches.FetchException("unsupported source");
    };
  }

  private AppRelease fromGithub(ReleaseRequest request, Instant now) {
    String url =
        request.IncludePrereleases
            ? Endpoints.github + "/repos/" + request.Repository + "/releases"
            : Endpoints.github + "/repos/" + request.Repository + "/releases/latest";
    var builder = Requests.get(url);
    if (request.token != null) {
      builder.header("Authorization", "Bearer " + request.token);
    }
    var response = Requests.json(HttpClients.standard(), builder.build());
    if (request.IncludePrereleases) {
      if (!response.isArray() || response.isEmpty()) {
        throw new Fetches.FetchException(
            "no releases found for repository " + request.Repository);
      }
      response = response.get(0);
    }
    var release = new AppRelease();
    release.Source = "github";
    release.Name = request.Repository;
    release.Version = Text.normalizeVersionFormat(response.path("tag_name").asText(""));
    release.NotesUrl = response.path("html_url").asText("");
    release.TimeReleased = Text.parseRfc3339(response.path("published_at").asText(""), now);
    release.Downvotes = response.path("reactions").path("-1").asInt();
    return release;
  }

  private AppRelease fromGitlab(ReleaseRequest request, Instant now) {
    var builder =
        Requests.get(
            Endpoints.gitlab
                + "/api/v4/projects/"
                + QueryParameters.encode(request.Repository)
                + "/releases/permalink/latest");
    if (request.token != null) {
      builder.header("PRIVATE-TOKEN", request.token);
    }
    var response = Requests.json(HttpClients.standard(), builder.build());
    var release = new AppRelease();
    release.Source = "gitlab";
    release.Name = request.Repository;
    release.Version = Text.normalizeVersionFormat(response.path("tag_name").asText(""));
    release.NotesUrl = response.path("_links").path("self").asText("");
    release.TimeReleased = Text.parseRfc3339(response.path("released_at").asText(""), now);
    return release;
  }

  private AppRelease fromCodeberg(ReleaseRequest request, Instant now) {
    var response =
        Requests.json(
            HttpClients.standard(),
            Requests.get(
                    Endpoints.codeberg
                        + "/api/v1/repos/"
                        + request.Repository
                        + "/releases/latest")
                .build());
    var release = new AppRelease();
    release.Source = "codeberg";
    release.Name = request.Repository;
    release.Version = Text.normalizeVersionFormat(response.path("tag_name").asText(""));
    release.NotesUrl = response.path("html_url").asText("");
    release.TimeReleased = Text.parseRfc3339(response.path("published_at").asText(""), now);
    return release;
  }

  private AppRelease fromDockerHub(ReleaseRequest request, Instant now) {
    var nameParts = request.Repository.split("/", -1);
    if (nameParts.length > 2) {
      throw new Fetches.FetchException("invalid repository name: " + request.Repository);
    }
    if (nameParts.length == 1) {
      nameParts = new String[] {"library", nameParts[0]};
    }
    var tagParts = nameParts[1].split(":", 2);
    String url =
        tagParts.length == 2
            ? Endpoints.dockerHub
                + "/v2/namespaces/"
                + nameParts[0]
                + "/repositories/"
                + tagParts[0]
                + "/tags/"
                + tagParts[1]
            : Endpoints.dockerHub
                + "/v2/namespaces/"
                + nameParts[0]
                + "/repositories/"
                + nameParts[1]
                + "/tags";
    var builder = Requests.get(url);
    if (request.token != null) {
      builder.header("Authorization", "Bearer " + request.token);
    }
    HttpRequest built = builder.build();
    var response = Requests.json(HttpClients.standard(), built);
    String tagName;
    String lastPushed;
    if (tagParts.length == 1) {
      var results = response.path("results");
      if (!results.isArray() || results.isEmpty()) {
        throw new Fetches.FetchException("no tags found for repository: " + request.Repository);
      }
      tagName = results.get(0).path("name").asText("");
      lastPushed = results.get(0).path("tag_last_pushed").asText("");
    } else {
      tagName = response.path("name").asText("");
      lastPushed = response.path("tag_last_pushed").asText("");
    }
    String repo = tagParts.length == 1 ? nameParts[1] : tagParts[0];
    String displayName;
    String notesUrl;
    if (nameParts[0].equals("library")) {
      displayName = repo;
      notesUrl = "https://hub.docker.com/_/" + repo + "/tags?name=" + tagName;
    } else {
      displayName = nameParts[0] + "/" + repo;
      notesUrl = "https://hub.docker.com/r/" + displayName + "/tags?name=" + tagName;
    }
    var release = new AppRelease();
    release.Source = "dockerhub";
    release.NotesUrl = notesUrl;
    release.Name = displayName;
    release.Version = tagName;
    release.TimeReleased = Text.parseRfc3339(lastPushed, now);
    return release;
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
