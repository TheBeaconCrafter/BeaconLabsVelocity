package org.bcnlab.beaconLabsVelocity.command.chat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bcnlab.beaconLabsVelocity.util.ColorParser;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.MessageService;

import java.util.Optional;

/**
 * Command to reply to the last player who messaged you
 * Usage: /r <message>
 * Aliases: /reply
 * Permission: beaconlabs.message (default: true)
 */
public class ReplyCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final MessageService messageService;

    public ReplyCommand(BeaconLabsVelocity plugin, MessageService messageService) {
        this.plugin = plugin;
        this.messageService = messageService;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        // Check if source is a player
        if (!(source instanceof Player)) {
            source.sendMessage(plugin.getPrefix(source).append(Component.text("Only players can use this command.", NamedTextColor.RED)));
            return;
        }

        Player sender = (Player) source;

        // Check arguments
        if (args.length < 1) {
            sender.sendMessage(plugin.getPrefix(sender).append(Component.text("Usage: /r <message>", NamedTextColor.GRAY)));
            return;
        }

        String message = String.join(" ", args);

        // Try local last sender first
        Optional<Player> optRecipient = messageService.getLastMessageSender(sender);
        if (optRecipient.isPresent()) {
            Player recipient = optRecipient.get();
            if (!recipient.isActive()) {
                sender.sendMessage(plugin.getPrefix(sender).append(Component.text("Player '" + recipient.getUsername() + "' is no longer online.", NamedTextColor.RED)));
                return;
            }
            
            String privacy = plugin.getPlayerSettingsService().getPlayerSetting(recipient.getUniqueId(), "msg_privacy", "everyone");
            if (privacy.equals("nobody")) {
                sender.sendMessage(plugin.getPrefix(sender).append(Component.text("You cannot message this player.", NamedTextColor.RED)));
                return;
            } else if (privacy.equals("friends_only")) {
                if (!plugin.getFriendService().areFriends(sender.getUniqueId(), recipient.getUniqueId())) {
                    sender.sendMessage(plugin.getPrefix(sender).append(Component.text("This player only accepts messages from friends.", NamedTextColor.RED)));
                    return;
                }
            }
            
            messageService.sendPrivateMessage(sender, recipient, message);
            return;
        }

        String lastSenderUsername = messageService.getLastSenderUsername(sender.getUniqueId());
        if (lastSenderUsername != null && !lastSenderUsername.isEmpty() && plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            java.util.UUID targetUuid = plugin.getCrossProxyService().getPlayerUuidByName(lastSenderUsername);
            if (targetUuid == null) {
                sender.sendMessage(plugin.getPrefix(sender).append(Component.text("Player '" + lastSenderUsername + "' is no longer online.", NamedTextColor.RED)));
                return;
            }
            
            String privacy = plugin.getPlayerSettingsService().getPlayerSetting(targetUuid, "msg_privacy", "everyone");
            if (privacy.equals("nobody")) {
                sender.sendMessage(plugin.getPrefix(sender).append(Component.text("You cannot message this player.", NamedTextColor.RED)));
                return;
            } else if (privacy.equals("friends_only")) {
                if (!plugin.getFriendService().areFriends(sender.getUniqueId(), targetUuid)) {
                    sender.sendMessage(plugin.getPrefix(sender).append(Component.text("This player only accepts messages from friends.", NamedTextColor.RED)));
                    return;
                }
            }
            
            String recipientMessage = messageService.formatIncomingMessage(sender, message);
            plugin.getCrossProxyService().publishPrivateMsg(lastSenderUsername, sender.getUniqueId().toString(), sender.getUsername(), recipientMessage);
            String recipientPrefix = MessageService.convertLegacyToMiniMessage(plugin.getCrossProxyService().getPlayerPrefix(lastSenderUsername));
            Component senderMsg = MiniMessage.miniMessage().deserialize(String.format("<dark_gray>[<gray>You <dark_gray>-> %s<reset><gray>%s<dark_gray>]: <gray>%s", recipientPrefix, lastSenderUsername, message));
            sender.sendMessage(senderMsg);
            return;
        }

        sender.sendMessage(plugin.getPrefix(sender).append(Component.text("You have no one to reply to.", NamedTextColor.RED)));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.message");
    }
}
