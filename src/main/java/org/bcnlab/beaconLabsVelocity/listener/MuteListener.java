package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.slf4j.Logger;

public class MuteListener {

    private final BeaconLabsVelocity plugin;
    private final PunishmentService punishmentService;
    private final PunishmentConfig punishmentConfig;
    private final Logger logger;

    public MuteListener(BeaconLabsVelocity plugin, PunishmentService punishmentService, PunishmentConfig punishmentConfig, Logger logger) {
        this.plugin = plugin;
        this.punishmentService = punishmentService;
        this.punishmentConfig = punishmentConfig;
        this.logger = logger;
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        // Don't check players with bypass permission
        if (player.hasPermission("beaconlabs.punish.mute.bypass")) {
            return;
        }

        // Check if player is muted
        boolean muted = false;
        try {
             muted = punishmentService.isMuted(player.getUniqueId());
        } catch (Exception e) {
            logger.error("Error checking mute status for " + player.getUsername(), e);
            // Decide if you want to allow chat on error or deny it
            // event.setResult(PlayerChatEvent.ChatResult.denied());
            // player.sendMessage(plugin.getPrefix().append(Component.text("Error checking your mute status.", NamedTextColor.RED)));
            return; // Allow chat if error occurs for now
        }


        if (muted) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            String muteMessage = punishmentConfig.getMessage("mute-enforce");
            if (muteMessage == null) {
                muteMessage = "&cYou are currently muted."; // Fallback message
                logger.warn("Missing 'mute-enforce' message in punishments.yml");
            }
            player.sendMessage(plugin.getPrefix().append(LegacyComponentSerializer.legacyAmpersand().deserialize(muteMessage)));
        }
    }
}
