package org.bcnlab.beaconLabsVelocity.command.punishment;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Command to clear all punishments for a player
 * Usage: /cpunish <player>
 */
public class ClearPunishmentCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final PunishmentConfig config;
    private final PunishmentService service;

    public ClearPunishmentCommand(BeaconLabsVelocity plugin, ProxyServer server, PunishmentConfig config, PunishmentService service) {
        this.plugin = plugin;
        this.server = server;
        this.config = config;
        this.service = service;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        
        if (!src.hasPermission("beaconlabs.punish.clear")) {
            src.sendMessage(plugin.getPrefix().append(
                LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("no-permission"))));
            return;
        }
        
        if (args.length < 1) {
            src.sendMessage(plugin.getPrefix().append(
                LegacyComponentSerializer.legacyAmpersand().deserialize("&cUsage: /cpunish <player>")));
            return;
        }
        
        String playerName = args[0];
        
        // Try to find the player (online or offline)
        UUID targetUUID = null;
        Player onlinePlayer = server.getPlayer(playerName).orElse(null);
        
        if (onlinePlayer != null) {
            targetUUID = onlinePlayer.getUniqueId();
            playerName = onlinePlayer.getUsername(); // Use correct case
        } else {
            // Try to get UUID for offline player
            targetUUID = service.getPlayerUUID(playerName);
            if (targetUUID == null) {
                src.sendMessage(plugin.getPrefix().append(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(
                        config.getMessage("player-not-found").replace("{player}", playerName))));
                return;
            }
        }
        
        // Clear all punishments for the player
        int count = service.clearPunishments(targetUUID);
        
        // Send success message
        if (count > 0) {
            src.sendMessage(plugin.getPrefix().append(
                LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&aCleared all punishments for &f" + playerName + "&a. &7(" + count + " records removed)")));
        } else {
            src.sendMessage(plugin.getPrefix().append(
                LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&aNo punishment records found for &f" + playerName + "&a.")));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            return server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
