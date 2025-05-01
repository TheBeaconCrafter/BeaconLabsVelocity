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

/**
 * /unmute <player> - lifts an active mute
 */
public class UnmuteCommand implements SimpleCommand {
    private final ProxyServer server;
    private final BeaconLabsVelocity plugin;
    private final PunishmentService service;
    private final PunishmentConfig config;

    public UnmuteCommand(BeaconLabsVelocity plugin, ProxyServer server, PunishmentService service, PunishmentConfig config) {
        this.plugin = plugin;
        this.server = server;
        this.service = service;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        if (!src.hasPermission("beaconlabs.punish.unmute")) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("no-permission"))));
            return;
        }
        if (args.length < 1) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize("&cUsage: /unmute <player>")));
            return;
        }
        String targetName = args[0];
        Player target = server.getPlayer(targetName).orElse(null);
        if (target == null) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(config.getMessage("player-not-found").replace("{player}", targetName))));
            return;
        }
        boolean success = service.unmute(target.getUniqueId());
        String msg = success
                ? config.getMessage("unmute-success").replace("{player}", target.getUsername())
                : "&cNo active mute found for " + targetName;
        src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(msg)));
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
