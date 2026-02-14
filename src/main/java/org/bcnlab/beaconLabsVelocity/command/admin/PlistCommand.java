package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /plist - list all players and their servers across all proxies (like glist, network-wide).
 * /plist &lt;server&gt; - list players on that server across all proxies.
 */
public class PlistCommand implements SimpleCommand {

    private static final String PERMISSION = "beaconlabs.command.plist";

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;

    public PlistCommand(BeaconLabsVelocity plugin, ProxyServer server) {
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

        if (plugin.getCrossProxyService() == null || !plugin.getCrossProxyService().isEnabled()) {
            // Fallback to local only (like glist)
            sendLocalPlist(source, args.length > 0 ? args[0] : null);
            return;
        }

        Set<String> proxyIds = plugin.getCrossProxyService().getProxyIds();
        Map<String, List<Map.Entry<String, String>>> serverToPlayers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int total = 0;

        for (String pid : proxyIds) {
            List<Map.Entry<String, String>> list = plugin.getCrossProxyService().getPlayerListForProxy(pid);
            for (Map.Entry<String, String> e : list) {
                String playerName = e.getKey();
                String serverName = e.getValue() != null && !e.getValue().isEmpty() ? e.getValue() : "?";
                serverToPlayers.computeIfAbsent(serverName, k -> new ArrayList<>()).add(e);
                total++;
            }
        }

        String filterServer = args.length > 0 ? args[0] : null;
        if (filterServer != null) {
            List<Map.Entry<String, String>> onServer = serverToPlayers.get(filterServer);
            if (onServer == null || onServer.isEmpty()) {
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("No players on server '" + filterServer + "' across the network.", NamedTextColor.YELLOW)));
                return;
            }
            String names = onServer.stream().map(Map.Entry::getKey).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("[" + filterServer + "] ", NamedTextColor.GREEN))
                    .append(Component.text("(" + onServer.size() + "): ", NamedTextColor.GRAY))
                    .append(Component.text(names, NamedTextColor.WHITE)));
            return;
        }

        source.sendMessage(plugin.getPrefix().append(
                Component.text("Players across all proxies (" + total + "):", NamedTextColor.GOLD)));
        for (Map.Entry<String, List<Map.Entry<String, String>>> entry : serverToPlayers.entrySet()) {
            String serverName = entry.getKey();
            List<Map.Entry<String, String>> players = entry.getValue();
            String names = players.stream().map(Map.Entry::getKey).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
            source.sendMessage(Component.text("  [", NamedTextColor.DARK_GRAY)
                    .append(Component.text(serverName, NamedTextColor.GREEN))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("(" + players.size() + "): ", NamedTextColor.GRAY))
                    .append(Component.text(names, NamedTextColor.WHITE)));
        }
    }

    private void sendLocalPlist(CommandSource source, String filterServer) {
        Map<String, List<String>> serverToPlayers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        server.getAllPlayers().forEach(p -> {
            String s = p.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("?");
            serverToPlayers.computeIfAbsent(s, k -> new ArrayList<>()).add(p.getUsername());
        });
        if (filterServer != null) {
            List<String> onServer = serverToPlayers.get(filterServer);
            if (onServer == null || onServer.isEmpty()) {
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("No players on server '" + filterServer + "'.", NamedTextColor.YELLOW)));
                return;
            }
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("[" + filterServer + "] (" + onServer.size() + "): ", NamedTextColor.GREEN))
                    .append(Component.text(String.join(", ", onServer), NamedTextColor.WHITE)));
            return;
        }
        int total = serverToPlayers.values().stream().mapToInt(List::size).sum();
        source.sendMessage(plugin.getPrefix().append(
                Component.text("Players on this proxy (" + total + "):", NamedTextColor.GOLD)));
        serverToPlayers.forEach((s, names) -> source.sendMessage(
                Component.text("  [", NamedTextColor.DARK_GRAY)
                        .append(Component.text(s, NamedTextColor.GREEN))
                        .append(Component.text("] (" + names.size() + "): ", NamedTextColor.GRAY))
                        .append(Component.text(String.join(", ", names), NamedTextColor.WHITE))));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length != 1) return List.of();
        String prefix = invocation.arguments()[0].toLowerCase();
        Set<String> servers = new TreeSet<>();
        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            for (String pid : plugin.getCrossProxyService().getProxyIds()) {
                plugin.getCrossProxyService().getPlayerListForProxy(pid).stream()
                        .map(e -> e.getValue())
                        .filter(s -> s != null && !s.equals("?"))
                        .forEach(servers::add);
            }
        } else {
            server.getAllServers().stream().map(s -> s.getServerInfo().getName()).forEach(servers::add);
        }
        return servers.stream().filter(s -> s.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
    }
}
