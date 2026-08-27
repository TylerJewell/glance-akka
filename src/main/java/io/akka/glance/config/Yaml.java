package io.akka.glance.config;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.composer.Composer;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Decodes a configuration file the way {@code gopkg.in/yaml.v3} decodes into a struct.
 *
 * <p>The node tree is used rather than a mapping to classes because several of the
 * configuration's field types decide their own shape — a proxy that is either a URL or a
 * mapping, an icon whose prefix picks a source, a map that keeps its order. Those implement
 * {@link Decodable} and are handed the node.
 */
public final class Yaml {

  /** A field type that reads its own node, standing in for {@code UnmarshalYAML}. */
  public interface Decodable {
    void decode(Node node);
  }

  private Yaml() {}

  /** Parses one document. An empty document decodes as nothing at all. */
  public static Node compose(String source) {
    var reader = new StreamReader(source);
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setCodePointLimit(Integer.MAX_VALUE);
    var composer = new Composer(new ParserImpl(reader, options), new Resolver(), options);
    return composer.getSingleNode();
  }

  /** Fills {@code target}'s fields from {@code node}, leaving absent keys as they are. */
  public static void decodeInto(Node node, Object target) {
    if (node == null) {
      return;
    }
    if (!(node instanceof MappingNode mapping)) {
      throw new ConfigException("expected a mapping, got " + kindOf(node));
    }
    var byKey = new LinkedHashMap<String, Node>();
    for (var tuple : mapping.getValue()) {
      byKey.put(scalar(tuple.getKeyNode()), tuple.getValueNode());
    }
    for (var field : fieldsOf(target.getClass())) {
      var annotation = field.getAnnotation(Y.class);
      if (annotation != null && annotation.skip()) {
        continue;
      }
      if (annotation != null && annotation.inline()) {
        field.setAccessible(true);
        try {
          var nested = field.get(target);
          if (nested == null) {
            nested = field.getType().getDeclaredConstructor().newInstance();
            field.set(target, nested);
          }
          decodeInto(node, nested);
        } catch (ReflectiveOperationException e) {
          throw new ConfigException("reading " + field.getName() + ": " + e.getMessage());
        }
        continue;
      }
      String key =
          annotation != null && !annotation.value().isEmpty()
              ? annotation.value()
              : field.getName().toLowerCase(Locale.ROOT);
      var value = byKey.get(key);
      if (value == null) {
        continue;
      }
      field.setAccessible(true);
      try {
        field.set(target, decode(value, field.getType(), field.getGenericType()));
      } catch (IllegalAccessException e) {
        throw new ConfigException("setting " + field.getName() + ": " + e.getMessage());
      }
    }
  }

