package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MuteListener {

    private final PunishmentService punishmentService;
    private final PunishmentConfig punishmentConfig;
    private final Logger logger;
    private final BeaconLabsVelocity plugin;

    // Maintain a set of muted players who have attempted to speak in the last few seconds
    private final Set<UUID> recentMuteNotifications = Collections.synchronizedSet(new HashSet<>());
    private final long NOTIFICATION_COOLDOWN = 3000; // 3 seconds cooldown between notifications

    public MuteListener(BeaconLabsVelocity plugin, PunishmentService punishmentService, PunishmentConfig punishmentConfig, Logger logger) {
        this.plugin = plugin;
        this.punishmentService = punishmentService;
        this.punishmentConfig = punishmentConfig;
        this.logger = logger;
    }
    
    @SuppressWarnings("deprecation") // setResult is deprecated but necessary here
    @Subscribe(order = com.velocitypowered.api.event.PostOrder.FIRST) 
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String username = player.getUsername();

        boolean isMuted = punishmentService.isMuted(playerUuid);

        if (isMuted) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            
            // Prevent further processing of this event by other listeners if it's denied
            if (!event.getResult().isAllowed()) {
                boolean recentlyNotified = recentMuteNotifications.contains(playerUuid);
                
                if (!recentlyNotified) {
                    logger.info("[MuteListener] Sending mute notification to {}.", username); // Log notification sending
                    PunishmentService.PunishmentRecord muteRecord = punishmentService.getActiveMute(playerUuid);
                    String reason = muteRecord != null ? muteRecord.reason : "No reason specified";
                    
                    String muteMessageTemplate = punishmentConfig.getMessage("mute-message");
                    if (muteMessageTemplate == null) {
                        muteMessageTemplate = "&c&lYou are currently muted and cannot chat. &7Reason: &f{reason}";
                        logger.warn("Mute message template 'mute-message' not found in punishments.yml. Using default message.");
                    }
                    
                    String muteMessage = muteMessageTemplate.replace("{reason}", reason);
                    if (muteRecord != null && muteMessage.contains("{duration}")) {
                        long duration = muteRecord.duration;
                        String formattedDuration = duration < 0 ? "Permanent" : 
                            org.bcnlab.beaconLabsVelocity.util.DurationUtils.formatDuration(duration);
                        muteMessage = muteMessage.replace("{duration}", formattedDuration);
                    } else {
                         muteMessage = muteMessage.replace("{duration}", "Unknown"); // Handle case where duration placeholder exists but record is null
                    }
                    
                    Component formattedMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(muteMessage);
                    player.sendMessage(formattedMessage);
                    
                    recentMuteNotifications.add(playerUuid);
                    plugin.getServer().getScheduler().buildTask(plugin, () -> {
                        recentMuteNotifications.remove(playerUuid);
                    }).delay(NOTIFICATION_COOLDOWN, TimeUnit.MILLISECONDS).schedule();  
                } else {
                     //logger.info("[MuteListener] Player {} was recently notified. Skipping notification.", username); // Log skipped notification
                }
                return; 
            } else {
                 //logger.warn("[MuteListener] Event for {} was set to denied, but getResult().isAllowed() is still true! Something might be overriding the cancellation.", username); // Log potential override
            }
        } else {
            //logger.info("[MuteListener] Player {} is not muted. Allowing chat event.", username); // Log allowed event
        }
    }
}
