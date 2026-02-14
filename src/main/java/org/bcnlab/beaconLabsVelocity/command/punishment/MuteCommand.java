package org.bcnlab.beaconLabsVelocity.command.punishment;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.bcnlab.beaconLabsVelocity.util.DurationUtils;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class MuteCommand implements SimpleCommand {
    private final ProxyServer server;
    private final BeaconLabsVelocity plugin;
    private final PunishmentConfig config;
    private final PunishmentService service;

    public MuteCommand(BeaconLabsVelocity plugin, ProxyServer server, PunishmentConfig config, PunishmentService service) {
        this.plugin = plugin;
        this.server = server;
        this.config = config;
        this.service = service;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        Component noPerm = plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("no-permission")));
        if (!src.hasPermission("beaconlabs.punish.mute")) {
            src.sendMessage(noPerm);
            return;
        }
        if (args.length < 2) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize("&cUsage: /mute <player> <duration> [reason]")));
            return;
        }
        String targetName = args[0];
        if (src instanceof Player && ((Player) src).getUsername().equalsIgnoreCase(targetName)) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("self-punish"))));
            return;
        }
        long duration = DurationUtils.parseDuration(args[1]);
        String reason = config.getMessage("default-reason");
        if (args.length > 2) {
            reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        }

        Player target = server.getPlayer(targetName).orElse(null);
        if (target == null) {
            // Player not on this proxy: try to resolve UUID from DB (cross-proxy mute)
            UUID offlineUuid = service.getPlayerUUID(targetName);
            String canonicalName = targetName;
            if (offlineUuid == null && plugin.getPlayerStatsService() != null) {
                PlayerStatsService.PlayerData data = plugin.getPlayerStatsService().getPlayerDataByName(targetName);
                if (data != null) {
                    offlineUuid = data.getPlayerId();
                    canonicalName = data.getPlayerName();
                }
            }
            if (offlineUuid != null) {
                service.punish(offlineUuid, canonicalName,
                        (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                        src instanceof Player ? ((Player) src).getUsername() : "Console",
                        "mute", duration, reason);
                String msg = config.getMessage("mute-success")
                        .replace("{player}", canonicalName)
                        .replace("{duration}", DurationUtils.formatDuration(duration))
                        .replace("{reason}", reason);
                src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(msg + (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled() ? " (on another proxy)" : ""))));
                if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                    plugin.getCrossProxyService().publishMuteApplied(offlineUuid, reason, DurationUtils.formatDuration(duration));
                }
            } else {
                src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("player-not-found").replace("{player}", targetName))));
            }
            return;
        }

        service.punish(target.getUniqueId(), target.getUsername(),
                (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                src instanceof Player ? ((Player) src).getUsername() : "Console",
                "mute", duration, reason);
        String msg = config.getMessage("mute-success")
                .replace("{player}", target.getUsername())
                .replace("{duration}", DurationUtils.formatDuration(duration))
                .replace("{reason}", reason);
        src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(msg)));
        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            plugin.getCrossProxyService().publishMuteApplied(target.getUniqueId(), reason, DurationUtils.formatDuration(duration));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                return plugin.getCrossProxyService().getOnlinePlayerNames().stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
            }
            return server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            return List.of("10m","1h","1d","1mo","1y","permanent");
        }
        return List.of();
    }
}
