package io.akka.glance.sysinfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Reads the machine this is running on.
 *
 * <p>Host name, platform and boot time are read once and kept: they do not change while the
 * process runs, and asking for them is the expensive part.
 */
public final class Sysinfo {

  private static final oshi.SystemInfo OSHI = new oshi.SystemInfo();

  /** The sensor keys the original recognises as the processor's own. */
  private static final List<String> CPU_TEMPERATURE_SENSORS =
      List.of("coretemp_package_id_0", "coretemp", "k10temp", "zenpower", "cpu_thermal");

  private static CachedHost cachedHost;

  private record CachedHost(String hostname, String platform, long bootTimeSeconds) {}

  private Sysinfo() {}

  /** What was read, and everything that could not be. */
  public record Collected(SystemInfo info, List<String> errors) {}

  public static Collected collect(SystemInfoRequest request) {
    if (request == null) {
      request = new SystemInfoRequest();
    }
    var errors = new ArrayList<String>();
    var info = new SystemInfo();

    if (cachedHost == null) {
      try {
        var os = OSHI.getOperatingSystem();
        cachedHost =
            new CachedHost(
                OSHI.getOperatingSystem().getNetworkParams().getHostName(),
                os.getFamily().toLowerCase(Locale.ROOT),
                os.getSystemBootTime());
      } catch (RuntimeException e) {
        errors.add("getting host info: " + e.getMessage());
      }
    }
    if (cachedHost != null) {
      info.HostInfoIsAvailable = true;
      info.Hostname = cachedHost.hostname();
      info.Platform = cachedHost.platform();
      info.BootTimeSeconds = cachedHost.bootTimeSeconds();
    }

    try {
      var processor = OSHI.getHardware().getProcessor();
      int cores = processor.getLogicalProcessorCount();
      double[] loads = processor.getSystemLoadAverage(3);
      if (cores > 0 && loads.length >= 3 && loads[0] >= 0) {
        info.CPU.LoadIsAvailable = true;
        if (isWindows()) {
          // The figures Windows reports do not divide sensibly by core count.
          info.CPU.Load1Percent = (int) Math.min(loads[0] * 100, 100);
          info.CPU.Load15Percent = (int) Math.min(loads[2] * 100, 100);
        } else {
          info.CPU.Load1Percent = (int) Math.min(loads[0] / cores * 100, 100);
          info.CPU.Load15Percent = (int) Math.min(loads[2] / cores * 100, 100);
        }
      } else if (cores <= 0) {
        errors.add("getting core count: no processors reported");
      } else {
        errors.add("getting load avg: load average is not available on this platform");
      }
    } catch (RuntimeException e) {
      errors.add("getting load avg: " + e.getMessage());
    }

    try {
      var memory = OSHI.getHardware().getMemory();
      long total = memory.getTotal();
      long used = total - memory.getAvailable();
      info.Memory.IsAvailable = true;
      info.Memory.TotalMB = total / 1024 / 1024;
      info.Memory.UsedMB = used / 1024 / 1024;
      info.Memory.UsedPercent =
          total == 0 ? 0 : (int) Math.min((double) used / total * 100, 100);

      var swap = memory.getVirtualMemory();
      long swapTotal = swap.getSwapTotal();
      long swapUsed = swap.getSwapUsed();
      info.Memory.SwapIsAvailable = true;
      info.Memory.SwapTotalMB = swapTotal / 1024 / 1024;
      info.Memory.SwapUsedMB = swapUsed / 1024 / 1024;
      info.Memory.SwapUsedPercent =
          swapTotal == 0 ? 0 : (int) Math.min((double) swapUsed / swapTotal * 100, 100);
    } catch (RuntimeException e) {
      errors.add("getting memory info: " + e.getMessage());
    }

    // The original does not read a temperature on Windows or the BSDs, where the reading
    // either needs raised privileges or is not implemented.
    if (!isWindows() && !isBsd()) {
      try {
        var readings = temperatures();
        if (!request.CPUTempSensor.isEmpty()) {
          for (var reading : readings) {
            if (reading.key().equals(request.CPUTempSensor)) {
              info.CPU.TemperatureIsAvailable = true;
              info.CPU.TemperatureC = (int) reading.celsius();
              break;
            }
          }
          if (!info.CPU.TemperatureIsAvailable) {
            errors.add("CPU temperature sensor " + request.CPUTempSensor + " not found");
          }
        } else {
          for (var reading : readings) {
            if (CPU_TEMPERATURE_SENSORS.contains(reading.key())) {
              info.CPU.TemperatureIsAvailable = true;
              info.CPU.TemperatureC = (int) reading.celsius();
              break;
            }
          }
        }
      } catch (RuntimeException e) {
        errors.add("getting sensor readings: " + e.getMessage());
      }
    }

    var added = new LinkedHashSet<String>();
    if (!request.HideMountpointsByDefault) {
      try {
        for (var store : OSHI.getOperatingSystem().getFileSystem().getFileStores()) {
          addMountpoint(
              info, errors, added, store.getMount(), request.Mountpoints.get(store.getMount()),
              request.HideMountpointsByDefault);
        }
      } catch (RuntimeException e) {
        errors.add("getting filesystems: " + e.getMessage());
      }
    }
    for (var entry : request.Mountpoints.entrySet()) {
      addMountpoint(
          info, errors, added, entry.getKey(), entry.getValue(), request.HideMountpointsByDefault);
    }
    info.Mountpoints.sort(
        Comparator.comparingInt((SystemInfo.MountpointInfo m) -> m.UsedPercent).reversed());
    return new Collected(info, errors);
  }

  private static void addMountpoint(
      SystemInfo info,
      List<String> errors,
      LinkedHashSet<String> added,
      String path,
      SystemInfoRequest.MountpointRequest request,
      boolean hideByDefault) {
    if (added.contains(path)) {
      return;
    }
    boolean hidden = hideByDefault;
    if (request != null && request.Hide != null) {
      hidden = request.Hide;
    }
    if (hidden) {
      return;
    }
    var usage = usage(path);
    if (usage == null) {
      errors.add("getting filesystem usage for " + path + ": no such file or directory");
      return;
    }
    var mountpoint = new SystemInfo.MountpointInfo();
    mountpoint.Path = path;
    mountpoint.Name = request == null ? "" : request.Name;
    mountpoint.TotalMB = usage.total() / 1024 / 1024;
    mountpoint.UsedMB = usage.used() / 1024 / 1024;
    mountpoint.UsedPercent =
        usage.total() == 0
            ? 0
            : (int) Math.min((double) usage.used() / usage.total() * 100, 100);
    info.Mountpoints.add(mountpoint);
    added.add(path);
  }

  private record Usage(long total, long used) {}

  private static Usage usage(String path) {
    for (var store : OSHI.getOperatingSystem().getFileSystem().getFileStores()) {
      if (store.getMount().equals(path)) {
        long total = store.getTotalSpace();
        return new Usage(total, total - store.getUsableSpace());
      }
    }
    var file = new java.io.File(path);
    if (!file.exists()) {
      return null;
    }
    long total = file.getTotalSpace();
    return new Usage(total, total - file.getUsableSpace());
  }

  /** One sensor's reading. */
  public record Temperature(String key, double celsius) {}

  /** Every temperature sensor this machine reports. */
  public static List<Temperature> temperatures() {
    var out = new ArrayList<Temperature>();
    var sensors = OSHI.getHardware().getSensors();
    double cpu = sensors.getCpuTemperature();
    if (cpu > 0) {
      out.add(new Temperature("coretemp", cpu));
    }
    return out;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static boolean isBsd() {
    String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return name.contains("bsd");
  }
}
