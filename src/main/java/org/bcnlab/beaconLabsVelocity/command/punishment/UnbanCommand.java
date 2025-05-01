package org.bcnlab.beaconLabsVelocity.command.punishment;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity; // Import plugin
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig; // Import config
import org.bcnlab.beaconLabsVelocity.service.PunishmentService; // Import service
import org.slf4j.Logger; // Import Logger

import java.util.List;
import java.util.stream.Collectors;

/**
 * /unban <player> - lifts an active ban
 */
public class UnbanCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin; // Add plugin field
    private final ProxyServer server;
    private final PunishmentService service;
    private final PunishmentConfig config;
    private final Logger logger; // Add logger for warnings

    public UnbanCommand(BeaconLabsVelocity plugin, ProxyServer server, PunishmentService service, PunishmentConfig config, Logger logger) {
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

        if (!src.hasPermission("beaconlabs.punish.unban")) {
            String noPermMsg = config.getMessage("no-permission");
            if (noPermMsg == null) {
                noPermMsg = "&cYou do not have permission to use this command.";
                logger.warn("Missing 'no-permission' message in punishments.yml");
            }
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(noPermMsg)));
            return;
        }

        // Usage Message with Prefix
        if (args.length < 1) {
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize("&cUsage: /unban <player>")));
            return;
        }

        String targetName = args[0];
        // Note: Unbanning often needs to work for offline players.
        // We might need a way to look up UUIDs for offline players later.
        // For now, it only works for online players.
        Player target = server.getPlayer(targetName).orElse(null);

        if (target == null) {
            String notFoundMsg = config.getMessage("player-not-found");
            if (notFoundMsg == null) {
                notFoundMsg = "&cPlayer &f{player} &cnot found.";
                 logger.warn("Missing 'player-not-found' message in punishments.yml");
            }
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    notFoundMsg.replace("{player}", targetName)
            )));
            return;
        }

        boolean success = false;
        try {
             success = service.unban(target.getUniqueId()); // Use unban()
        } catch (Exception e) {
            logger.error("Error occurred while unbanning " + targetName, e);
            src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize("&cAn internal error occurred.")));
            return;
        }


        String msg;
        if (success) {
            String successMsg = config.getMessage("unban-success");
            if (successMsg == null) {
                successMsg = "&a{player} has been unbanned.";
                 logger.warn("Missing 'unban-success' message in punishments.yml");
            }
            msg = successMsg.replace("{player}", target.getUsername());
        } else {
            // Provide a more specific message if possible, otherwise generic failure
             msg = "&cNo active ban found for " + targetName + " or an error occurred.";
        }
        // Add prefix to final message
        src.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(msg)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            // Suggest online players
            return server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
