package io.akka.glance.app;

import io.akka.glance.config.Config;
import io.akka.glance.config.ConfigLoader;
import io.akka.glance.widget.Widget;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The configuration this server is currently running.
 *
 * <p>One at a time: replacing it is what a changed configuration file does, and every request
 * after that reads the new one. A page is refreshed under its own lock, because the widgets on
 * it hold the state a refresh changes and two requests refreshing the same page at once would
 * each see half of the other's work.
 */
public final class Site {

  private static volatile Application current;

  private static final Map<String, ReentrantLock> pageLocks = new ConcurrentHashMap<>();

  /** Bumped whenever a page's content changes, which is what an open page watches. */
  private static final Map<String, Long> pageRevisions = new ConcurrentHashMap<>();

  private Site() {}

  public static void load(String yaml, Instant now) {
    replace(new Application(ConfigLoader.fromYaml(yaml), now));
  }

  public static void replace(Application application) {
    current = application;
    pageLocks.clear();
    pageRevisions.clear();
  }

  public static Application application() {
    var application = current;
    if (application == null) {
      throw new IllegalStateException("no configuration has been loaded");
    }
    return application;
  }

  public static boolean isLoaded() {
    return current != null;
  }

  /** Refreshes whatever on the page is due, and says whether anything changed. */
  public static boolean refresh(Config.Page page, Instant now) {
    var lock = lockFor(page);
    lock.lock();
    try {
      return refreshHeld(page, now);
    } finally {
      lock.unlock();
    }
  }

  /** Renders one page's contents, refreshing anything due first. */
  public static String content(Config.Page page, Instant now) {
    var lock = lockFor(page);
    lock.lock();
    try {
      refreshHeld(page, now);
      return render(page);
    } finally {
      lock.unlock();
    }
  }

  /**
   * The page's contents when a refresh has changed them since {@code seen}, and nothing when
   * it has not.
   *
   * <p>One acquisition covers the refresh, the revision and the rendering, so what comes back
   * is the markup that revision names rather than whatever a second request left behind
   * between the two.
   */
  public static Update updated(Config.Page page, Instant now, long seen) {
    var lock = lockFor(page);
    lock.lock();
    try {
      refreshHeld(page, now);
      long revision = pageRevisions.getOrDefault(page.Slug, 0L);
      return revision == seen ? new Update(revision, null) : new Update(revision, render(page));
    } finally {
      lock.unlock();
    }
  }

  /** Which revision a page is at, and its markup when that revision is new to the caller. */
  public record Update(long revision, String content) {}

  private static ReentrantLock lockFor(Config.Page page) {
    return pageLocks.computeIfAbsent(page.Slug, ignored -> new ReentrantLock());
  }

  private static String render(Config.Page page) {
    return io.akka.glance.render.Templates.of("page-content.html")
        .execute(new Application.TemplateData(null, page, new Application.RequestData(null)));
  }

  /** The refresh itself, with the page's lock already held. */
  private static boolean refreshHeld(Config.Page page, Instant now) {
    var due = new ArrayList<Widget>();
    for (var widget : page.HeadWidgets) {
      if (widget.requiresUpdate(now)) {
        due.add(widget);
      }
    }
    for (var column : page.Columns) {
      for (var widget : column.Widgets) {
        if (widget.requiresUpdate(now)) {
          due.add(widget);
        }
      }
    }
    if (due.isEmpty()) {
      return false;
    }
    var running = new ArrayList<java.util.concurrent.Future<?>>(due.size());
    for (var widget : due) {
      running.add(io.akka.glance.widget.Fetches.submit(() -> widget.update(now)));
    }
    for (var future : running) {
      try {
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (java.util.concurrent.ExecutionException e) {
        // A widget records its own failure; nothing here can do better than let it.
      }
    }
    pageRevisions.merge(page.Slug, 1L, Long::sum);
    return true;
  }

  /** How many times this page's contents have changed since the configuration was loaded. */
  public static long revision(String slug) {
    return pageRevisions.getOrDefault(slug, 0L);
  }

  /** Every widget on a page, containers walked into. */
  public static List<Widget> widgetsOf(Config.Page page) {
    var out = new ArrayList<Widget>();
    for (var widget : page.HeadWidgets) {
      collect(widget, out);
    }
    for (var column : page.Columns) {
      for (var widget : column.Widgets) {
        collect(widget, out);
      }
    }
    return out;
  }

  private static void collect(Widget widget, List<Widget> into) {
    into.add(widget);
    if (widget instanceof io.akka.glance.widget.ContainerWidget container) {
      for (var child : container.children()) {
        collect(child, into);
      }
    }
  }
}
