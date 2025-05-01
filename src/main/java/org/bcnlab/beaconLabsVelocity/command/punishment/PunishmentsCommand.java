package org.bcnlab.beaconLabsVelocity.command.punishment;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService.PunishmentRecord;
import org.bcnlab.beaconLabsVelocity.util.DurationUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * /punishments <player> - show punishment history
 */
public class PunishmentsCommand implements SimpleCommand {
    private final ProxyServer server;
    private final PunishmentService service;
    private final PunishmentConfig config;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final BeaconLabsVelocity plugin;

    public PunishmentsCommand(BeaconLabsVelocity plugin, ProxyServer server, PunishmentService service, PunishmentConfig config) {
        this.plugin = plugin;
        this.server = server;
        this.service = service;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        if (!src.hasPermission("beaconlabs.punish.history")) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("no-permission"))));
            return;
        }
        if (args.length < 1) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize("&cUsage: /punishments <player>")));
            return;
        }
        String targetName = args[0];
        Player target = server.getPlayer(targetName).orElse(null);
        if (target == null) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(config.getMessage("player-not-found").replace("{player}", targetName))));
            return;
        }
        UUID uuid = target.getUniqueId();
        List<PunishmentRecord> history = service.getHistory(uuid);

        String header = config.getMessage("history-header").replace("{player}", targetName);
        src.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(header));

        if (history.isEmpty()) {
            src.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("history-empty")));
            return;
        }

        for (PunishmentRecord record : history) {
            String status = record.active ? "&aActive" : "&cInactive";
            String durationStr = DurationUtils.formatDuration(record.duration);
            String dateStr = dateFormat.format(new Date(record.startTime));
            String line = config.getMessage("history-line")
                    .replace("{status}", status)
                    .replace("{type}", record.type)
                    .replace("{date}", dateStr)
                    .replace("{reason}", record.reason)
                    .replace("{duration}", durationStr)
                    .replace("{issuer}", record.issuerName != null ? record.issuerName : "Console");
            src.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            return server.getAllPlayers().stream().map(Player::getUsername).toList();
        }
        return List.of();
    }
}
