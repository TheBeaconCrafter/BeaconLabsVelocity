package org.bcnlab.beaconLabsVelocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.cacheddata.CachedMetaData;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.crossproxy.CrossProxyService;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command to display online staff members. Works across proxies when Redis cross-proxy is enabled.
 */
public class StaffCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;
    private static final String PERMISSION = "beaconlabs.visual.staff";

    public StaffCommand(BeaconLabsVelocity plugin) {
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

        Component staffList = Component.text("Staff Online:", NamedTextColor.GREEN);
        boolean hasStaff = false;

        CrossProxyService cross = plugin.getCrossProxyService();
        if (cross != null && cross.isEnabled()) {
            Set<String> proxyIds = cross.getProxyIds();
            boolean multiProxy = proxyIds.size() > 1;
            for (String proxyId : proxyIds.stream().sorted().toList()) {
                List<Map.Entry<String, String>> staff = cross.getStaffListForProxy(proxyId);
                for (Map.Entry<String, String> e : staff) {
                    String username = e.getKey();
                    String serverName = e.getValue();
                    Component displayName = getDisplayNameForStaff(username, proxyId, cross);
                    Component hoverText = Component.text("Server: " + serverName + (multiProxy ? " | Proxy: " + proxyId : ""), NamedTextColor.GOLD);
                    Component playerComponent = displayName
                            .hoverEvent(HoverEvent.showText(hoverText))
                            .clickEvent(ClickEvent.runCommand("/server " + serverName));
                    staffList = staffList.append(Component.newline())
                            .append(Component.text(" - ", NamedTextColor.GRAY))
                            .append(playerComponent)
                            .append(Component.text(" [", NamedTextColor.DARK_GRAY))
                            .append(Component.text(serverName, NamedTextColor.GREEN))
                            .append(Component.text("]", NamedTextColor.DARK_GRAY));
                    if (multiProxy) {
                        staffList = staffList.append(Component.text(" (", NamedTextColor.DARK_GRAY))
                                .append(Component.text(proxyId, NamedTextColor.AQUA))
                                .append(Component.text(")", NamedTextColor.DARK_GRAY));
                    }
                    hasStaff = true;
                }
            }
        } else {
            Collection<Player> onlinePlayers = plugin.getServer().getAllPlayers();
            for (Player onlinePlayer : onlinePlayers) {
                if (onlinePlayer.hasPermission(PERMISSION)) {
                    Component displayName = getDisplayName(onlinePlayer);
                    String serverName = onlinePlayer.getCurrentServer()
                            .map(sc -> sc.getServerInfo().getName())
                            .orElse("Unknown");
                    Component hoverText = Component.text("Server: " + serverName, NamedTextColor.GOLD);
                    Component playerComponent = displayName
                            .hoverEvent(HoverEvent.showText(hoverText))
                            .clickEvent(ClickEvent.runCommand("/server " + serverName));
                    staffList = staffList.append(Component.newline())
                            .append(Component.text(" - ", NamedTextColor.GRAY))
                            .append(playerComponent)
                            .append(Component.text(" [", NamedTextColor.DARK_GRAY))
                            .append(Component.text(serverName, NamedTextColor.GREEN))
                            .append(Component.text("]", NamedTextColor.DARK_GRAY));
                    hasStaff = true;
                }
            }
        }

        if (!hasStaff) {
            staffList = staffList.append(Component.newline())
                    .append(Component.text(" No staff members are currently online.", NamedTextColor.YELLOW));
        }

        source.sendMessage(plugin.getPrefix().append(staffList));
    }

    /** Display name for a staff member on another proxy (prefix from Redis + username). */
    private Component getDisplayNameForStaff(String username, String proxyId, CrossProxyService cross) {
        String prefix = cross.getPlayerPrefix(username);
        if (prefix != null && !prefix.isEmpty()) {
            try {
                return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix).append(Component.text(username));
            } catch (Exception ignored) { }
        }
        return Component.text(username, NamedTextColor.AQUA);
    }

    /** Get the display name of a local player (LuckPerms prefix + role color). */
    private Component getDisplayName(Player player) {
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                CachedMetaData metaData = user.getCachedData().getMetaData();
                String prefix = metaData.getPrefix();
                if (prefix != null && !prefix.isEmpty()) {
                    Component prefixComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(prefix);
                    return prefixComponent.append(Component.text(player.getUsername()));
                }
            }
            if (player.hasPermission("beaconlabs.visual.admin")) {
                return Component.text(player.getUsername(), NamedTextColor.RED);
            } else if (player.hasPermission("beaconlabs.visual.mod")) {
                return Component.text(player.getUsername(), NamedTextColor.GOLD);
            } else {
                return Component.text(player.getUsername(), NamedTextColor.AQUA);
            }
        } catch (Exception e) {
            plugin.getLogger().warn("Failed to get LuckPerms prefix for player {}: {}", player.getUsername(), e.getMessage());
            return Component.text(player.getUsername(), NamedTextColor.AQUA);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
