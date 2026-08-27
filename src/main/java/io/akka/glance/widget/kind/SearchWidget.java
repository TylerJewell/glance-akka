package io.akka.glance.widget.kind;

import io.akka.glance.config.ConfigException;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.Widget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A search box, and any number of shortcuts that send the query somewhere else. */
public final class SearchWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("search.html", "widget-base.html");

  private static final Map<String, String> ENGINES =
      Map.of(
          "duckduckgo", "https://duckduckgo.com/?q={QUERY}",
          "google", "https://www.google.com/search?q={QUERY}",
          "bing", "https://www.bing.com/search?q={QUERY}",
          "perplexity", "https://www.perplexity.ai/search?q={QUERY}",
          "kagi", "https://kagi.com/search?q={QUERY}",
          "startpage", "https://www.startpage.com/search?q={QUERY}");

  @Y(skip = true)
  private Safe cachedHTML = Safe.html("");

  @Y("search-engine")
  public String SearchEngine = "";

  @Y("bangs")
  public List<Bang> Bangs = new ArrayList<>();

  @Y("new-tab")
  public boolean NewTab;

  @Y("target")
  public String Target = "";

  @Y("autofocus")
  public boolean Autofocus;

  @Y("placeholder")
  public String Placeholder = "";

  /** One shortcut: what to type, what to call it, and where it sends the query. */
  public static final class Bang {
    @Y("title")
    public String Title = "";

    @Y("shortcut")
    public String Shortcut = "";

    @Y("url")
    public String URL = "";
  }

  /**
   * The placeholder is carried to the browser as {@code !QUERY!}.
   *
   * <p>Braces in a URL are escaped by the template whatever type the value has, so the
   * browser is given a spelling that survives the trip and puts the braces back.
   */
  static String convertSearchUrl(String url) {
    return url.replace("{QUERY}", "!QUERY!");
  }

  @Override
  public void initialize() {
    withTitle("Search").withError(null);
    if (SearchEngine.isEmpty()) {
      SearchEngine = "duckduckgo";
    }
    if (Placeholder.isEmpty()) {
      Placeholder = "Type here to search…";
    }
    var known = ENGINES.get(SearchEngine);
    if (known != null) {
      SearchEngine = known;
    }
    SearchEngine = convertSearchUrl(SearchEngine);
    for (int i = 0; i < Bangs.size(); i++) {
      var bang = Bangs.get(i);
      if (bang.Shortcut.isEmpty()) {
        throw new ConfigException("search bang #" + (i + 1) + " has no shortcut");
      }
      if (bang.URL.isEmpty()) {
        throw new ConfigException("search bang #" + (i + 1) + " has no URL");
      }
      bang.URL = convertSearchUrl(bang.URL);
    }
    cachedHTML = renderTemplate(this, TEMPLATE);
  }

  @Override
  public Safe Render() {
    return cachedHTML;
  }
}
