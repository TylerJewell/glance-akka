package io.akka.glance.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.akka.glance.net.Requests;
import java.util.ArrayList;
import java.util.List;

/**
 * One value inside a JSON document, addressed by path.
 *
 * <p>The paths are the ones the original's templates write: names separated by dots, a
 * number for an element of an array, {@code #} for how many an array holds, and {@code #.name}
 * to take that name from every element. A path that names nothing yields a result that does
 * not exist rather than an error, which is what makes a template's {@code Exists} check
 * meaningful.
 */
public final class JsonResult {

  private final JsonNode node;
  private final String raw;

  public JsonResult(JsonNode node) {
    this.node = node == null ? MissingNode.getInstance() : node;
    this.raw = this.node.isMissingNode() ? "" : this.node.toString();
  }

  public static JsonResult parse(String body) {
    if (body == null || body.isBlank()) {
      return new JsonResult(MissingNode.getInstance());
    }
    try {
      return new JsonResult(Requests.mapper().readTree(body));
    } catch (Exception e) {
      return new JsonResult(MissingNode.getInstance());
    }
  }

  /** The document's own text, which {@code JSONLines} reads back a line at a time. */
  public String Raw() {
    return raw;
  }

  public JsonNode node() {
    return node;
  }

  public JsonResult Get(String path) {
    return new JsonResult(resolve(node, path));
  }

  public boolean Exists(String path) {
    var found = resolve(node, path);
    return !found.isMissingNode() && !found.isNull();
  }

  public List<JsonResult> Array(String path) {
    var target = path == null || path.isEmpty() ? node : resolve(node, path);
    var out = new ArrayList<JsonResult>();
    if (target.isArray()) {
      for (var item : target) {
        out.add(new JsonResult(item));
      }
    } else if (!target.isMissingNode() && !target.isNull()) {
      out.add(new JsonResult(target));
    }
    return out;
  }

  public String String(String path) {
    var target = path == null || path.isEmpty() ? node : resolve(node, path);
    if (target.isMissingNode() || target.isNull()) {
      return "";
    }
    if (target.isTextual()) {
      return target.asText();
    }
    if (target.isNumber()) {
      return io.akka.glance.gotemplate.GoFormat.value(
          target.isIntegralNumber() ? (Object) target.asLong() : (Object) target.asDouble());
    }
    if (target.isBoolean()) {
      return target.asBoolean() ? "true" : "false";
    }
    return target.toString();
  }

  public long Int(String path) {
    var target = path == null || path.isEmpty() ? node : resolve(node, path);
    if (target.isTextual()) {
      try {
        return (long) Double.parseDouble(target.asText().trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    if (target.isBoolean()) {
      return target.asBoolean() ? 1 : 0;
    }
    return target.asLong();
  }

  public double Float(String path) {
    var target = path == null || path.isEmpty() ? node : resolve(node, path);
    if (target.isTextual()) {
      try {
        return Double.parseDouble(target.asText().trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    if (target.isBoolean()) {
      return target.asBoolean() ? 1 : 0;
    }
    return target.asDouble();
  }

  public boolean Bool(String path) {
    var target = path == null || path.isEmpty() ? node : resolve(node, path);
    if (target.isTextual()) {
      String text = target.asText().trim();
      return text.equals("true") || text.equals("1") || text.equals("yes");
    }
    if (target.isNumber()) {
      return target.asDouble() != 0;
    }
    return target.asBoolean();
  }

  private static JsonNode resolve(JsonNode from, String path) {
    if (path == null || path.isEmpty()) {
      return from;
    }
    JsonNode current = from;
    for (var segment : splitPath(path)) {
      if (current == null || current.isMissingNode() || current.isNull()) {
        return MissingNode.getInstance();
      }
      if (segment.equals("#")) {
        return Requests.mapper().getNodeFactory().numberNode(current.isArray() ? current.size() : 0);
      }
      if (segment.startsWith("#.") || segment.startsWith("#")) {
        // "#" followed by a name takes that name from every element.
        String inner = segment.startsWith("#.") ? segment.substring(2) : segment.substring(1);
        var collected = Requests.mapper().createArrayNode();
        if (current.isArray()) {
          for (var item : current) {
            var value = resolve(item, inner);
            if (!value.isMissingNode()) {
              collected.add(value);
            }
          }
        }
        current = collected;
        continue;
      }
      if (current.isArray()) {
        try {
          current = current.path(Integer.parseInt(segment));
        } catch (NumberFormatException e) {
          return MissingNode.getInstance();
        }
        continue;
      }
      current = current.path(segment);
    }
    return current == null ? MissingNode.getInstance() : current;
  }

  /** Splits on dots, honouring a backslash before one that is part of a name. */
  private static List<String> splitPath(String path) {
    var out = new ArrayList<String>();
    var current = new StringBuilder();
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '\\' && i + 1 < path.length()) {
        current.append(path.charAt(i + 1));
        i++;
        continue;
      }
      if (c == '.') {
        out.add(current.toString());
        current.setLength(0);
        continue;
      }
      current.append(c);
    }
    out.add(current.toString());
    // "#.name" arrives as two segments; put it back together so the mapping form works.
    var merged = new ArrayList<String>();
    for (int i = 0; i < out.size(); i++) {
      if (out.get(i).equals("#") && i + 1 < out.size()) {
        merged.add("#." + out.get(i + 1));
        i++;
        continue;
      }
      merged.add(out.get(i));
    }
    return merged;
  }
}
