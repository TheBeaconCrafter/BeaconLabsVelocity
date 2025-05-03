package org.bcnlab.beaconLabsVelocity.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to handle private messaging between players
 */
public class MessageService {
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final Logger logger;
    
    // Store the last conversation partner for each player
    private final Map<UUID, UUID> lastMessageRecipients = new ConcurrentHashMap<>();
    
    // Format for messages
    private final String outgoingFormat = "&7You &8-> &7%s&8: &f%s";
    private final String incomingFormat = "&7%s &8-> &7You&8: &f%s";

    public MessageService(BeaconLabsVelocity plugin, ProxyServer server, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
    }

    /**
     * Send a private message from one player to another
     *
     * @param sender The sending player
     * @param recipient The recipient player
     * @param message The message content
     * @return true if the message was sent, false otherwise
     */
    public boolean sendPrivateMessage(Player sender, Player recipient, String message) {
        if (sender.equals(recipient)) {
            sender.sendMessage(Component.text("You cannot message yourself!", NamedTextColor.RED));
            return false;
        }

        // Format messages for sender and recipient
        Component senderMessage = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(String.format(outgoingFormat, recipient.getUsername(), message));
        
        Component recipientMessage = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(String.format(incomingFormat, sender.getUsername(), message));

        // Send the messages
        sender.sendMessage(senderMessage);
        recipient.sendMessage(recipientMessage);
        
        // Update last message maps
        lastMessageRecipients.put(sender.getUniqueId(), recipient.getUniqueId());
        lastMessageRecipients.put(recipient.getUniqueId(), sender.getUniqueId());
        
        // Log the message
        logger.info("[PM] {} -> {}: {}", sender.getUsername(), recipient.getUsername(), message);
        
        return true;
    }

    /**
     * Get the player who last sent a message to the given player
     *
     * @param player The player to check
     * @return Optional containing the last player who messaged them, or empty if none
     */
    public Optional<Player> getLastMessageSender(Player player) {
        UUID lastSenderId = lastMessageRecipients.get(player.getUniqueId());
        
        if (lastSenderId != null) {
            return server.getPlayer(lastSenderId);
        }
        
        return Optional.empty();
    }
    
    /**
     * Clear conversation data for a player (e.g., on disconnect)
     *
     * @param playerUuid The UUID of the player to remove
     */
    public void clearPlayerData(UUID playerUuid) {
        lastMessageRecipients.remove(playerUuid);
    }
}
