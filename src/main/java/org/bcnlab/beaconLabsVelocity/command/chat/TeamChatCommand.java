package org.bcnlab.beaconLabsVelocity.command.chat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.MessageService;

/**
 * Command for team chat visible only to players with beaconlabs.teamchat permission
 * Usage: /teamchat <message> or /tc <message>
 */
public class TeamChatCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final MessageService messageService;
    
    // Format for team chat messages
    private final String teamChatFormat = "&8[&cTeam&8] [%s&7%s&8]: &f%s"; // Args: prefix, name, message

    public TeamChatCommand(BeaconLabsVelocity plugin, MessageService messageService) {
        this.plugin = plugin;
        this.messageService = messageService;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        // Check if source is a player
        if (!(source instanceof Player)) {
            source.sendMessage(plugin.getPrefix().append(Component.text("Only players can use team chat.", NamedTextColor.RED)));
            return;
        }

        Player sender = (Player) source;

        // Check for message content
        if (args.length == 0) {
            sender.sendMessage(plugin.getPrefix().append(Component.text("Usage: /teamchat <message>", NamedTextColor.RED)));
            return;
        }

        // Combine arguments into message
        String message = String.join(" ", args);
        String senderPrefix = messageService.getPlayerPrefix(sender);
        String messageLegacy = String.format(teamChatFormat, senderPrefix, sender.getUsername(), message);

        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            plugin.getCrossProxyService().publishTeamChat(messageLegacy);
        } else {
            Component formattedMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(messageLegacy);
            for (Player player : plugin.getServer().getAllPlayers()) {
                if (player.hasPermission("beaconlabs.teamchat")) {
                    player.sendMessage(formattedMessage);
                }
            }
        }
        plugin.getLogger().info("[TeamChat] {}: {}", sender.getUsername(), message);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.teamchat");
    }
}
