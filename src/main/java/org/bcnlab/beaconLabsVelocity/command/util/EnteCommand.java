package org.bcnlab.beaconLabsVelocity.command.util;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * /ente [player] - shows a duck emoji title in the center of the screen.
 * With no target: shows to yourself. With target: shows to that player.
 */
public class EnteCommand implements SimpleCommand {

    private static final String PERMISSION = "beaconlabs.command.ente";
    private static final String PERMISSION_OTHERS = "beaconlabs.command.ente.others";
    private static final String DUCK = "🦆";

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
                    Component.text("You don't have permission to use this command.", net.kyori.adventure.text.format.NamedTextColor.RED)));
            return;
        }

        if (invocation.arguments().length == 0) {
            if (!(invocation.source() instanceof Player)) {
                invocation.source().sendMessage(plugin.getPrefix().append(
                        Component.text("Specify a player: /ente <player>", net.kyori.adventure.text.format.NamedTextColor.RED)));
                return;
            }
            showDuckTitle((Player) invocation.source());
            return;
        }

        if (!invocation.source().hasPermission(PERMISSION_OTHERS)) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("You don't have permission to show the duck to other players.", net.kyori.adventure.text.format.NamedTextColor.RED)));
            return;
        }

        String targetName = invocation.arguments()[0];
        Optional<Player> target = server.getPlayer(targetName);
        if (target.isEmpty()) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("Player not found: " + targetName, net.kyori.adventure.text.format.NamedTextColor.RED)));
            return;
        }

        showDuckTitle(target.get());
        invocation.source().sendMessage(plugin.getPrefix().append(
                Component.text("Duck shown to " + target.get().getUsername() + ".", net.kyori.adventure.text.format.NamedTextColor.GREEN)));
    }

    private void showDuckTitle(Player player) {
        Title title = Title.title(
                Component.text(DUCK),
                Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))
        );
        player.showTitle(title);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length == 1 && invocation.source().hasPermission(PERMISSION_OTHERS)) {
            String prefix = invocation.arguments()[0].toLowerCase();
            return server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .limit(50)
                    .toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
