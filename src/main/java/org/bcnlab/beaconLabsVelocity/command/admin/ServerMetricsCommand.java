package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.io.File;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.text.DecimalFormat;
import java.util.concurrent.TimeUnit;

/**
 * Command to display server metrics like CPU, memory, and disk usage
 */
public class ServerMetricsCommand implements SimpleCommand {

    private final String permission;
    private final BeaconLabsVelocity plugin;
    private final DecimalFormat df = new DecimalFormat("#.##");
      public ServerMetricsCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
        this.permission = "beaconlabs.admin.servermetrics";
    }

    @Override    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        
        // Check permission
        if (!source.hasPermission(permission)) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Header
        Component header = Component.text("━━━ SERVER METRICS ━━━", NamedTextColor.GOLD, TextDecoration.BOLD);
          // Get system metrics
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        
        // OS Info
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        int availableProcessors = osBean.getAvailableProcessors();
          // JVM Memory Usage
        long maxMemory = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        long allocatedMemory = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        long freeMemory = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        long usedMemory = allocatedMemory - freeMemory;
        
        // System Memory Usage
        SystemMemoryInfo systemMemory = getSystemMemoryInfo();
        
        // Get CPU Load
        double cpuLoad = getCpuLoad(osBean);
        
        // JVM Uptime
        long uptime = runtimeBean.getUptime();
        String formattedUptime = formatUptime(uptime);
        
        // Disk Usage
        String diskUsage = getDiskUsage();
        
        // Build the message
        Component message = Component.empty()
            .append(header).append(Component.newline())
            .append(Component.text("OS: ", NamedTextColor.YELLOW))
            .append(Component.text(osName + " " + osVersion + " (" + osArch + ")", NamedTextColor.WHITE))
            .append(Component.newline())            .append(Component.text("CPU: ", NamedTextColor.YELLOW))
            .append(Component.text(df.format(cpuLoad) + "% ", getCpuLoadColor(cpuLoad)))
            .append(Component.text("(" + availableProcessors + " cores)", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("JVM Memory: ", NamedTextColor.YELLOW))
            .append(Component.text(usedMemory + "MB / " + maxMemory + "MB ", getMemoryLoadColor(usedMemory, maxMemory)))
            .append(Component.text("(" + df.format((double)usedMemory / maxMemory * 100) + "%)", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("System Memory: ", NamedTextColor.YELLOW))
            .append(Component.text(systemMemory.getUsedFormatted() + " / " + systemMemory.getTotalFormatted() + " ", 
                    getMemoryLoadColor(systemMemory.getUsedMB(), systemMemory.getTotalMB())))
            .append(Component.text("(" + df.format(systemMemory.getUsedPercentage()) + "%)", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("Disk: ", NamedTextColor.YELLOW))
            .append(Component.text(diskUsage, NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("JVM Uptime: ", NamedTextColor.YELLOW))
            .append(Component.text(formattedUptime, NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("Java Version: ", NamedTextColor.YELLOW))
            .append(Component.text(System.getProperty("java.version"), NamedTextColor.WHITE));
        
        source.sendMessage(plugin.getPrefix().append(message));
    }
    
    /**
     * Get the CPU load as a percentage (0-100)
     * 
     * @param osBean The OperatingSystemMXBean to use
     * @return CPU load as a percentage
     */
    private double getCpuLoad(OperatingSystemMXBean osBean) {
        double load = osBean.getSystemLoadAverage();
        
        // If load is negative, it's not available on this platform
        if (load < 0) {
            // Try different approach using the com.sun package if available
            try {
                java.lang.reflect.Method method = osBean.getClass().getDeclaredMethod("getProcessCpuLoad");
                method.setAccessible(true);
                load = ((Double) method.invoke(osBean)) * 100;
            } catch (Exception e) {
                // Fallback to a default value or estimation
                load = 0;
            }
        } else {
            // Convert load average to a percentage based on available processors
            load = (load / osBean.getAvailableProcessors()) * 100;
        }
        
        return load < 0 ? 0 : (load > 100 ? 100 : load);
    }
    
    /**
     * Get disk usage information for the root partition
     * 
     * @return Formatted disk usage string
     */
    private String getDiskUsage() {
        File root = new File("/");
        long totalSpace = root.getTotalSpace() / 1024 / 1024 / 1024;  // GB
        long usableSpace = root.getUsableSpace() / 1024 / 1024 / 1024;  // GB
        long usedSpace = totalSpace - usableSpace;
        
        if (totalSpace == 0) {
            // Try Windows C drive if root doesn't work
            root = new File("C:\\");
            totalSpace = root.getTotalSpace() / 1024 / 1024 / 1024;
            usableSpace = root.getUsableSpace() / 1024 / 1024 / 1024;
            usedSpace = totalSpace - usableSpace;
        }
        
        // Handle case where disk info is not available
        if (totalSpace == 0) {
            return "Not available";
        }
        
        double usedPercentage = (double) usedSpace / totalSpace * 100;
        return usedSpace + "GB / " + totalSpace + "GB (" + df.format(usedPercentage) + "%)";
    }
    
    /**
     * Format JVM uptime into days, hours, minutes, seconds
     * 
     * @param uptimeMs Uptime in milliseconds
     * @return Formatted uptime string
     */
    private String formatUptime(long uptimeMs) {
        long days = TimeUnit.MILLISECONDS.toDays(uptimeMs);
        uptimeMs -= TimeUnit.DAYS.toMillis(days);
        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMs);
        uptimeMs -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMs);
        uptimeMs -= TimeUnit.MINUTES.toMillis(minutes);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMs);

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("m ");
        }
        sb.append(seconds).append("s");

        return sb.toString();
    }
    
    /**
     * Get appropriate color for CPU load display
     * 
     * @param load CPU load percentage
     * @return Color based on load
     */
    private NamedTextColor getCpuLoadColor(double load) {
        if (load < 50) {
            return NamedTextColor.GREEN;
        } else if (load < 80) {
            return NamedTextColor.YELLOW;
        } else {
            return NamedTextColor.RED;
        }
    }
    
    /**
     * Get appropriate color for memory usage display
     * 
     * @param used Used memory in MB
     * @param max Max memory in MB
     * @return Color based on memory usage
     */
    private NamedTextColor getMemoryLoadColor(long used, long max) {
        double percentage = (double) used / max * 100;
        if (percentage < 60) {
            return NamedTextColor.GREEN;
        } else if (percentage < 85) {
            return NamedTextColor.YELLOW;
        } else {
            return NamedTextColor.RED;
        }
    }
      @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(permission);
    }
    
    /**
     * Represents system memory information
     */    private static class SystemMemoryInfo {
        private final long totalMB;
        private final long usedMB;
        private final double usedPercentage;

        public SystemMemoryInfo(long totalMB, long freeMB) {
            this.totalMB = totalMB;
            this.usedMB = totalMB - freeMB;
            this.usedPercentage = totalMB > 0 ? ((double) usedMB / totalMB) * 100 : 0;
        }

        public long getTotalMB() {
            return totalMB;
        }

        public long getUsedMB() {
            return usedMB;
        }

        public double getUsedPercentage() {
            return usedPercentage;
        }
        
        public String getTotalFormatted() {
            // Format as GB if larger than 1024MB
            if (totalMB >= 1024) {
                return String.format("%.1f GB", totalMB / 1024.0);
            }
            return totalMB + " MB";
        }
        
        public String getUsedFormatted() {
            // Format as GB if larger than 1024MB
            if (usedMB >= 1024) {
                return String.format("%.1f GB", usedMB / 1024.0);
            }
            return usedMB + " MB";
        }
    }

    /**
     * Get system memory information (total and free) using different methods
     * depending on the operating system
     * 
     * @return SystemMemoryInfo object containing memory details
     */
    private SystemMemoryInfo getSystemMemoryInfo() {
        String os = System.getProperty("os.name").toLowerCase();
        
        try {
            // Windows-specific approach
            if (os.contains("win")) {
                return getWindowsMemoryInfo();
            }
            // Linux-specific approach
            else if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                return getUnixMemoryInfo();
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to retrieve system memory info", e);
        }
        
        // Fallback to JVM memory (not accurate for system memory)
        long maxMemory = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        long freeMemory = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        return new SystemMemoryInfo(maxMemory, freeMemory);
    }
    
    /**     * Get memory information on Windows systems
     * 
     * @return SystemMemoryInfo for Windows
     */
    private SystemMemoryInfo getWindowsMemoryInfo() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "wmic", "OS", "get", "FreePhysicalMemory,TotalVisibleMemorySize", "/Value");
        Process process = pb.start();
        process.getOutputStream().close();
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        
        long totalMemoryMB = 0;
        long freeMemoryMB = 0;
        
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("FreePhysicalMemory")) {
                String value = line.split("=")[1].trim();
                // Convert from KB to MB
                freeMemoryMB = Long.parseLong(value) / 1024;
            }
            if (line.startsWith("TotalVisibleMemorySize")) {
                String value = line.split("=")[1].trim();
                // Convert from KB to MB
                totalMemoryMB = Long.parseLong(value) / 1024;
            }
        }
        
        reader.close();
        return new SystemMemoryInfo(totalMemoryMB, freeMemoryMB);
    }
    
    /**
     * Get memory information on Unix-like systems (Linux, macOS)
     * 
     * @return SystemMemoryInfo for Unix systems
     */    private SystemMemoryInfo getUnixMemoryInfo() throws IOException {
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            // macOS approach
            ProcessBuilder pb = new ProcessBuilder("sysctl", "-n", "hw.memsize", "hw.usermem");
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            long totalMemoryBytes = Long.parseLong(reader.readLine().trim());
            long freeMemoryBytes = Long.parseLong(reader.readLine().trim());
            
            reader.close();
            return new SystemMemoryInfo(totalMemoryBytes / 1024 / 1024, freeMemoryBytes / 1024 / 1024);        } else {
            // Linux approach using /proc/meminfo
            ProcessBuilder pb = new ProcessBuilder("cat", "/proc/meminfo");
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            String line;
            long totalMemoryKB = 0;
            long freeMemoryKB = 0;
            long cachedMemoryKB = 0;
            long buffersKB = 0;
            
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    totalMemoryKB = parseMemInfoLine(line);
                } else if (line.startsWith("MemFree:")) {
                    freeMemoryKB = parseMemInfoLine(line);
                } else if (line.startsWith("Cached:")) {
                    cachedMemoryKB = parseMemInfoLine(line);
                } else if (line.startsWith("Buffers:")) {
                    buffersKB = parseMemInfoLine(line);
                }
            }
            
            reader.close();
            
            // Linux considers cached memory as used, but it's available for applications
            // so we add it to free memory for a more accurate representation
            long actualFreeMemoryKB = freeMemoryKB + cachedMemoryKB + buffersKB;
            
            return new SystemMemoryInfo(totalMemoryKB / 1024, actualFreeMemoryKB / 1024);
        }
    }
    
    /**
     * Parse a line from /proc/meminfo to extract the memory value in KB
     * 
     * @param line The line from /proc/meminfo
     * @return The memory value in KB
     */
    private long parseMemInfoLine(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            return Long.parseLong(parts[1]);
        }
        return 0;
    }
}
