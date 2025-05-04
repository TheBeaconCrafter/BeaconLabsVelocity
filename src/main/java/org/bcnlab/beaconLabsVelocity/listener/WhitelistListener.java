package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;


import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.WhitelistService;

/**
 * Listener to enforce the proxy whitelist
 */
public class WhitelistListener {
    private final BeaconLabsVelocity plugin;
    private final WhitelistService whitelistService;

    public WhitelistListener(BeaconLabsVelocity plugin, WhitelistService whitelistService) {
        this.plugin = plugin;
        this.whitelistService = whitelistService;
    }    @Subscribe
    public void onPlayerLogin(LoginEvent event) {
        Player player = event.getPlayer();
        
        // Skip checks if whitelist is disabled
        if (!whitelistService.isWhitelistEnabled()) {
            return;
        }
        
        // Skip checks if player has bypass permission
        if (player.hasPermission(whitelistService.getBypassPermission())) {
            return;
        }
        
        // Check if player is whitelisted using the whitelist service
        try {
            boolean isWhitelisted = whitelistService.isPlayerWhitelisted(player.getUsername()).get();
            
            if (!isWhitelisted) {
                // Get kick message from whitelist service
                String kickMessageStr = whitelistService.getKickMessage();
                
                // Player is not whitelisted, deny connection
                event.setResult(LoginEvent.ComponentResult.denied(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(kickMessageStr)
                ));
                
                // Log the denied connection
                plugin.getLogger().info("Player {} was denied connection: not on the whitelist", player.getUsername());
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error checking whitelist for player {}", player.getUsername(), e);
            // Deny connection on error to be safe
            event.setResult(LoginEvent.ComponentResult.denied(
                Component.text("An error occurred while checking whitelist. Please try again later.")
            ));
        }
    }
      // No need for a separate isPlayerWhitelisted method as we now use the WhitelistService
}
