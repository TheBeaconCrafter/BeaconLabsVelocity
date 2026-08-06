package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.WhitelistService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Command to manage the proxy-wide whitelist
 */
public class ProxyWhitelistCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final WhitelistService whitelistService;
    private static final String WHITELIST_PERMISSION = "beaconlabs.command.whitelist";
    
    public ProxyWhitelistCommand(BeaconLabsVelocity plugin, WhitelistService whitelistService) {
        this.plugin = plugin;
        this.whitelistService = whitelistService;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        // Check permission
        if (!source.hasPermission(WHITELIST_PERMISSION)) {
            source.sendMessage(plugin.getPrefix(source).append(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            ));
            return;
        }
        
        // No arguments provided
        if (args.length == 0) {
            sendUsage(source);
            return;
        }
        
        // Handle whitelist commands
        switch (args[0].toLowerCase()) {
            case "on":
                setWhitelistEnabled(true, source);
                break;
            case "off":
                setWhitelistEnabled(false, source);
                break;
            case "add":
                if (args.length < 2) {
                    source.sendMessage(plugin.getPrefix(source).append(
                        Component.text("Please specify a player name.", NamedTextColor.RED)
                    ));
                    return;
                }
                addPlayer(source, args[1]);
                break;
            case "remove":
            case "del":
            case "delete":
                if (args.length < 2) {
                    source.sendMessage(plugin.getPrefix(source).append(
                        Component.text("Please specify a player name.", NamedTextColor.RED)
                    ));
                    return;
                }
                removePlayer(source, args[1]);
                break;
            case "list":
                listPlayers(source);
                break;
            case "status":
                checkStatus(source);
                break;
            default:
                sendUsage(source);
                break;
        }
    }
    
    private void sendUsage(CommandSource source) {
        source.sendMessage(plugin.getPrefix(source).append(
            Component.text("Whitelist Commands:", NamedTextColor.GOLD)
        ));
        source.sendMessage(Component.text("  /proxywhitelist on - Enable the whitelist", NamedTextColor.GOLD));
        source.sendMessage(Component.text("  /proxywhitelist off - Disable the whitelist", NamedTextColor.GOLD));
        source.sendMessage(Component.text("  /proxywhitelist add <player> - Add a player to the whitelist", NamedTextColor.GOLD));
        source.sendMessage(Component.text("  /proxywhitelist remove <player> - Remove a player from the whitelist", NamedTextColor.GOLD));
        source.sendMessage(Component.text("  /proxywhitelist list - List all whitelisted players", NamedTextColor.GOLD));
        source.sendMessage(Component.text("  /proxywhitelist status - Check if whitelist is enabled", NamedTextColor.GOLD));
    }
    
    private void setWhitelistEnabled(boolean enabled, CommandSource source) {
        boolean changed = whitelistService.setWhitelistEnabled(enabled);
        
        String status = enabled ? "enabled" : "disabled";
        if (changed) {
            source.sendMessage(plugin.getPrefix(source).append(
                Component.text("Whitelist has been " + status + ".", NamedTextColor.GREEN)
            ));
            
            // If whitelist is being enabled, kick non-whitelisted players
            if (enabled) {
                kickNonWhitelistedPlayers(source);
            }
            
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                plugin.getCrossProxyService().publishWhitelistSet(enabled);
            }
            
            // Log the action
            String sourceName = source instanceof Player ? ((Player) source).getUsername() : "Console";
            plugin.getLogger().info("Whitelist {} by {}", status, sourceName);
        } else {
            source.sendMessage(plugin.getPrefix(source).append(
                Component.text("Whitelist was already " + status + ".", NamedTextColor.GOLD)
            ));
        }
    }
    
    /**
     * Kick all players who are not whitelisted or don't have bypass permission
     */
    private void kickNonWhitelistedPlayers(CommandSource source) {
        whitelistService.kickNonWhitelistedPlayers().thenAccept(kickCount -> {
            if (kickCount > 0) {
                source.sendMessage(plugin.getPrefix(source).append(
                    Component.text("Kicked " + kickCount + " non-whitelisted players.", NamedTextColor.GOLD)
                ));
            }
        }).exceptionally(e -> {
            plugin.getLogger().error("Error kicking non-whitelisted players", e);
            source.sendMessage(plugin.getPrefix(source).append(
                Component.text("An error occurred while kicking non-whitelisted players.", NamedTextColor.RED)
            ));
            return null;
        });
    }
    
    private void addPlayer(CommandSource source, String playerName) {
        // Get who added this player
        String addedBy = source instanceof Player ? ((Player) source).getUsername() : "Console";
        
        whitelistService.addPlayer(playerName, addedBy).thenAccept(success -> {
            if (success) {
                source.sendMessage(plugin.getPrefix(source).append(
                    Component.text("Added player ", NamedTextColor.GREEN)
                        .append(Component.text(playerName, NamedTextColor.GOLD))
                        .append(Component.text(" to the whitelist.", NamedTextColor.GREEN))
                ));
            } else {
                source.sendMessage(plugin.getPrefix(source).append(
                    Component.text("Failed to add player to whitelist.", NamedTextColor.RED)
                ));
            }
        }).exceptionally(e -> {
            plugin.getLogger().error("Error adding player to whitelist", e);
            source.sendMessage(plugin.getPrefix(source).append(
                Component.text("An error occurred while adding player to whitelist.", NamedTextColor.RED)
            ));
            return null;
        });
    }
    
    private void removePlayer(CommandSource source, String playerName) {
        whitelistService.removePlayer(playerName).thenAccept(success -> {
            if (success) {
                source.sendMessage(plugin.getPrefix(source).append(
                    Component.text("Removed player ", NamedTextColor.GREEN)
                        .append(Component.text(playerName, NamedTextColor.GOLD))
                        .append(Component.text(" from the whitelist.", NamedTextColor.GREEN))
                ));
                
                // If player is online and whitelist is enabled, kick them
                if (whitelistService.isWhitelistEnabled()) {
                    plugin.getServer().getPlayer(playerName).ifPresent(player -> {
                        // Skip if they have bypass permission
                        if (player.hasPermission(whitelistService.getBypassPermission())) {
                            return;
                        }
                        
                        // Kick the player
                        Component kickMessage = MiniMessage.miniMessage()
                            .deserialize(whitelistService.getKickMessage());
                        player.disconnect(kickMessage);
                        
                        // Notify source
                        source.sendMessage(plugin.getPrefix(source).append(
                            Component.text("Kicked player ", NamedTextColor.GOLD)
                                .append(Component.text(playerName, NamedTextColor.GOLD))
                                .append(Component.text(" as they are no longer whitelisted.", NamedTextColor.GOLD))
                        ));
                    });
                }
            } else {
                source.sendMessage(plugin.getPrefix(source).append(
                    Component.text("Player ", NamedTextColor.GOLD)
                        .append(Component.text(playerName, NamedTextColor.GOLD))
                        .append(Component.text(" was not found in the whitelist.", NamedTextColor.GOLD))
                ));
            }
        }).exceptionally(e -> {
            plugin.getLogger().error("Error removing player from whitelist", e);
            source.sendMessage(plugin.getPrefix(source).append(
                Component.text("An error occurred while removing player from whitelist.", NamedTextColor.RED)
            ));
            return null;
        });
    }
    
    private void listPlayers(CommandSource source) {
        whitelistService.getWhitelistedPlayers().thenAccept(players -> {
            source.sendMessage(plugin.getPrefix(source).append(
                Component.text("Whitelisted players (" + players.size() + "):", NamedTextColor.GOLD)
            ));
            
            if (players.isEmpty()) {
                source.sendMessage(Component.text("  No players are whitelisted.", NamedTextColor.GOLD));
            } else {
                // Sort alphabetically
                Collections.sort(players, String.CASE_INSENSITIVE_ORDER);
                
                // Format nicely
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < players.size(); i++) {
                    builder.append(players.get(i));
                    if (i < players.size() - 1) {
                        builder.append(", ");
                    }
                    
                    // Every 5 players, start a new line
                    if ((i + 1) % 5 == 0 && i < players.size() - 1) {
                        source.sendMessage(Component.text("  " + builder, NamedTextColor.GOLD));
                        builder = new StringBuilder();
                    }
                }
                
                // Send any remaining players
                if (builder.length() > 0) {
                    source.sendMessage(Component.text("  " + builder, NamedTextColor.GOLD));
                }
            }
        }).exceptionally(e -> {
            plugin.getLogger().error("Error listing whitelisted players", e);
            source.sendMessage(plugin.getPrefix(source).append(
                Component.text("An error occurred while retrieving whitelisted players.", NamedTextColor.RED)
            ));
            return null;
        });
    }
    
    private void checkStatus(CommandSource source) {
        boolean enabled = whitelistService.isWhitelistEnabled();
        String status = enabled ? "enabled" : "disabled";
        NamedTextColor color = enabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        
        source.sendMessage(plugin.getPrefix(source).append(
            Component.text("Whitelist is currently ", NamedTextColor.GOLD)
                .append(Component.text(status, color))
                .append(Component.text(".", NamedTextColor.GOLD))
        ));
        
        if (enabled) {
            // Also show bypass permission
            source.sendMessage(Component.text("  Bypass permission: ", NamedTextColor.GOLD)
                .append(Component.text(whitelistService.getBypassPermission(), NamedTextColor.GOLD)));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(WHITELIST_PERMISSION);
    }
    
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        
        // No arg suggestions
        if (args.length == 0) {
            return List.of("on", "off", "add", "remove", "list", "status");
        }
        
        // First arg suggestions
        if (args.length == 1) {
            return List.of("on", "off", "add", "remove", "list", "status").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        // Second arg suggestions for remove command
        if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || 
                                args[0].equalsIgnoreCase("del") || 
                                args[0].equalsIgnoreCase("delete"))) {
            try {
                return whitelistService.getWhitelistedPlayers().get().stream()
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
            } catch (InterruptedException | ExecutionException e) {
                plugin.getLogger().error("Error fetching whitelisted players for tab completion", e);
                return List.of();
            }
        }
        
        // Second arg suggestions for add command
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            java.util.Collection<String> onlinePlayers;
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                onlinePlayers = plugin.getCrossProxyService().getOnlinePlayerNames();
            } else {
                onlinePlayers = plugin.getServer().getAllPlayers().stream()
                    .map(Player::getUsername)
                    .collect(Collectors.toList());
            }
            try {
                List<String> whitelistedPlayers = whitelistService.getWhitelistedPlayers().get();
                return onlinePlayers.stream()
                    .filter(name -> !whitelistedPlayers.contains(name))
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
            } catch (InterruptedException | ExecutionException e) {
                plugin.getLogger().error("Error fetching whitelisted players for tab completion", e);
                return List.of();
            }
        }
        
        return List.of();
    }
}
