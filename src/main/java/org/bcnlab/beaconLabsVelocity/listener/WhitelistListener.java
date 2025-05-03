package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.bcnlab.beaconLabsVelocity.service.WhitelistService;

/**
 * Listener to enforce the proxy whitelist
 */
public class WhitelistListener {
    private final BeaconLabsVelocity plugin;
    private final DatabaseManager databaseManager;

    public WhitelistListener(BeaconLabsVelocity plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }    @Subscribe
    public void onPlayerLogin(LoginEvent event) {
        Player player = event.getPlayer();
        
        // Check if whitelist is enabled
        boolean whitelistEnabled = plugin.getConfig().node("whitelist", "enabled").getBoolean(false);
        
        // Skip whitelist check if disabled or player has bypass permission
        if (!whitelistEnabled || player.hasPermission("beaconlabs.whitelist.bypass")) {
            return;
        }
        
        // Check if player is whitelisted by username (case-insensitive)
        boolean isWhitelisted = isPlayerWhitelisted(player.getUsername());
        
        if (!isWhitelisted) {
            // Get custom kick message from config
            String kickMessageStr = plugin.getConfig().node("whitelist", "kick-message")
                .getString("&4BeaconLabs &r&8| &c&lWHITELIST ONLY\n&7You are not on the whitelist.\n&7Please contact an administrator to gain access.");
            
            // Player is not whitelisted, deny connection
            event.setResult(LoginEvent.ComponentResult.denied(
                LegacyComponentSerializer.legacyAmpersand().deserialize(kickMessageStr)
            ));
            
            // Log the denied connection
            plugin.getLogger().info("Player {} was denied connection: not on the whitelist", player.getUsername());
        }
    }
    
    /**
     * Check if a player is whitelisted by username (more reliable than UUID check)
     * 
     * @param username The player's username to check
     * @return True if the player is whitelisted, false otherwise
     */
    private boolean isPlayerWhitelisted(String username) {
        if (!databaseManager.isConnected()) {
            plugin.getLogger().warn("Database is not connected. Cannot check whitelist status for player {}.", username);
            return false;
        }
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM proxy_whitelist WHERE LOWER(player_name) = LOWER(?)")) {
            
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next(); // If there's a result, player is whitelisted
            }
            
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to check whitelist status for player {}", username, e);
            return false;
        }
    }
}
