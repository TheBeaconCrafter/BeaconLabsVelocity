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
    private Object luckPerms; // Using Object type to avoid compilation issues
    
    // Store the last conversation partner for each player
    private final Map<UUID, UUID> lastMessageRecipients = new ConcurrentHashMap<>();
      // Format for messages with brackets around player sections
    private final String outgoingFormat = "&8[&7You &8-> %s&7%s&8]: &f%s";  // Args: prefix, name, message
    private final String incomingFormat = "&8[%s&7%s &8-> &7You&8]: &f%s"; // Args: prefix, name, message

    public MessageService(BeaconLabsVelocity plugin, ProxyServer server, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        
        // We'll initialize LuckPerms in a delayed task to ensure it's loaded
        server.getScheduler()
            .buildTask(plugin, this::initializeLuckPerms)
            .delay(2, java.util.concurrent.TimeUnit.SECONDS)
            .schedule();
    }
    
    /**
     * Initialize LuckPerms API
     */
    private void initializeLuckPerms() {
        try {
            // Use reflection to avoid direct compilation dependency
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            this.luckPerms = providerClass.getMethod("get").invoke(null);
            logger.info("Successfully hooked into LuckPerms for player prefixes.");
        } catch (Exception e) {
            logger.warn("Failed to hook into LuckPerms. Player prefixes will not be shown.", e);
            this.luckPerms = null;
        }
    }    /**
     * Format the incoming PM message as legacy string (for cross-proxy delivery).
     */
    public String formatIncomingMessageLegacy(Player sender, String message) {
        String senderPrefix = getPlayerPrefix(sender);
        return String.format(incomingFormat, senderPrefix, sender.getUsername(), message);
    }

    /**
     * Get a player's prefix from LuckPerms or empty if not available
     *
     * @param player The player to get the prefix for
     * @return The formatted prefix string
     */
    public String getPlayerPrefix(Player player) {
        if (luckPerms == null) {
            return "";
        }
        
        try {
            // Use reflection to safely access LuckPerms API
            Class<?> luckPermsClass = luckPerms.getClass();
            Object userManager = luckPermsClass.getMethod("getUserManager").invoke(luckPerms);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUniqueId());
            
            if (user == null) {
                return "";
            }
            
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object metaData = cachedData.getClass().getMethod("getMetaData").invoke(cachedData);
            String prefix = (String) metaData.getClass().getMethod("getPrefix").invoke(metaData);
            
            if (prefix == null || prefix.isEmpty()) {
                return "";
            }
            
            // Format prefix - no brackets needed as they'll be around the entire player section
            return prefix + " ";
        } catch (Exception e) {
            logger.warn("Error getting prefix for player {}: {}", player.getUsername(), e.getMessage());
            return "";
        }
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

        // Get prefixes for both players
        String recipientPrefix = getPlayerPrefix(recipient);
        String senderPrefix = getPlayerPrefix(sender);

        // Format messages for sender and recipient
        Component senderMessage = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(String.format(outgoingFormat, recipientPrefix, recipient.getUsername(), message));
        
        Component recipientMessage = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(String.format(incomingFormat, senderPrefix, sender.getUsername(), message));

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
