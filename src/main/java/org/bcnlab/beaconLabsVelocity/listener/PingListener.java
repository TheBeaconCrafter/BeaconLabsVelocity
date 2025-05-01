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
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        ServerPing.Builder pingBuilder = event.getPing().asBuilder();

        String motdLine1 = config.node("motd", "line1").getString("<gradient:#5e4fa2:#f79459><bold>BeaconLabs</bold></gradient> <gray>»</gray> <hover:show_text:'<rainbow>Join the Adventure!</rainbow>'><gold>Your Network!</gold></hover>");
        String motdLine2 = config.node("motd", "line2").getString("<aqua>Playing on <yellow>1.21.4+</yellow></aqua>");
        int maxPlayers = config.node("motd", "max-players").getInt(100);

        String versionName = config.node("motd", "version-name").getString("BeaconLabs 1.21.4+");
        int versionProtocol = config.node("motd", "version-protocol").getInt(769);

        Component motdComponent = miniMessage.deserialize(motdLine1 + "\n" + motdLine2);

        pingBuilder.description(motdComponent);
        pingBuilder.maximumPlayers(maxPlayers);

        pingBuilder.onlinePlayers(server.getPlayerCount());
        pingBuilder.version(new ServerPing.Version(versionProtocol, versionName));

        event.setPing(pingBuilder.build());
    }
}