  private static List<Field> fieldsOf(Class<?> type) {
    var out = new ArrayList<Field>();
    for (var current = type; current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (var field : current.getDeclaredFields()) {
        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
          out.add(field);
        }
      }
    }
    return out;
  }

  /** Decodes one node into one type. */
  @SuppressWarnings("unchecked")
  public static Object decode(Node node, Class<?> type, java.lang.reflect.Type generic) {
    if (node.getTag() == Tag.NULL) {
      return null;
    }
    if (Decodable.class.isAssignableFrom(type)) {
      try {
        var value = (Decodable) type.getDeclaredConstructor().newInstance();
        if (value instanceof OrderedYamlMap<?> ordered) {
          ordered.withValueType((Class) elementType(generic, 0));
        }
        value.decode(node);
        return value;
      } catch (ReflectiveOperationException e) {
        throw new ConfigException("decoding " + type.getSimpleName() + ": " + causeOf(e));
      }
    }
    if (type == String.class) {
      return scalar(node);
    }
    if (type == boolean.class || type == Boolean.class) {
      return parseBoolean(scalar(node));
    }
    if (type == int.class || type == Integer.class) {
      return (int) parseLong(scalar(node));
    }
    if (type == long.class || type == Long.class) {
      return parseLong(scalar(node));
    }
    if (type == double.class || type == Double.class) {
      return Double.parseDouble(scalar(node).trim());
    }
    if (type == float.class || type == Float.class) {
      return Float.parseFloat(scalar(node).trim());
    }
    if (type == Object.class) {
      return plain(node);
    }
    if (List.class.isAssignableFrom(type)) {
      var element = elementType(generic, 0);
      var out = new ArrayList<Object>();
      if (!(node instanceof SequenceNode sequence)) {
        throw new ConfigException("expected a list, got " + kindOf(node));
      }
      for (var item : sequence.getValue()) {
        out.add(decode(item, element, element));
      }
      return out;
    }
    if (Map.class.isAssignableFrom(type)) {
      var element = elementType(generic, 1);
      var out = new LinkedHashMap<String, Object>();
      if (!(node instanceof MappingNode mapping)) {
        throw new ConfigException("expected a mapping, got " + kindOf(node));
      }
      for (var tuple : mapping.getValue()) {
        out.put(scalar(tuple.getKeyNode()), decode(tuple.getValueNode(), element, element));
      }
      return out;
    }
    try {
      var value = type.getDeclaredConstructor().newInstance();
      decodeInto(node, value);
      return value;
    } catch (ReflectiveOperationException e) {
      throw new ConfigException("decoding " + type.getSimpleName() + ": " + causeOf(e));
    }
  }

  private static String causeOf(ReflectiveOperationException e) {
    var cause = e.getCause();
    if (cause instanceof ConfigException known) {
      throw known;
    }
    return cause == null ? e.toString() : String.valueOf(cause.getMessage());
  }

  private static Class<?> elementType(java.lang.reflect.Type generic, int index) {
    if (generic instanceof ParameterizedType parameterized) {
      var argument = parameterized.getActualTypeArguments()[index];
      if (argument instanceof Class<?> type) {
        return type;
      }
      if (argument instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> raw) {
        return raw;
      }
    }
    return Object.class;
  }

  /** A node as the plain value {@code any} would receive. */
  public static Object plain(Node node) {
    return switch (node) {
      case ScalarNode s -> scalarValue(s);
      case SequenceNode s -> {
        var out = new ArrayList<>();
        for (var item : s.getValue()) {
          out.add(plain(item));
        }
        yield out;
      }
      case MappingNode m -> {
        var out = new LinkedHashMap<String, Object>();
        for (var tuple : m.getValue()) {
          out.put(scalar(tuple.getKeyNode()), plain(tuple.getValueNode()));
        }
        yield out;
      }
      default -> null;
    };
  }

  private static Object scalarValue(ScalarNode node) {
    String text = node.getValue();
    if (node.getTag() == Tag.NULL) {
      return null;
    }
    if (node.getTag() == Tag.BOOL) {
      return parseBoolean(text);
    }
    if (node.getTag() == Tag.INT) {
      return Long.parseLong(text.replace("_", ""));
    }
    if (node.getTag() == Tag.FLOAT) {
      return Double.parseDouble(text.replace("_", ""));
    }
    return text;
  }

  /** The text of a scalar node, or an error naming what was there instead. */
  public static String scalar(Node node) {
    if (node instanceof ScalarNode s) {
      return s.getValue();
    }
    throw new ConfigException("expected a value, got " + kindOf(node));
  }

  public static boolean isScalar(Node node) {
    return node instanceof ScalarNode;
  }

  private static String kindOf(Node node) {
    return switch (node) {
      case MappingNode ignored -> "a mapping";
      case SequenceNode ignored -> "a list";
      case ScalarNode ignored -> "a value";
      default -> "nothing";
    };
  }

  private static boolean parseBoolean(String text) {
    return switch (text.trim().toLowerCase(Locale.ROOT)) {
      case "true", "yes", "on", "y" -> true;
      case "false", "no", "off", "n", "" -> false;
      default -> throw new ConfigException("cannot unmarshal !!str `" + text + "` into bool");
    };
  }

  private static long parseLong(String text) {
    String cleaned = text.trim().replace("_", "");
    if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) {
      return Long.parseLong(cleaned.substring(2), 16);
    }
    try {
      return Long.parseLong(cleaned);
    } catch (NumberFormatException e) {
      throw new ConfigException("cannot unmarshal !!str `" + text + "` into an integer");
    }
  }

  /** The mapping's entries in file order, for a type that has to keep that order. */
  public static List<NodeTuple> entries(Node node) {
    if (!(node instanceof MappingNode mapping)) {
      throw new ConfigException("expected a mapping, got " + kindOf(node));
    }
    return mapping.getValue();
  }
}
