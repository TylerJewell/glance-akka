package io.akka.glance.application;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;

/** Hands the components the two objects that are shared rather than per-request. */
@Setup
public class GlanceSetup implements ServiceSetup {

  private final ComponentClient componentClient;

  public GlanceSetup(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Override
  public DependencyProvider createDependencyProvider() {
    // One connection pool and one set of workers for the whole service: a fetcher per
    // request would open a pool per request, and the per-feed cache underneath it depends
    // on connections being reused.
    var fetcher = new FeedFetcher(FeedFetcher.DEFAULT_TIMEOUT);
    var refresh = new PageRefresh(componentClient, fetcher);

    return new DependencyProvider() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> T getDependency(Class<T> clazz) {
        if (clazz == FeedFetcher.class) {
          return (T) fetcher;
        }
        if (clazz == PageRefresh.class) {
          return (T) refresh;
        }
        throw new IllegalArgumentException("no such dependency: " + clazz);
      }
    };
  }
}
