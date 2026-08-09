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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Command to send private messages to other players
 * Usage: /msg <player> <message>
 * Aliases: /tell, /w, /whisper, /m
 * Permission: beaconlabs.message (default: true)
 */
public class MessageCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final MessageService messageService;

    public MessageCommand(BeaconLabsVelocity plugin, MessageService messageService) {
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
        if (args.length < 2) {
            sender.sendMessage(plugin.getPrefix(sender).append(Component.text("Usage: /msg <player> <message>", NamedTextColor.GRAY)));
            return;
        }

        // Combine remaining arguments into message
        String[] messageArgs = new String[args.length - 1];
        System.arraycopy(args, 1, messageArgs, 0, args.length - 1);
        String message = String.join(" ", messageArgs);
        String recipientName = args[0];

        Optional<Player> optRecipient = plugin.getServer().getPlayer(recipientName);
        if (optRecipient.isPresent()) {
            Player recipient = optRecipient.get();
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

        // Not on this proxy: try cross-proxy /msg
        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            java.util.UUID targetUuid = plugin.getCrossProxyService().getPlayerUuidByName(recipientName);
            if (targetUuid == null) {
                sender.sendMessage(plugin.getPrefix(sender).append(Component.text("Player '" + recipientName + "' not found or offline.", NamedTextColor.RED)));
                return;
            }

            // Note: Since target is on another proxy, we can't easily synchronously check their privacy setting from DB if it's strictly cached locally.
            // But since it's the database, we can check it directly via service which falls back to DB.
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
            plugin.getCrossProxyService().publishPrivateMsg(recipientName, sender.getUniqueId().toString(), sender.getUsername(), recipientMessage);
            // Get the recipient's prefix from Redis for the outgoing display
            String recipientPrefix = MessageService.convertLegacyToMiniMessage(plugin.getCrossProxyService().getPlayerPrefix(recipientName));
            Component senderMsg = MiniMessage.miniMessage().deserialize(String.format("<dark_gray>[<gray>You <dark_gray>-> %s<gray>%s<dark_gray>]: <gray>%s", recipientPrefix, recipientName, message));
            sender.sendMessage(senderMsg);
        } else {
            sender.sendMessage(plugin.getPrefix(sender).append(Component.text("Player '" + recipientName + "' not found or offline.", NamedTextColor.RED)));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length == 0 || invocation.arguments().length == 1) {
            String input = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase();
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                return plugin.getCrossProxyService().getOnlinePlayerNames().stream()
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
            }
            return plugin.getServer().getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.message");
    }
}
