package io.akka.glance.render;

import io.akka.glance.gotemplate.Funcs;
import io.akka.glance.gotemplate.GoFormat;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.gotemplate.TemplateException;
import io.akka.glance.util.Numbers;
import io.akka.glance.util.Resources;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The original's template files, parsed, and the functions they call.
 *
 * <p>{@link #of} is {@code mustParseTemplate}: a primary file plus the ones it needs
 * associated with it. The files themselves are shipped unchanged under {@code
 * resources/glance/templates}.
 */
public final class Templates {

  private static final Map<String, Template> CACHE = new ConcurrentHashMap<>();

  private Templates() {}

  /** {@code mustParseTemplate(primary, dependencies...)}. */
  public static Template of(String primary, String... dependencies) {
    String key = primary + "|" + String.join("|", dependencies);
    return CACHE.computeIfAbsent(
        key,
        ignored -> {
          var template = Template.parse(functions(), primary, source(primary));
          for (var dependency : dependencies) {
            template.associate(dependency, source(dependency));
          }
          return template;
        });
  }

  private static String source(String name) {
    return Resources.text("glance/templates/" + name);
  }

  /** {@code globalTemplateFunctions}. */
  public static Funcs functions() {
    var funcs = new Funcs();
    funcs.put("formatApproxNumber", args -> Numbers.approx(GoFormat.toLongPublic(args.getFirst())));
    funcs.put("formatNumber", args -> Numbers.grouped(args.getFirst()));
    funcs.put("safeCSS", args -> Safe.css(text(args.getFirst())));
    funcs.put("safeURL", args -> Safe.url(text(args.getFirst())));
    funcs.put("safeHTML", args -> Safe.html(text(args.getFirst())));
    funcs.put(
        "absInt", args -> Math.abs(GoFormat.toLongPublic(args.getFirst())));
    funcs.put("formatPrice", args -> Numbers.price(2, toDouble(args.getFirst())));
    funcs.put(
        "formatPriceWithPrecision",
        args -> Numbers.price((int) GoFormat.toLongPublic(args.get(0)), toDouble(args.get(1))));
    funcs.put("dynamicRelativeTimeAttrs", args -> dynamicRelativeTimeAttrs(args.getFirst()));
    funcs.put(
        "formatServerMegabytes",
        args -> Safe.html(Numbers.serverMegabytes(GoFormat.toLongPublic(args.getFirst()))));
    return funcs;
  }

  /** {@code dynamicRelativeTimeAttrs} — anything that can answer {@code Unix}. */
  private static Safe dynamicRelativeTimeAttrs(Object value) {
    long seconds =
        switch (value) {
          case null -> 0L;
          case io.akka.glance.util.GoInstant wrapped -> wrapped.Unix();
          case java.time.Instant instant -> instant.getEpochSecond();
          case Number number -> number.longValue();
          default -> throw new TemplateException(
              "dynamicRelativeTimeAttrs: " + value.getClass().getSimpleName() + " has no Unix");
        };
    return Safe.attr("data-dynamic-relative-time=\"" + seconds + "\"");
  }

  private static String text(Object value) {
    return value instanceof Safe safe ? safe.value() : GoFormat.value(value);
  }

  private static double toDouble(Object value) {
    return value instanceof Number number ? number.doubleValue() : 0;
  }

  /** Renders one template to a string, the way {@code executeTemplateToString} does. */
  public static String render(Template template, Object data) {
    return template.execute(data);
  }

  static List<String> names() {
    return List.of();
  }
}
