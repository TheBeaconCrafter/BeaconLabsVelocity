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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
        
        // Use PunishmentService to get UUID for online or offline players
        UUID targetUUID = service.getPlayerUUID(targetName);
        
        if (targetUUID == null) {
            String notFoundMsg = config.getMessage("player-not-found");
            if (notFoundMsg == null) {
                notFoundMsg = "&cPlayer &f{player} &cnot found.";
                // Consider adding logger warning here if needed
            }
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(notFoundMsg.replace("{player}", targetName))));
            return;
        }
        
        // Fetch history using the found UUID
        List<PunishmentRecord> history = service.getHistory(targetUUID);

        // Use the provided targetName for the header, as we might not have the exact casing from the DB
        String header = config.getMessage("history-header").replace("{player}", targetName);
        src.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(header));

        if (history.isEmpty()) {
            src.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("history-empty")));
            return;
        }        for (PunishmentRecord record : history) {            String status = record.active ? "&aActive" : "&cInactive";
            String durationStr = DurationUtils.formatDuration(record.duration);
              // Format start date using the utility method
            String dateStr = dateFormat.format(PunishmentService.parseTimestamp(record.startTime));
            
            // Format expiry date using the utility method
            String expiryStr;
            if (record.endTime <= 0) {
                expiryStr = "Never";
            } else {
                expiryStr = dateFormat.format(PunishmentService.parseTimestamp(record.endTime));
            }
            String line = config.getMessage("history-line")
                    .replace("{status}", status)
                    .replace("{type}", record.type)
                    .replace("{date}", dateStr)
                    .replace("{reason}", record.reason)
                    .replace("{duration}", durationStr)
                    .replace("{expiry}", expiryStr)
                    .replace("{issuer}", record.issuerName != null ? record.issuerName : "Console");
            src.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
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
        }
        return List.of();
    }
}
