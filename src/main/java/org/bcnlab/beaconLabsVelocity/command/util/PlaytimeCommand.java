package org.bcnlab.beaconLabsVelocity.command.util;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService.IpHistoryEntry;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService.PlayerPlaytimeEntry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Command to check player playtime and IP history
 * Usage: /playtime [player]
 * Aliases: /pt
 * Permissions:
 * - beaconlabs.command.playtime - View your own playtime
 * - beaconlabs.command.playtime.others - View others' playtime
 * - beaconlabs.command.playtime.ip - View IP history (admin only)
 * - beaconlabs.command.playtime.top - View top playtime list
 */
public class PlaytimeCommand implements SimpleCommand {
    
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final PlayerStatsService playerStatsService;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    
    public PlaytimeCommand(BeaconLabsVelocity plugin, ProxyServer server, PlayerStatsService playerStatsService) {
        this.plugin = plugin;
        this.server = server;
        this.playerStatsService = playerStatsService;
    }
    
    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        if (args.length == 0) {
            // View own playtime
            if (!(source instanceof Player)) {
                source.sendMessage(Component.text("This command can only be used by players when no arguments are provided.", NamedTextColor.RED));
                return;
            }
            
            Player player = (Player) source;
            if (!player.hasPermission("beaconlabs.command.playtime")) {
                source.sendMessage(plugin.getPrefix().append(Component.text("You don't have permission to use this command.", NamedTextColor.RED)));
                return;
            }
            
            showPlayerPlaytime(source, player.getUniqueId(), player.getUsername());
            return;
        }
        
        String subcommand = args[0].toLowerCase();
        
        // Handle special subcommands
        if (subcommand.equals("top")) {
            if (!source.hasPermission("beaconlabs.command.playtime.top")) {
                source.sendMessage(plugin.getPrefix().append(Component.text("You don't have permission to view the top playtime list.", NamedTextColor.RED)));
                return;
            }
            
            int limit = 10; // Default limit
            if (args.length > 1) {
                try {
                    limit = Integer.parseInt(args[1]);
                    if (limit < 1) limit = 10;
                    if (limit > 100) limit = 100;
                } catch (NumberFormatException e) {
                    // Ignore parse error and use default
                }
            }
            
            showTopPlaytime(source, limit);
            return;
        }
        
        // View another player's playtime
        if (!source.hasPermission("beaconlabs.command.playtime.others")) {
            source.sendMessage(plugin.getPrefix().append(Component.text("You don't have permission to view others' playtime.", NamedTextColor.RED)));
            return;
        }
        
        String targetName = args[0];
        Optional<Player> targetOptional = server.getPlayer(targetName);
        
        if (targetOptional.isPresent()) {
            // Target is online
            Player target = targetOptional.get();
            showPlayerPlaytime(source, target.getUniqueId(), target.getUsername());
            
            // Show IP history if allowed
            if (source.hasPermission("beaconlabs.command.playtime.ip")) {
                showPlayerIpHistory(source, target.getUniqueId(), target.getUsername());
            }
        } else {
            // Try to get info for offline player
            // First try to get UUID from online players with similar names
            UUID offlineUuid = server.getAllPlayers().stream()
                    .filter(p -> p.getUsername().equalsIgnoreCase(targetName))
                    .map(Player::getUniqueId)
                    .findFirst()
                    .orElse(null);
                    
            // If not found, check other services
            if (offlineUuid == null) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Try using /info command for more details about " + targetName, NamedTextColor.YELLOW)
                ));
                return;
            }
            
            // If we have a UUID, show the playtime
            if (offlineUuid != null) {
                showPlayerPlaytime(source, offlineUuid, targetName);
                
                // Show IP history if allowed
                if (source.hasPermission("beaconlabs.command.playtime.ip")) {
                    showPlayerIpHistory(source, offlineUuid, targetName);
                }
            } else {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Player not found: " + targetName, NamedTextColor.RED)
                ));
            }
        }
    }
    
    /**
     * Show a player's playtime information
     */
    private void showPlayerPlaytime(CommandSource source, UUID playerId, String playerName) {
        long playtime = playerStatsService.getPlayerPlaytime(playerId);
        String formattedPlaytime = PlayerStatsService.formatPlaytime(playtime);
        
        source.sendMessage(Component.text()
            .append(plugin.getPrefix())
            .append(Component.text(playerName + "'s Playtime: ", NamedTextColor.YELLOW))
            .append(Component.text(formattedPlaytime, NamedTextColor.GREEN))
            .build()
        );
    }
    
    /**
     * Show a player's IP history (admin only)
     */
    private void showPlayerIpHistory(CommandSource source, UUID playerId, String playerName) {
        List<IpHistoryEntry> ipHistory = playerStatsService.getPlayerIpHistory(playerId);
        
        if (ipHistory.isEmpty()) {
            source.sendMessage(Component.text()
                .append(plugin.getPrefix())
                .append(Component.text("No IP history found for " + playerName, NamedTextColor.RED))
                .build()
            );
            return;
        }
        
        source.sendMessage(Component.text()
            .append(plugin.getPrefix())
            .append(Component.text(playerName + "'s Recent IPs:", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
            .build()
        );
        
        for (IpHistoryEntry entry : ipHistory) {
            source.sendMessage(Component.text()
                .append(Component.text(" • ", NamedTextColor.GOLD))
                .append(Component.text(entry.getIpAddress(), NamedTextColor.WHITE))
                .append(Component.text(" (", NamedTextColor.GRAY))
                .append(Component.text(dateFormat.format(new Date(entry.getTimestamp())), NamedTextColor.AQUA))
                .append(Component.text(")", NamedTextColor.GRAY))
                .build()
            );
        }
    }
    
    /**
     * Show the top players by playtime
     */
    private void showTopPlaytime(CommandSource source, int limit) {
        List<PlayerPlaytimeEntry> topPlayers = playerStatsService.getTopPlaytimePlayers(limit);
        
        if (topPlayers.isEmpty()) {
            source.sendMessage(Component.text()
                .append(plugin.getPrefix())
                .append(Component.text("No playtime data found.", NamedTextColor.RED))
                .build()
            );
            return;
        }
        
        source.sendMessage(Component.text()
            .append(plugin.getPrefix())
            .append(Component.text("Top " + limit + " Players by Playtime:", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .build()
        );
        
        int rank = 1;
        for (PlayerPlaytimeEntry entry : topPlayers) {
            NamedTextColor rankColor;
            switch (rank) {
                case 1: rankColor = NamedTextColor.GOLD; break;
                case 2: rankColor = NamedTextColor.GRAY; break;
                case 3: rankColor = NamedTextColor.DARK_RED; break;
                default: rankColor = NamedTextColor.GREEN;
            }
            
            source.sendMessage(Component.text()
                .append(Component.text("#" + rank + " ", rankColor).decorate(TextDecoration.BOLD))
                .append(Component.text(entry.getPlayerName(), NamedTextColor.WHITE))
                .append(Component.text(" - ", NamedTextColor.GRAY))
                .append(Component.text(entry.getFormattedPlaytime(), NamedTextColor.AQUA))
                .build()
            );
            
            rank++;
        }
    }
    
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        CommandSource source = invocation.source();
        
        if (args.length == 1) {
            // Suggest online players and special subcommands
            List<String> suggestions = server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .collect(Collectors.toList());
            
            if (source.hasPermission("beaconlabs.command.playtime.top")) {
                suggestions.add("top");
            }
            
            return suggestions;
        }
        
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            // Suggest limits for top command
            return List.of("5", "10", "25", "50", "100");
        }
        
        return List.of();
    }
}
