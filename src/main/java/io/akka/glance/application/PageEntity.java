package io.akka.glance.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.List;

/** Which widgets a page holds, in the order the page draws them. */
@Component(id = "page")
public class PageEntity extends KeyValueEntity<PageEntity.Page> {

  public record Page(String title, List<String> widgetIds) {}

  @Override
  public Page emptyState() {
    return new Page("", List.of());
  }

  public Effect<Page> configure(Page page) {
    return effects()
        .updateState(new Page(page.title(), List.copyOf(page.widgetIds())))
        .thenReply(page);
  }

  public ReadOnlyEffect<Page> get() {
    return effects().reply(currentState());
  }
}
