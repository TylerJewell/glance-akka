package io.akka.glance.render;

import io.akka.glance.config.ConfigException;
import io.akka.glance.config.QueryParameters;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Funcs;
import io.akka.glance.gotemplate.GoFormat;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.TemplateException;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.util.GoInstant;
import io.akka.glance.util.GoLayout;
import io.akka.glance.util.JsonResult;
import io.akka.glance.util.Text;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetches;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The custom-api widget's own request, response and template functions.
 *
 * <p>Everything a user writes into a {@code template:} block reaches these: reading the
 * response, shaping lists, arithmetic, times, and making further requests from inside the
 * template.
 */
public final class CustomApiFuncs {

  private static final Map<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

  private CustomApiFuncs() {}

  /** One request the widget makes, primary or otherwise. */
  public static final class Request {

    @Y("url")
    public String URL = "";

    @Y("allow-insecure")
    public boolean AllowInsecure;

    @Y("headers")
    public Map<String, String> Headers = new LinkedHashMap<>();

    @Y("parameters")
    public QueryParameters Parameters = new QueryParameters();

    @Y("method")
    public String Method = "";

    @Y("body-type")
    public String BodyType = "";

    @Y("body")
    public Object Body;

    @Y("skip-json-validation")
    public boolean SkipJSONValidation;

    @Y(skip = true)
    String bodyText;

    @Y(skip = true)
    HttpRequest built;

