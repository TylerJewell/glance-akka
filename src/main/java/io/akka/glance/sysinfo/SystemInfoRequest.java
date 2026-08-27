package io.akka.glance.sysinfo;

import io.akka.glance.config.Y;
import java.util.LinkedHashMap;
import java.util.Map;

/** What to look at on a machine, and what to call it. */
public final class SystemInfoRequest {

  @Y("cpu-temp-sensor")
  public String CPUTempSensor = "";

  @Y("hide-mountpoints-by-default")
  public boolean HideMountpointsByDefault;

  @Y("mountpoints")
  public Map<String, MountpointRequest> Mountpoints = new LinkedHashMap<>();

  /** One filesystem's own settings. */
  public static final class MountpointRequest {
    @Y("name")
    public String Name = "";

    /**
     * Absent rather than false when nothing was written, because absent takes the widget's
     * own default and false overrides it.
     */
    @Y("hide")
    public Boolean Hide;
  }
}
