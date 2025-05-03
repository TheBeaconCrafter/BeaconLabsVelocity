package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.WhitelistService;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Command to manage the proxy-wide whitelist
 */
public class ProxyWhitelistCommandNew implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final WhitelistService whitelistService;
    private static final String WHITELIST_PERMISSION = "beaconlabs.command.whitelist";

    public ProxyWhitelistCommandNew(BeaconLabsVelocity plugin, WhitelistService whitelistService) {
        this.plugin = plugin;
        this.whitelistService = whitelistService;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        // Check permission
        if (!source.hasPermission(WHITELIST_PERMISSION)) {
            source.sendMessage(plugin.getPrefix().append(
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
                    source.sendMessage(plugin.getPrefix().append(
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
                    source.sendMessage(plugin.getPrefix().append(
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
        source.sendMessage(plugin.getPrefix().append(
            Component.text("Whitelist Commands:", NamedTextColor.GOLD)
        ));
        source.sendMessage(Component.text("  /proxywhitelist on - Enable the whitelist", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /proxywhitelist off - Disable the whitelist", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /proxywhitelist add <player> - Add a player to the whitelist", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /proxywhitelist remove <player> - Remove a player from the whitelist", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /proxywhitelist list - List all whitelisted players", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /proxywhitelist status - Check if whitelist is enabled", NamedTextColor.YELLOW));
    }
    
    private void setWhitelistEnabled(boolean enabled, CommandSource source) {
        boolean changed = whitelistService.setWhitelistEnabled(enabled);
        
        String status = enabled ? "enabled" : "disabled";
        if (changed) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Whitelist has been " + status + ".", NamedTextColor.GREEN)
            ));
            
            // If whitelist is being enabled, kick non-whitelisted players
            if (enabled) {
                kickNonWhitelistedPlayers(source);
            }
            
            // Log the action
            String sourceName = source instanceof Player ? ((Player) source).getUsername() : "Console";
            plugin.getLogger().info("Whitelist {} by {}", status, sourceName);
        } else {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Whitelist was already " + status + ".", NamedTextColor.YELLOW)
            ));
        }
    }
    
    /**
     * Kick all players who are not whitelisted or don't have bypass permission
     */
    private void kickNonWhitelistedPlayers(CommandSource source) {
        whitelistService.kickNonWhitelistedPlayers().thenAccept(kickCount -> {
            if (kickCount > 0) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Kicked " + kickCount + " non-whitelisted players.", NamedTextColor.YELLOW)
                ));
            }
        }).exceptionally(e -> {
            plugin.getLogger().error("Error kicking non-whitelisted players", e);
            source.sendMessage(plugin.getPrefix().append(
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
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Added player ", NamedTextColor.GREEN)
                        .append(Component.text(playerName, NamedTextColor.GOLD))
                        .append(Component.text(" to the whitelist.", NamedTextColor.GREEN))
                ));
            } else {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Failed to add player to whitelist.", NamedTextColor.RED)
                ));
            }
        }).exceptionally(e -> {
            plugin.getLogger().error("Error adding player to whitelist", e);
            source.sendMessage(plugin.getPrefix().append(
                Component.text("An error occurred while adding player to whitelist.", NamedTextColor.RED)
            ));
            return null;
        });
    }
    
    private void removePlayer(CommandSource source, String playerName) {
        whitelistService.removePlayer(playerName).thenAccept(success -> {
            if (success) {
                source.sendMessage(plugin.getPrefix().append(
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
                        player.disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(whitelistService.getKickMessage()));
                        source.sendMessage(plugin.getPrefix().append(
                            Component.text("Kicked player ", NamedTextColor.YELLOW)
                                .append(Component.text(playerName, NamedTextColor.GOLD))
                                .append(Component.text(" as they are no longer whitelisted.", NamedTextColor.YELLOW))
                        ));
                    });
                }
            } else {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Player ", NamedTextColor.YELLOW)
                        .append(Component.text(playerName, NamedTextColor.GOLD))
                        .append(Component.text(" was not found in the whitelist.", NamedTextColor.YELLOW))
                ));
            }
        }).exceptionally(e -> {
            plugin.getLogger().error("Error removing player from whitelist", e);
            source.sendMessage(plugin.getPrefix().append(
                Component.text("An error occurred while removing player from whitelist.", NamedTextColor.RED)
            ));
            return null;
        });
    }
    
    private void listPlayers(CommandSource source) {
        whitelistService.getWhitelistedPlayers().thenAccept(players -> {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Whitelisted players (" + players.size() + "):", NamedTextColor.GOLD)
            ));
            
            if (players.isEmpty()) {
                source.sendMessage(Component.text("  No players are whitelisted.", NamedTextColor.YELLOW));
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
                        source.sendMessage(Component.text("  " + builder, NamedTextColor.YELLOW));
                        builder = new StringBuilder();
                    }
                }
                
                // Send any remaining players
                if (builder.length() > 0) {
                    source.sendMessage(Component.text("  " + builder, NamedTextColor.YELLOW));
                }
            }
        }).exceptionally(e -> {
            plugin.getLogger().error("Error listing whitelisted players", e);
            source.sendMessage(plugin.getPrefix().append(
                Component.text("An error occurred while retrieving whitelisted players.", NamedTextColor.RED)
            ));
            return null;
        });
    }
    
    private void checkStatus(CommandSource source) {
        boolean enabled = whitelistService.isWhitelistEnabled();
        String status = enabled ? "enabled" : "disabled";
        NamedTextColor color = enabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        
        source.sendMessage(plugin.getPrefix().append(
            Component.text("Whitelist is currently ", NamedTextColor.GOLD)
                .append(Component.text(status, color))
                .append(Component.text(".", NamedTextColor.GOLD))
        ));
        
        if (enabled) {
            // Also show bypass permission
            source.sendMessage(Component.text("Bypass permission: ", NamedTextColor.GOLD)
                .append(Component.text(whitelistService.getBypassPermission(), NamedTextColor.YELLOW)));
        }
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
            // Suggest online players who aren't already whitelisted
            List<String> onlinePlayers = plugin.getServer().getAllPlayers().stream()
                .map(Player::getUsername)
                .collect(Collectors.toList());
            
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
