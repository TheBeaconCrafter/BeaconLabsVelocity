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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /kick <player> [reason]
 */
public class KickCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final PunishmentConfig config;
    private final PunishmentService service;

    public KickCommand(BeaconLabsVelocity plugin, ProxyServer server, PunishmentConfig config, PunishmentService service) {
        this.plugin = plugin;
        this.server = server;
        this.config = config;
        this.service = service;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        if (!src.hasPermission("beaconlabs.punish.kick")) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("no-permission"))));
            return;
        }
        if (args.length < 1) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize("&cUsage: /kick <player> [reason]")));
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
        String reason = config.getMessage("default-reason");
        if (args.length > 1) {
            reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        }
        // Record kick punishment (duration 0)
        service.punish(target.getUniqueId(), target.getUsername(),
                (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                (src instanceof Player) ? ((Player) src).getUsername() : "Console",
                "kick", 0L, reason);
        // Notify executor
        String msg = config.getMessage("kick-success")
                .replace("{player}", target.getUsername())
                .replace("{reason}", reason);
        Component comp = LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
        src.sendMessage(comp);
        // Kick target
        target.disconnect(comp);
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
