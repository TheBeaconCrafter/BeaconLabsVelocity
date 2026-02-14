package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.Subscribe;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

/**
 * When cross-proxy (Redis) is enabled, notifies other proxies that this player connected here,
 * so they can kick the duplicate session (one session per account across proxies).
 */
public class CrossProxyLoginListener {

    private final BeaconLabsVelocity plugin;

    public CrossProxyLoginListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (plugin.getCrossProxyService() == null || !plugin.getCrossProxyService().isEnabled()) return;
        if (plugin.getCrossProxyService().isAllowDoubleJoin()) return; // don't notify other proxies; allow double-join
        plugin.getCrossProxyService().publishPlayerConnect(event.getPlayer().getUniqueId());
    }
}
