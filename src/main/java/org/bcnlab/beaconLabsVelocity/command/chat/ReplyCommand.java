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
            source.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return;
        }

        Player sender = (Player) source;

        // Check arguments
        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /r <message>", NamedTextColor.RED));
            return;
        }

        // Get the last message sender
        Optional<Player> optRecipient = messageService.getLastMessageSender(sender);

        if (!optRecipient.isPresent()) {
            sender.sendMessage(Component.text("You have no one to reply to.", NamedTextColor.RED));
            return;
        }

        // Check if the recipient is still online
        Player recipient = optRecipient.get();
        if (!recipient.isActive()) {
            sender.sendMessage(Component.text("Player '" + recipient.getUsername() + "' is no longer online.", NamedTextColor.RED));
            return;
        }

        // Combine arguments into message
        String message = String.join(" ", args);

        // Send the message
        messageService.sendPrivateMessage(sender, recipient, message);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.message");
    }
}