    /** Settles the method, encodes the body and builds the request. */
    public void initialize() {
      if (URL.isEmpty()) {
        return;
      }
      if (Body != null) {
        if (Method.isEmpty()) {
          Method = "POST";
        }
        if (BodyType.isEmpty()) {
          BodyType = "json";
        }
        if (!BodyType.equals("json") && !BodyType.equals("string")) {
          throw new ConfigException("invalid body type, must be either 'json' or 'string'");
        }
        if (BodyType.equals("json")) {
          bodyText = Requests.writeJson(Body);
        } else {
          if (!(Body instanceof String text)) {
            throw new ConfigException("body must be a string when body-type is 'string'");
          }
          bodyText = text;
        }
      } else if (Method.isEmpty()) {
        Method = "GET";
      }
      String url = URL;
      if (!Parameters.isEmpty()) {
        int mark = url.indexOf('?');
        url = (mark < 0 ? url : url.substring(0, mark)) + "?" + Parameters.toQueryString();
      }
      var builder = HttpRequest.newBuilder(URI.create(url)).timeout(HttpClients.DEFAULT_TIMEOUT);
      var publisher =
          bodyText == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8);
      builder.method(Method.toUpperCase(Locale.ROOT), publisher);
      if (BodyType.equals("json")) {
        builder.header("Content-Type", "application/json");
      }
      Requests.withHeaders(builder, Headers);
      built = builder.build();
    }
  }

  /** What one request came back with. */
  public record ResponseData(JsonResult JSON, Response Response) {}

  /** The response's own shape, as a Go template sees {@code *http.Response}. */
  public record Response(String Status, int StatusCode, Map<String, List<String>> Header) {

    static Response empty() {
      return new Response("", 0, Map.of());
    }
  }

  /** What the user's template executes against. */
  public static final class TemplateData {

    public final JsonResult JSON;
    public final Response Response;
    public final Options Options;
    private final Map<String, ResponseData> subrequests;

    public TemplateData(
        ResponseData primary, Map<String, ResponseData> subrequests, Map<String, Object> options) {
      this.JSON = primary.JSON();
      this.Response = primary.Response();
      this.subrequests = subrequests;
      this.Options = new Options(options);
    }

    /** A response that is a JSON document per line, as several results. */
    public List<JsonResult> JSONLines() {
      var out = new ArrayList<JsonResult>(5);
      for (var line : JSON.Raw().split("\n")) {
        if (!line.isBlank()) {
          out.add(JsonResult.parse(line.trim()));
        }
      }
      return out;
    }

    public ResponseData Subrequest(String key) {
      var found = subrequests.get(key);
      if (found == null) {
        throw new TemplateException("subrequest with key \"" + key + "\" has not been defined");
      }
      return found;
    }
  }

  /** The named values a template may consult, with a default for each reading. */
  public record Options(Map<String, Object> values) {

    public String StringOr(String key, String fallback) {
      var value = values.get(key);
      return value instanceof String text ? text : fallback;
    }

    public long IntOr(String key, long fallback) {
      var value = values.get(key);
      return value instanceof Long number ? number : fallback;
    }

    public double FloatOr(String key, double fallback) {
      var value = values.get(key);
      if (value instanceof Double number) {
        return number;
      }
      return fallback;
    }

    public boolean BoolOr(String key, boolean fallback) {
      var value = values.get(key);
      return value instanceof Boolean flag ? flag : fallback;
    }

    public String JSON(String key) {
      if (!values.containsKey(key)) {
        throw new TemplateException("key \"" + key + "\" does not exist in options");
      }
      return Requests.writeJson(values.get(key));
    }
  }

  /** Sends one request and reads the response as JSON. */
  public static ResponseData fetch(Request request) {
    if (request == null || request.URL.isEmpty()) {
      return new ResponseData(new JsonResult(null), Response.empty());
    }
    var client = request.AllowInsecure ? HttpClients.insecure() : HttpClients.standard();
    var response = Requests.sendRaw(client, request.built);
    String body = response.body().trim();
    if (!request.SkipJSONValidation && !body.isEmpty() && !isValidJson(body)) {
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        throw new Fetches.FetchException(Err.of("invalid response JSON"));
      }
      throw new Fetches.FetchException(
          Err.of(response.statusCode() + " " + statusText(response.statusCode())));
    }
    return new ResponseData(
        JsonResult.parse(body),
        new Response(
            response.statusCode() + " " + statusText(response.statusCode()),
            response.statusCode(),
            response.headers().map()));
  }

  private static boolean isValidJson(String body) {
    try {
      Requests.mapper().readTree(body);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** {@code http.StatusText} for the codes a service is likely to answer with. */
  static String statusText(int code) {
    return switch (code) {
      case 200 -> "OK";
      case 201 -> "Created";
      case 202 -> "Accepted";
      case 204 -> "No Content";
      case 301 -> "Moved Permanently";
      case 302 -> "Found";
      case 304 -> "Not Modified";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 408 -> "Request Timeout";
      case 409 -> "Conflict";
      case 418 -> "I'm a teapot";
      case 422 -> "Unprocessable Entity";
      case 429 -> "Too Many Requests";
      case 500 -> "Internal Server Error";
      case 501 -> "Not Implemented";
      case 502 -> "Bad Gateway";
      case 503 -> "Service Unavailable";
      case 504 -> "Gateway Timeout";
      default -> "";
    };
  }

  /** Everything the page's own functions do, plus what a custom template may call. */
  public static Funcs functions() {
    var funcs = Templates.functions();
    funcs.put("toFloat", args -> (double) GoFormat.toLongPublic(args.getFirst()));
    funcs.put("toInt", args -> (long) toDouble(args.getFirst()));
    funcs.put("add", args -> math(args.get(0), args.get(1), "add"));
    funcs.put("sub", args -> math(args.get(0), args.get(1), "sub"));
    funcs.put("mul", args -> math(args.get(0), args.get(1), "mul"));
    funcs.put("div", args -> math(args.get(0), args.get(1), "div"));
    funcs.put(
        "mod",
        args -> {
          long b = GoFormat.toLongPublic(args.get(1));
          return b == 0 ? 0L : GoFormat.toLongPublic(args.get(0)) % b;
        });
    funcs.put("now", args -> GoInstant.now());
    funcs.put(
        "offsetNow",
        args -> {
          var offset = parseGoDuration(text(args.getFirst()));
          return GoInstant.of(offset == null ? Instant.now() : Instant.now().plus(offset));
        });
    funcs.put(
        "duration",
        args -> {
          var parsed = parseGoDuration(text(args.getFirst()));
          return parsed == null ? 0L : parsed.toNanos();
        });
    funcs.put(
        "parseTime",
        args -> GoInstant.of(GoLayout.parse(text(args.get(0)), text(args.get(1)), ZoneOffset.UTC)));
    funcs.put(
        "parseLocalTime",
        args -> GoInstant.of(GoLayout.parse(text(args.get(0)), text(args.get(1)), ZoneId.systemDefault()), ZoneId.systemDefault()));
    funcs.put(
        "formatTime",
        args -> GoLayout.format(text(args.get(0)), instant(args.get(1)), ZoneOffset.UTC));
    funcs.put("toRelativeTime", args -> relativeTimeAttrs(instant(args.getFirst())));
    funcs.put(
        "parseRelativeTime",
        args ->
            relativeTimeAttrs(GoLayout.parse(text(args.get(0)), text(args.get(1)), ZoneOffset.UTC)));
    funcs.put("startOfDay", args -> GoInstant.of(startOfDay(instant(args.getFirst()))));
    funcs.put("endOfDay", args -> GoInstant.of(endOfDay(instant(args.getFirst()))));
    // The value being worked on is the last argument, so that a pipeline reads left to right.
    funcs.put("trimPrefix", args -> stripPrefix(text(args.get(1)), text(args.get(0))));
    funcs.put("trimSuffix", args -> stripSuffix(text(args.get(1)), text(args.get(0))));
    funcs.put("trimSpace", args -> text(args.getFirst()).strip());
    funcs.put(
        "replaceAll", args -> text(args.get(2)).replace(text(args.get(0)), text(args.get(1))));
    funcs.put(
        "replaceMatches",
        args -> {
          String subject = text(args.get(2));
          if (subject.isEmpty()) {
            return "";
          }
          return regex(text(args.get(0)))
              .matcher(subject)
              .replaceAll(Matcher.quoteReplacement(text(args.get(1))));
        });
    funcs.put(
        "findMatch",
        args -> {
          String subject = text(args.get(1));
          if (subject.isEmpty()) {
            return "";
          }
          var matcher = regex(text(args.get(0))).matcher(subject);
          return matcher.find() ? matcher.group() : "";
        });
    funcs.put(
        "findSubmatch",
        args -> {
          String subject = text(args.get(1));
          if (subject.isEmpty()) {
            return "";
          }
          var matcher = regex(text(args.get(0))).matcher(subject);
          if (!matcher.find() || matcher.groupCount() < 1) {
            return "";
          }
          var group = matcher.group(1);
          return group == null ? "" : group;
        });
    funcs.put(
        "percentChange",
        args -> Text.percentChange(toDouble(args.get(0)), toDouble(args.get(1))));
    funcs.put("sortByString", args -> sortBy(args, (result, key) -> result.String(key)));
    funcs.put(
        "sortByInt",
        args ->
            sortResults(
                args,
                Comparator.comparingLong(result -> result.Int(text(args.get(0)))),
                text(args.get(1))));
    funcs.put(
        "sortByFloat",
        args ->
            sortResults(
                args,
                Comparator.comparingDouble(result -> result.Float(text(args.get(0)))),
                text(args.get(1))));
    funcs.put(
        "sortByTime",
        args -> {
          String key = text(args.get(0));
          String layout = text(args.get(1));
          String order = text(args.get(2));
          var results = results(args.get(3));
          results.sort(
              Comparator.comparing(
                  result -> GoLayout.parse(layout, result.String(key), ZoneOffset.UTC)));
          if (!order.equals("asc")) {
            java.util.Collections.reverse(results);
          }
          return results;
        });
    funcs.put(
        "concat",
        args -> {
          var out = new StringBuilder();
          for (var arg : args) {
            out.append(text(arg));
          }
          return out.toString();
        });
    funcs.put(
        "unique",
        args -> {
          String key = text(args.get(0));
          var seen = new LinkedHashSet<String>();
          var out = new ArrayList<JsonResult>();
          for (var result : results(args.get(1))) {
            if (seen.add(result.String(key))) {
              out.add(result);
            }
          }
          return out;
        });
    funcs.put(
        "newRequest",
        args -> {
          var request = new Request();
          request.URL = text(args.getFirst());
          return request;
        });
    funcs.put(
        "withHeader",
        args -> {
          var request = (Request) args.get(2);
          request.Headers.put(text(args.get(0)), text(args.get(1)));
          return request;
        });
    funcs.put(
        "withParameter",
        args -> {
          var request = (Request) args.get(2);
          request
              .Parameters
              .values()
              .computeIfAbsent(text(args.get(0)), ignored -> new ArrayList<>())
              .add(text(args.get(1)));
          return request;
        });
    funcs.put(
        "withStringBody",
        args -> {
          var request = (Request) args.get(1);
          request.Body = text(args.get(0));
          request.BodyType = "string";
          return request;
        });
    funcs.put(
        "withAllowInsecure",
        args -> {
          var request = (Request) args.get(1);
          var value = args.get(0);
          if (value instanceof Boolean flag) {
            request.AllowInsecure = flag;
          } else if (value instanceof String text
              && text.toLowerCase(Locale.ROOT).equals("true")) {
            request.AllowInsecure = true;
          }
          return request;
        });
    funcs.put(
        "getResponse",
        args -> {
          var request = (Request) args.getFirst();
          request.initialize();
          try {
            return fetch(request);
          } catch (Fetches.FetchException e) {
            // A failed request inside a template answers with the failure in the status
            // rather than stopping the render, which is what the original does.
            return new ResponseData(
                new JsonResult(null), new Response(e.error().message(), 0, Map.of()));
          }
        });
    return funcs;
  }

  private interface KeyReader {
    String read(JsonResult result, String key);
  }

  private static Object sortBy(List<Object> args, KeyReader reader) {
    String key = text(args.get(0));
    String order = text(args.get(1));
    var results = results(args.get(2));
    results.sort(Comparator.comparing(result -> reader.read(result, key)));
    if (!order.equals("asc")) {
      java.util.Collections.reverse(results);
    }
    return results;
  }

  private static Object sortResults(
      List<Object> args, Comparator<JsonResult> comparator, String order) {
    var results = results(args.get(2));
    results.sort(comparator);
    if (!order.equals("asc")) {
      java.util.Collections.reverse(results);
    }
    return results;
  }

  @SuppressWarnings("unchecked")
  private static List<JsonResult> results(Object value) {
    if (value instanceof List<?> list) {
      return new ArrayList<>((List<JsonResult>) list);
    }
    return new ArrayList<>();
  }

  private static Pattern regex(String pattern) {
    return REGEX_CACHE.computeIfAbsent(pattern, Pattern::compile);
  }

  /** Arithmetic that keeps whole numbers whole and gives up on anything else. */
  private static Object math(Object a, Object b, String op) {
    if (a instanceof Long left && b instanceof Long right) {
      return switch (op) {
        case "add" -> left + right;
        case "sub" -> left - right;
        case "mul" -> left * right;
        case "div" -> right == 0 ? 0L : left / right;
        default -> 0L;
      };
    }
    if (!(a instanceof Number) || !(b instanceof Number)) {
      return Double.NaN;
    }
    double left = toDouble(a);
    double right = toDouble(b);
    return switch (op) {
      case "add" -> left + right;
      case "sub" -> left - right;
      case "mul" -> left * right;
      case "div" -> right == 0 ? 0.0 : left / right;
      default -> 0.0;
    };
  }

  private static Safe relativeTimeAttrs(Instant instant) {
    return Safe.attr("data-dynamic-relative-time=\"" + instant.getEpochSecond() + "\"");
  }

  private static Instant startOfDay(Instant instant) {
    return LocalDate.ofInstant(instant, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private static Instant endOfDay(Instant instant) {
    return LocalDate.ofInstant(instant, ZoneOffset.UTC)
        .atTime(23, 59, 59)
        .atZone(ZoneOffset.UTC)
        .toInstant();
  }

  /** {@code time.ParseDuration} — a run of number-and-unit pairs, or nothing at all. */
  public static Duration parseGoDuration(String value) {
    var matcher = Pattern.compile("([0-9.]+)(ns|us|µs|ms|s|m|h)").matcher(value);
    long nanos = 0;
    int matched = 0;
    boolean negative = value.startsWith("-");
    while (matcher.find()) {
      matched += matcher.group().length();
      double amount = Double.parseDouble(matcher.group(1));
      nanos +=
          (long)
              (amount
                  * switch (matcher.group(2)) {
                    case "ns" -> 1L;
                    case "us", "µs" -> 1_000L;
                    case "ms" -> 1_000_000L;
                    case "s" -> 1_000_000_000L;
                    case "m" -> 60_000_000_000L;
                    default -> 3_600_000_000_000L;
                  });
    }
    if (matched == 0 || matched != value.length() - (negative ? 1 : 0)) {
      return null;
    }
    return Duration.ofNanos(negative ? -nanos : nanos);
  }

  private static String stripPrefix(String value, String prefix) {
    return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
  }

  private static String stripSuffix(String value, String suffix) {
    return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
  }

  private static String text(Object value) {
    return value instanceof Safe safe ? safe.value() : GoFormat.value(value);
  }

  private static double toDouble(Object value) {
    return value instanceof Number number ? number.doubleValue() : 0;
  }

  private static Instant instant(Object value) {
    if (value instanceof GoInstant wrapped) {
      return wrapped.instant();
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof Number number) {
      return Instant.ofEpochSecond(number.longValue());
    }
    return Instant.EPOCH;
  }
}
