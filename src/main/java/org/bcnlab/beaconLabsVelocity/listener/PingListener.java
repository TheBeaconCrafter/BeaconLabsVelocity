package org.bcnlab.beaconLabsVelocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.spongepowered.configurate.ConfigurationNode;

import java.net.InetSocketAddress;
import java.util.Locale;

public class PingListener {
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final ConfigurationNode config;
    private final MiniMessage miniMessage;

    @Inject
    public PingListener(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
        this.config = plugin.getConfig();
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        ServerPing.Builder pingBuilder = event.getPing().asBuilder();        // Check if maintenance mode is active and service exists
        if (plugin.getMaintenanceService() != null && plugin.getMaintenanceService().isMaintenanceMode()) {
            // Get maintenance MOTD - already in MiniMessage format
            String maintenanceMOTD = plugin.getMaintenanceService().getMaintenanceMOTD();
            
            // Parse the MiniMessage formatted MOTD
            // The MOTD is already in MiniMessage format from config.yml
            Component motdComponent = miniMessage.deserialize(maintenanceMOTD);
            pingBuilder.description(motdComponent);
        } else {
            ConfigurationNode defaultMotdNode = config.node("motd");
            ConfigurationNode motdNode = resolveMotdNode(event, defaultMotdNode);
            String motdLine1 = getString(motdNode, defaultMotdNode, "line1", "<gradient:#5e4fa2:#f79459><bold>BeaconLabs</bold></gradient> <gray>»</gray> <hover:show_text:'<rainbow>Join the Adventure!</rainbow>'><gold>Your Network!</gold></hover>");
            String motdLine2 = getString(motdNode, defaultMotdNode, "line2", "<aqua>Playing on <yellow>1.21.4+</yellow></aqua>");
            
            Component motdComponent = miniMessage.deserialize(motdLine1 + "\n" + motdLine2);
            pingBuilder.description(motdComponent);
        }
        
        ConfigurationNode defaultMotdNode = config.node("motd");
        ConfigurationNode motdNode = resolveMotdNode(event, defaultMotdNode);
        int maxPlayers = getInt(motdNode, defaultMotdNode, "max-players", 100);
        String versionName = getString(motdNode, defaultMotdNode, "version-name", "");
        int versionProtocol = getInt(motdNode, defaultMotdNode, "version-protocol", 769);

        pingBuilder.maximumPlayers(maxPlayers);
        int playerCount = (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled())
            ? plugin.getCrossProxyService().getTotalPlayerCount()
            : server.getPlayerCount();
        pingBuilder.onlinePlayers(playerCount);
        // Only override version when version-name is set; otherwise keep Velocity's default (real server version)
        if (versionName != null && !versionName.isBlank()) {
            pingBuilder.version(new ServerPing.Version(versionProtocol, versionName));
        }

        event.setPing(pingBuilder.build());
    }

    private ConfigurationNode resolveMotdNode(ProxyPingEvent event, ConfigurationNode defaultMotdNode) {
        String hostname = event.getConnection().getVirtualHost()
                .map(InetSocketAddress::getHostString)
                .map(host -> host.toLowerCase(Locale.ROOT))
                .orElse("");
        if (hostname.isEmpty()) {
            return defaultMotdNode;
        }

        ConfigurationNode hostsNode = defaultMotdNode.node("hosts");
        ConfigurationNode hostNode = hostsNode.node(hostname);
        if (!hostNode.virtual()) {
            return hostNode;
        }

        return defaultMotdNode;
    }

    private String getString(ConfigurationNode node, ConfigurationNode fallbackNode, String key, String defaultValue) {
        String value = node.node(key).getString("");
        if (!value.isBlank()) {
            return value;
        }

        String fallbackValue = fallbackNode.node(key).getString(defaultValue);
        return fallbackValue != null ? fallbackValue : defaultValue;
    }

    private int getInt(ConfigurationNode node, ConfigurationNode fallbackNode, String key, int defaultValue) {
        if (!node.node(key).virtual()) {
            return node.node(key).getInt(defaultValue);
        }

        return fallbackNode.node(key).getInt(defaultValue);
    }
  
}
