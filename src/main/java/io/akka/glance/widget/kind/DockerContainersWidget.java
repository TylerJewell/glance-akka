package io.akka.glance.widget.kind;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.glance.config.CustomIcon;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.net.UnixSocketHttp;
import io.akka.glance.render.Templates;
import io.akka.glance.util.Text;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What is running on a Docker daemon, as its containers' own labels describe them. */
public final class DockerContainersWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("docker-containers.html", "widget-base.html");

  private static final String LABEL_HIDE = "glance.hide";
  private static final String LABEL_NAME = "glance.name";
  private static final String LABEL_URL = "glance.url";
  private static final String LABEL_DESCRIPTION = "glance.description";
  private static final String LABEL_SAME_TAB = "glance.same-tab";
  private static final String LABEL_ICON = "glance.icon";
  private static final String LABEL_ID = "glance.id";
  private static final String LABEL_PARENT = "glance.parent";
  private static final String LABEL_CATEGORY = "glance.category";

  private static final String ICON_OK = "ok";
  private static final String ICON_PAUSED = "paused";
  private static final String ICON_WARN = "warn";
  private static final String ICON_OTHER = "other";

  /** Which state a list is sorted by first: the ones wanting attention come first. */
  private static final Map<String, Integer> ICON_PRIORITY =
      Map.of(ICON_WARN, 0, ICON_OTHER, 1, ICON_PAUSED, 2, ICON_OK, 3);

  @Y("hide-by-default")
  public boolean HideByDefault;

  @Y("running-only")
  public boolean RunningOnly;

  @Y("category")
  public String Category = "";

  @Y("sock-path")
  public String SockPath = "";

  @Y("format-container-names")
  public boolean FormatContainerNames;

  @Y(skip = true)
  public List<Container> Containers = new ArrayList<>();

  @Y("containers")
  public Map<String, Map<String, String>> LabelOverrides = new LinkedHashMap<>();

  /** One container, as the widget shows it. */
  public static final class Container {
    public String Name = "";
    public String URL = "";
    public boolean SameTab;
    public String Image = "";
    public String State = "";
    public String StateText = "";
    public String StateIcon = "";
    public String Description = "";
    public CustomIcon Icon = new CustomIcon();
    public List<Container> Children = new ArrayList<>();
  }

  /** One container as the daemon describes it. */
  private record Raw(List<String> names, String image, String state, String status,
      Map<String, String> labels) {

    String label(String name, String fallback) {
      var value = labels.get(name);
      return value == null || value.isEmpty() ? fallback : value;
    }
  }

  @Override
  public void initialize() {
    withTitle("Docker Containers").withCacheDuration(Duration.ofMinutes(1));
    if (SockPath.isEmpty()) {
      SockPath = "/var/run/docker.sock";
    }
  }

  @Override
  public void update(Instant now) {
    var fetched = fetch();
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    var containers = fetched.value();
    sortByStateIconThenName(containers);
    Containers = containers;
  }

  private Fetched<List<Container>> fetch() {
    List<Raw> raw;
    try {
      raw = fromSource();
    } catch (Fetches.FetchException e) {
      return Fetched.failed(Err.of("fetching containers: " + e.error()));
    }
    var parents = new ArrayList<Raw>(raw.size());
    var children = new LinkedHashMap<String, List<Raw>>();
    for (var container : raw) {
      if (isHidden(container)) {
        continue;
      }
      boolean isParent = !container.label(LABEL_ID, "").isEmpty();
      String parent = container.label(LABEL_PARENT, "");
      if (!isParent && !parent.isEmpty()) {
        children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(container);
      } else {
        parents.add(container);
      }
    }
    var out = new ArrayList<Container>(parents.size());
    for (var container : parents) {
      var shown = new Container();
      shown.Name = deriveName(container);
      shown.URL = container.label(LABEL_URL, "");
      shown.Description = container.label(LABEL_DESCRIPTION, "");
      shown.SameTab = Text.stringToBool(container.label(LABEL_SAME_TAB, "false"));
      shown.Image = container.image();
      shown.State = container.state().toLowerCase(Locale.ROOT);
      shown.StateText = container.status().toLowerCase(Locale.ROOT);
      shown.Icon = CustomIcon.of(container.label(LABEL_ICON, "si:docker"));
      String id = container.label(LABEL_ID, "");
      if (!id.isEmpty() && children.containsKey(id)) {
        for (var child : children.get(id)) {
          var shownChild = new Container();
          shownChild.Name = deriveName(child);
          shownChild.StateText = child.status();
          shownChild.StateIcon = stateIcon(child);
          shown.Children.add(shownChild);
        }
      }
      sortByStateIconThenName(shown.Children);
      // A child wanting attention raises the parent's own mark.
      String icon = null;
      for (var child : shown.Children) {
        if (child.StateIcon.equals(ICON_WARN)) {
          icon = ICON_WARN;
          break;
        }
      }
      shown.StateIcon = icon != null ? icon : stateIcon(container);
      out.add(shown);
    }
    return Fetched.of(out);
  }

  private boolean isHidden(Raw container) {
    String value = container.label(LABEL_HIDE, "");
    return value.isEmpty() ? HideByDefault : Text.stringToBool(value);
  }

  private String deriveName(Raw container) {
    String labelled = container.label(LABEL_NAME, "");
    if (!labelled.isEmpty()) {
      return labelled;
    }
    if (container.names().isEmpty() || container.names().getFirst().isEmpty()) {
      return "n/a";
    }
    String name = trimLeading(container.names().getFirst(), '/');
    if (!FormatContainerNames) {
      return name;
    }
    name = name.replace('_', ' ').replace('-', ' ');
    var words = name.split(" ", -1);
    for (int i = 0; i < words.length; i++) {
      if (!words[i].isEmpty()) {
        words[i] = words[i].substring(0, 1).toUpperCase(Locale.ROOT) + words[i].substring(1);
      }
    }
    return String.join(" ", words);
  }

  private static String trimLeading(String value, char cut) {
    int start = 0;
    while (start < value.length() && value.charAt(start) == cut) {
      start++;
    }
    return value.substring(start);
  }

  static String stateIcon(Raw container) {
    if (container.status().toLowerCase(Locale.ROOT).contains("(unhealthy)")) {
      return ICON_WARN;
    }
    return switch (container.state().toLowerCase(Locale.ROOT)) {
      case "running" -> ICON_OK;
      case "paused" -> ICON_PAUSED;
      case "exited", "dead" -> ICON_WARN;
      default -> ICON_OTHER;
    };
  }

  static void sortByStateIconThenName(List<Container> containers) {
    containers.sort(
        Comparator.comparingInt((Container container) -> ICON_PRIORITY.getOrDefault(container.StateIcon, 0))
            .thenComparing(container -> container.Name.toLowerCase(Locale.ROOT)));
  }

  private List<Raw> fromSource() {
    String body;
    String all = RunningOnly ? "false" : "true";
    String path = "/containers/json?all=" + all;
    if (SockPath.startsWith("tcp://")
        || SockPath.startsWith("http://")
        || SockPath.startsWith("https://")) {
      URI parsed;
      try {
        parsed = URI.create(SockPath);
      } catch (IllegalArgumentException e) {
        throw new Fetches.FetchException("parsing URL: " + e.getMessage());
      }
      String scheme = parsed.getScheme().equals("tcp") ? "http" : parsed.getScheme();
      int port = parsed.getPort();
      if (port < 0) {
        port = scheme.equals("https") ? 443 : 80;
      }
      var response =
          Requests.sendRaw(
              HttpClients.standard(),
              Requests.get(
                      scheme + "://" + parsed.getHost() + ":" + port + path,
                      Duration.ofSeconds(5))
                  .build());
      if (response.statusCode() != 200) {
        throw new Fetches.FetchException("non-200 response status: " + response.statusCode());
      }
      body = response.body();
    } else {
      try {
        var response = UnixSocketHttp.get(SockPath, path, Duration.ofSeconds(5));
        if (response.status() != 200) {
          throw new Fetches.FetchException("non-200 response status: " + response.statusLine());
        }
        body = response.body();
      } catch (IOException e) {
        throw new Fetches.FetchException("sending request to socket: " + e.getMessage());
      }
    }
    JsonNode parsed = Requests.parse(body);
    var containers = new ArrayList<Raw>();
    for (var node : parsed) {
      var names = new ArrayList<String>();
      for (var name : node.path("Names")) {
        names.add(name.asText());
      }
      var labels = new LinkedHashMap<String, String>();
      node.path("Labels")
          .fields()
          .forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText("")));
      var container =
          new Raw(
              names,
              node.path("Image").asText(""),
              node.path("State").asText(""),
              node.path("Status").asText(""),
              labels);
      String name = names.isEmpty() ? "" : trimLeading(names.getFirst(), '/');
      if (!name.isEmpty()) {
        var overrides = LabelOverrides.get(name);
        if (overrides != null) {
          overrides.forEach((label, value) -> labels.put("glance." + label, value));
        }
      }
      containers.add(container);
    }
    // Filtering happens here rather than through the daemon's own filters because a label
    // may have been overridden by the configuration a moment ago.
    if (!Category.isEmpty()) {
      containers.removeIf(container -> !container.label(LABEL_CATEGORY, "").equals(Category));
    }
    return containers;
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
