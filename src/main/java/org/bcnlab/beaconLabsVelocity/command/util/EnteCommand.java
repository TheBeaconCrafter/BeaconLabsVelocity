package org.bcnlab.beaconLabsVelocity.command.util;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * /ente [player] - shows a duck emoji title in the center of the screen.
 * With no target: shows to yourself. With target: shows to that player.
 */
public class EnteCommand implements SimpleCommand {

    private static final String PERMISSION = "beaconlabs.command.ente";
    private static final String PERMISSION_OTHERS = "beaconlabs.command.ente.others";

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;

    public EnteCommand(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!invocation.source().hasPermission(PERMISSION)) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("You don't have permission to use this command.", NamedTextColor.RED)));
            return;
        }

        if (invocation.arguments().length == 0) {
            if (!(invocation.source() instanceof Player)) {
                invocation.source().sendMessage(plugin.getPrefix().append(
                        Component.text("Specify a player: /ente <player>", NamedTextColor.RED)));
                return;
            }
            plugin.showEnteTitleTo((Player) invocation.source());
            return;
        }

        if (!invocation.source().hasPermission(PERMISSION_OTHERS)) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("You don't have permission to show the duck to other players.", NamedTextColor.RED)));
            return;
        }

        String targetName = invocation.arguments()[0];
        Optional<Player> target = server.getPlayer(targetName);
        if (target.isPresent()) {
            plugin.showEnteTitleTo(target.get());
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("Duck shown to " + target.get().getUsername() + ".", NamedTextColor.GREEN)));
            return;
        }

        // Cross-proxy: target not on this proxy
        var crossProxy = plugin.getCrossProxyService();
        if (crossProxy != null && crossProxy.isEnabled()) {
            String exactName = crossProxy.getOnlinePlayerNames().stream()
                    .filter(n -> n != null && n.equalsIgnoreCase(targetName))
                    .findFirst()
                    .orElse(null);
            if (exactName != null) {
                crossProxy.publishEnte(exactName);
                invocation.source().sendMessage(plugin.getPrefix().append(
                        Component.text("Duck shown to " + exactName + " (on another proxy).", NamedTextColor.GREEN)));
                return;
            }
        }

        invocation.source().sendMessage(plugin.getPrefix().append(
                Component.text("Player not found: " + targetName, NamedTextColor.RED)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length != 1 || !invocation.source().hasPermission(PERMISSION_OTHERS)) {
            return List.of();
        }
        String prefix = invocation.arguments()[0].toLowerCase();
        Stream<String> names = server.getAllPlayers().stream().map(Player::getUsername);
        var crossProxy = plugin.getCrossProxyService();
        if (crossProxy != null && crossProxy.isEnabled()) {
            names = Stream.concat(names, crossProxy.getOnlinePlayerNames().stream());
        }
        return names.distinct()
                .filter(name -> name != null && name.toLowerCase().startsWith(prefix))
                .limit(50)
                .toList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
