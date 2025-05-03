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

import java.text.SimpleDateFormat;
import java.util.UUID;

public class BanLoginListener {

    private final PunishmentService punishmentService;
    private final PunishmentConfig punishmentConfig;
    private final Logger logger;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");

    public BanLoginListener(BeaconLabsVelocity plugin, PunishmentService punishmentService, PunishmentConfig punishmentConfig, Logger logger) {
        this.punishmentService = punishmentService;
        this.punishmentConfig = punishmentConfig;
        this.logger = logger;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        // Don't check players with bypass permission
        if (player.hasPermission("beaconlabs.punish.ban.bypass")) {
            return;
        }

        if (punishmentService.isBanned(playerUuid)) {            PunishmentService.PunishmentRecord banRecord = punishmentService.getActiveBan(playerUuid);
            // Use the correct config key as specified in the user's config
            String banMessageTemplate = punishmentConfig.getMessage("ban-login-deny");
            String defaultKickMessage = "&cYou are banned from this server.";            if (banRecord != null && banMessageTemplate != null) {
                String reason = banRecord.reason;
                long duration = banRecord.duration;
                long endTime = banRecord.endTime;                String formattedDuration = (duration < 0) ? "Permanent" : DurationUtils.formatDuration(duration);
                  String formattedEndTime;
                if (endTime <= 0) {
                    formattedEndTime = "Never";
                } else {
                    // Use the utility method to handle various timestamp formats
                    formattedEndTime = DATE_FORMAT.format(PunishmentService.parseTimestamp(endTime));
                }

                // Log the values being used to help debug template issues
                logger.debug("Ban message replacement values: reason='{}', duration='{}', expires='{}'", 
                            reason, formattedDuration, formattedEndTime);

                String banMessage = banMessageTemplate
                        .replace("{reason}", reason)
                        .replace("{duration}", formattedDuration)
                        .replace("{expires}", formattedEndTime);

                Component kickReason = LegacyComponentSerializer.legacyAmpersand().deserialize(banMessage);
                event.setResult(LoginEvent.ComponentResult.denied(kickReason));
                logger.info("Denied login for banned player: " + player.getUsername() + " (UUID: " + playerUuid + ")");
            } else {
                // Fallback kick message if details are missing or template is null
                if (banMessageTemplate == null) {
                    logger.warn("Ban message template 'ban-login-deny' not found in punishments.yml");
                }
                if (banRecord == null) {
                    logger.warn("Could not retrieve active ban record for " + player.getUsername());
                }
                Component defaultKickReason = LegacyComponentSerializer.legacyAmpersand().deserialize(defaultKickMessage);
                event.setResult(LoginEvent.ComponentResult.denied(defaultKickReason));
                logger.warn("Denied login for banned player " + player.getUsername() + ". Used default kick message.");
            }
        }
    }
}
