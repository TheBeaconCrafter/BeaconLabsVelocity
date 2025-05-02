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

/**
 * /unmute <player> - lifts an active mute
 */
public class UnmuteCommand implements SimpleCommand {
    private final ProxyServer server;
    private final BeaconLabsVelocity plugin;
    private final PunishmentService service;
    private final PunishmentConfig config;    private final org.slf4j.Logger logger;
    
    public UnmuteCommand(BeaconLabsVelocity plugin, ProxyServer server, PunishmentService service, PunishmentConfig config, org.slf4j.Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.service = service;
        this.config = config;
        this.logger = logger;
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
        }        String targetName = args[0];
        // Try to get UUID for both online and offline players
        UUID targetUUID = service.getPlayerUUID(targetName);
        if (targetUUID == null) {
            String notFoundMsg = config.getMessage("player-not-found");
            if (notFoundMsg == null) {
                notFoundMsg = "&cPlayer &f{player} &cnot found.";
                logger.warn("Missing 'player-not-found' message in punishments.yml");
            }
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(notFoundMsg.replace("{player}", targetName))));
            return;
        }
        boolean success = service.unmute(targetUUID);
        String msg = success
                ? config.getMessage("unmute-success").replace("{player}", targetName)
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
