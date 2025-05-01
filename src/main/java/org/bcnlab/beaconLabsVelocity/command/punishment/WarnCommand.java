package org.bcnlab.beaconLabsVelocity.command.punishment;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig.PredefinedReason;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.bcnlab.beaconLabsVelocity.util.DurationUtils;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /warn <player> <reason>
 */
public class WarnCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final PunishmentConfig config;
    private final PunishmentService service;

    public WarnCommand(BeaconLabsVelocity plugin, ProxyServer server, PunishmentConfig config, PunishmentService service) {
        this.plugin = plugin;
        this.server = server;
        this.config = config;
        this.service = service;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        if (!src.hasPermission("beaconlabs.punish.warn")) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("no-permission"))));
            return;
        }
        if (args.length < 2) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize("&cUsage: /warn <player> <reasonKey>")));
            return;
        }
        String targetName = args[0];
        if (src instanceof Player && ((Player) src).getUsername().equalsIgnoreCase(targetName)) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("self-punish"))));
            return;
        }
        Player target = server.getPlayer(targetName).orElse(null);
        if (target == null) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(config.getMessage("player-not-found").replace("{player}", targetName))));
            return;
        }
        String reasonKey = args[1].toLowerCase();
        PredefinedReason pr = config.getPredefinedReason(reasonKey);
        if (pr == null) {
            // unknown reason key
            String keys = String.join(", ", config.getAllPredefinedReasons().keySet());
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize("&cUnknown reason key. Available: " + keys)));
            return;
        }
        long duration = DurationUtils.parseDuration(pr.getDuration());
        String reason = pr.getReason();
        service.punish(
                target.getUniqueId(), target.getUsername(),
                (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                (src instanceof Player) ? ((Player) src).getUsername() : "Console",
                pr.getType(), duration, reason
        );
        String msg = config.getMessage("warn-success")
                .replace("{player}", target.getUsername())
                .replace("{reason}", reason);
        src.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            return server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            return config.getAllPredefinedReasons().keySet().stream().collect(Collectors.toList());
        }
        return List.of();
    }
}
