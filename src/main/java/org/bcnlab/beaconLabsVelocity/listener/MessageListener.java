package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import org.bcnlab.beaconLabsVelocity.service.MessageService;

/**
 * Listener to handle cleanup tasks for the messaging system
 */
public class MessageListener {
    private final MessageService messageService;
    
    public MessageListener(MessageService messageService) {
        this.messageService = messageService;
    }
    
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        // Clean up the player's messaging data when they disconnect
        messageService.clearPlayerData(event.getPlayer().getUniqueId());
    }
}
