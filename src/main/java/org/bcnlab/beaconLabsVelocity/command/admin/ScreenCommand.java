package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.AntiBotService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ScreenCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;

    public ScreenCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!src.hasPermission("beaconlabs.antiabuse")) {
            src.sendMessage(plugin.getPrefix().append(Component.text("You do not have permission.", NamedTextColor.RED)));
            return;
        }

        if (args.length < 2) {
            src.sendMessage(plugin.getPrefix().append(Component.text("Usage: /screen <clean|force> <player/ip>", NamedTextColor.RED)));
            return;
        }

        String action = args[0].toLowerCase();
        String target = args[1];

        if (action.equals("clean")) {
            if (target.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
                int removed = plugin.getAntiBotService().removeScreeningPassByIp(target);
                src.sendMessage(plugin.getPrefix().append(Component.text("Cleaned screening passes for IP " + target + " (" + removed + " removed)", NamedTextColor.GREEN)));
            } else {
                UUID targetUuid = plugin.getPunishmentService().getPlayerUUID(target);
                if (targetUuid == null && plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                    targetUuid = plugin.getCrossProxyService().getPlayerUuidByName(target);
                }
                if (targetUuid == null && plugin.getPlayerStatsService() != null) {
                    var pd = plugin.getPlayerStatsService().getPlayerDataByName(target);
                    if (pd != null) targetUuid = pd.getPlayerId();
                }

                if (targetUuid == null) {
                    src.sendMessage(plugin.getPrefix().append(Component.text("Player not found: " + target, NamedTextColor.RED)));
                    return;
                }

                int removed = plugin.getAntiBotService().removeScreeningPassByUuid(targetUuid);
                src.sendMessage(plugin.getPrefix().append(Component.text("Cleaned screening passes for player " + target + " (" + removed + " removed)", NamedTextColor.GREEN)));
            }
        } else if (action.equals("force")) {
            UUID targetUuid = plugin.getPunishmentService().getPlayerUUID(target);
            if (targetUuid == null && plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                targetUuid = plugin.getCrossProxyService().getPlayerUuidByName(target);
            }
            if (targetUuid == null && plugin.getPlayerStatsService() != null) {
                var pd = plugin.getPlayerStatsService().getPlayerDataByName(target);
                if (pd != null) targetUuid = pd.getPlayerId();
            }

            if (targetUuid == null) {
                src.sendMessage(plugin.getPrefix().append(Component.text("Player not found: " + target, NamedTextColor.RED)));
                return;
            }

            plugin.getAntiBotService().setForceScreen(targetUuid);
            src.sendMessage(plugin.getPrefix().append(Component.text("Player " + target + " will be force screened on next login.", NamedTextColor.GREEN)));
        } else {
            src.sendMessage(plugin.getPrefix().append(Component.text("Unknown action. Use clean or force.", NamedTextColor.RED)));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            return List.of("clean", "force").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            List<String> names = new ArrayList<>();
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                names.addAll(plugin.getCrossProxyService().getOnlinePlayerNames());
            } else {
                names.addAll(plugin.getServer().getAllPlayers().stream().map(Player::getUsername).collect(Collectors.toList()));
            }
            return names.stream()
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.antiabuse");
    }
}
