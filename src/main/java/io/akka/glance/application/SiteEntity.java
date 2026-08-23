package io.akka.glance.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.List;

/**
 * Which pages the site has, in the order the navigation lists them.
 *
 * <p>The original reads this out of the same configuration file the pages come from, so its
 * shell can draw a link per page on every page. Held separately here because a page does not
 * otherwise need to know about its siblings.
 */
@Component(id = "site")
public class SiteEntity extends KeyValueEntity<SiteEntity.Site> {

  /** The one instance: a service serves one site. */
  public static final String ID = "site";

  public record Link(String slug, String title) {}

  public record Site(List<Link> pages) {}

  @Override
  public Site emptyState() {
    return new Site(List.of());
  }

  public Effect<Site> configure(Site site) {
    return effects().updateState(new Site(List.copyOf(site.pages()))).thenReply(site);
  }

  public ReadOnlyEffect<Site> get() {
    return effects().reply(currentState());
  }
}
