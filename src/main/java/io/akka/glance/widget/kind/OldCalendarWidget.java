package io.akka.glance.widget.kind;

import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.Widget;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Three weeks around today, worked out on the server and refreshed on the hour. */
public final class OldCalendarWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("old-calendar.html", "widget-base.html");

  @Y(skip = true)
  public Calendar Calendar;

  @Y("start-sunday")
  public boolean StartSunday;

  /** What the template draws: the three weeks, and where today sits inside them. */
  public static final class Calendar {
    public int CurrentDay;
    public int CurrentWeekNumber;
    public String CurrentMonthName = "";
    public int CurrentYear;
    public List<Integer> Days = new ArrayList<>();
  }

  @Override
  public void initialize() {
    withTitle("Calendar").withCacheOnTheHour();
  }

  @Override
  public void update(Instant now) {
    Calendar = build(LocalDate.ofInstant(now, ZoneId.systemDefault()), StartSunday);
    withError(null);
    scheduleNextUpdate(now);
  }

  /**
   * The week before, this week and the week after, as day numbers. A number outside this
   * month is the corresponding day of the neighbouring one, which is why the list carries
   * numbers rather than dates.
   */
  public static Calendar build(LocalDate today, boolean startSunday) {
    var isoWeekFields = WeekFields.ISO;
    int year = today.get(isoWeekFields.weekBasedYear());
    int week = today.get(isoWeekFields.weekOfWeekBasedYear());

    // Go counts Sunday as 0; shifting by six makes Monday the start instead.
    int weekday = today.getDayOfWeek().getValue() % 7;
    if (!startSunday) {
      weekday = (weekday + 6) % 7;
    }

    int currentMonthDays = today.lengthOfMonth();
    int previousMonthDays = today.minusMonths(1).lengthOfMonth();
    int startDaysFrom = today.getDayOfMonth() - weekday - 7;

    var days = new ArrayList<Integer>(21);
    for (int i = 0; i < 21; i++) {
      int day = startDaysFrom + i;
      if (day < 1) {
        day = previousMonthDays + day;
      } else if (day > currentMonthDays) {
        day = day - currentMonthDays;
      }
      days.add(day);
    }

    var calendar = new Calendar();
    calendar.CurrentDay = today.getDayOfMonth();
    calendar.CurrentWeekNumber = week;
    calendar.CurrentMonthName = today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    calendar.CurrentYear = year;
    calendar.Days = days;
    return calendar;
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
