package io.akka.glance.widget.kind;

import io.akka.glance.config.ConfigException;
import io.akka.glance.config.QueryParameters;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.gotemplate.TemplateException;
import io.akka.glance.render.CustomApiFuncs;
import io.akka.glance.render.Templates;
import io.akka.glance.util.JsonResult;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * A widget whose content is a template over somebody else's JSON.
 *
 * <p>The template is the user's own and is executed by the same engine that renders the
 * original's pages, with a further set of functions for reading the response and shaping what
 * comes out of it.
 */
public final class CustomApiWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("custom-api.html", "widget-base.html");

  @Y(inline = true)
  public CustomApiFuncs.Request Request = new CustomApiFuncs.Request();

  @Y("subrequests")
  public Map<String, CustomApiFuncs.Request> Subrequests = new LinkedHashMap<>();

  @Y("options")
  public Map<String, Object> Options = new LinkedHashMap<>();

  @Y("template")
  public String TemplateSource = "";

  @Y("frameless")
  public boolean Frameless;

  @Y(skip = true)
  private Template compiledTemplate;

  @Y(skip = true)
  public Safe CompiledHTML = Safe.html("");

  @Override
  public void initialize() {
    withTitle("Custom API").withCacheDuration(Duration.ofHours(1));
    try {
      Request.initialize();
    } catch (ConfigException e) {
      throw new ConfigException("initializing primary request: " + e.getMessage());
    }
    for (var entry : Subrequests.entrySet()) {
      try {
        entry.getValue().initialize();
      } catch (ConfigException e) {
        throw new ConfigException(
            "initializing subrequest \"" + entry.getKey() + "\": " + e.getMessage());
      }
    }
    if (TemplateSource.isEmpty()) {
      throw new ConfigException("template is required");
    }
    try {
      compiledTemplate = Template.parse(CustomApiFuncs.functions(), "", TemplateSource);
    } catch (TemplateException e) {
      throw new ConfigException("parsing template: " + e.getMessage());
    }
  }

  @Override
  public void update(Instant now) {
    var fetched = render();
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    CompiledHTML = fetched.value();
  }

  private Fetched<Safe> render() {
    CustomApiFuncs.ResponseData primary;
    var subData = new LinkedHashMap<String, CustomApiFuncs.ResponseData>();
    if (Subrequests.isEmpty()) {
      try {
        primary = CustomApiFuncs.fetch(Request);
      } catch (Fetches.FetchException e) {
        return Fetched.failed(e.error());
      }
    } else {
      // Every request runs at once and the first failure is the widget's answer.
      var running = new ArrayList<Future<CustomApiFuncs.ResponseData>>();
      var keys = new ArrayList<String>();
      running.add(Fetches.submitCall(() -> CustomApiFuncs.fetch(Request)));
      keys.add(null);
      for (var entry : Subrequests.entrySet()) {
        keys.add(entry.getKey());
        running.add(Fetches.submitCall(() -> CustomApiFuncs.fetch(entry.getValue())));
      }
      Err failure = null;
      var results = new ArrayList<CustomApiFuncs.ResponseData>();
      for (var future : running) {
        var outcome = Fetches.await(future);
        results.add(outcome.value());
        if (outcome.error() != null && failure == null) {
          failure = outcome.error();
        }
      }
      if (failure != null) {
        return Fetched.failed(failure);
      }
      primary = results.getFirst();
      for (int i = 1; i < keys.size(); i++) {
        subData.put(keys.get(i), results.get(i));
      }
    }
    var data = new CustomApiFuncs.TemplateData(primary, subData, Options);
    try {
      return Fetched.of(Safe.html(compiledTemplate.execute(data)));
    } catch (TemplateException e) {
      return Fetched.failed(Err.of(e.getMessage()));
    }
  }

  /** What the template's own {@code .JSON} reads. */
  public JsonResult json() {
    return new JsonResult(null);
  }

  /** The named parameters a template may consult; a list keeps the reader's order. */
  public List<String> optionKeys() {
    return List.copyOf(Options.keySet());
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
