package org.bcnlab.beaconLabsVelocity.command.util;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Command to check a player's ping
 * Usage: /ping [player]
 * Permission: beaconlabs.command.ping (own ping)
 * Permission: beaconlabs.command.ping.others (check other players' ping)
 */
public class PingCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;

    public PingCommand(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            if (source instanceof Player player) {
                long pingValue = player.getPing();
                Component message = formatPingMessage(player.getUsername(), pingValue);
                source.sendMessage(plugin.getPrefix().append(message));
            } else {
                source.sendMessage(plugin.getPrefix().append(Component.text("Usage: /ping <player>", NamedTextColor.RED)));
            }
            return;
        }

        String targetName = args[0];
        
        if (source instanceof Player && !((Player) source).getUsername().equalsIgnoreCase(targetName) &&
            !source.hasPermission("beaconlabs.command.ping.others")) {
            source.sendMessage(plugin.getPrefix().append(Component.text("You don't have permission to check other players' ping.", NamedTextColor.RED)));
            return;
        }

        Optional<Player> optionalTarget = server.getPlayer(targetName);
        if (optionalTarget.isEmpty()) {
            source.sendMessage(plugin.getPrefix().append(Component.text("Player not found: " + targetName, NamedTextColor.RED)));
            return;
        }
        Player target = optionalTarget.get();
        long pingValue = target.getPing();
        Component message = formatPingMessage(target.getUsername(), pingValue);
        source.sendMessage(plugin.getPrefix().append(message));
    }
    /*
     * Format a ping message with color based on ping value
     */
    private Component formatPingMessage(String playerName, long ping) {
        NamedTextColor pingColor;
        
        // Color based on ping quality
        if (ping < 50) {
            pingColor = NamedTextColor.GREEN;
        } else if (ping < 150) {
            pingColor = NamedTextColor.YELLOW;
        } else if (ping < 300) {
            pingColor = NamedTextColor.GOLD;
        } else {
            pingColor = NamedTextColor.RED;
        }
        
        return Component.text(playerName + "'s ping: ", NamedTextColor.GRAY)
                .append(Component.text(ping + "ms", pingColor));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        if (args.length == 1 && (!(source instanceof Player) || source.hasPermission("beaconlabs.command.ping.others"))) {
            String partialName = args[0].toLowerCase();
            return server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(partialName))
                    .collect(Collectors.toList());
        }
        
        return List.of();
    }
}
