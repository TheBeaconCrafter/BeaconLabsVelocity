package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

/**
 * When cross-proxy (Redis) is enabled, removes the player from the online-proxy map on disconnect
 * so /info can show correct "online on proxy X" or "offline".
 */
public class CrossProxyDisconnectListener {

    private final BeaconLabsVelocity plugin;

    public CrossProxyDisconnectListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (plugin.getCrossProxyService() == null || !plugin.getCrossProxyService().isEnabled()) return;
        plugin.getCrossProxyService().removePlayerProxy(event.getPlayer().getUniqueId());
        plugin.getCrossProxyService().updatePlayerList();
    }
}
