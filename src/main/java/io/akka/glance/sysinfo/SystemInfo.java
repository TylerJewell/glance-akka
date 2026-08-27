package io.akka.glance.sysinfo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * What one machine reports about itself.
 *
 * <p>Every reading carries a flag saying whether it could be taken, because a widget shows
 * nothing rather than a zero where the machine would not answer. The JSON names are the
 * original's, so a glance instance and this one can act as each other's remote agent.
 */
public final class SystemInfo {

  @JsonProperty("host_info_is_available")
  public boolean HostInfoIsAvailable;

  @JsonProperty("boot_time")
  public long BootTimeSeconds;

  @JsonProperty("hostname")
  public String Hostname = "";

  @JsonProperty("platform")
  public String Platform = "";

  @JsonProperty("cpu")
  public Cpu CPU = new Cpu();

  @JsonProperty("memory")
  public Memory Memory = new Memory();

  @JsonProperty("mountpoints")
  public List<MountpointInfo> Mountpoints = new ArrayList<>();

  /** What a template reads for the relative time since the machine started. */
  public io.akka.glance.util.GoInstant BootTime() {
    return io.akka.glance.util.GoInstant.of(java.time.Instant.ofEpochSecond(BootTimeSeconds));
  }

  /** Load and temperature. */
  public static final class Cpu {
    @JsonProperty("load_is_available")
    public boolean LoadIsAvailable;

    @JsonProperty("load1_percent")
    public int Load1Percent;

    @JsonProperty("load15_percent")
    public int Load15Percent;

    @JsonProperty("temperature_is_available")
    public boolean TemperatureIsAvailable;

    @JsonProperty("temperature_c")
    public int TemperatureC;
  }

  /** Memory in use, and swap. */
  public static final class Memory {
    @JsonProperty("memory_is_available")
    public boolean IsAvailable;

    @JsonProperty("total_mb")
    public long TotalMB;

    @JsonProperty("used_mb")
    public long UsedMB;

    @JsonProperty("used_percent")
    public int UsedPercent;

    @JsonProperty("swap_is_available")
    public boolean SwapIsAvailable;

    @JsonProperty("swap_total_mb")
    public long SwapTotalMB;

    @JsonProperty("swap_used_mb")
    public long SwapUsedMB;

    @JsonProperty("swap_used_percent")
    public int SwapUsedPercent;
  }

  /** One filesystem. */
  public static final class MountpointInfo {
    @JsonProperty("path")
    public String Path = "";

    @JsonProperty("name")
    public String Name = "";

    @JsonProperty("total_mb")
    public long TotalMB;

    @JsonProperty("used_mb")
    public long UsedMB;

    @JsonProperty("used_percent")
    public int UsedPercent;
  }
}
