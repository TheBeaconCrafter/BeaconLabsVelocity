package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

/**
 * When cross-proxy (Redis) is enabled, notifies other proxies that this player connected here,
 * so they can kick the duplicate session (one session per account across proxies).
 * Also handles pending transfer: if player was transferred via /proxies send, connect them to the same backend.
 */
public class CrossProxyLoginListener {

    private final BeaconLabsVelocity plugin;

    public CrossProxyLoginListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (plugin.getCrossProxyService() == null || !plugin.getCrossProxyService().isEnabled()) return;
        Player player = event.getPlayer();
        plugin.getCrossProxyService().setPlayerProxy(player.getUniqueId(), plugin.getCrossProxyService().getProxyId());
        plugin.getCrossProxyService().updatePlayerList();

        // If player was transferred here via /proxies send, send them to the same backend they were on
        String pendingServer = plugin.getCrossProxyService().getAndClearPendingTransfer(player.getUniqueId());
        if (pendingServer != null && !pendingServer.isEmpty()) {
            ProxyServer server = plugin.getServer();
            server.getServer(pendingServer).ifPresent(rs ->
                    player.createConnectionRequest(rs).connectWithIndication());
        }

        if (plugin.getCrossProxyService().isAllowDoubleJoin()) return; // don't notify other proxies; allow double-join
        plugin.getCrossProxyService().publishPlayerConnect(player.getUniqueId());
    }
}
