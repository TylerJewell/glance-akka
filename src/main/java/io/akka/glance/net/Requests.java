package io.akka.glance.net;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.glance.util.Text;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetches;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * One request, and what comes back.
 *
 * <p>The status check and the truncated body in the message are the original's own: a
 * failure is reported with the first 256 characters of whatever the service said, because
 * that is usually the whole of the explanation.
 */
public final class Requests {

  /** What the original identifies itself as where it does not pretend to be a browser. */
  public static final String USER_AGENT =
      "Glance/" + Version.BUILD + " +https://github.com/glanceapp/glance";

  private static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private Requests() {}

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static HttpRequest.Builder get(String url) {
    return HttpRequest.newBuilder(URI.create(url)).GET().timeout(HttpClients.DEFAULT_TIMEOUT);
  }

  public static HttpRequest.Builder get(String url, Duration timeout) {
    return HttpRequest.newBuilder(URI.create(url)).GET().timeout(timeout);
  }

  /** Sends a request and returns the body, failing on any status but 200. */
  public static HttpResponse<String> send(HttpClient client, HttpRequest request) {
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        var truncated = Text.limitStringLength(response.body(), 256).value();
        throw new Fetches.FetchException(
            Err.of(
                "unexpected status code "
                    + response.statusCode()
                    + " from "
                    + request.uri()
                    + ", response: "
                    + truncated));
      }
      return response;
    } catch (IOException e) {
      throw new Fetches.FetchException(Err.of(describe(e, request.uri())));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Fetches.FetchException(Err.of("interrupted"));
    }
  }

  /** The same, without insisting on a status of 200. */
  public static HttpResponse<String> sendRaw(HttpClient client, HttpRequest request) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new Fetches.FetchException(Err.of(describe(e, request.uri())));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Fetches.FetchException(Err.of("interrupted"));
    }
  }

  private static String describe(IOException e, URI uri) {
    String message = e.getMessage() == null ? e.toString() : e.getMessage();
    return "Get \"" + uri + "\": " + message;
  }

  /** {@code decodeJsonFromRequest}. */
  public static <T> T json(HttpClient client, HttpRequest request, Class<T> type) {
    var response = send(client, request);
    return parse(response.body(), type);
  }

  public static JsonNode json(HttpClient client, HttpRequest request) {
    var response = send(client, request);
    return parse(response.body());
  }

  public static <T> T parse(String body, Class<T> type) {
    try {
      return MAPPER.readValue(body, type);
    } catch (IOException e) {
      throw new Fetches.FetchException(Err.of(e.getMessage()));
    }
  }

  public static JsonNode parse(String body) {
    try {
      return MAPPER.readTree(body);
    } catch (IOException e) {
      throw new Fetches.FetchException(Err.of(e.getMessage()));
    }
  }

  public static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Adds every header in {@code headers}, which is what a widget's own list becomes. */
  public static HttpRequest.Builder withHeaders(
      HttpRequest.Builder builder, Map<String, String> headers) {
    for (var entry : headers.entrySet()) {
      builder.header(entry.getKey(), entry.getValue());
    }
    return builder;
  }
}
