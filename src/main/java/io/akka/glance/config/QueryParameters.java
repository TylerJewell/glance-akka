package io.akka.glance.config;

import io.akka.glance.gotemplate.GoFormat;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.yaml.snakeyaml.nodes.Node;

/**
 * Extra query parameters for a widget's own request.
 *
 * <p>A value may be one item or a list of them, and a number or a boolean is written the way
 * Go writes it rather than the way the file spelled it.
 */
public final class QueryParameters implements Yaml.Decodable {

  private final Map<String, List<String>> values = new LinkedHashMap<>();

  public Map<String, List<String>> values() {
    return values;
  }

  public boolean isEmpty() {
    return values.isEmpty();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void decode(Node node) {
    var decoded = (Map<String, Object>) Yaml.plain(node);
    if (decoded == null) {
      return;
    }
    for (var entry : decoded.entrySet()) {
      var out = new ArrayList<String>();
      if (entry.getValue() instanceof List<?> list) {
        for (var item : list) {
          out.add(one(item));
        }
      } else {
        out.add(one(entry.getValue()));
      }
      values.put(entry.getKey(), out);
    }
  }

  private static String one(Object value) {
    if (value instanceof String s) {
      return s;
    }
    if (value instanceof Boolean || value instanceof Number) {
      return GoFormat.value(value);
    }
    throw new ConfigException(
        "invalid query parameter value type: " + (value == null ? "nil" : value.getClass()));
  }

  /**
   * {@code url.Values.Encode} — the keys sorted, each value percent-encoded, joined with
   * {@code &}.
   */
  public String toQueryString() {
    var sorted = new TreeMap<>(values);
    var out = new StringBuilder();
    for (var entry : sorted.entrySet()) {
      for (var value : entry.getValue()) {
        if (!out.isEmpty()) {
          out.append('&');
        }
        out.append(encode(entry.getKey())).append('=').append(encode(value));
      }
    }
    return out.toString();
  }

  /** {@code url.QueryEscape} — the same as Java's, except that a space becomes {@code +}. */
  public static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
