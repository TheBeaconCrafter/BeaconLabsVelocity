package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.ServerGuardService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Command to check and manage server guard settings
 */
public class ServerGuardCommand implements SimpleCommand {    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final ServerGuardService guardService;
    
    private final String usePermission;
    private final String reloadPermission;

    public ServerGuardCommand(BeaconLabsVelocity plugin, ProxyServer server, ServerGuardService guardService) {
        this.plugin = plugin;
        this.server = server;
        this.guardService = guardService;
        
        // Load permissions from config
        this.usePermission = plugin.getConfig().node("serverguard", "permissions", "use")
                .getString("beaconlabs.command.serverguard");
        this.reloadPermission = plugin.getConfig().node("serverguard", "permissions", "reload")
                .getString("beaconlabs.command.serverguard.reload");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
          // Check permission
        if (!source.hasPermission(usePermission)) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            ));
            return;
        }        // Check if we're reloading the configuration
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!source.hasPermission(reloadPermission)) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("You don't have permission to reload the server guard configuration.", NamedTextColor.RED)
                ));
                return;
            }
            
            guardService.loadConfig();
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Server guard configuration reloaded.", NamedTextColor.GREEN)
            ));
            return;
        }
        
        // Check if we're showing status for default player
        if (args.length > 0 && args[0].equalsIgnoreCase("all")) {
            showAllServersForDefaultPlayer(source);
            return;
        }

        // If no player specified, use the command source (if it's a player)
        Player targetPlayer = null;
        String serverName = null;
        
        // Check for specific server or player arguments
        if (args.length > 0) {
            // Try to get server directly first
            Optional<String> foundServer = guardService.getServerName(args[0]);
            if (foundServer.isPresent()) {
                serverName = foundServer.get();
            } else {
                // Try to find player
                Optional<Player> foundPlayer = server.getPlayer(args[0]);
                if (foundPlayer.isPresent()) {
                    targetPlayer = foundPlayer.get();
                    
                    // Check if there's a second argument for server
                    if (args.length > 1) {
                        Optional<String> playerServer = guardService.getServerName(args[1]);
                        serverName = playerServer.orElse(null);
                    }
                } else {
                    // Neither server nor player found
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Unknown server or player: ", NamedTextColor.RED)
                    ).append(
                        Component.text(args[0], NamedTextColor.YELLOW)
                    ));
                    return;
                }
            }
        }
        
        // If command source is a player and no target player specified, use the source
        if (targetPlayer == null && source instanceof Player) {
            targetPlayer = (Player) source;
        }
        
        // If no player available and no server specified, show usage
        if (targetPlayer == null && serverName == null) {
            showUsage(source);
            return;
        }
        
        // If no server specified but we have a player, show all servers for that player
        if (serverName == null) {
            showAllServersForPlayer(source, targetPlayer);
            return;
        }
        
        // Show specific server status for player
        showServerStatus(source, targetPlayer, serverName);
    }

    /**
     * Show command usage information
     */
    private void showUsage(CommandSource source) {
        source.sendMessage(plugin.getPrefix().append(
            Component.text("Server Guard Commands:", NamedTextColor.YELLOW, TextDecoration.BOLD)
        ));
        
        source.sendMessage(Component.text("» ", NamedTextColor.GRAY)
            .append(Component.text("/serverguard", NamedTextColor.GOLD))
            .append(Component.text(" - Check your access to all servers", NamedTextColor.WHITE))
        );
        
        source.sendMessage(Component.text("» ", NamedTextColor.GRAY)
            .append(Component.text("/serverguard <server>", NamedTextColor.GOLD))
            .append(Component.text(" - Check your access to a specific server", NamedTextColor.WHITE))
        );
        
        source.sendMessage(Component.text("» ", NamedTextColor.GRAY)
            .append(Component.text("/serverguard <player>", NamedTextColor.GOLD))
            .append(Component.text(" - Check a player's access to all servers", NamedTextColor.WHITE))
        );
        
        source.sendMessage(Component.text("» ", NamedTextColor.GRAY)
            .append(Component.text("/serverguard <player> <server>", NamedTextColor.GOLD))
            .append(Component.text(" - Check a player's access to a specific server", NamedTextColor.WHITE))
        );
          if (source.hasPermission(reloadPermission)) {
            source.sendMessage(Component.text("» ", NamedTextColor.GRAY)
                .append(Component.text("/serverguard reload", NamedTextColor.GOLD))
                .append(Component.text(" - Reload server guard configuration", NamedTextColor.WHITE))
            );
        }
    }

    /**
     * Show all server access status for a player
     */
    private void showAllServersForPlayer(CommandSource source, Player player) {
        String defaultAction = guardService.isDefaultAllow() ? "ALLOW" : "BLOCK";
        
        source.sendMessage(plugin.getPrefix().append(
            Component.text("Server Guard Status for ", NamedTextColor.YELLOW)
        ).append(
            Component.text(player.getUsername(), NamedTextColor.AQUA)
        ));
        
        source.sendMessage(Component.text("Default action: ", NamedTextColor.GRAY)
            .append(Component.text(defaultAction, 
                defaultAction.equals("ALLOW") ? NamedTextColor.GREEN : NamedTextColor.RED))
        );
        
        List<String> servers = guardService.getAllServerNames();
        
        for (String serverName : servers) {
            ServerGuardService.GuardStatus status = guardService.getServerStatus(player, serverName);
            
            Component statusComponent = Component.text(serverName, NamedTextColor.YELLOW)
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(status.getAction().toString(),
                    status.getAction() == ServerGuardService.GuardAction.ALLOW ? 
                        NamedTextColor.GREEN : NamedTextColor.RED
                ))
                .append(Component.text(" - " + status.getReason(), NamedTextColor.GRAY));
                
            if (status.getPermission() != null) {
                statusComponent = statusComponent.append(Component.text(" (" + status.getPermission() + ")", 
                    NamedTextColor.DARK_AQUA));
            }
            
            source.sendMessage(statusComponent);
        }
    }

    /**
     * Show specific server access status for a player
     */
    private void showServerStatus(CommandSource source, Player player, String serverName) {
        ServerGuardService.GuardStatus status = guardService.getServerStatus(player, serverName);
        
        source.sendMessage(plugin.getPrefix().append(
            Component.text("Server Guard Status:", NamedTextColor.YELLOW)
        ));
        
        source.sendMessage(Component.text("Player: ", NamedTextColor.GRAY)
            .append(Component.text(player.getUsername(), NamedTextColor.AQUA))
        );
        
        source.sendMessage(Component.text("Server: ", NamedTextColor.GRAY)
            .append(Component.text(serverName, NamedTextColor.YELLOW))
        );
        
        source.sendMessage(Component.text("Access: ", NamedTextColor.GRAY)
            .append(Component.text(status.getAction().toString(),
                status.getAction() == ServerGuardService.GuardAction.ALLOW ? 
                    NamedTextColor.GREEN : NamedTextColor.RED
            ))
        );
        
        source.sendMessage(Component.text("Reason: ", NamedTextColor.GRAY)
            .append(Component.text(status.getReason(), NamedTextColor.WHITE))
        );
        
        if (status.getPermission() != null) {
            source.sendMessage(Component.text("Permission: ", NamedTextColor.GRAY)
                .append(Component.text(status.getPermission(), NamedTextColor.DARK_AQUA))
            );
        }
    }

    /**
     * Show all server access status for a default player with no permissions
     * 
     * @param source The command source to send the messages to
     */
    private void showAllServersForDefaultPlayer(CommandSource source) {
        String defaultAction = guardService.isDefaultAllow() ? "ALLOW" : "BLOCK";
        
        source.sendMessage(plugin.getPrefix().append(
            Component.text("Server Guard Status for ", NamedTextColor.YELLOW)
        ).append(
            Component.text("DEFAULT PLAYER", NamedTextColor.AQUA, TextDecoration.BOLD)
        ));
        
        source.sendMessage(Component.text("Default action: ", NamedTextColor.GRAY)
            .append(Component.text(defaultAction, 
                defaultAction.equals("ALLOW") ? NamedTextColor.GREEN : NamedTextColor.RED))
        );
        
        List<String> servers = guardService.getAllServerNames();
        
        for (String serverName : servers) {
            // Create a mock player status (no permissions)
            ServerGuardService.GuardStatus status = guardService.getDefaultPlayerStatus(serverName);
            
            Component statusComponent = Component.text(serverName, NamedTextColor.YELLOW)
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(status.getAction().toString(),
                    status.getAction() == ServerGuardService.GuardAction.ALLOW ? 
                        NamedTextColor.GREEN : NamedTextColor.RED
                ))
                .append(Component.text(" - " + status.getReason(), NamedTextColor.GRAY));
                
            if (status.getPermission() != null) {
                statusComponent = statusComponent.append(Component.text(" (" + status.getPermission() + ")", 
                    NamedTextColor.DARK_AQUA));
            }
            
            source.sendMessage(statusComponent);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        final String[] args = invocation.arguments();        // If first argument, suggest reload, player names, and server names
        if (args.length == 0) {
            List<String> suggestions = new ArrayList<>();
            
            if (invocation.source().hasPermission(reloadPermission)) {
                suggestions.add("reload");
            }
            
            // Add all player names
            server.getAllPlayers().forEach(player -> suggestions.add(player.getUsername()));
            
            // Add all server names
            suggestions.addAll(guardService.getAllServerNames());
            
            return suggestions;
        }
          // First argument being entered
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            String input = args[0].toLowerCase();
            
            if ("reload".startsWith(input) && invocation.source().hasPermission(reloadPermission)) {
                suggestions.add("reload");
            }

            if ("all".startsWith(input) && invocation.source().hasPermission(reloadPermission)) {
                suggestions.add("all");
            }
            
            // Add matching player names
            server.getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase().startsWith(input))
                .forEach(suggestions::add);
            
            // Add matching server names
            suggestions.addAll(guardService.getAllServerNames().stream()
                .filter(name -> name.toLowerCase().startsWith(input))
                .collect(Collectors.toList()));
            
            return suggestions;
        }
        
        // Second argument would be for server name after player name
        if (args.length == 2) {
            String input = args[1].toLowerCase();
            
            // Only suggest server names if first arg was a player
            if (server.getPlayer(args[0]).isPresent()) {
                return guardService.getAllServerNames().stream()
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
            }
        }
        
        return List.of();
    }    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(usePermission);
    }
}
