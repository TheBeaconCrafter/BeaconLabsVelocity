package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Command to teleport to other servers or players
 * Usage: /goto <server|player>
 * Permission: beaconlabs.command.goto
 */
public class GoToCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;

    public GoToCommand(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
    }
    
    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        // Check permission
        if (!source.hasPermission("beaconlabs.command.goto")) {
            source.sendMessage(plugin.getPrefix().append(Component.text("You don't have permission to use this command.", NamedTextColor.RED)));
            return;
        }
        
        // Only players can use this command
        if (!(source instanceof Player player)) {
            source.sendMessage(plugin.getPrefix().append(Component.text("This command can only be used by players.", NamedTextColor.RED)));
            return;
        }
        
        // Check if a target is specified
        if (args.length < 1) {
            source.sendMessage(plugin.getPrefix().append(Component.text("Usage: /goto <server|player>", NamedTextColor.RED)));
            return;
        }
        
        String target = args[0];
        
        // First check if target is a player (on this proxy)
        Optional<Player> targetPlayer = server.getPlayer(target);
        if (targetPlayer.isPresent()) {
            Player targetP = targetPlayer.get();
            if (player.equals(targetP)) {
                source.sendMessage(plugin.getPrefix().append(Component.text("You cannot teleport to yourself.", NamedTextColor.RED)));
                return;
            }
            targetP.getCurrentServer().ifPresent(serverConnection -> {
                RegisteredServer targetServer = serverConnection.getServer();
                teleportToServer(player, targetServer, "Player " + targetP.getUsername());
            });
            return;
        }

        // Target may be on another proxy: resolve server from cross-proxy plist
        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            String targetServerName = plugin.getCrossProxyService().getPlayerCurrentServer(target);
            if (targetServerName != null) {
                Optional<RegisteredServer> rs = server.getServer(targetServerName);
                if (rs.isPresent()) {
                    teleportToServer(player, rs.get(), "Player " + target);
                    return;
                }
            }
        }
        
        // If not a player, try to find a server with that name
        Optional<RegisteredServer> targetServer = server.getServer(target);
        if (targetServer.isEmpty()) {
            source.sendMessage(plugin.getPrefix().append(Component.text("Server or player not found: " + target, NamedTextColor.RED)));
            return;
        }
        
        teleportToServer(player, targetServer.get(), "Server " + target);
    }
    
    /**
     * Teleport a player to a specific server
     */
    private void teleportToServer(Player player, RegisteredServer targetServer, String destination) {
        // Check if player is already on that server
        if (player.getCurrentServer().isPresent() && 
            player.getCurrentServer().get().getServer().equals(targetServer)) {
            player.sendMessage(plugin.getPrefix().append(
                Component.text("You are already connected to this server.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Send teleport message
        player.sendMessage(plugin.getPrefix().append(
            Component.text("Connecting to " + destination + "...", NamedTextColor.GREEN)
        ));
        
        // Connect the player to the target server
        player.createConnectionRequest(targetServer).connect()
            .thenAcceptAsync(result -> {
                if (result.isSuccessful()) {
                    player.sendMessage(plugin.getPrefix().append(
                        Component.text("Successfully connected to " + destination + "!", NamedTextColor.GREEN)
                    ));
                } else {
                    player.sendMessage(plugin.getPrefix().append(
                        Component.text("Failed to connect to " + destination + ": " + result.getReasonComponent().orElse(Component.text("Unknown reason")), NamedTextColor.RED)
                    ));
                }
            });
    }
    
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        
        if (args.length == 1) {
            String partialName = args[0].toLowerCase();
            List<String> suggestions = new java.util.ArrayList<>();
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                suggestions.addAll(plugin.getCrossProxyService().getOnlinePlayerNames().stream()
                        .filter(name -> name.toLowerCase().startsWith(partialName))
                        .collect(Collectors.toList()));
            } else {
                suggestions.addAll(server.getAllPlayers().stream()
                        .map(Player::getUsername)
                        .filter(name -> name.toLowerCase().startsWith(partialName))
                        .collect(Collectors.toList()));
            }
            // Add server names
            suggestions.addAll(server.getAllServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .filter(name -> name.toLowerCase().startsWith(partialName))
                    .collect(Collectors.toList()));
            
            return suggestions;
        }
        
        return List.of();
    }
}
