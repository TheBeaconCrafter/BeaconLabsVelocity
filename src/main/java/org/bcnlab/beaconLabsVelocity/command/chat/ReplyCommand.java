package org.bcnlab.beaconLabsVelocity.command.chat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
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
            source.sendMessage(plugin.getPrefix().append(Component.text("Only players can use this command.", NamedTextColor.RED)));
            return;
        }

        Player sender = (Player) source;

        // Check arguments
        if (args.length < 1) {
            sender.sendMessage(plugin.getPrefix().append(Component.text("Usage: /r <message>", NamedTextColor.RED)));
            return;
        }

        String message = String.join(" ", args);

        // Try local last sender first
        Optional<Player> optRecipient = messageService.getLastMessageSender(sender);
        if (optRecipient.isPresent()) {
            Player recipient = optRecipient.get();
            if (!recipient.isActive()) {
                sender.sendMessage(plugin.getPrefix().append(Component.text("Player '" + recipient.getUsername() + "' is no longer online.", NamedTextColor.RED)));
                return;
            }
            messageService.sendPrivateMessage(sender, recipient, message);
            return;
        }

        // Cross-proxy: last message was from a player on another proxy
        String lastSenderUsername = messageService.getLastSenderUsername(sender.getUniqueId());
        if (lastSenderUsername != null && !lastSenderUsername.isEmpty() && plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            String recipientMessageLegacy = messageService.formatIncomingMessageLegacy(sender, message);
            plugin.getCrossProxyService().publishPrivateMsg(lastSenderUsername, sender.getUniqueId().toString(), sender.getUsername(), recipientMessageLegacy);
            Component senderMsg = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(String.format("&8[&7You &8-> %s&7%s&8]: &f%s", "", lastSenderUsername, message));
            sender.sendMessage(senderMsg);
            return;
        }

        sender.sendMessage(plugin.getPrefix().append(Component.text("You have no one to reply to.", NamedTextColor.RED)));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.message");
    }
}
