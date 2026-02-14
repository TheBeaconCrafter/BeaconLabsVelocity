package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Send players to a server. Works across proxies when Redis cross-proxy is enabled.
 * Usage: /send * &lt;server&gt; | /send &lt;player&gt; &lt;server&gt;
 * /proxysend is an alias.
 */
public class SendCommand implements SimpleCommand {

    private static final String PERMISSION = "beaconlabs.command.send";

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;

    public SendCommand(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("You don't have permission to use this command.", NamedTextColor.RED)));
            return;
        }
        if (args.length < 2) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("Usage: /send * <server> or /send <player> <server>", NamedTextColor.RED)));
            return;
        }

        String target = args[0];
        String serverName = args[1];
        Optional<RegisteredServer> registeredServer = server.getServer(serverName);
        if (registeredServer.isEmpty()) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("Unknown server: " + serverName, NamedTextColor.RED)));
            return;
        }

        if ("*".equals(target)) {
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                plugin.getCrossProxyService().publishSendAll(serverName);
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("Sending all players on the network to " + serverName + "...", NamedTextColor.GREEN)));
            } else {
                int count = 0;
                for (Player p : server.getAllPlayers()) {
                    p.createConnectionRequest(registeredServer.get()).connectWithIndication();
                    count++;
                }
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("Sent " + count + " player(s) to " + serverName + ".", NamedTextColor.GREEN)));
            }
            return;
        }

        // Single player: by name (might be on this proxy or another)
        Optional<Player> localPlayer = server.getPlayer(target);
        if (localPlayer.isPresent()) {
            Player p = localPlayer.get();
            p.createConnectionRequest(registeredServer.get()).connectWithIndication();
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("Sent " + p.getUsername() + " to " + serverName + ".", NamedTextColor.GREEN)));
            return;
        }

        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            java.util.UUID uuid = plugin.getCrossProxyService().getPlayerUuidByName(target);
            if (uuid == null && plugin.getPunishmentService() != null) {
                uuid = plugin.getPunishmentService().getPlayerUUID(target);
                if (uuid != null && plugin.getCrossProxyService().getPlayerProxy(uuid) == null)
                    uuid = null; // not on any proxy
            }
            if (uuid != null) {
                plugin.getCrossProxyService().publishSendPlayer(uuid, serverName);
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("Sent " + target + " to " + serverName + " (cross-proxy).", NamedTextColor.GREEN)));
                return;
            }
        }

        source.sendMessage(plugin.getPrefix().append(
                Component.text("Player not found: " + target, NamedTextColor.RED)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        java.util.stream.Stream<String> playerNames = (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled())
                ? plugin.getCrossProxyService().getOnlinePlayerNames().stream()
                : server.getAllPlayers().stream().map(Player::getUsername);
        if (args.length == 0) {
            return Stream.concat(Stream.of("*"), playerNames).collect(Collectors.toList());
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Stream.concat(
                    Stream.of("*").filter(s -> s.startsWith(prefix)),
                    (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()
                            ? plugin.getCrossProxyService().getOnlinePlayerNames().stream()
                            : server.getAllPlayers().stream().map(Player::getUsername))
                            .filter(n -> n.toLowerCase().startsWith(prefix))
            ).collect(Collectors.toList());
        }
        if (args.length == 2) {
            return server.getAllServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
