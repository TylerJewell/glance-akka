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
import io.akka.glance.widget.Widget;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/** One GitHub repository: its counts, its open tickets and its last commits. */
public final class RepositoryWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("repository.html", "widget-base.html");

  @Y("repository")
  public String RequestedRepository = "";

  @Y("token")
  public String Token = "";

  @Y("pull-requests-limit")
  public int PullRequestsLimit;

  @Y("issues-limit")
  public int IssuesLimit;

  @Y("commits-limit")
  public int CommitsLimit;

  @Y(skip = true)
  public Repository Repository = new Repository();

  /** What the template shows. */
  public static final class Repository {
    public String Name = "";
    public int Stars;
    public int Forks;
    public int OpenPullRequests;
    public List<Ticket> PullRequests = new ArrayList<>();
    public int OpenIssues;
    public List<Ticket> Issues = new ArrayList<>();
    public int LastCommits;
    public List<Commit> Commits = new ArrayList<>();
  }

  /** One open issue or pull request. */
  public static final class Ticket {
    public int Number;
    public Instant CreatedAt = Instant.EPOCH;
    public String Title = "";
  }

  /** One commit. */
  public static final class Commit {
    public String Sha = "";
    public String Author = "";
    public Instant CreatedAt = Instant.EPOCH;
    public String Message = "";
  }

  @Override
  public void initialize() {
    withTitle("Repository").withCacheDuration(Duration.ofHours(1));
    if (PullRequestsLimit == 0 || PullRequestsLimit < -1) {
      PullRequestsLimit = 3;
    }
    if (IssuesLimit == 0 || IssuesLimit < -1) {
      IssuesLimit = 3;
    }
    if (CommitsLimit == 0 || CommitsLimit < -1) {
      CommitsLimit = -1;
    }
  }

  @Override
  public void update(Instant now) {
    var fetched = fetch(now);
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    Repository = fetched.value();
  }

  private HttpRequest.Builder authorised(String url) {
    var builder = Requests.get(url);
    if (!Token.isEmpty()) {
      builder.header("Authorization", "Bearer " + Token);
    }
    return builder;
  }

  private Fetched<Repository> fetch(Instant now) {
    String repo = RequestedRepository;
    Future<JsonNode> details =
        Fetches.submitCall(
            () -> Requests.json(HttpClients.standard(), authorised(Endpoints.github + "/repos/" + repo).build()));
    Future<JsonNode> pulls =
        PullRequestsLimit > 0
            ? Fetches.submitCall(
                () ->
                    Requests.json(
                        HttpClients.standard(),
                        authorised(
                                Endpoints.github
                                    + "/search/issues?q=is:pr+is:open+repo:"
                                    + repo
                                    + "&per_page="
                                    + PullRequestsLimit)
                            .build()))
            : null;
    Future<JsonNode> issues =
        IssuesLimit > 0
            ? Fetches.submitCall(
                () ->
                    Requests.json(
                        HttpClients.standard(),
                        authorised(
                                Endpoints.github
                                    + "/search/issues?q=is:issue+is:open+repo:"
                                    + repo
                                    + "&per_page="
                                    + IssuesLimit)
                            .build()))
            : null;
    Future<JsonNode> commits =
        CommitsLimit > 0
            ? Fetches.submitCall(
                () ->
                    Requests.json(
                        HttpClients.standard(),
                        authorised(
                                Endpoints.github
                                    + "/repos/"
                                    + repo
                                    + "/commits?per_page="
                                    + CommitsLimit)
                            .build()))
            : null;

    var detailsResult = Fetches.await(details);
    if (detailsResult.error() != null) {
      return Fetched.failed(
          Err.NO_CONTENT.because("could not get repository details: " + detailsResult.error()));
    }
    var repository = new Repository();
    repository.Name = detailsResult.value().path("full_name").asText("");
    repository.Stars = detailsResult.value().path("stargazers_count").asInt();
    repository.Forks = detailsResult.value().path("forks_count").asInt();

    Err error = null;
    if (pulls != null) {
      var result = Fetches.await(pulls);
      if (result.error() != null) {
        error = Err.PARTIAL_CONTENT.because("could not get PRs: " + result.error());
      } else {
        repository.OpenPullRequests = result.value().path("total_count").asInt();
        repository.PullRequests = tickets(result.value(), now);
      }
    }
    if (issues != null) {
      var result = Fetches.await(issues);
      if (result.error() != null) {
        // The original keeps only the last of these, so a failure here replaces one above.
        error = Err.PARTIAL_CONTENT.because("could not get issues: " + result.error());
      } else {
        repository.OpenIssues = result.value().path("total_count").asInt();
        repository.Issues = tickets(result.value(), now);
      }
    }
    if (commits != null) {
      var result = Fetches.await(commits);
      if (result.error() != null) {
        error = Err.PARTIAL_CONTENT.because("could not get commits: " + result.error());
      } else {
        for (var node : result.value()) {
          var commit = new Commit();
          commit.Sha = node.path("sha").asText("");
          commit.Author = node.path("commit").path("author").path("name").asText("");
          commit.CreatedAt =
              Text.parseRfc3339(node.path("commit").path("author").path("date").asText(""), now);
          commit.Message = node.path("commit").path("message").asText("").split("\n\n", 2)[0];
          repository.Commits.add(commit);
        }
      }
    }
    return Fetched.of(repository, error);
  }

  private static List<Ticket> tickets(JsonNode response, Instant now) {
    var out = new ArrayList<Ticket>();
    for (var node : response.path("items")) {
      var ticket = new Ticket();
      ticket.Number = node.path("number").asInt();
      ticket.CreatedAt = Text.parseRfc3339(node.path("created_at").asText(""), now);
      ticket.Title = node.path("title").asText("");
      out.add(ticket);
    }
    return out;
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
