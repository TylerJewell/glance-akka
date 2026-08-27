package io.akka.glance.config;

import io.akka.glance.net.HttpClients;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.yaml.snakeyaml.nodes.Node;

/**
 * A proxy for one widget's own requests, written either as a URL or as a mapping carrying a
 * URL, a timeout and whether an untrusted certificate is accepted.
 */
public final class ProxyOptions implements Yaml.Decodable {

  @Y("url")
  public String URL = "";

  @Y("allow-insecure")
  public boolean AllowInsecure;

  @Y("timeout")
  public DurationField Timeout;

  @Y(skip = true)
  private HttpClient client;

  /** The client this proxy asks for, or nothing when no proxy was configured. */
  public HttpClient client() {
    return client;
  }

  @Override
  public void decode(Node node) {
    String proxyUrl = "";
    if (Yaml.isScalar(node)) {
      proxyUrl = Yaml.scalar(node);
    } else {
      Yaml.decodeInto(node, this);
    }
    if (proxyUrl.isEmpty() && URL.isEmpty()) {
      return;
    }
    if (!URL.isEmpty()) {
      proxyUrl = URL;
    }
    URI parsed;
    try {
      parsed = URI.create(proxyUrl);
    } catch (IllegalArgumentException e) {
      throw new ConfigException("parsing proxy URL: " + e.getMessage());
    }
    var timeout =
        Timeout != null && !Timeout.isZero() ? Timeout.duration() : HttpClients.DEFAULT_TIMEOUT;
    client = HttpClients.through(parsed, timeout, AllowInsecure);
  }

  /** Whether anything at all was configured, which is what a widget checks before using it. */
  public boolean isConfigured() {
    return client != null;
  }

  public Duration timeout() {
    return Timeout != null && !Timeout.isZero() ? Timeout.duration() : HttpClients.DEFAULT_TIMEOUT;
  }
}
