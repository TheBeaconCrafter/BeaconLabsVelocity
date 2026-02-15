package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.LegalService;

import java.util.concurrent.TimeUnit;

/**
 * When legal feature is enabled and show-on-first-join is true, shows the legal interface to players who have not yet accepted.
 * If enforce-accept is true, schedules a kick after 60 seconds unless they accept.
 */
public class LegalListener {

    private static final int KICK_DELAY_SECONDS = 60;
    /** Delay before showing legal book on join so the client is fully in-game and ready to open the book. */
    private static final int JOIN_LEGAL_DELAY_SECONDS = 2;

    private final BeaconLabsVelocity plugin;
    private final LegalService legalService;

    public LegalListener(BeaconLabsVelocity plugin, LegalService legalService) {
        this.plugin = plugin;
        this.legalService = legalService;
    }

    @Subscribe(order = PostOrder.LATE)
    public void onPostLogin(PostLoginEvent event) {
        if (!legalService.isEnabled() || !legalService.isShowOnFirstJoin()) return;
        if (plugin.getDatabaseManager() == null || !plugin.getDatabaseManager().isConnected()) return;

        Player player = event.getPlayer();
        legalService.hasAcceptedAsync(player.getUniqueId()).thenAccept(accepted -> {
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                if (!player.isActive()) return;
                if (accepted) return;

                // Delay showing legal so the client is fully in-game and can open the book
                plugin.getServer().getScheduler().buildTask(plugin, () -> {
                    if (!player.isActive()) return;
                    legalService.showLegalTo(player);
                    player.sendMessage(plugin.getPrefix().append(
                            net.kyori.adventure.text.Component.text("Type ", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                            .append(net.kyori.adventure.text.Component.text("/legal accept", net.kyori.adventure.text.format.NamedTextColor.GREEN))
                            .append(net.kyori.adventure.text.Component.text(" to accept and continue.", net.kyori.adventure.text.format.NamedTextColor.YELLOW)));

                    if (legalService.isEnforceAccept()) {
                        var task = plugin.getServer().getScheduler().buildTask(plugin, () -> {
                            if (!player.isActive()) return;
                            if (!legalService.hasPendingKick(player.getUniqueId())) return;
                            legalService.clearPendingKick(player.getUniqueId());
                            String msg = legalService.getKickMessage();
                            if (msg == null || msg.isEmpty()) msg = "You must accept the Terms of Service and Privacy Policy to play.";
                            player.disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
                        }).delay(KICK_DELAY_SECONDS, TimeUnit.SECONDS).schedule();
                        legalService.registerPendingKickCancel(player.getUniqueId(), task::cancel);
                    }
                }).delay(JOIN_LEGAL_DELAY_SECONDS, TimeUnit.SECONDS).schedule();
            }).schedule();
        });
    }
}
