package io.akka.glance.widget.kind;

import io.akka.glance.config.ConfigException;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.Widget;
import java.util.Map;

/** A month, drawn in the browser. The widget's only decision is which day the week starts on. */
public final class CalendarWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("calendar.html", "widget-base.html");

  private static final Map<String, Integer> WEEKDAYS =
      Map.of(
          "sunday", 0,
          "monday", 1,
          "tuesday", 2,
          "wednesday", 3,
          "thursday", 4,
          "friday", 5,
          "saturday", 6);

  @Y("first-day-of-week")
  public String FirstDayOfWeek = "";

  @Y(skip = true)
  public int FirstDay;

  @Y(skip = true)
  private Safe cachedHTML = Safe.html("");

  @Override
  public void initialize() {
    withTitle("Calendar").withError(null);
    if (FirstDayOfWeek.isEmpty()) {
      FirstDayOfWeek = "monday";
    } else if (!WEEKDAYS.containsKey(FirstDayOfWeek)) {
      throw new ConfigException("invalid first day of week");
    }
    FirstDay = WEEKDAYS.get(FirstDayOfWeek);
    cachedHTML = renderTemplate(this, TEMPLATE);
  }

  @Override
  public Safe Render() {
    return cachedHTML;
  }
}
