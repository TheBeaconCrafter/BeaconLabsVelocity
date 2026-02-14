package org.bcnlab.beaconLabsVelocity.command.chat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
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
            source.sendMessage(plugin.getPrefix().append(Component.text("Only players can use this command.", NamedTextColor.RED)));
            return;
        }

        Player sender = (Player) source;

        // Check arguments
        if (args.length < 2) {
            sender.sendMessage(plugin.getPrefix().append(Component.text("Usage: /msg <player> <message>", NamedTextColor.RED)));
            return;
        }

        // Combine remaining arguments into message
        String[] messageArgs = new String[args.length - 1];
        System.arraycopy(args, 1, messageArgs, 0, args.length - 1);
        String message = String.join(" ", messageArgs);
        String recipientName = args[0];

        Optional<Player> optRecipient = plugin.getServer().getPlayer(recipientName);
        if (optRecipient.isPresent()) {
            messageService.sendPrivateMessage(sender, optRecipient.get(), message);
            return;
        }

        // Not on this proxy: try cross-proxy /msg
        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            String recipientMessageLegacy = messageService.formatIncomingMessageLegacy(sender, message);
            plugin.getCrossProxyService().publishPrivateMsg(recipientName, recipientMessageLegacy);
            Component senderMsg = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(String.format("&8[&7You &8-> %s&7%s&8]: &f%s", "", recipientName, message));
            sender.sendMessage(senderMsg);
            sender.sendMessage(plugin.getPrefix().append(Component.text("Message sent to " + recipientName + " (cross-proxy).", NamedTextColor.GRAY)));
        } else {
            sender.sendMessage(plugin.getPrefix().append(Component.text("Player '" + recipientName + "' not found or offline.", NamedTextColor.RED)));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length == 0 || invocation.arguments().length == 1) {
            // Suggest online player names for the first argument
            String input = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase();
            
            return plugin.getServer().getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }
        
        // No suggestions for the message content
        return new ArrayList<>();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.message");
    }
}
