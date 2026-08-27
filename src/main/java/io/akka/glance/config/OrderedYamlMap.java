package io.akka.glance.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.nodes.Node;

/**
 * A mapping that keeps the order its keys were written in.
 *
 * <p>The theme picker lists presets in file order, so the order is part of what the file
 * says. {@link #Items} is what the templates range over.
 */
public final class OrderedYamlMap<V> implements Yaml.Decodable {

  private final List<String> keys = new ArrayList<>();
  private final Map<String, V> data = new LinkedHashMap<>();
  private Class<V> valueType;

  public OrderedYamlMap() {}

  public OrderedYamlMap(Class<V> valueType) {
    this.valueType = valueType;
  }

  public static <V> OrderedYamlMap<V> of(Class<V> valueType, List<String> keys, List<V> values) {
    if (keys.size() != values.size()) {
      throw new ConfigException("keys and values must have the same length");
    }
    var out = new OrderedYamlMap<V>(valueType);
    for (int i = 0; i < keys.size(); i++) {
      out.keys.add(keys.get(i));
      out.data.put(keys.get(i), values.get(i));
    }
    return out;
  }

  /** What a template ranges over: key and value, in the order the file wrote them. */
  public List<Map.Entry<String, V>> Items() {
    var out = new ArrayList<Map.Entry<String, V>>(keys.size());
    for (var key : keys) {
      var value = data.get(key);
      if (value != null) {
        out.add(Map.entry(key, value));
      }
    }
    return out;
  }

  public V Get(String key) {
    return data.get(key);
  }

  public boolean has(String key) {
    return data.containsKey(key);
  }

  public List<String> keys() {
    return List.copyOf(keys);
  }

  public int size() {
    return keys.size();
  }

  /** This map's entries first, then any of the other's that this one does not already have. */
  public OrderedYamlMap<V> Merge(OrderedYamlMap<V> other) {
    var merged = new OrderedYamlMap<V>(valueType != null ? valueType : other.valueType);
    merged.keys.addAll(keys);
    merged.data.putAll(data);
    for (var key : other.keys) {
      if (!data.containsKey(key)) {
        merged.keys.add(key);
      }
    }
    merged.data.putAll(other.data);
    return merged;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void decode(Node node) {
    for (var tuple : Yaml.entries(node)) {
      String key = Yaml.scalar(tuple.getKeyNode());
      if (data.containsKey(key)) {
        throw new ConfigException("orderedMap: duplicate key " + key);
      }
      var value = (V) Yaml.decode(tuple.getValueNode(), valueType(), valueType());
      keys.add(key);
      data.put(key, value);
    }
  }

  private Class<V> valueType() {
    if (valueType == null) {
      throw new ConfigException("orderedMap: no value type");
    }
    return valueType;
  }

  /** Names the type of value this map holds, for a field the decoder builds by reflection. */
  @SuppressWarnings("unchecked")
  public void withValueType(Class<?> type) {
    this.valueType = (Class<V>) type;
  }
}
