package org.bcnlab.beaconLabsVelocity.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service to manage the whitelist functionality
 */
public class WhitelistService {
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final DatabaseManager databaseManager;
    private final AtomicBoolean whitelistEnabled = new AtomicBoolean(false);
    
    // Configuration values
    private String kickMessage;
    private String bypassPermission;
    
    // SQL statements
    private static final String SQL_CREATE_TABLE = 
        "CREATE TABLE IF NOT EXISTS proxy_whitelist (" +
        "id INT AUTO_INCREMENT PRIMARY KEY, " +
        "player_uuid VARCHAR(36) NOT NULL, " +
        "player_name VARCHAR(16) NOT NULL, " +
        "added_by VARCHAR(36), " +
        "added_at BIGINT NOT NULL, " +
        "UNIQUE KEY unique_name (player_name(16)))";
    
    private static final String SQL_ADD_PLAYER = 
        "INSERT INTO proxy_whitelist (player_uuid, player_name, added_by, added_at) VALUES (?, ?, ?, ?) " +
        "ON DUPLICATE KEY UPDATE player_name = ?, added_by = ?, added_at = ?";
    
    private static final String SQL_REMOVE_PLAYER =
        "DELETE FROM proxy_whitelist WHERE LOWER(player_name) = LOWER(?)";
    
    private static final String SQL_CHECK_PLAYER =
        "SELECT 1 FROM proxy_whitelist WHERE LOWER(player_name) = LOWER(?)";
    
    private static final String SQL_LIST_PLAYERS =
        "SELECT player_name FROM proxy_whitelist ORDER BY player_name";

    public WhitelistService(BeaconLabsVelocity plugin, ProxyServer server, DatabaseManager databaseManager, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.databaseManager = databaseManager;
        this.logger = logger;
        
        // Initialize database table
        initWhitelistTable();
        
        // Load configuration
        loadConfig();
    }
    
