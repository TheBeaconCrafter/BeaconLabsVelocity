package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * /proxies - list all connected proxies (their IDs).
 */
public class ProxiesCommand implements SimpleCommand {

    private static final String PERMISSION = "beaconlabs.command.proxies";

    private final BeaconLabsVelocity plugin;

    public ProxiesCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

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
}
