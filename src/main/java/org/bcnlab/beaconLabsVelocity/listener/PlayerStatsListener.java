package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;
import org.slf4j.Logger;

/**
 * Listener for tracking player connections for playtime and IP history
 */
public class PlayerStatsListener {
    
    private final BeaconLabsVelocity plugin;
    private final PlayerStatsService playerStatsService;
    private final Logger logger;
    
    public PlayerStatsListener(BeaconLabsVelocity plugin, PlayerStatsService playerStatsService, Logger logger) {
        this.plugin = plugin;
        this.playerStatsService = playerStatsService;
        this.logger = logger;
    }
    
    @Subscribe(order = PostOrder.LAST)
    public void onPlayerJoin(PostLoginEvent event) {
        Player player = event.getPlayer();
        
        // Record player login in a separate thread to not block the login process
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try {
                playerStatsService.recordLogin(player);
                logger.debug("Recorded login for player: " + player.getUsername());
            } catch (Exception e) {
                logger.error("Error recording login for player: " + player.getUsername(), e);
            }
        }).schedule();
    }
    
    @Subscribe(order = PostOrder.LAST)
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        
        // Record player logout in a separate thread
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try {
                playerStatsService.recordLogout(player);
                logger.debug("Recorded logout for player: " + player.getUsername());
            } catch (Exception e) {
                logger.error("Error recording logout for player: " + player.getUsername(), e);
            }
        }).schedule();
    }
}
