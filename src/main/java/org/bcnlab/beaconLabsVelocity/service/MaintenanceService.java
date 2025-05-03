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
                            logger.info("Using line-based maintenance MOTD format");
                            maintenanceMOTD = line1 + "\n" + line2;
                        } else {
                            logger.info("Maintenance MOTD lines are empty, using default");
                        }
                    } else {
                        // It's a direct string value
                        String directMotd = motdNode.getString("");
                        if (!directMotd.isEmpty()) {
                            maintenanceMOTD = directMotd;
                            logger.info("Using direct maintenance MOTD format");
                        }
                    }
                } else {
                    logger.info("No MOTD configuration found in maintenance section");
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
                
                // Check if file exists
                if (java.nio.file.Files.exists(configPath)) {
                    // Read all lines
                    java.util.List<String> lines = java.nio.file.Files.readAllLines(configPath);
                    boolean updated = false;
                    
                    // Find and replace only the maintenance.enabled line
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        // Look for "enabled:" with proper indentation, preserving it
                        if (line.trim().startsWith("enabled:")) {
                            // Keep the same indentation, just change the value
                            String indent = line.substring(0, line.indexOf("enabled:"));
                            lines.set(i, indent + "enabled: " + enabled);
                            updated = true;
                            logger.info("Updated maintenance enabled flag in config: " + enabled);
                            break;
                        }
                    }
                    
                    // Only write if we actually changed something
                    if (updated) {
                        java.nio.file.Files.write(configPath, lines);
                    } else {
                        logger.warn("Could not find maintenance.enabled key in config file");
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
     * Toggle maintenance mode
     * 
     * @param enable Whether to enable or disable maintenance mode
     * @return true if the maintenance mode was changed, false if on cooldown
     */
    public boolean toggleMaintenance(boolean enable) {
        // Check if cooldown is active
        if (cooldownActive.get()) {
            return false;
        }
        
        // If we're already in the requested state, no change needed
        if (maintenanceMode.get() == enable) {
            return true;
        }
        
        // Set cooldown if enabling maintenance
        if (enable) {
            startCooldown();
        }
        
        // Update state
        maintenanceMode.set(enable);
        
        // Save to config
        saveMaintenanceState(enable);
        
        // If enabling, kick players without permission
        if (enable) {
            kickPlayersWithoutPermission();
        }
        
        return true;
    }
    
    /**
     * Start the maintenance cooldown period
     */
    private void startCooldown() {
        cooldownActive.set(true);
        
        // Send warning titles to all players
        server.getAllPlayers().forEach(this::sendMaintenanceWarning);
        
        // Schedule task to disable cooldown after 10 seconds
        server.getScheduler().buildTask(plugin, () -> {
            cooldownActive.set(false);
        }).delay(10, TimeUnit.SECONDS).schedule();
        
        // Schedule countdown announcements
        for (int i = 10; i > 0; i--) {
            final int seconds = i;
            server.getScheduler().buildTask(plugin, () -> {
                sendCountdownNotification(seconds);
            }).delay(10 - i, TimeUnit.SECONDS).schedule();
        }
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
                plugin.saveConfig();
                logger.info("Updated maintenance MOTD in config");
            }
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
