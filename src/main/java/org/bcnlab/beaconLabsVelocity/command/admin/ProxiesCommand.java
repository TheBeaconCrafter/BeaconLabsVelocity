package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.crossproxy.CrossProxyService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /proxies - list all connected proxies.
 * /proxies send <player|server|proxy|*> <proxy> - transfer players to another proxy (1.20.5+ transfer packets).
 * /proxies info <proxy> - show proxy details (hostname, player count).
 * /proxies debug - print cross-proxy debug info (permission: beaconlabs.command.proxies.debug).
 */
public class ProxiesCommand implements SimpleCommand {

    private static final String PERMISSION = "beaconlabs.command.proxies";
    private static final String PERMISSION_DEBUG = "beaconlabs.command.proxies.debug";
    private static final String PERMISSION_SEND = "beaconlabs.command.proxies.send";

    private final BeaconLabsVelocity plugin;

    public ProxiesCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length > 0 && "debug".equalsIgnoreCase(args[0])) {
            if (!source.hasPermission(PERMISSION_DEBUG)) {
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("You don't have permission to use this command.", NamedTextColor.RED)));
                return;
            }
            if (plugin.getCrossProxyService() == null) {
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("Cross-proxy service is not available.", NamedTextColor.RED)));
                return;
            }
            List<String> lines = plugin.getCrossProxyService().getDebugInfo();
            for (String line : lines) {
                source.sendMessage(Component.text(line, NamedTextColor.GRAY));
                plugin.getLogger().info("[proxies debug] " + line);
            }
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("Debug output also written to console/log.", NamedTextColor.DARK_GRAY)));
            return;
        }

        if (args.length > 0 && "info".equalsIgnoreCase(args[0])) {
            if (!source.hasPermission(PERMISSION)) {
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("You don't have permission to use this command.", NamedTextColor.RED)));
                return;
            }
            if (args.length < 2) {
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("Usage: /proxies info <proxy>", NamedTextColor.RED)));
                return;
            }
            handleInfo(source, args[1]);
            return;
        }

        if (args.length > 0 && "send".equalsIgnoreCase(args[0])) {
            if (!source.hasPermission(PERMISSION_SEND)) {
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("You don't have permission to use this command.", NamedTextColor.RED)));
                return;
            }
            if (args.length < 3) {
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("Usage: /proxies send <player|server|proxy|*> <proxy>", NamedTextColor.RED)));
                return;
            }
            handleSend(source, args[1], args[2]);
            return;
        }

        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("You don't have permission to use this command.", NamedTextColor.RED)));
            return;
        }

        if (plugin.getCrossProxyService() == null || !plugin.getCrossProxyService().isEnabled()) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("Cross-proxy is disabled. Only this proxy is active.", NamedTextColor.YELLOW)));
            if (plugin.getCrossProxyService() != null) {
                source.sendMessage(plugin.getPrefix().append(
                        Component.text("This proxy ID: ", NamedTextColor.GRAY))
                        .append(Component.text(plugin.getCrossProxyService().getProxyId(), NamedTextColor.AQUA)));
            }
            return;
        }

        Set<String> proxyIds = plugin.getCrossProxyService().getProxyIds();
        if (proxyIds.isEmpty()) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("No proxies registered. (Only this proxy: " + plugin.getCrossProxyService().getProxyId() + ")", NamedTextColor.YELLOW)));
            return;
        }

        String list = proxyIds.stream().sorted().collect(Collectors.joining(", "));
        source.sendMessage(plugin.getPrefix().append(
                Component.text("Connected proxies (" + proxyIds.size() + "): ", NamedTextColor.GOLD))
                .append(Component.text(list, NamedTextColor.AQUA)));
    }

    private void handleInfo(CommandSource source, String proxyId) {
        CrossProxyService cross = plugin.getCrossProxyService();
        if (cross == null || !cross.isEnabled()) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("Cross-proxy is disabled.", NamedTextColor.RED)));
            return;
        }
        Set<String> ids = cross.getProxyIds();
        if (!ids.contains(proxyId)) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("Unknown proxy: " + proxyId, NamedTextColor.RED)));
            return;
        }
        String hostname = cross.getProxyHostname(proxyId);
        int playerCount = cross.getPlayerListForProxy(proxyId).size();
        source.sendMessage(plugin.getPrefix()
                .append(Component.text("Proxy ", NamedTextColor.GRAY))
                .append(Component.text(proxyId, NamedTextColor.AQUA))
                .append(Component.text(" | Host: ", NamedTextColor.GRAY))
                .append(Component.text(hostname != null && !hostname.isEmpty() ? hostname : "—", NamedTextColor.WHITE))
                .append(Component.text(" | Players: ", NamedTextColor.GRAY))
                .append(Component.text(playerCount, NamedTextColor.YELLOW)));
    }

    private void handleSend(CommandSource source, String selector, String targetProxyId) {
        CrossProxyService cross = plugin.getCrossProxyService();
        if (cross == null || !cross.isEnabled()) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("Cross-proxy is disabled.", NamedTextColor.RED)));
            return;
        }
        String hostname = cross.getProxyHostname(targetProxyId);
        if (hostname == null || hostname.isEmpty()) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text("Target proxy '" + targetProxyId + "' has no public-hostname set in config.", NamedTextColor.RED)));
            return;
        }

        List<TransferTarget> targets = resolveTransferTargets(selector, cross);
        // Skip players already on the target proxy (e.g. "send * na" from NA should only send EU players)
        targets = targets.stream()
                .filter(t -> !targetProxyId.equalsIgnoreCase(t.onProxyId))
                .collect(Collectors.toList());

        if (targets.isEmpty()) {
            source.sendMessage(plugin.getPrefix().append(
                    Component.text(targetProxyId.equalsIgnoreCase(cross.getProxyId())
                            ? "No players to transfer (all matched players are already on this proxy)."
                            : "No players matched '" + selector + "' or all are already on that proxy.", NamedTextColor.RED)));
            return;
        }

        int ok = 0;
        int versionTooOld = 0;
        int requestedOther = 0;
        ProxyServer server = plugin.getServer();

        for (TransferTarget t : targets) {
            cross.setPendingTransfer(targetProxyId, t.uuid, t.backendServer);
            if (t.onThisProxy) {
                Optional<Player> p = server.getPlayer(t.uuid);
                if (p.isPresent()) {
                    Optional<String> err = cross.performTransferToHost(p.get(), hostname);
                    if (err.isEmpty()) ok++;
                    else if (err.get().contains("version is too old")) versionTooOld++;
                    else source.sendMessage(plugin.getPrefix().append(Component.text(p.get().getUsername() + ": " + err.get(), NamedTextColor.RED)));
                }
            } else {
                cross.publishProxyTransferRequest(t.uuid, targetProxyId, t.backendServer);
                requestedOther++;
            }
        }

        Component result = plugin.getPrefix()
                .append(Component.text("Transfer to " + targetProxyId + ": ", NamedTextColor.GOLD))
                .append(Component.text(ok + " sent", NamedTextColor.GREEN));
        if (versionTooOld > 0) result = result.append(Component.text(", " + versionTooOld + " (game version too old, need 1.20.5+)", NamedTextColor.YELLOW));
        if (requestedOther > 0) result = result.append(Component.text(", " + requestedOther + " requested on other proxy", NamedTextColor.AQUA));
        result = result.append(Component.text(".", NamedTextColor.GOLD));
        source.sendMessage(result);
    }

    private static final class TransferTarget {
        final UUID uuid;
        final String backendServer;
        final boolean onThisProxy;
        /** Proxy ID the player is currently on (so we can skip if same as target). */
        final String onProxyId;

        TransferTarget(UUID uuid, String backendServer, boolean onThisProxy, String onProxyId) {
            this.uuid = uuid;
            this.backendServer = backendServer != null && !backendServer.isEmpty() ? backendServer : "lobby";
            this.onThisProxy = onThisProxy;
            this.onProxyId = onProxyId != null ? onProxyId : "";
        }
    }

    private List<TransferTarget> resolveTransferTargets(String selector, CrossProxyService cross) {
        List<TransferTarget> out = new ArrayList<>();
        ProxyServer server = plugin.getServer();
        String thisId = cross.getProxyId();
        String sel = selector.trim().toLowerCase();

        if ("*".equals(sel)) {
            for (Player p : server.getAllPlayers()) {
                String backend = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("lobby");
                out.add(new TransferTarget(p.getUniqueId(), backend, true, thisId));
            }
            for (String pid : cross.getProxyIds()) {
                if (pid.equals(thisId)) continue;
                for (var e : cross.getPlayerListForProxy(pid)) {
                    String username = e.getKey();
                    String backend = e.getValue();
                    UUID uuid = cross.getPlayerUuidByName(username);
                    if (uuid != null) out.add(new TransferTarget(uuid, backend, false, pid));
                }
            }
            return out;
        }

        if ("server".equals(sel) || "proxy".equals(sel)) {
            return out; // need server:name or proxy:id
        }

        if (sel.startsWith("server:")) {
            String serverName = sel.substring(7).trim();
            for (Player p : server.getAllPlayers()) {
                String backend = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
                if (serverName.equalsIgnoreCase(backend)) out.add(new TransferTarget(p.getUniqueId(), backend, true, thisId));
            }
            for (String pid : cross.getProxyIds()) {
                if (pid.equals(thisId)) continue;
                for (var e : cross.getPlayerListForProxy(pid)) {
                    if (serverName.equalsIgnoreCase(e.getValue())) {
                        UUID uuid = cross.getPlayerUuidByName(e.getKey());
                        if (uuid != null) out.add(new TransferTarget(uuid, e.getValue(), false, pid));
                    }
                }
            }
            return out;
        }

        if (sel.startsWith("proxy:")) {
            String pid = sel.substring(6).trim();
            if (pid.equals(thisId)) {
                for (Player p : server.getAllPlayers()) {
                    String backend = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("lobby");
                    out.add(new TransferTarget(p.getUniqueId(), backend, true, thisId));
                }
            } else {
                for (var e : cross.getPlayerListForProxy(pid)) {
                    UUID uuid = cross.getPlayerUuidByName(e.getKey());
                    if (uuid != null) out.add(new TransferTarget(uuid, e.getValue(), false, pid));
                }
            }
            return out;
        }

        // Single player by name
        Optional<Player> local = server.getPlayer(selector);
        if (local.isPresent()) {
            Player p = local.get();
            String backend = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("lobby");
            out.add(new TransferTarget(p.getUniqueId(), backend, true, thisId));
            return out;
        }
        UUID uuid = cross.getPlayerUuidByName(selector);
        if (uuid != null) {
            String onProxy = cross.getPlayerProxy(uuid);
            String backend = "lobby";
            if (thisId.equals(onProxy)) {
                Optional<Player> p = server.getPlayer(uuid);
                if (p.isPresent()) backend = p.get().getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("lobby");
            } else if (onProxy != null) {
                for (var e : cross.getPlayerListForProxy(onProxy)) {
                    if (e.getKey().equalsIgnoreCase(selector)) { backend = e.getValue(); break; }
                }
            }
            out.add(new TransferTarget(uuid, backend, thisId.equals(onProxy), onProxy != null ? onProxy : thisId));
        }
        return out;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            if (invocation.source().hasPermission(PERMISSION_DEBUG) && "debug".toLowerCase().startsWith(args[0].toLowerCase())) out.add("debug");
            if (invocation.source().hasPermission(PERMISSION) && "info".toLowerCase().startsWith(args[0].toLowerCase())) out.add("info");
            if (invocation.source().hasPermission(PERMISSION_SEND) && "send".toLowerCase().startsWith(args[0].toLowerCase())) out.add("send");
            return out;
        }
        if (args.length == 2 && "info".equalsIgnoreCase(args[0]) && plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            String prefix = args[1].toLowerCase();
            return plugin.getCrossProxyService().getProxyIds().stream()
                    .filter(id -> id.toLowerCase().startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && "send".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase();
            List<String> out = new ArrayList<>();
            if ("*".toLowerCase().startsWith(prefix)) out.add("*");
            if ("player".toLowerCase().startsWith(prefix)) out.add("player");
            if ("server:".toLowerCase().startsWith(prefix)) out.add("server:");
            if ("proxy:".toLowerCase().startsWith(prefix)) out.add("proxy:");
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                for (String name : plugin.getCrossProxyService().getOnlinePlayerNames()) {
                    if (name.toLowerCase().startsWith(prefix)) out.add(name);
                }
            }
            return out.stream().limit(50).collect(Collectors.toList());
        }
        if (args.length == 3 && "send".equalsIgnoreCase(args[0]) && plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            String prefix = args[2].toLowerCase();
            return plugin.getCrossProxyService().getProxyIds().stream()
                    .filter(id -> id.toLowerCase().startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
