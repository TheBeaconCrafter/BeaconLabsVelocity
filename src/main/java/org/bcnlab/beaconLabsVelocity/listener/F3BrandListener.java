package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.brand.F3BrandService;

import java.util.concurrent.TimeUnit;

/**
 * Schedules client-bound brand packets after the player finishes (re)connecting to a backend.
 */
public final class F3BrandListener {

    private final BeaconLabsVelocity plugin;
    private final F3BrandService f3BrandService;

    public F3BrandListener(BeaconLabsVelocity plugin, F3BrandService f3BrandService) {
        this.plugin = plugin;
        this.f3BrandService = f3BrandService;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (!f3BrandService.isEnabled()) return;
        Player player = event.getPlayer();
        if (player == null) return;

        long delay = f3BrandService.delayMs();
        plugin.getServer().getScheduler()
                .buildTask(plugin, () -> f3BrandService.send(player))
                .delay(delay, TimeUnit.MILLISECONDS)
                .schedule();
    }
}