    /**
     * Initialize the whitelist database table
     */
    private void initWhitelistTable() {
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Whitelist will not store player data.");
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SQL_CREATE_TABLE)) {
                stmt.execute();
                logger.info("Proxy whitelist table initialized.");
            } catch (SQLException e) {
                logger.error("Failed to create proxy whitelist table", e);
            }
        });
    }
    
    /**
     * Load whitelist configuration
     */
    public void loadConfig() {
        // Default values
        kickMessage = "&4BeaconLabs &r&8| &c&lWHITELIST ONLY\n&7You are not on the whitelist.\n&7Please contact an administrator to gain access.";
        bypassPermission = "beaconlabs.whitelist.bypass";
        
        // Get values from config
        ConfigurationNode config = plugin.getConfig();
        if (config != null) {
            // Load whitelist state
            boolean enabled = config.node("whitelist", "enabled").getBoolean(false);
            whitelistEnabled.set(enabled);
            
            // Load kick message
            kickMessage = config.node("whitelist", "kick-message").getString(kickMessage);
            
            // Load bypass permission
            bypassPermission = config.node("whitelist", "bypass-permission").getString(bypassPermission);
        }
        
        logger.info("Whitelist mode is " + (whitelistEnabled.get() ? "enabled" : "disabled"));
    }
    
    /**
     * Save whitelist state to config
     */
    public void saveWhitelistState(boolean enabled) {
        try {
            // Update config
            ConfigurationNode config = plugin.getConfig();
            if (config != null) {
                config.node("whitelist", "enabled").set(enabled);
                plugin.saveConfig();
                logger.info("Updated whitelist state in config: " + (enabled ? "enabled" : "disabled"));
            }
        } catch (SerializationException e) {
            logger.error("Failed to save whitelist state to config", e);
        }
    }
    
    /**
     * Set whitelist enabled state
     * 
     * @param enabled Whether whitelist should be enabled
     * @return true if the state was changed, false if already in that state
     */
    public boolean setWhitelistEnabled(boolean enabled) {
        // If we're already in the requested state, no change needed
        if (whitelistEnabled.get() == enabled) {
            return false;
        }
        
        // Update state
        whitelistEnabled.set(enabled);
        
        // Save to config
        saveWhitelistState(enabled);
        
        return true;
    }
    
    /**
     * Add a player to the whitelist
     * 
     * @param playerName Player name to add (case preserved but lookups are case-insensitive)
     * @param addedBy The name or UUID of who added the player
     * @return CompletableFuture that completes with true if added successfully, false otherwise
     */
    public CompletableFuture<Boolean> addPlayer(String playerName, String addedBy) {
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Cannot add player to whitelist.");
            return CompletableFuture.completedFuture(false);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SQL_ADD_PLAYER)) {
                
                UUID playerUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes());
                long currentTime = System.currentTimeMillis();
                
                // Parameters for INSERT
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, playerName);
                stmt.setString(3, addedBy);
                stmt.setLong(4, currentTime);
                
                // Parameters for UPDATE
                stmt.setString(5, playerName);
                stmt.setString(6, addedBy);
                stmt.setLong(7, currentTime);
                
                int affectedRows = stmt.executeUpdate();
                
                if (affectedRows > 0) {
                    logger.info("Player {} added to whitelist by {}", playerName, addedBy);
                    return true;
                } else {
                    logger.warn("Failed to add player {} to whitelist", playerName);
                    return false;
                }
            } catch (SQLException e) {
                logger.error("Database error adding player to whitelist: {}", e.getMessage(), e);
                return false;
            }
        });
    }
    
    /**
     * Remove a player from the whitelist
     * 
     * @param playerName Player name to remove
     * @return CompletableFuture that completes with true if removed successfully, false if not found
     */
    public CompletableFuture<Boolean> removePlayer(String playerName) {
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Cannot remove player from whitelist.");
            return CompletableFuture.completedFuture(false);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SQL_REMOVE_PLAYER)) {
                
                stmt.setString(1, playerName);
                int affectedRows = stmt.executeUpdate();
                
                if (affectedRows > 0) {
                    logger.info("Player {} removed from whitelist", playerName);
                    return true;
                } else {
                    logger.warn("Player {} not found in whitelist", playerName);
                    return false;
                }
            } catch (SQLException e) {
                logger.error("Database error removing player from whitelist: {}", e.getMessage(), e);
                return false;
            }
        });
    }
    
    /**
     * Check if a player is whitelisted
     * 
     * @param playerName Player name to check
     * @return CompletableFuture that completes with true if player is whitelisted, false otherwise
     */
    public CompletableFuture<Boolean> isPlayerWhitelisted(String playerName) {
        // If whitelist is disabled, everyone is whitelisted
        if (!whitelistEnabled.get()) {
            return CompletableFuture.completedFuture(true);
        }
        
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Cannot check whitelist status for {}.", playerName);
            return CompletableFuture.completedFuture(false);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SQL_CHECK_PLAYER)) {
                
                stmt.setString(1, playerName);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next(); // If there's a result, player is whitelisted
                }
            } catch (SQLException e) {
                logger.error("Database error checking whitelist status: {}", e.getMessage(), e);
                return false;
            }
        });
    }
    
    /**
     * Get a list of all whitelisted players
     * 
     * @return CompletableFuture that completes with a list of player names
     */
    public CompletableFuture<List<String>> getWhitelistedPlayers() {
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Cannot list whitelisted players.");
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        
        return CompletableFuture.supplyAsync(() -> {
            List<String> players = new ArrayList<>();
            
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SQL_LIST_PLAYERS);
                 ResultSet rs = stmt.executeQuery()) {
                
                while (rs.next()) {
                    players.add(rs.getString("player_name"));
                }
            } catch (SQLException e) {
                logger.error("Database error listing whitelisted players: {}", e.getMessage(), e);
            }
            
            return players;
        });
    }
      /**
     * Get the bypass permission
     * 
     * @return The permission string for bypass
     */
    public String getBypassPermission() {
        return bypassPermission;
    }
    
    /**
     * Get the kick message
     * 
     * @return The formatted kick message
     */
    public String getKickMessage() {
        return kickMessage;
    }
    
    /**
     * Check if whitelist is enabled
     * 
     * @return True if whitelist is enabled, false otherwise
     */
    public boolean isWhitelistEnabled() {
        return whitelistEnabled.get();
    }
    
    /**
     * Check if a player can join during whitelist mode
     * 
     * @param player The player to check
     * @return CompletableFuture that completes with true if the player can join, false otherwise
     */
    public CompletableFuture<Boolean> canJoinDuringWhitelist(Player player) {
        // If whitelist is disabled, all players can join
        if (!whitelistEnabled.get()) {
            return CompletableFuture.completedFuture(true);
        }
        
        // If player has bypass permission, they can join
        if (player.hasPermission(bypassPermission)) {
            return CompletableFuture.completedFuture(true);
        }
        
        // Check if player is whitelisted
        return isPlayerWhitelisted(player.getUsername());
    }
    
    /**
     * Kick all players who are not whitelisted and don't have bypass permission
     * 
     * @return CompletableFuture that completes with the number of players kicked
     */
    public CompletableFuture<Integer> kickNonWhitelistedPlayers() {
        if (!whitelistEnabled.get()) {
            return CompletableFuture.completedFuture(0);
        }
        
        Component kickMessageComponent = LegacyComponentSerializer.legacyAmpersand()
            .deserialize(kickMessage);
        
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        List<Player> playersToKick = new ArrayList<>();
        
        for (Player player : server.getAllPlayers()) {
            // Skip players with bypass permission
            if (player.hasPermission(bypassPermission)) {
                continue;
            }
            
            CompletableFuture<Boolean> future = isPlayerWhitelisted(player.getUsername())
                .thenApply(whitelisted -> {
                    if (!whitelisted) {
                        playersToKick.add(player);
                    }
                    return !whitelisted;
                });
                
            futures.add(future);
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                // Now kick all players that need to be kicked
                for (Player player : playersToKick) {
                    player.disconnect(kickMessageComponent);
                }
                return playersToKick.size();
            });
    }
    
    /**
     * Set the kick message for whitelist
     * 
     * @param message The new kick message
     */
    public void setKickMessage(String message) {
        this.kickMessage = message;
        
        try {
            // Update config
            ConfigurationNode config = plugin.getConfig();
            if (config != null) {
                config.node("whitelist", "kick-message").set(message);
                plugin.saveConfig();
            }
        } catch (SerializationException e) {
            logger.error("Failed to save whitelist kick message to config", e);
        }
    }
      // End of class
}
