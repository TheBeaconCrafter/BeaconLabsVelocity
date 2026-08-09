package org.bcnlab.beaconLabsVelocity.command.punishment;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig.PredefinedReason;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.bcnlab.beaconLabsVelocity.util.DurationUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bcnlab.beaconLabsVelocity.util.ColorParser;

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
            src.sendMessage(plugin.getPrefix(src).append(ColorParser.parse(config.getMessage("no-permission"))));
            return;
        }
        if (args.length < 2) {
            src.sendMessage(plugin.getPrefix(src).append(ColorParser.parse("&7Usage: /warn <player> <reasonKey>")));
            return;
        }        String targetName = args[0];
        if (src instanceof Player && ((Player) src).getUsername().equalsIgnoreCase(targetName)) {
            src.sendMessage(plugin.getPrefix(src).append(ColorParser.parse(config.getMessage("self-punish"))));
            return;
        }
        
        // First validate the reason key
        String reasonKey = args[1].toLowerCase();
        PredefinedReason pr = config.getPredefinedReason(reasonKey);
        if (pr == null) {
            // unknown reason key
            String keys = String.join(", ", config.getAllPredefinedReasons().keySet());
            src.sendMessage(plugin.getPrefix(src).append(ColorParser.parse("&cUnknown reason key. Available: " + keys)));
            return;
        }
        
        // Get the duration and reason
        long duration = DurationUtils.parseDuration(pr.getDuration());
        String reason = pr.getReason();
        
        // Try to find the player
        Optional<Player> optionalTarget = server.getPlayer(targetName);
        
        if (optionalTarget.isPresent()) {
            // Player is online
            Player target = optionalTarget.get();
            
            // Apply the warning
            service.punish(
                    target.getUniqueId(), target.getUsername(),
                    (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                    (src instanceof Player) ? ((Player) src).getUsername() : "Console",
                    pr.getType(), duration, reason
            );
            
            // Send success message
            String msg = config.getMessage("warn-success")
                    .replace("{player}", target.getUsername())
                    .replace("{reason}", reason);
            src.sendMessage(ColorParser.parse(msg));
            
            // Notify the player
            target.sendMessage(plugin.getPrefix(target).append(
                ColorParser.parse(
                    "&cYou have been warned: " + reason
                )
            ));
            
        } else {
            // Player is offline - try to look up UUID in database
            UUID offlineUuid = service.getPlayerUUID(targetName);
            
            if (offlineUuid != null) {
                // Found player data - apply warning to offline player
                service.punish(
                        offlineUuid, targetName,
                        (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                        (src instanceof Player) ? ((Player) src).getUsername() : "Console",
                        pr.getType(), duration, reason
                );
                
                // Notify executor with prefix
                String msg = config.getMessage("warn-success")
                        .replace("{player}", targetName)
                        .replace("{reason}", reason) + " (Offline player)";
                src.sendMessage(ColorParser.parse(msg));
                
            } else {
                // Create a new offline warning entry
                UUID generatedUuid = UUID.nameUUIDFromBytes(("offlineplayer:" + targetName.toLowerCase()).getBytes());
                
                service.punish(
                        generatedUuid, targetName,
                        (src instanceof Player) ? ((Player) src).getUniqueId() : null,
                        (src instanceof Player) ? ((Player) src).getUsername() : "Console",
                        pr.getType(), duration, reason
                );
                
                // Notify executor with prefix
                String msg = config.getMessage("warn-success")
                        .replace("{player}", targetName)
                        .replace("{reason}", reason) + " (New offline player)";
                src.sendMessage(ColorParser.parse(msg));
            }
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
            return config.getAllPredefinedReasons().keySet().stream().collect(Collectors.toList());
        }
        return List.of();
    }
}
