package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.crossproxy.CrossProxyService;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * /proxy - shows which proxy the player (or console) is connected to.
 * /proxy &lt;proxyid&gt; - transfer yourself to that proxy (current player only). Permission: beaconlabs.command.proxy.transfer
 */
public class ProxyCommand implements SimpleCommand {

    private static final String PERMISSION_TRANSFER = "beaconlabs.command.proxy.transfer";

    private final BeaconLabsVelocity plugin;

    public ProxyCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length == 0) {
            String proxyId = plugin.getCrossProxyService() != null
                    ? plugin.getCrossProxyService().getProxyId()
                    : "—";
            invocation.source().sendMessage(plugin.getPrefix()
                    .append(Component.text("You are connected to proxy: ", NamedTextColor.GRAY))
                    .append(Component.text(proxyId, NamedTextColor.AQUA)));
            return;
        }

        // /proxy <proxyid> - transfer self (player only)
        if (!invocation.source().hasPermission(PERMISSION_TRANSFER)) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("You don't have permission to transfer to another proxy.", NamedTextColor.RED)));
            return;
        }
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("Only players can transfer to another proxy.", NamedTextColor.RED)));
            return;
        }

        Player player = (Player) invocation.source();
        String targetProxyId = args[0].trim();
        CrossProxyService cross = plugin.getCrossProxyService();

        if (cross == null || !cross.isEnabled()) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("Cross-proxy is disabled.", NamedTextColor.RED)));
            return;
        }
        if (targetProxyId.equalsIgnoreCase(cross.getProxyId())) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("You are already on this proxy.", NamedTextColor.YELLOW)));
            return;
        }

        if (!cross.getProxyIds().contains(targetProxyId)) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("Unknown proxy: " + targetProxyId, NamedTextColor.RED)));
            return;
        }

        String hostname = cross.getProxyHostname(targetProxyId);
        if (hostname == null || hostname.isEmpty()) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("Target proxy has no public-hostname set.", NamedTextColor.RED)));
            return;
        }

        String backendServer = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("lobby");
        cross.setPendingTransfer(targetProxyId, player.getUniqueId(), backendServer);

        Optional<String> err = cross.performTransferToHost(player, hostname);
        if (err.isEmpty()) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("Transferring you to proxy " + targetProxyId + "...", NamedTextColor.GREEN)));
        } else {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text(err.get(), NamedTextColor.RED)));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1 && invocation.source().hasPermission(PERMISSION_TRANSFER)
                && plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            String prefix = args[0].toLowerCase();
            return plugin.getCrossProxyService().getProxyIds().stream()
                    .filter(id -> id.toLowerCase().startsWith(prefix) && !id.equals(plugin.getCrossProxyService().getProxyId()))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
