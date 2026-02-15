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
    }    @Subscribe
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
            // Regular MOTD when not in maintenance mode
            String motdLine1 = config.node("motd", "line1").getString("<gradient:#5e4fa2:#f79459><bold>BeaconLabs</bold></gradient> <gray>»</gray> <hover:show_text:'<rainbow>Join the Adventure!</rainbow>'><gold>Your Network!</gold></hover>");
            String motdLine2 = config.node("motd", "line2").getString("<aqua>Playing on <yellow>1.21.4+</yellow></aqua>");
            
            Component motdComponent = miniMessage.deserialize(motdLine1 + "\n" + motdLine2);
            pingBuilder.description(motdComponent);
        }
        
        int maxPlayers = config.node("motd", "max-players").getInt(100);
        String versionName = config.node("motd", "version-name").getString("");
        int versionProtocol = config.node("motd", "version-protocol").getInt(769);

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
  
}
