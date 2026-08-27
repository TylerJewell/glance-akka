package io.akka.glance.gotemplate;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * How a template reads a value: what a name resolves to on it, and whether it is true.
 *
 * <p>A name is looked up as a method first and a field second, both spelled exactly as the
 * template spells it. The model classes therefore carry Go's own capitalisation, which is
 * what lets the original's template files be shipped unchanged.
 */
public final class Values {

  private Values() {}

  /** Go's {@code truth}: the zero value of every kind is false, and so is nil. */
  public static boolean isTrue(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String s) {
      return !s.isEmpty();
    }
    if (value instanceof Safe safe) {
      return !safe.value().isEmpty();
    }
    if (value instanceof Number n) {
      return n.doubleValue() != 0;
    }
    if (value instanceof Collection<?> c) {
      return !c.isEmpty();
    }
    if (value instanceof Map<?, ?> m) {
      return !m.isEmpty();
    }
    if (value.getClass().isArray()) {
      return Array.getLength(value) != 0;
    }
    return true;
  }

  /** {@code len}. */
  public static int length(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof String s) {
      return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    if (value instanceof Safe safe) {
      return safe.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    if (value instanceof Collection<?> c) {
      return c.size();
    }
    if (value instanceof Map<?, ?> m) {
      return m.size();
    }
    if (value.getClass().isArray()) {
      return Array.getLength(value);
    }
    throw new TemplateException("len of untyped nil or a value with no length");
  }

  /** {@code index}. */
  public static Object index(Object value, List<Object> keys) {
    Object current = value;
    for (var key : keys) {
      if (current == null) {
        return null;
      }
      if (current instanceof Map<?, ?> map) {
        current = map.get(key);
        continue;
      }
      int at = (int) GoFormat.toLong(key);
      if (current instanceof List<?> list) {
        current = at >= 0 && at < list.size() ? list.get(at) : null;
      } else if (current.getClass().isArray()) {
        current = at >= 0 && at < Array.getLength(current) ? Array.get(current, at) : null;
      } else if (current instanceof String s) {
        current = at >= 0 && at < s.length() ? (long) s.charAt(at) : null;
      } else {
        throw new TemplateException("cannot index " + current.getClass().getSimpleName());
      }
    }
    return current;
  }

  /** What a range walks, as a list of key/value pairs. */
  public record Entry(Object key, Object value) {}

  public static List<Entry> entries(Object value) {
    var out = new ArrayList<Entry>();
    if (value == null) {
      return out;
    }
    if (value instanceof Iterable<?> iterable) {
      long i = 0;
      for (var item : iterable) {
        if (item instanceof Map.Entry<?, ?> pair) {
          out.add(new Entry(pair.getKey(), pair.getValue()));
        } else {
          out.add(new Entry(i, item));
        }
        i++;
      }
      return out;
    }
    if (value instanceof Map<?, ?> map) {
      for (var pair : map.entrySet()) {
        out.add(new Entry(pair.getKey(), pair.getValue()));
      }
      return out;
    }
    if (value.getClass().isArray()) {
      for (int i = 0; i < Array.getLength(value); i++) {
        out.add(new Entry((long) i, Array.get(value, i)));
      }
      return out;
    }
    if (value instanceof Number n) {
      for (long i = 0; i < n.longValue(); i++) {
        out.add(new Entry(i, i));
      }
      return out;
    }
    throw new TemplateException("range over " + value.getClass().getSimpleName());
  }

  /**
   * Reads {@code name} off {@code target}, calling it when it is a method and taking it as a
   * field otherwise. A name on a null receiver is null rather than an error, which is what a
   * Go template does for a nil pointer's field.
   */
  public static Object resolve(Object target, String name, List<Object> args) {
    if (target == null) {
      return null;
    }
    if (args.isEmpty() && target instanceof Map<?, ?> map && map.containsKey(name)) {
      return map.get(name);
    }
    var method = findMethod(target.getClass(), name, args.size());
    if (method != null) {
      try {
        method.setAccessible(true);
        return method.invoke(target, coerce(method, args));
      } catch (InvocationTargetException e) {
        throw new TemplateException(
            "calling " + name + ": " + describe(e.getCause()), e.getCause());
      } catch (IllegalAccessException e) {
        throw new TemplateException("calling " + name + ": " + e.getMessage(), e);
      }
    }
    if (args.isEmpty()) {
      var field = findField(target.getClass(), name);
      if (field != null) {
        try {
          field.setAccessible(true);
          return field.get(target);
        } catch (IllegalAccessException e) {
          throw new TemplateException("reading " + name + ": " + e.getMessage(), e);
        }
      }
      if (target instanceof Map<?, ?> map) {
        return map.get(name);
      }
    }
    throw new TemplateException(
        "can't evaluate field " + name + " in type " + target.getClass().getName());
  }

  private static String describe(Throwable cause) {
    if (cause == null) {
      return "unknown error";
    }
    return cause.getMessage() == null ? cause.toString() : cause.getMessage();
  }

  private static Object[] coerce(Method method, List<Object> args) {
    var types = method.getParameterTypes();
    var out = new Object[args.size()];
    for (int i = 0; i < args.size(); i++) {
      out[i] = coerceOne(types[i], args.get(i));
    }
    return out;
  }

  static Object coerceOne(Class<?> type, Object value) {
    if (value == null) {
      return null;
    }
    if (type == int.class || type == Integer.class) {
      return (int) GoFormat.toLong(value);
    }
    if (type == long.class || type == Long.class) {
      return GoFormat.toLong(value);
    }
    if (type == double.class || type == Double.class) {
      return GoFormat.toDouble(value);
    }
    if (type == float.class || type == Float.class) {
      return (float) GoFormat.toDouble(value);
    }
    if (type == boolean.class || type == Boolean.class) {
      return isTrue(value);
    }
    if (type == String.class && !(value instanceof String)) {
      return Escapers.stringify(value);
    }
    return value;
  }

  private static Method findMethod(Class<?> type, String name, int arity) {
    for (var method : type.getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == arity) {
        return method;
      }
    }
    return null;
  }

  private static Field findField(Class<?> type, String name) {
    for (var field : type.getFields()) {
      if (field.getName().equals(name)) {
        return field;
      }
    }
    return null;
  }

  /** Go's {@code eq} and friends, which compare across the numeric kinds but not across kinds. */
  public static int compare(Object a, Object b) {
    if (a instanceof Number || b instanceof Number) {
      double left = GoFormat.toDouble(a);
      double right = GoFormat.toDouble(b);
      return Double.compare(left, right);
    }
    return Escapers.stringify(a).compareTo(Escapers.stringify(b));
  }

  public static boolean equal(Object a, Object b) {
    if (a == null || b == null) {
      return a == b;
    }
    if (a instanceof Boolean || b instanceof Boolean) {
      return isTrue(a) == isTrue(b);
    }
    if (a instanceof Number && b instanceof Number) {
      return GoFormat.toDouble(a) == GoFormat.toDouble(b);
    }
    if (isText(a) && isText(b)) {
      return Escapers.stringify(a).equals(Escapers.stringify(b));
    }
    if (a instanceof Number || b instanceof Number) {
      return GoFormat.toDouble(a) == GoFormat.toDouble(b);
    }
    return a.equals(b);
  }

  private static boolean isText(Object v) {
    return v instanceof String || v instanceof Safe || v instanceof Character;
  }
}
