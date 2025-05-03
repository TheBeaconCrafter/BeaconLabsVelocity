package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.service.MaintenanceService;

/**
 * Listener for handling maintenance mode events
 */
public class MaintenanceListener {
    
    private final MaintenanceService maintenanceService;
    
    public MaintenanceListener(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }
      /**
     * Process login attempts during maintenance mode
     */
    @Subscribe(order = PostOrder.FIRST)
    public void onPlayerLogin(LoginEvent event) {
        // Check if player can join during maintenance
        if (!maintenanceService.canJoinDuringMaintenance(event.getPlayer())) {
            // Kick the player with the configured message
            event.setResult(LoginEvent.ComponentResult.denied(
                LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(maintenanceService.getKickMessage())
            ));
        }
    }
}
