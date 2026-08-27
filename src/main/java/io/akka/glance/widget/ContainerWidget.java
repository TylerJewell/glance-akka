package io.akka.glance.widget;

import io.akka.glance.config.Config;
import io.akka.glance.config.ConfigException;
import io.akka.glance.config.Y;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * A widget that holds other widgets: a group, or a split column.
 *
 * <p>It has no schedule of its own — it is due when any of the widgets inside it is, and
 * updating it updates those, each on its own thread the way the original does.
 */
public abstract class ContainerWidget extends Widget {

  @Y("widgets")
  public Config.Widgets Widgets = new Config.Widgets();

  /** The children, for a caller that walks the tree. */
  public List<Widget> children() {
    return List.copyOf(Widgets);
  }

  protected void initializeWidgets() {
    for (var widget : Widgets) {
      try {
        widget.initialize();
      } catch (ConfigException e) {
        throw new ConfigException(widget.GetType() + " widget: " + e.getMessage());
      }
    }
  }

  @Override
  public void update(Instant now) {
    var running = new ArrayList<Future<?>>();
    for (var widget : Widgets) {
      if (!widget.requiresUpdate(now)) {
        continue;
      }
      running.add(Fetches.submit(() -> widget.update(now)));
    }
    for (var future : running) {
      try {
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        // A widget records its own failure; nothing here can do better than let it.
      }
    }
  }

  @Override
  public void setProviders(Providers providers) {
    super.setProviders(providers);
    for (var widget : Widgets) {
      widget.setProviders(providers);
    }
  }

  @Override
  public boolean requiresUpdate(Instant now) {
    for (var widget : Widgets) {
      if (widget.requiresUpdate(now)) {
        return true;
      }
    }
    return false;
  }
}
