package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import org.bcnlab.beaconLabsVelocity.service.MessageService;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

/**
 * Listener to handle cleanup tasks for the messaging system
 */
public class MessageListener {
    private final MessageService messageService;
    private final BeaconLabsVelocity plugin;
    
    public MessageListener(BeaconLabsVelocity plugin, MessageService messageService) {
        this.plugin = plugin;
        this.messageService = messageService;
    }
    
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        // Clean up the player's messaging data when they disconnect
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        messageService.clearPlayerData(uuid);
        if (plugin.getPlayerSettingsService() != null) {
            plugin.getPlayerSettingsService().removePlayer(uuid);
        }
        if (plugin.getFriendService() != null) {
            plugin.getFriendService().clearPlayerCache(uuid);
        }
    }
}
