package org.bcnlab.beaconLabsVelocity.legal;

import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract;
import com.github.retrooper.packetevents.event.simple.PacketPlayReceiveEvent;
import com.github.retrooper.packetevents.event.simple.PacketPlaySendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.velocitypowered.api.proxy.Player;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.LegalService;

/**
 * When a player is viewing the legal book: re-open the book on close, and cancel drop/click in hotbar to keep book undroppable.
 */
public final class LegalBookPacketListener extends SimplePacketListenerAbstract {

    private final BeaconLabsVelocity plugin;
    private final LegalService legalService;

    public LegalBookPacketListener(BeaconLabsVelocity plugin, LegalService legalService) {
        this.plugin = plugin;
        this.legalService = legalService;
    }

    @Override
    public void onPacketPlayReceive(PacketPlayReceiveEvent event) {
        if (!legalService.isEnabled()) return;
        Object playerObj = event.getPlayer();
        if (!(playerObj instanceof Player player)) return;
        if (!legalService.isViewingLegalBook(player.getUniqueId())) return;

        if (event.getPacketType() == PacketType.Play.Client.CLOSE_WINDOW) {
            // They closed the book (or any window) – re-open the book immediately
            event.setCancelled(true);
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                if (!player.isActive()) return;
                legalService.reOpenBookFor(player);
            }).schedule();
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW) {
            try {
                WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
                int slot = click.getSlot();
                // Hotbar slots in player inventory (window 0) are 36–44
                if (slot >= 36 && slot <= 44) {
                    // Cancel so they can't drop or move the book; we'll re-send the book to restore
                    event.setCancelled(true);
                    plugin.getServer().getScheduler().buildTask(plugin, () -> {
                        if (!player.isActive()) return;
                        legalService.reOpenBookFor(player);
                    }).schedule();
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onPacketPlaySend(PacketPlaySendEvent event) {
        if (!legalService.isEnabled()) return;
        Object playerObj = event.getPlayer();
        if (!(playerObj instanceof Player player)) return;
        if (legalService.isViewingLegalBook(player.getUniqueId())) return;

        if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            try {
                WrapperPlayServerSetSlot setSlot = new WrapperPlayServerSetSlot(event);
                if (setSlot.getWindowId() != 0) return;
                int slot = setSlot.getSlot();
                if (slot >= 36 && slot <= 44) {
                    legalService.saveHotbarSlot(player.getUniqueId(), slot, setSlot.getItem());
                }
            } catch (Throwable ignored) {}
            return;
        }

        // Capture hotbar from full window sync (server often sends this on join instead of 9× SetSlot)
        if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            try {
                WrapperPlayServerWindowItems windowItems = new WrapperPlayServerWindowItems(event);
                if (windowItems.getWindowId() != 0) return;
                var items = windowItems.getItems();
                if (items == null) return;
                for (int slot = 36; slot <= 44; slot++) {
                    if (slot < items.size()) {
                        com.github.retrooper.packetevents.protocol.item.ItemStack item = items.get(slot);
                        legalService.saveHotbarSlot(player.getUniqueId(), slot, item);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        User user = event.getUser();
        if (user != null && user.getUUID() != null) {
            legalService.removeViewingLegalBook(user.getUUID());
            legalService.clearRestoreSnapshot(user.getUUID());
        }
    }
}
