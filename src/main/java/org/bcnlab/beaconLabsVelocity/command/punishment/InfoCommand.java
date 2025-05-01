package org.bcnlab.beaconLabsVelocity.command.punishment;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService.PunishmentRecord;
import org.bcnlab.beaconLabsVelocity.util.DiscordWebhook;
import org.bcnlab.beaconLabsVelocity.util.DurationUtils;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /info <player> - show punishment status and info
 */
public class InfoCommand implements SimpleCommand {
    private final ProxyServer server;
    private final PunishmentService service;
    private final PunishmentConfig config;
    private final BeaconLabsVelocity plugin;

    public InfoCommand(ProxyServer server, PunishmentService service, BeaconLabsVelocity plugin, PunishmentConfig config) {
        this.server = server;
        this.service = service;
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        if (!src.hasPermission("beaconlabs.punish.info")) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("no-permission"))));
            return;
        }
        if (args.length < 1) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize("&cUsage: /info <player>")));
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
        boolean banned = service.isBanned(uuid);
        String header = config.getMessage("info-header").replace("{player}", targetName);
        src.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(header));
        List<PunishmentRecord> history = service.getHistory(uuid);
        for (PunishmentRecord record : history) {
            if (!record.active) continue;
            String line = config.getMessage("info-line")
                .replace("{type}", record.type)
                .replace("{date}", String.valueOf(record.startTime))
                .replace("{reason}", record.reason)
                .replace("{duration}", DurationUtils.formatDuration(record.duration));
            src.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }
        // webhook
        DiscordWebhook.send("Info viewed for " + targetName + " by " + src);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            return server.getAllPlayers().stream().map(Player::getUsername).collect(Collectors.toList());
        }
        return List.of();
    }
}
