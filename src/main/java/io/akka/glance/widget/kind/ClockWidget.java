package io.akka.glance.widget.kind;

import io.akka.glance.config.ConfigException;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.Widget;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** The time, here and in as many other places as are configured. */
public final class ClockWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("clock.html", "widget-base.html");

  @Y(skip = true)
  private Safe cachedHTML = Safe.html("");

  @Y("hour-format")
  public String HourFormat = "";

  @Y("timezones")
  public List<Timezone> Timezones = new ArrayList<>();

  /** One other place, and what to call it. */
  public static final class Timezone {
    @Y("timezone")
    public String Timezone = "";

    @Y("label")
    public String Label = "";
  }

  @Override
  public void initialize() {
    withTitle("Clock").withError(null);
    if (HourFormat.isEmpty()) {
      HourFormat = "24h";
    } else if (!HourFormat.equals("12h") && !HourFormat.equals("24h")) {
      throw new ConfigException("hour-format must be either 12h or 24h");
    }
    for (var zone : Timezones) {
      if (zone.Timezone.isEmpty()) {
        throw new ConfigException("missing timezone value");
      }
      try {
        ZoneId.of(zone.Timezone);
      } catch (DateTimeException e) {
        throw new ConfigException(
            "invalid timezone '" + zone.Timezone + "': unknown time zone " + zone.Timezone);
      }
    }
    cachedHTML = renderTemplate(this, TEMPLATE);
  }

  @Override
  public Safe Render() {
    return cachedHTML;
  }
}
