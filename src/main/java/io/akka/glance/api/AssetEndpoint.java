package io.akka.glance.api;

import akka.http.javadsl.model.ContentType;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpCharsets;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.glance.app.Site;
import io.akka.glance.util.Assets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * The files the pages ask for: the shipped ones, the style sheet built from them, the web
 * manifest, and anything the user put in an assets directory.
 *
 * <p>The shipped ones are served from a path carrying a digest of all of them, so a browser
 * may keep them for a day without ever holding a stale one.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class AssetEndpoint extends AbstractHttpEndpoint {

  /** The wording Go's own file server answers a missing path with, newline and all. */
  private static final String NOT_FOUND_BODY = "404 page not found\n";

  private static final Duration STATIC_CACHE = Duration.ofHours(24);
  private static final Duration USER_ASSET_CACHE = Duration.ofHours(2);

  /**
   * A page whose slug is {@code static}.
   *
   * <p>This endpoint owns the prefix, so the router does not offer the bare path back to the
   * page endpoint; the original serves it as an ordinary page and so does this.
   */
  @Get("/static")
  public HttpResponse staticPage() {
    return Pages.shell(requestContext(), "static");
  }

  /** The same, for a page whose slug is {@code assets}. */
  @Get("/assets")
  public HttpResponse assetsPage() {
    return Pages.shell(requestContext(), "assets");
  }

  @Get("/static/{hash}/{first}")
  public HttpResponse asset(String hash, String first) {
    return serveStatic(hash, first);
  }

  @Get("/static/{hash}/{first}/{second}")
  public HttpResponse asset(String hash, String first, String second) {
    return serveStatic(hash, first + "/" + second);
  }

  @Get("/static/{hash}/{first}/{second}/{third}")
  public HttpResponse asset(String hash, String first, String second, String third) {
    return serveStatic(hash, first + "/" + second + "/" + third);
  }

  @Get("/manifest.json")
  public HttpResponse manifest() {
    return cached(
        Site.application().manifest().getBytes(StandardCharsets.UTF_8),
        ContentTypes.APPLICATION_JSON,
        STATIC_CACHE);
  }

  @Get("/assets/{first}")
  public HttpResponse userAsset(String first) {
    return serveUserAsset(first);
  }

  @Get("/assets/{first}/{second}")
  public HttpResponse userAsset(String first, String second) {
    return serveUserAsset(first + "/" + second);
  }

  @Get("/assets/{first}/{second}/{third}")
  public HttpResponse userAsset(String first, String second, String third) {
    return serveUserAsset(first + "/" + second + "/" + third);
  }

  /**
   * What a file server answers for a path it does not hold. The original serves its assets
   * through Go's own file server, whose wording this is; a caller that reads the body sees
   * the same thing from either.
   */
  private static HttpResponse missingFile() {
    return HttpResponse.create()
        .withStatus(StatusCodes.NOT_FOUND)
        .withEntity(
            ContentTypes.TEXT_PLAIN_UTF8,
            NOT_FOUND_BODY.getBytes(StandardCharsets.UTF_8));
  }

  private HttpResponse serveStatic(String hash, String path) {
    if (!hash.equals(Assets.hash())) {
      return missingFile();
    }
    // The bundle is not a file on disk; it is every style sheet, joined.
    if (path.equals("css/bundle.css")) {
      return cached(
          Assets.bundledCss(),
          ContentTypes.create(MediaTypes.TEXT_CSS, HttpCharsets.UTF_8),
          STATIC_CACHE);
    }
    if (!Assets.exists(path)) {
      return missingFile();
    }
    return cached(Assets.read(path), contentTypeOf(path), STATIC_CACHE);
  }

  private HttpResponse serveUserAsset(String path) {
    String directory = Site.application().Config.Server.AssetsPath;
    if (directory.isEmpty()) {
      return missingFile();
    }
    var root = Path.of(directory).toAbsolutePath().normalize();
    var file = root.resolve(path).normalize();
    if (!file.startsWith(root) || !Files.isRegularFile(file)) {
      return missingFile();
    }
    try {
      return cached(Files.readAllBytes(file), contentTypeOf(path), USER_ASSET_CACHE);
    } catch (IOException e) {
      return missingFile();
    }
  }

  private static HttpResponse cached(byte[] body, ContentType contentType, Duration duration) {
    return HttpResponse.create()
        .withStatus(StatusCodes.OK)
        .addHeader(
            RawHeader.create("Cache-Control", "public, max-age=" + duration.getSeconds()))
        .withEntity(contentType, body);
  }

  /** The kind of file, from its extension, for the kinds this ships. */
  static ContentType contentTypeOf(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    int dot = lower.lastIndexOf('.');
    String extension = dot < 0 ? "" : lower.substring(dot + 1);
    return switch (extension) {
      case "css" -> ContentTypes.create(MediaTypes.TEXT_CSS, HttpCharsets.UTF_8);
      case "js", "mjs" -> ContentTypes.create(MediaTypes.APPLICATION_JAVASCRIPT, HttpCharsets.UTF_8);
      case "json" -> ContentTypes.APPLICATION_JSON;
      case "html" -> ContentTypes.TEXT_HTML_UTF8;
      case "svg" -> ContentTypes.create(MediaTypes.applicationBinary("svg+xml", true));
      case "png" -> ContentTypes.create(MediaTypes.IMAGE_PNG);
      case "jpg", "jpeg" -> ContentTypes.create(MediaTypes.IMAGE_JPEG);
      case "gif" -> ContentTypes.create(MediaTypes.IMAGE_GIF);
      case "ico" -> ContentTypes.create(MediaTypes.IMAGE_X_ICON);
      case "woff2" -> ContentTypes.create(MediaTypes.applicationBinary("font-woff2", true));
      case "woff" -> ContentTypes.create(MediaTypes.applicationBinary("font-woff", true));
      case "txt" -> ContentTypes.TEXT_PLAIN_UTF8;
      default -> ContentTypes.APPLICATION_OCTET_STREAM;
    };
  }
}
