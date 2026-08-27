package io.akka.glance.widget;

import io.akka.glance.config.DurationField;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What every widget carries: its heading, its schedule, and whether it has anything to show.
 *
 * <p>The public fields spell their names the way the original's structs do, because the
 * original's own template files read them by those names.
 *
 * <p>The clock is an argument rather than a reading of the wall clock. The original reads
 * {@code time.Now()} at each of these sites; taking it as a parameter is what lets the same
 * instant be put to both systems when they are compared.
 */
public abstract class Widget {

  /** How the widget's content goes stale. */
  public enum CacheType {
    /** Never; the content is built once when the configuration is read. */
    INFINITE,
    /** After a fixed span. */
    DURATION,
    /** At the top of the next hour. */
    ON_THE_HOUR
  }

  private static final AtomicLong ID_COUNTER = new AtomicLong();

  @Y(skip = true)
  public long ID;

  @Y(skip = true)
  public Providers Providers;

  @Y("type")
  public String Type = "";

  @Y("title")
  public String Title = "";

  @Y("title-url")
  public String TitleURL = "";

  @Y("hide-header")
  public boolean HideHeader;

  @Y("css-class")
  public String CSSClass = "";

  @Y("cache")
  public DurationField CustomCacheDuration;

  @Y(skip = true)
  public boolean ContentAvailable;

  @Y(skip = true)
  public boolean WIP;

  @Y(skip = true)
  public Err Error;

  @Y(skip = true)
  public Err Notice;

  @Y(skip = true)
  protected Duration cacheDuration = Duration.ZERO;

  @Y(skip = true)
  protected CacheType cacheType = CacheType.INFINITE;

  @Y(skip = true)
  protected Instant nextUpdate;

  @Y(skip = true)
  protected int updateRetriedTimes;

  protected Widget() {
    ID = ID_COUNTER.incrementAndGet();
  }

  /** Only for a test that needs two runs to produce the same identifiers. */
  public static void resetIdCounter() {
    ID_COUNTER.set(0);
  }

  // Read from the templates.

  public abstract Safe Render();

  public String GetType() {
    return Type;
  }

  public long GetID() {
    return ID;
  }

  public boolean IsWIP() {
    return WIP;
  }

  // The rest is the machinery around a widget rather than what it shows.

  /** Checks the configuration and builds whatever does not depend on a fetch. */
  public abstract void initialize();

  /** Fetches. The default does nothing, which is right for a widget with static content. */
  public void update(Instant now) {}

  public boolean requiresUpdate(Instant now) {
    if (cacheType == CacheType.INFINITE) {
      return false;
    }
    if (nextUpdate == null) {
      return true;
    }
    return now.isAfter(nextUpdate);
  }

  public void setProviders(Providers providers) {
    this.Providers = providers;
  }

  public void setHideHeader(boolean value) {
    this.HideHeader = value;
  }

  public Instant nextUpdate() {
    return nextUpdate;
  }

  public int updateRetriedTimes() {
    return updateRetriedTimes;
  }

  public CacheType cacheType() {
    return cacheType;
  }

  public Duration cacheDuration() {
    return cacheDuration;
  }

  /** Renders one of this widget's templates, falling back to the error markup on a failure. */
  public Safe renderTemplate(Object data, Template template) {
    try {
      return Safe.html(template.execute(data));
    } catch (RuntimeException first) {
      ContentAvailable = false;
      Error = Err.of(first);
      try {
        return Safe.html(template.execute(data));
      } catch (RuntimeException second) {
        return Safe.html("");
      }
    }
  }

  public Widget withTitle(String title) {
    if (Title.isEmpty()) {
      Title = title;
    }
    return this;
  }

  public Widget withTitleURL(String titleUrl) {
    if (TitleURL.isEmpty()) {
      TitleURL = titleUrl;
    }
    return this;
  }

  public Widget withCacheDuration(Duration duration) {
    cacheType = CacheType.DURATION;
    if (duration.isNegative() || CustomCacheDuration == null || CustomCacheDuration.isZero()) {
      cacheDuration = duration;
    } else {
      cacheDuration = CustomCacheDuration.duration();
    }
    return this;
  }

  public Widget withCacheOnTheHour() {
    cacheType = CacheType.ON_THE_HOUR;
    return this;
  }

  public Widget withNotice(Err error) {
    Notice = error;
    return this;
  }

  public Widget withError(Err error) {
    if (error == null && !ContentAvailable) {
      ContentAvailable = true;
    }
    Error = error;
    return this;
  }

  /**
   * What an update does with whatever came back. Partial content is kept and reported as a
   * notice; anything else replaces the content with the error.
   *
   * @return whether the update should carry on and use what it fetched
   */
  public boolean canContinueUpdateAfterHandlingErr(Err error, Instant now) {
    if (error != null) {
      scheduleEarlyUpdate(now);
      if (!error.is(Err.PARTIAL_CONTENT)) {
        withError(error);
        withNotice(null);
        return false;
      }
      withError(null);
      withNotice(error);
      return true;
    }
    withNotice(null);
    withError(null);
    scheduleNextUpdate(now);
    return true;
  }

  public Instant getNextUpdateTime(Instant now) {
    if (cacheType == CacheType.DURATION) {
      return now.plus(cacheDuration);
    }
    if (cacheType == CacheType.ON_THE_HOUR) {
      var local = ZonedDateTime.ofInstant(now, ZoneId.systemDefault());
      long seconds = (60L - local.getMinute()) * 60L - local.getSecond();
      return now.plusSeconds(seconds);
    }
    return null;
  }

  public Widget scheduleNextUpdate(Instant now) {
    nextUpdate = getNextUpdateTime(now);
    updateRetriedTimes = 0;
    return this;
  }

  /**
   * A failed update is retried sooner, and the wait grows with the square of how many
   * failures there have been, up to five.
   */
  public Widget scheduleEarlyUpdate(Instant now) {
    updateRetriedTimes++;
    if (updateRetriedTimes > 5) {
      updateRetriedTimes = 5;
    }
    var early = now.plus(Duration.ofMinutes((long) Math.pow(updateRetriedTimes, 2)));
    var usual = getNextUpdateTime(now);
    nextUpdate = usual != null && early.isAfter(usual) ? usual : early;
    return this;
  }
}
