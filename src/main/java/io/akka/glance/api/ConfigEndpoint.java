package io.akka.glance.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.glance.app.Site;
import io.akka.glance.application.SiteEntity;
import io.akka.glance.config.ConfigException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Setting the configuration without a file.
 *
 * <p>The original reads one from disk and watches it; this instance can also be given one over
 * HTTP, which is what makes it configurable where there is no filesystem to write to and what
 * the benchmark uses to put the same configuration to both systems. The text is the same YAML
 * either way.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/config")
public class ConfigEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public ConfigEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /**
   * Whether this instance offers the route at all.
   *
   * <p>Off unless the operator turns it on, and both paths answer {@code 404} until they do.
   * Setting a configuration is the authority to point {@code server.assets-path} anywhere on
   * the disk, and the original guards the same authority with the permissions on a file; an
   * instance that was not deliberately made settable therefore serves exactly the routes the
   * original serves.
   */
  private static boolean offered() {
    String property = System.getProperty("glance.config-api");
    if (property != null && !property.isEmpty()) {
      return property.equals("on");
    }
    return "on".equals(System.getenv("GLANCE_CONFIG_API"));
  }

  /**
   * The largest configuration this will keep.
   *
   * <p>What is kept is entity state, which stops replicating across regions past a megabyte.
   * A configuration is a hand-written file — the one shipped by default is under six
   * kilobytes — so the ceiling is far above anything real and refusing at it says what
   * happened rather than failing later inside the runtime.
   */
  private static final int LARGEST_CONFIG_BYTES = 512 * 1024;

  /** A configuration to load, and the instant to consider it loaded at. */
  public record Load(String yaml, String at) {}

  @Put("/")
  public HttpResponse load(Load request) {
    if (!offered()) {
      return Requests.notFoundPage();
    }
    if (request == null || request.yaml() == null || request.yaml().isBlank()) {
      return HttpResponse.create()
          .withStatus(StatusCodes.BAD_REQUEST)
          .withEntity(
              ContentTypes.TEXT_PLAIN_UTF8, "no configuration".getBytes(StandardCharsets.UTF_8));
    }
    if (request.yaml().getBytes(StandardCharsets.UTF_8).length > LARGEST_CONFIG_BYTES) {
      return HttpResponse.create()
          .withStatus(StatusCodes.PAYLOAD_TOO_LARGE)
          .withEntity(
              ContentTypes.TEXT_PLAIN_UTF8,
              ("Config file is larger than " + LARGEST_CONFIG_BYTES + " bytes")
                  .getBytes(StandardCharsets.UTF_8));
    }
    Instant at;
    try {
      at =
          request.at() == null || request.at().isBlank()
              ? Instant.now()
              : Instant.parse(request.at().trim());
    } catch (DateTimeParseException e) {
      // Refused rather than left to the runtime: an instant a caller cannot spell is not a
      // fault the caller can retry past, and an unhandled parse failure reaches them as a
      // correlation identifier with nothing in it about which field was wrong.
      return HttpResponse.create()
          .withStatus(StatusCodes.BAD_REQUEST)
          .withEntity(
              ContentTypes.TEXT_PLAIN_UTF8,
              "Load instant is not an ISO-8601 instant".getBytes(StandardCharsets.UTF_8));
    }
    try {
      Site.load(request.yaml(), at);
    } catch (ConfigException e) {
      return HttpResponse.create()
          .withStatus(StatusCodes.BAD_REQUEST)
          .withEntity(
              ContentTypes.TEXT_PLAIN_UTF8,
              ("Config file is invalid: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
    }
    componentClient
        .forKeyValueEntity(SiteEntity.ID)
        .method(SiteEntity::set)
        .invoke(new SiteEntity.State(request.yaml(), at.toString()));
    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .withEntity(ContentTypes.TEXT_PLAIN_UTF8, new byte[0]);
  }

  @Get("/")
  public HttpResponse current() {
    if (!offered()) {
      return Requests.notFoundPage();
    }
    var state = componentClient.forKeyValueEntity(SiteEntity.ID).method(SiteEntity::get).invoke();
    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .withEntity(ContentTypes.TEXT_PLAIN_UTF8, state.yaml().getBytes(StandardCharsets.UTF_8));
  }
}
