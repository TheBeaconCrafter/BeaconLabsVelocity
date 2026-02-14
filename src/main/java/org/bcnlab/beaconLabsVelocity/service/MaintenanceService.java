package org.bcnlab.beaconLabsVelocity.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Service to manage the server maintenance mode
 */
public class MaintenanceService {
    
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final AtomicBoolean maintenanceMode = new AtomicBoolean(false);
    private final AtomicBoolean cooldownActive = new AtomicBoolean(false);
    
    // Configuration values
    private String kickMessage;
    private String maintenanceMOTD;
    private String requiredPermission;
    
    public MaintenanceService(BeaconLabsVelocity plugin, ProxyServer server, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        
        // Load maintenance state from config
        loadConfig();
    }
    
    /**
     * Load maintenance configuration
     */
    public void loadConfig() {
        // Default values
        kickMessage = "&cServer is currently under maintenance. Please check back later.";
        maintenanceMOTD = "<red><bold>MAINTENANCE MODE</bold></red> <dark_gray>|</dark_gray> <gray>Back soon!</gray>\n<yellow>We're working on improvements</yellow>";
        requiredPermission = "beaconlabs.maintenance.bypass";
        
        // Get values from config
        if (plugin.getConfig() != null) {
            // Get the kick message with fallback
            kickMessage = plugin.getConfig().node("maintenance", "kick-message").getString(kickMessage);
              // Check if the maintenance node exists
            ConfigurationNode maintenanceNode = plugin.getConfig().node("maintenance");
            if (!maintenanceNode.virtual()) {
                // Try to get the MOTD configuration
                ConfigurationNode motdNode = maintenanceNode.node("motd");
                
                if (!motdNode.virtual()) {
                    // Check if motdNode is a map or value
                    if (motdNode.isMap()) {
                        // It's using the line1/line2 structure
                        String line1 = motdNode.node("line1").getString("");
                        String line2 = motdNode.node("line2").getString("");
                        
                        // Only update if at least one line has content
                        if (!line1.isEmpty() || !line2.isEmpty()) {
                            maintenanceMOTD = line1 + "\n" + line2;
                        } else {
                            logger.info("Maintenance MOTD lines are empty, using default");
                        }
                    } else {
                        // It's a direct string value
                        String directMotd = motdNode.getString("");
                        if (!directMotd.isEmpty()) {
                            maintenanceMOTD = directMotd;
                        }
                    }
                } else {
                    logger.warn("No MOTD configuration found in maintenance section");
                }
            }
            
            // Get bypass permission with fallback
            requiredPermission = plugin.getConfig().node("maintenance", "bypass-permission").getString(requiredPermission);
            
            // Get enabled state with fallback to false
            maintenanceMode.set(plugin.getConfig().node("maintenance", "enabled").getBoolean(false));
        }
        
        logger.info("Maintenance mode is " + (maintenanceMode.get() ? "enabled" : "disabled"));
    }
      /**
     * Save maintenance state to config
     */
    public void saveMaintenanceState(boolean enabled) {
        try {
            // Update the current state in memory first
            maintenanceMode.set(enabled);
            
            // Also update the config node for future loads
            if (plugin.getConfig() != null) {
                plugin.getConfig().node("maintenance", "enabled").set(enabled);
            }
              // But now instead of saving the whole config, just update the enabled flag directly in the file
            try {
                Path configPath = plugin.getDataDirectory().resolve("config.yml");
                logger.info("Config path: " + configPath.toString());
                
                // Check if file exists
                if (java.nio.file.Files.exists(configPath)) {
                    // Read all lines
                    java.util.List<String> lines = java.nio.file.Files.readAllLines(configPath);
                    boolean updated = false;
                    boolean inMaintenanceSection = false;
                    
                    // Find and replace only the maintenance.enabled line
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).trim();
                        
                        // Check if we're entering the maintenance section
                        if (line.equals("maintenance:")) {
                            inMaintenanceSection = true;
                            continue;
                        }
                        
                        // If we're in a new section, we're no longer in maintenance
                        if (inMaintenanceSection && line.endsWith(":") && !line.startsWith(" ") && !line.startsWith("#")) {
                            inMaintenanceSection = false;
                        }
                        
                        // If we're in maintenance section and find enabled: line
                        if (inMaintenanceSection && line.trim().startsWith("enabled:")) {
                            // Keep the original indentation
                            String originalLine = lines.get(i);
                            String indent = originalLine.substring(0, originalLine.indexOf("enabled:"));
                            lines.set(i, indent + "enabled: " + enabled + " # Whether maintenance mode is currently active");
                            updated = true;
                            break;
                        }
                    }
                      // Only write if we actually changed something
                    if (updated) {
                        java.nio.file.Files.write(configPath, lines);
                    } else {
                        logger.warn("Could not find maintenance.enabled key in config file, trying alternative approach");                        // Try a regex-based replacement as a backup approach
                        String content = String.join("\n", lines);
                        String newContent = content;
                        
                        // Look for the maintenance section and its enabled flag
                        int maintenanceIndex = content.indexOf("maintenance:");
                        if (maintenanceIndex != -1) {
                            int enabledIndex = content.indexOf("enabled:", maintenanceIndex);
                            if (enabledIndex != -1) {
                                // Find the end of the enabled line
                                int lineEnd = content.indexOf("\n", enabledIndex);
                                if (lineEnd == -1) lineEnd = content.length(); // Handle EOF
                                
                                // Get the line
                                String enabledLine = content.substring(enabledIndex, lineEnd);
                                
                                // Create replacement with same indentation and comment if present
                                String replacement = enabledLine.replaceFirst("(enabled:\\s*)(true|false)(.*)", 
                                                                           "$1" + enabled + "$3");
                                
                                // Replace in the content
                                newContent = content.substring(0, enabledIndex) + 
                                             replacement + 
                                             content.substring(lineEnd);
                            }
                        }
                        
                        // Only write if we actually made a change
                        if (!content.equals(newContent)) {
                            java.nio.file.Files.writeString(configPath, newContent);
                            updated = true;
                        }
                    }
                      // Log the final result
                    if (!updated) {
                        logger.error("Failed to update maintenance mode in config file, using fallback approach with Configurate API");
                        
                        // Emergency fallback - use the normal API as a last resort
                        try {
                            // Update only the enabled flag
                            plugin.getConfig().node("maintenance", "enabled").set(enabled);
                            
                            // Save with default Configurate loader
                            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                                .path(configPath)
                                .build();
                                
                            loader.save(plugin.getConfig());
                            logger.info("Used fallback Configurate API to save maintenance state");
                        } catch (Exception saveEx) {
                            logger.error("Even fallback approach failed", saveEx);
                        }
                    }
                } else {
                    logger.warn("Config file does not exist, cannot update maintenance state directly");
                    // Fall back to using the normal save if file doesn't exist
                    plugin.saveConfig();
                }
            } catch (IOException e) {
                logger.error("Failed to directly update maintenance state in config", e);
            }
        } catch (Exception e) {
            logger.error("Error when saving maintenance state", e);
        }
    }
      /**
     * Toggle maintenance mode. When enabling, shows 10-second title countdown first, then sets state and kicks.
     *
     * @param enable Whether to enable or disable maintenance mode
     * @param whenEnabledAfterCountdown Optional; called when maintenance is actually turned on after the 10s countdown (only when enable is true)
     * @return true if the toggle was accepted, false if on cooldown
     */
    public boolean toggleMaintenance(boolean enable, Runnable whenEnabledAfterCountdown) {
        if (cooldownActive.get()) {
            logger.info("Maintenance toggle rejected: cooldown is active");
            return false;
        }
        if (maintenanceMode.get() == enable) {
            logger.info("Maintenance toggle: already in requested state (" + enable + ")");
            return true;
        }

        if (enable) {
            startCooldown(() -> {
                maintenanceMode.set(true);
                saveMaintenanceState(true);
                kickPlayersWithoutPermission();
                cooldownActive.set(false);
                if (whenEnabledAfterCountdown != null) {
                    whenEnabledAfterCountdown.run();
                }
            });
            return true;
        }

        maintenanceMode.set(false);
        saveMaintenanceState(false);
        cooldownActive.set(false);
        return true;
    }

    /**
     * Start the maintenance cooldown period: show titles and countdown, then run the given task after 10 seconds.
     */
    private void startCooldown(Runnable afterCountdown) {
        cooldownActive.set(true);

        server.getAllPlayers().forEach(this::sendMaintenanceWarning);

        for (int i = 10; i > 0; i--) {
            final int seconds = i;
            server.getScheduler().buildTask(plugin, () -> sendCountdownNotification(seconds))
                    .delay(10 - i, TimeUnit.SECONDS).schedule();
        }

        server.getScheduler().buildTask(plugin, () -> {
            if (afterCountdown != null) {
                afterCountdown.run();
            } else {
                cooldownActive.set(false);
            }
        }).delay(10, TimeUnit.SECONDS).schedule();
    }
    
    /**
     * Send maintenance warning title to a player
     */
    private void sendMaintenanceWarning(Player player) {
        // Create title
        Title title = Title.title(
            Component.text("MAINTENANCE MODE", NamedTextColor.RED),
            Component.text("Server will enter maintenance in 10 seconds", NamedTextColor.GOLD),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(1))
        );
        
        // Send title
        player.showTitle(title);
    }
    
    /**
     * Send countdown notification
     */
    private void sendCountdownNotification(int seconds) {
        // Create title
        Title title = Title.title(
            Component.text("MAINTENANCE MODE", NamedTextColor.RED),
            Component.text("Server entering maintenance in " + seconds + " seconds", NamedTextColor.GOLD),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(500))
        );
        
        // Send to all players
        server.getAllPlayers().forEach(player -> player.showTitle(title));
    }
    
    /**
     * Kick all players without the bypass permission
     */
    private void kickPlayersWithoutPermission() {
        Component kickMessageComponent = LegacyComponentSerializer.legacyAmpersand()
            .deserialize(kickMessage);
        
        server.getAllPlayers().forEach(player -> {
            if (!player.hasPermission(requiredPermission)) {
                player.disconnect(kickMessageComponent);
            }
        });
    }
    
    /**
     * Set maintenance mode from a remote proxy (cross-proxy sync). No countdown; use for immediate set or after countdown.
     */
    public void setMaintenanceFromRemote(boolean enable) {
        if (maintenanceMode.get() == enable) return;
        maintenanceMode.set(enable);
        saveMaintenanceState(enable);
        if (enable) {
            kickPlayersWithoutPermission();
        }
    }

    /**
     * Run the same 10-second title countdown as local enable, then set maintenance and kick (for remote proxy).
     */
    public void runRemoteMaintenanceCountdown(Runnable afterCountdown) {
        server.getAllPlayers().forEach(this::sendMaintenanceWarning);
        for (int i = 10; i > 0; i--) {
            final int seconds = i;
            server.getScheduler().buildTask(plugin, () -> sendCountdownNotification(seconds))
                    .delay(10 - i, TimeUnit.SECONDS).schedule();
        }
        server.getScheduler().buildTask(plugin, () -> {
            setMaintenanceFromRemote(true);
            if (afterCountdown != null) afterCountdown.run();
        }).delay(10, TimeUnit.SECONDS).schedule();
    }

    /**
     * Check if a player can join during maintenance
     */
    public boolean canJoinDuringMaintenance(Player player) {
        // If maintenance is disabled, all players can join
        if (!maintenanceMode.get()) {
            return true;
        }
        
        // If player has bypass permission, they can join
        return player.hasPermission(requiredPermission);
    }
    
    /**
     * Check if maintenance mode is enabled
     */
    public boolean isMaintenanceMode() {
        return maintenanceMode.get();
    }
    
    /**
     * Get the maintenance MOTD
     * @return The maintenance MOTD string (may contain legacy color codes)
     */
    public String getMaintenanceMOTD() {
        return maintenanceMOTD;
    }
    
    /**
     * Update the maintenance MOTD
     * @param newMOTD The new MOTD to set (can include a newline character to separate lines)
     */
    public void setMaintenanceMOTD(String newMOTD) {
        this.maintenanceMOTD = newMOTD;
        
        // Update config if possible
        try {
            if (plugin.getConfig() != null) {
                // Split the MOTD into lines
                String[] lines = newMOTD.split("\n", 2);
                
                // Set the lines in config
                plugin.getConfig().node("maintenance", "motd", "line1").set(lines[0]);
                
                // Set the second line if available
                if (lines.length > 1) {
                    plugin.getConfig().node("maintenance", "motd", "line2").set(lines[1]);
                } else {
                    // Default second line if not provided
                    plugin.getConfig().node("maintenance", "motd", "line2").set("<yellow>We're working on improvements</yellow>");
                }
                
                // Save using plugin's method to preserve formatting
                plugin.saveConfig();            }
        } catch (Exception e) {
            logger.error("Failed to save maintenance MOTD", e);
        }
    }
    
    /**
     * Get kick message for maintenance mode
     */
    public String getKickMessage() {
        return kickMessage;
    }
}
