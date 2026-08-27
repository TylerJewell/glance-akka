package io.akka.glance.widget.kind;

import io.akka.glance.config.ConfigException;
import io.akka.glance.config.QueryParameters;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.util.Text;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A widget somebody else's server draws.
 *
 * <p>The response's own headers name the heading, the link and whether the markup is to be
 * trusted; anything not declared as markup, or declared but not allowed, is shown as text.
 */
public final class ExtensionWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("extension.html", "widget-base.html");

  private static final String DEFAULT_TITLE = "Extension";

  private static final String HEADER_TITLE = "Widget-Title";
  private static final String HEADER_TITLE_URL = "Widget-Title-URL";
  private static final String HEADER_CONTENT_TYPE = "Widget-Content-Type";
  private static final String HEADER_FRAMELESS = "Widget-Content-Frameless";

  @Y("url")
  public String URL = "";

  @Y("fallback-content-type")
  public String FallbackContentType = "";

  @Y("parameters")
  public QueryParameters Parameters = new QueryParameters();

  @Y("headers")
  public Map<String, String> Headers = new LinkedHashMap<>();

  @Y("allow-potentially-dangerous-html")
  public boolean AllowHtml;

  @Y(skip = true)
  public Extension Extension = new Extension();

  @Y(skip = true)
  private Safe cachedHTML = Safe.html("");

  /** What the other server sent. */
  public static final class Extension {
    public String Title = "";
    public String TitleURL = "";
    public Safe Content = Safe.html("");
    public boolean Frameless;
  }

  @Override
  public void initialize() {
    withTitle(DEFAULT_TITLE).withCacheDuration(Duration.ofMinutes(30));
    if (URL.isEmpty()) {
      throw new ConfigException("URL is required");
    }
    try {
      URI.create(URL);
    } catch (IllegalArgumentException e) {
      throw new ConfigException("parsing URL: " + e.getMessage());
    }
  }

  @Override
  public void update(Instant now) {
    Err error = null;
    var extension = new Extension();
    try {
      extension = fetch();
    } catch (Fetches.FetchException e) {
      error = Err.NO_CONTENT.because("request failed: " + e.error());
    }
    canContinueUpdateAfterHandlingErr(error, now);
    Extension = extension;
    if (Title.equals(DEFAULT_TITLE) && !extension.Title.isEmpty()) {
      Title = extension.Title;
    }
    if (TitleURL.isEmpty() && !extension.TitleURL.isEmpty()) {
      TitleURL = extension.TitleURL;
    }
    cachedHTML = renderTemplate(this, TEMPLATE);
  }

  private Extension fetch() {
    String url = URL;
    if (!Parameters.isEmpty()) {
      String query = Parameters.toQueryString();
      int mark = url.indexOf('?');
      url = (mark < 0 ? url : url.substring(0, mark)) + "?" + query;
    }
    var builder = Requests.get(url);
    Requests.withHeaders(builder, Headers);
    var response = Requests.sendRaw(HttpClients.standard(), builder.build());
    var extension = new Extension();
    extension.Title = header(response, HEADER_TITLE).isEmpty()
        ? "Extension"
        : header(response, HEADER_TITLE);
    extension.TitleURL = header(response, HEADER_TITLE_URL);
    boolean isHtml = header(response, HEADER_CONTENT_TYPE).equals("html");
    if (!isHtml && header(response, HEADER_CONTENT_TYPE).isEmpty()) {
      isHtml = FallbackContentType.equals("html");
    }
    extension.Frameless = Text.stringToBool(header(response, HEADER_FRAMELESS));
    extension.Content =
        isHtml && AllowHtml
            ? Safe.html(response.body())
            : Safe.html("<pre>" + escape(response.body()) + "</pre>");
    return extension;
  }

  /** {@code html.EscapeString} — the five characters that table covers, and no others. */
  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("'", "&#39;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&#34;");
  }

  private static String header(java.net.http.HttpResponse<String> response, String name) {
    return response.headers().firstValue(name).orElse("");
  }

  @Override
  public Safe Render() {
    return cachedHTML;
  }
}
