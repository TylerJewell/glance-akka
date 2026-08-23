package io.akka.glance.application;

import akka.javasdk.client.ComponentClient;
import io.akka.glance.domain.FeedMerge;
import io.akka.glance.domain.WidgetState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The page's refresh pass. SPEC-001 R13, R14.
 *
 * <p>Every widget the page holds that is due at {@code now} is refreshed, none that are not,
 * and the pass does not return until all of them have finished — which is what makes a page
 * response show the result of its own refresh rather than the previous one.
 */
public final class PageRefresh implements AutoCloseable {

  /** A page's widgets after a refresh pass: the top-level ones, each with its children. */
  public record Rendered(String id, WidgetState state, List<Rendered> children) {

    public boolean isContainer() {
      return !children.isEmpty();
    }
  }

  private final ComponentClient componentClient;
  private final FeedFetcher fetcher;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

  public PageRefresh(ComponentClient componentClient, FeedFetcher fetcher) {
    this.componentClient = componentClient;
    this.fetcher = fetcher;
  }

  /** Refreshes a page's due widgets and returns every widget's state, in page order. */
  public List<Rendered> refreshDue(String slug, Instant now) {
    var page = componentClient.forKeyValueEntity(slug).method(PageEntity::get).invoke();

    var loaded = new LinkedHashMap<String, WidgetEntity.Widget>();
    var due = new ArrayList<String>();
    for (var id : page.widgetIds()) {
      collect(id, loaded, due, now);
    }

    var refreshed = refreshAll(due, loaded, now);
    var out = new ArrayList<Rendered>();
    for (var id : page.widgetIds()) {
      out.add(assemble(id, loaded, refreshed));
    }
    return List.copyOf(out);
  }

  /** Reads a page's widgets without refreshing anything. */
  public List<Rendered> read(String slug) {
    var page = componentClient.forKeyValueEntity(slug).method(PageEntity::get).invoke();
    var loaded = new LinkedHashMap<String, WidgetEntity.Widget>();
    for (var id : page.widgetIds()) {
      collect(id, loaded, new ArrayList<>(), Instant.EPOCH);
    }
    return page.widgetIds().stream().map(id -> assemble(id, loaded, Map.of())).toList();
  }

  /**
   * Refreshes one widget whatever its deadline says, and returns the state it left behind.
   *
   * @param token identifies this refresh so a redelivered timer firing is applied once
   */
  public WidgetState refreshNow(String widgetId, Instant now, String token) {
    var widget = componentClient.forKeyValueEntity(widgetId).method(WidgetEntity::get).invoke();
    return refreshOne(widgetId, widget, now, token);
  }

  /**
   * Loads a widget and, where it holds others, its children — and lists the ones that are
   * due. A container is never itself due: what a container's due-ness means is that one of
   * its children is, and it is the child that gets refreshed.
   */
  private void collect(
      String id, Map<String, WidgetEntity.Widget> loaded, List<String> due, Instant now) {
    if (loaded.containsKey(id)) {
      return;
    }
    var widget = componentClient.forKeyValueEntity(id).method(WidgetEntity::get).invoke();
    loaded.put(id, widget);

    if (widget.isContainer()) {
      for (var childId : widget.childIds()) {
        collect(childId, loaded, due, now);
      }
      return;
    }
    if (widget.state().isDue(now)) {
      due.add(id);
    }
  }

  private Map<String, WidgetState> refreshAll(
      List<String> ids, Map<String, WidgetEntity.Widget> widgets, Instant now) {
    // One widget waiting on a slow feed must not hold up the rest of the page, so the
    // widgets run at the same time and the pass joins them all before returning.
    var pending = new LinkedHashMap<String, CompletableFuture<WidgetState>>();
    for (var id : ids) {
      var widget = widgets.get(id);
      pending.put(
          id, CompletableFuture.supplyAsync(() -> refreshOne(id, widget, now, null), workers));
    }

    var out = new LinkedHashMap<String, WidgetState>();
    for (var entry : pending.entrySet()) {
      try {
        out.put(entry.getKey(), entry.getValue().join());
      } catch (RuntimeException e) {
        // A refresh that could not be applied leaves the widget as it was; the page still
        // draws, which is the whole point of the partial-failure rules underneath it.
        out.put(entry.getKey(), widgets.get(entry.getKey()).state());
      }
    }
    return out;
  }

  private WidgetState refreshOne(
      String id, WidgetEntity.Widget widget, Instant now, String token) {
    var results = fetcher.fetchAll(widget.feeds(), widget.state().feedCache());
    var outcome =
        FeedMerge.merge(widget.feeds(), results, widget.state().preserveOrder(), widget.state().limit());
    return componentClient
        .forKeyValueEntity(id)
        .method(WidgetEntity::apply)
        .invoke(new WidgetEntity.Applied(outcome, now, token));
  }

  private Rendered assemble(
      String id, Map<String, WidgetEntity.Widget> loaded, Map<String, WidgetState> refreshed) {
    var widget = loaded.get(id);
    var children = new ArrayList<Rendered>();
    if (widget.isContainer()) {
      for (var childId : widget.childIds()) {
        children.add(assemble(childId, loaded, refreshed));
      }
    }
    var state = refreshed.getOrDefault(id, widget.state());
    return new Rendered(id, state, List.copyOf(children));
  }

  /**
   * The pool a caller on a non-blocking thread hands a read to. The reads are component
   * calls and they block; the stream that drives them must not.
   */
  public ExecutorService readers() {
    return workers;
  }

  @Override
  public void close() {
    workers.shutdown();
  }
}
