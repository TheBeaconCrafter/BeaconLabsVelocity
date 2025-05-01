package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.bcnlab.beaconLabsVelocity.util.DurationUtils;
import org.slf4j.Logger;

public class BanLoginListener {

    private final BeaconLabsVelocity plugin;
    private final PunishmentService punishmentService;
    private final PunishmentConfig punishmentConfig;
    private final Logger logger;

    public BanLoginListener(BeaconLabsVelocity plugin, PunishmentService punishmentService, PunishmentConfig punishmentConfig, Logger logger) {
        this.plugin = plugin;
        this.punishmentService = punishmentService;
        this.punishmentConfig = punishmentConfig;
        this.logger = logger;
    }

    @Subscribe
    public void onPlayerLogin(LoginEvent event) {
        Player player = event.getPlayer();

        // Don't check players with bypass permission
        if (player.hasPermission("beaconlabs.punish.ban.bypass")) {
            return;
        }

        PunishmentService.PunishmentRecord activeBan = null;
        try {
            activeBan = punishmentService.getActiveBan(player.getUniqueId());
        } catch (Exception e) {
             logger.error("Error checking ban status for " + player.getUsername(), e);
             // Allow login on error? Or deny? For safety, maybe deny.
             // event.setResult(ResultedEvent.ComponentResult.denied(Component.text("Error checking your ban status.", NamedTextColor.RED)));
             return; // Allow login for now
        }


        if (activeBan != null) {
            String banMessageFormat = punishmentConfig.getMessage("ban-login-deny");
            if (banMessageFormat == null) {
                banMessageFormat = "&cYou are banned.\nReason: {reason}\nExpires: {duration}"; // Fallback
                logger.warn("Missing 'ban-login-deny' message in punishments.yml");
            }

            String reason = activeBan.reason != null ? activeBan.reason : "N/A";
            String durationStr = DurationUtils.formatDuration(activeBan.duration); // Use duration from record

            Component banMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                    banMessageFormat.replace("{reason}", reason)
                                  .replace("{duration}", durationStr)
            );
            event.setResult(LoginEvent.ComponentResult.denied(banMessage));
        }
    }
}
