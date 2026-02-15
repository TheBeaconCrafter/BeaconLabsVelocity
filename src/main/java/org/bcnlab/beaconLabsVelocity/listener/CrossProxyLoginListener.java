package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.concurrent.TimeUnit;

/**
 * When cross-proxy (Redis) is enabled, notifies other proxies that this player connected here,
 * so they can kick the duplicate session (one session per account across proxies).
 * Also handles pending transfer: if player was transferred via /proxies send, connect them to the same backend.
 * Retries the connection after 3 seconds if the player is not yet on the server (e.g. "already trying to connect").
 */
public class CrossProxyLoginListener {

    private static final int RETRY_DELAY_SECONDS = 3;

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
            server.getServer(pendingServer).ifPresent(rs -> {
                player.createConnectionRequest(rs).connectWithIndication();
                // Retry after 3s if still not on that server (e.g. "already trying to connect" from transfer)
                final String targetServer = pendingServer;
                plugin.getServer().getScheduler().buildTask(plugin, () -> {
                    if (!player.isActive()) return;
                    boolean onTarget = player.getCurrentServer()
                            .map(sc -> sc.getServerInfo().getName().equals(targetServer))
                            .orElse(false);
                    if (!onTarget) {
                        server.getServer(targetServer).ifPresent(rs2 ->
                                player.createConnectionRequest(rs2).connectWithIndication());
                    }
                }).delay(RETRY_DELAY_SECONDS, TimeUnit.SECONDS).schedule();
            });
        }

        if (plugin.getCrossProxyService().isAllowDoubleJoin()) return; // don't notify other proxies; allow double-join
        plugin.getCrossProxyService().publishPlayerConnect(player.getUniqueId());
    }
}
