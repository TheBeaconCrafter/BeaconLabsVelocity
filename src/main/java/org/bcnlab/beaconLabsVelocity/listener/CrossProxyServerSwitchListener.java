package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

/**
 * When cross-proxy is enabled, updates the proxy player list in Redis when a player switches server.
 */
public class CrossProxyServerSwitchListener {

    private final BeaconLabsVelocity plugin;

    public CrossProxyServerSwitchListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (plugin.getCrossProxyService() == null || !plugin.getCrossProxyService().isEnabled()) return;
        plugin.getCrossProxyService().updatePlayerList();
    }
}
