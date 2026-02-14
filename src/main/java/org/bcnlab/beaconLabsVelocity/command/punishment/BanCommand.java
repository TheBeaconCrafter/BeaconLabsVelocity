package org.bcnlab.beaconLabsVelocity.command.punishment;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.bcnlab.beaconLabsVelocity.util.DurationUtils;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class BanCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final PunishmentConfig config;
    private final PunishmentService service;

    public BanCommand(BeaconLabsVelocity plugin, ProxyServer server, PunishmentConfig config, PunishmentService service) {
        this.plugin = plugin;
        this.server = server;
        this.config = config;
        this.service = service;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        if (!src.hasPermission("beaconlabs.punish.ban")) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("no-permission"))));
            return;
        }
        if (args.length < 2) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize("&cUsage: /ban <player> <duration> [reason]")));
            return;
        }        String targetName = args[0];
        if (src instanceof Player && ((Player) src).getUsername().equalsIgnoreCase(targetName)) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("self-punish"))));
            return;
        }
        
        // Parse duration and reason first
        long duration = DurationUtils.parseDuration(args[1]);
        String reason = config.getMessage("default-reason");
        if (args.length > 2) {
            reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        }
        
        // Try to find the player
        Optional<Player> optionalTarget = server.getPlayer(targetName);
        
        if (optionalTarget.isPresent()) {
            // Player is online - ban them directly
            Player target = optionalTarget.get();
            
            // Record the ban
            service.punish(
                    target.getUniqueId(), target.getUsername(),
                    (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                    (src instanceof Player) ? ((Player) src).getUsername() : "Console",
                    "ban", duration, reason
            );
            
            // Notify executor with prefix
            String successMsg = config.getMessage("ban-success")
                    .replace("{player}", target.getUsername())
                    .replace("{duration}", DurationUtils.formatDuration(duration))
                    .replace("{reason}", reason);
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(successMsg)));
            
            // Disconnect the player with ban-kick-message template and prefix
            String rawKick = config.getMessage("ban-screen")
                    .replace("{reason}", reason)
                    .replace("{duration}", DurationUtils.formatDuration(duration));
            Component kickComp = plugin.getPrefix().append(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(rawKick)
            );
            target.disconnect(kickComp);

            // Notify other proxies to kick this player if they have them
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                plugin.getCrossProxyService().publishKick(target.getUniqueId(), rawKick);
            }
        } else {
            // Player is offline - try to look up UUID in database
            UUID offlineUuid = service.getPlayerUUID(targetName);
            
            if (offlineUuid != null) {
                // Found player data - apply ban to offline player
                service.punish(
                        offlineUuid, targetName,
                        (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                        (src instanceof Player) ? ((Player) src).getUsername() : "Console",
                        "ban", duration, reason
                );
                
                // Notify executor with prefix
                String successMsg = config.getMessage("ban-success")
                        .replace("{player}", targetName)
                        .replace("{duration}", DurationUtils.formatDuration(duration))
                        .replace("{reason}", reason) + " (Offline player)";
                src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(successMsg)));
                if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                    String rawKick = config.getMessage("ban-screen").replace("{reason}", reason).replace("{duration}", DurationUtils.formatDuration(duration));
                    plugin.getCrossProxyService().publishKick(offlineUuid, rawKick);
                    plugin.getCrossProxyService().publishKickByName(targetName, rawKick); // in case they're on another proxy
                }
            } else {
                // Create a new offline ban entry
                UUID generatedUuid = UUID.nameUUIDFromBytes(("offlineplayer:" + targetName.toLowerCase()).getBytes());
                
                service.punish(
                        generatedUuid, targetName,
                        (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                        (src instanceof Player) ? ((Player) src).getUsername() : "Console",
                        "ban", duration, reason
                );
                
                // Notify executor with prefix
                String successMsg = config.getMessage("ban-success")
                        .replace("{player}", targetName)
                        .replace("{duration}", DurationUtils.formatDuration(duration))
                        .replace("{reason}", reason) + " (New offline player)";
                src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(successMsg)));
                if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                    String rawKick = config.getMessage("ban-screen").replace("{reason}", reason).replace("{duration}", DurationUtils.formatDuration(duration));
                    plugin.getCrossProxyService().publishKick(generatedUuid, rawKick);
                    plugin.getCrossProxyService().publishKickByName(targetName, rawKick); // in case they're on another proxy
                }
            }
        }        // Broadcast to notified players
        String rawBroadcast = config.getMessage("ban-broadcast");
        if (rawBroadcast != null) {
            rawBroadcast = rawBroadcast
                    .replace("{player}", targetName)
                    .replace("{issuer}", (src instanceof Player) ? ((Player) src).getUsername() : "Console")
                    .replace("{duration}", DurationUtils.formatDuration(duration))
                    .replace("{reason}", reason);
            Component broadcastComp = plugin.getPrefix().append(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(rawBroadcast)
            );
            server.getAllPlayers().stream()
                    .filter(p -> p.hasPermission("beaconlabs.punish.notify"))
                    .forEach(p -> p.sendMessage(broadcastComp));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            return server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            return List.of("10m","1h","1d","1mo","1y","permanent");
        }
        return List.of();
    }
}
