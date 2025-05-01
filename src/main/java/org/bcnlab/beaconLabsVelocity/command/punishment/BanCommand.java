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
        }
        String targetName = args[0];
        if (src instanceof Player && ((Player) src).getUsername().equalsIgnoreCase(targetName)) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("self-punish"))));
            return;
        }
        Player target = server.getPlayer(targetName).orElse(null);
        if (target == null) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    config.getMessage("player-not-found").replace("{player}", targetName)
            )));
            return;
        }
        long duration = DurationUtils.parseDuration(args[1]);
        String reason = config.getMessage("default-reason");
        if (args.length > 2) {
            reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        }
        service.punish(
                target.getUniqueId(), target.getUsername(),
                (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                src instanceof Player ? ((Player) src).getUsername() : "Console",
                "ban", duration, reason
        );
        // disconnect the player
        Component kickMsg = LegacyComponentSerializer.legacyAmpersand().deserialize(
                config.getMessage("ban-success")
                        .replace("{player}", target.getUsername())
                        .replace("{duration}", DurationUtils.formatDuration(duration))
                        .replace("{reason}", reason)
        );
        target.disconnect(kickMsg);
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
