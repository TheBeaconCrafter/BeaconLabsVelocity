package org.bcnlab.beaconLabsVelocity.command.server;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class LimboCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;

    public LimboCommand(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        
        String limboServerName = plugin.getConfig().node("limbo-server").getString("limbo");
        Optional<RegisteredServer> targetServer = server.getServer(limboServerName);
        
        if (targetServer.isEmpty()) {
            invocation.source().sendMessage(plugin.getPrefix().append(Component.text("Limbo server '" + limboServerName + "' not found!", NamedTextColor.RED)));
            return;
        }

        if (args.length == 0) {
            if (invocation.source() instanceof Player) {
                Player player = (Player) invocation.source();
                player.sendMessage(plugin.getPrefix().append(Component.text("Sending you to limbo...", NamedTextColor.GRAY)));
                player.createConnectionRequest(targetServer.get()).connectWithIndication().thenAccept(result -> {
                    if (!result) {
                        player.sendMessage(plugin.getPrefix().append(Component.text("Failed to connect to the limbo server.", NamedTextColor.RED)));
                    }
                });
            } else {
                invocation.source().sendMessage(plugin.getPrefix().append(Component.text("Console must specify a player: /limbo <player>", NamedTextColor.RED)));
            }
        } else if (args.length == 1) {
            if (!invocation.source().hasPermission("beaconlabs.admin.limbo")) {
                invocation.source().sendMessage(plugin.getPrefix().append(Component.text("You don't have permission to send others to limbo.", NamedTextColor.RED)));
                return;
            }
            Optional<Player> target = server.getPlayer(args[0]);
            if (target.isPresent()) {
                Player p = target.get();
                p.sendMessage(plugin.getPrefix().append(Component.text("You have been sent to limbo by an administrator.", NamedTextColor.GRAY)));
                p.createConnectionRequest(targetServer.get()).connectWithIndication();
                invocation.source().sendMessage(plugin.getPrefix().append(Component.text("Sent " + p.getUsername() + " to limbo.", NamedTextColor.GREEN)));
            } else {
                invocation.source().sendMessage(plugin.getPrefix().append(Component.text("Player not found.", NamedTextColor.RED)));
            }
        } else {
            invocation.source().sendMessage(plugin.getPrefix().append(Component.text("Usage: /limbo [player]", NamedTextColor.RED)));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length == 1 && invocation.source().hasPermission("beaconlabs.admin.limbo")) {
            return server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(invocation.arguments()[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
