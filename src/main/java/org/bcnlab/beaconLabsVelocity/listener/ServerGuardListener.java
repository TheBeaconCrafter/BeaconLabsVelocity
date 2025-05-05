package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.ServerGuardService;

/**
 * Listener that enforces server guard rules when players try to connect to servers
 */
public class ServerGuardListener {

    private final BeaconLabsVelocity plugin;
    private final ServerGuardService guardService;

    public ServerGuardListener(BeaconLabsVelocity plugin, ServerGuardService guardService) {
        this.plugin = plugin;
        this.guardService = guardService;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String targetServerName = event.getOriginalServer().getServerInfo().getName();

        // Check if player can access the server
        if (!guardService.canAccess(player, targetServerName)) {
            // Get the required permission if any
            String permission = guardService.getServerStatus(player, targetServerName).getPermission();

            // Build deny message
            Component message = plugin.getPrefix().append(
                Component.text("You don't have permission to access ", NamedTextColor.RED)
            ).append(
                Component.text(targetServerName, NamedTextColor.YELLOW)
            );
            
            // Add permission info if available
            if (permission != null && !permission.isEmpty()) {
                message = message.append(
                    Component.text(" (Requires: ", NamedTextColor.RED)
                ).append(
                    Component.text(permission, NamedTextColor.GRAY)
                ).append(
                    Component.text(")", NamedTextColor.RED)
                );
            }

            // Deny the connection and inform the player
            player.sendMessage(message);
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }
}
