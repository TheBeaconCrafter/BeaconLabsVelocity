package org.bcnlab.beaconLabsVelocity.legal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WrittenBookContent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.util.Filterable;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenBook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sends the Legal (TOS/Privacy) book GUI using PacketEvents.
 * Book contains title, TOS/Privacy links, and [Accept] / [Deny] buttons.
 */
public final class LegalBookPacketHelper {

    private LegalBookPacketHelper() {}

    /**
     * Show the legal content as a book GUI via PacketEvents (title, links, Accept/Deny buttons).
     * Sends SetSlot first, then OpenBook one tick later so the client has the book before opening.
     * @param logger optional, for debug logging (can be null)
     * @param plugin optional; if non-null, OpenBook is sent 1 tick after SetSlot for reliable opening
     * @return true if the book was sent, false if PacketEvents was unavailable or failed
     */
    public static boolean showBook(Player player, ConfigurationNode legal, Logger logger, BeaconLabsVelocity plugin) {
        if (logger != null) logger.info("[Legal book] showBook called for player {}", player.getUsername());

        if (PacketEvents.getAPI() == null) {
            if (logger != null) logger.info("[Legal book] PacketEvents API is null");
            return false;
        }
        var playerManager = PacketEvents.getAPI().getPlayerManager();
        if (playerManager == null) {
            if (logger != null) logger.info("[Legal book] PlayerManager is null");
            return false;
        }
        if (logger != null) logger.info("[Legal book] PacketEvents API and PlayerManager OK");

        User user = null;
        try {
            user = playerManager.getUser(player);
            if (logger != null) logger.info("[Legal book] User resolved: {}", user != null ? "yes" : "no");
        } catch (Throwable t) {
            if (logger != null) logger.info("[Legal book] Could not get User: {}", t.getMessage());
        }

        ItemStack bookItem = buildLegalBookItem(user, legal, logger);
        if (bookItem == null) {
            if (logger != null) logger.info("[Legal book] buildLegalBookItem returned null");
            return false;
        }
        if (logger != null) logger.info("[Legal book] Book item built successfully");

        // Send book to all hotbar slots (36-44) so it opens regardless of selected slot
        final User userFinal = user;
        final Logger loggerFinal = logger;

        try {
            for (int slot = 36; slot <= 44; slot++) {
                WrapperPlayServerSetSlot setSlot = new WrapperPlayServerSetSlot(0, 0, slot, bookItem);
                sendSetSlot(user, playerManager, player, setSlot);
            }
            if (logger != null) logger.info("[Legal book] SetSlot packets sent (slots 36-44, window 0)");

            WrapperPlayServerOpenBook openBook = new WrapperPlayServerOpenBook(InteractionHand.MAIN_HAND);

            if (plugin != null) {
                plugin.getServer().getScheduler().buildTask(plugin, () -> {
                    try {
                        sendOpenBook(userFinal, playerManager, player, openBook);
                        if (loggerFinal != null) loggerFinal.info("[Legal book] OpenBook packet sent (1 tick delayed)");
                    } catch (Throwable t) {
                        if (loggerFinal != null) loggerFinal.warn("[Legal book] OpenBook send failed: {}", t.getMessage());
                    }
                }).delay(50, TimeUnit.MILLISECONDS).schedule();
            } else {
                sendOpenBook(user, playerManager, player, openBook);
                if (logger != null) logger.info("[Legal book] OpenBook packet sent (no plugin, immediate)");
            }
            return true;
        } catch (Throwable t) {
            if (logger != null) logger.warn("[Legal book] sendPacket failed: {}", t.getMessage());
            return false;
        }
    }

    private static void sendSetSlot(User user, PlayerManager playerManager, Player player, WrapperPlayServerSetSlot setSlot) {
        if (user != null) {
            user.sendPacket(setSlot);
        } else {
            playerManager.sendPacket(player, setSlot);
        }
    }

    private static void sendOpenBook(User user, PlayerManager playerManager, Player player, WrapperPlayServerOpenBook openBook) {
        if (user != null) {
            user.sendPacket(openBook);
        } else {
            playerManager.sendPacket(player, openBook);
        }
    }

    private static final int HOTBAR_SLOT_MIN = 36;
    private static final int HOTBAR_SLOT_MAX = 44;

    /**
     * Clear the legal book from hotbar slots 36-44 and restore previous contents if we have a backup.
     * Call when the player accepts so the book disappears and their real hotbar is restored.
     * @param player the player
     * @param hotbarBackup optional array of 9 ItemStacks for slots 36-44 (from server); null or missing slots use AIR
     */
    public static void clearLegalBookFromHotbar(Player player, ItemStack[] hotbarBackup) {
        if (PacketEvents.getAPI() == null) return;
        PlayerManager playerManager = PacketEvents.getAPI().getPlayerManager();
        if (playerManager == null) return;
        User user = null;
        try {
            user = playerManager.getUser(player);
        } catch (Throwable ignored) {}
        ItemStack empty;
        try {
            empty = ItemStack.builder().type(ItemTypes.AIR).amount(0).build();
            if (empty == null) return;
        } catch (Throwable t) {
            return;
        }
        try {
            for (int slot = HOTBAR_SLOT_MIN; slot <= HOTBAR_SLOT_MAX; slot++) {
                int idx = slot - HOTBAR_SLOT_MIN;
                ItemStack toSend = (hotbarBackup != null && idx < hotbarBackup.length && hotbarBackup[idx] != null && !hotbarBackup[idx].isEmpty())
                        ? hotbarBackup[idx]
                        : empty;
                WrapperPlayServerSetSlot setSlot = new WrapperPlayServerSetSlot(0, 0, slot, toSend);
                sendSetSlot(user, playerManager, player, setSlot);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Re-send the legal book to hotbar and open it (e.g. when they closed the book or tried to drop it).
     * Call from packet listener when CloseWindow or hotbar click/drop is received for a player viewing legal.
     */
    public static void reOpenBook(Player player, ConfigurationNode legal, Logger logger) {
        if (PacketEvents.getAPI() == null) return;
        PlayerManager playerManager = PacketEvents.getAPI().getPlayerManager();
        if (playerManager == null) return;
        User user = null;
        try {
            user = playerManager.getUser(player);
        } catch (Throwable ignored) {}
        ItemStack bookItem = buildLegalBookItem(user, legal, logger);
        if (bookItem == null) return;
        try {
            for (int slot = 36; slot <= 44; slot++) {
                WrapperPlayServerSetSlot setSlot = new WrapperPlayServerSetSlot(0, 0, slot, bookItem);
                sendSetSlot(user, playerManager, player, setSlot);
            }
            WrapperPlayServerOpenBook openBook = new WrapperPlayServerOpenBook(InteractionHand.MAIN_HAND);
            sendOpenBook(user, playerManager, player, openBook);
        } catch (Throwable t) {
            if (logger != null) logger.debug("[Legal book] reOpenBook failed: {}", t.getMessage());
        }
    }

    /**
     * Build written book with title, TOS/Privacy links (clickable), and [Accept] / [Deny] buttons.
     * Pages are serialized to JSON (Minecraft protocol uses JSON text for book pages).
     */
    private static ItemStack buildLegalBookItem(User user, ConfigurationNode legal, Logger logger) {
        if (logger != null) logger.info("[Legal book] Building book item (user={})", user != null ? "present" : "null");
        try {
            String author = "Server";

            // Single page: intro, TOS/Privacy links, then Accept/Deny buttons (so links stay clickable longer between re-renders)
            Component page1 = Component.text("Terms of Service & Privacy\n\n", NamedTextColor.GRAY);
            ConfigurationNode tos = legal.node("tos");
            if (tos.node("enabled").getBoolean(true)) {
                String tosLink = tos.node("link").getString("");
                if (!tosLink.isEmpty()) {
                    page1 = page1.append(Component.text("Terms of Service: ", NamedTextColor.GRAY))
                            .append(Component.text("[Open Link]", NamedTextColor.AQUA)
                                    .clickEvent(ClickEvent.openUrl(tosLink))
                                    .hoverEvent(HoverEvent.showText(Component.text(tosLink, NamedTextColor.GRAY))));
                } else {
                    page1 = page1.append(Component.text("Terms of Service (see config)", NamedTextColor.GRAY));
                }
                page1 = page1.append(Component.newline());
            }
            ConfigurationNode privacy = legal.node("privacy");
            if (privacy.node("enabled").getBoolean(true)) {
                String privacyLink = privacy.node("link").getString("");
                if (!privacyLink.isEmpty()) {
                    page1 = page1.append(Component.text("Privacy Policy: ", NamedTextColor.GRAY))
                            .append(Component.text("[Open Link]", NamedTextColor.AQUA)
                                    .clickEvent(ClickEvent.openUrl(privacyLink))
                                    .hoverEvent(HoverEvent.showText(Component.text(privacyLink, NamedTextColor.GRAY))));
                } else {
                    page1 = page1.append(Component.text("Privacy Policy (see config)", NamedTextColor.GRAY));
                }
                page1 = page1.append(Component.newline());
            }
            boolean enforceAccept = legal.node("enforce-accept").getBoolean(false);
            if (enforceAccept) {
                page1 = page1.append(Component.text("If you do not accept, you will be kicked in 60 seconds.\n\n", NamedTextColor.DARK_GRAY));
            }
            page1 = page1.append(Component.text("Choose an option:\n\n", NamedTextColor.GRAY))
                    .append(Component.text("[Accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/legal accept"))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to accept and continue", NamedTextColor.GREEN))))
                    .append(Component.text("  ", NamedTextColor.GRAY))
                    .append(Component.text("[Deny]", NamedTextColor.RED, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/legal deny"))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to decline and disconnect", NamedTextColor.RED))));

            List<Filterable<Component>> pages = new ArrayList<>();
            pages.add(new Filterable<>(page1));

            WrittenBookContent content = new WrittenBookContent(
                    new Filterable<String>("Legal"), author, 0, pages, true);

            var builder = ItemStack.builder()
                    .type(ItemTypes.WRITTEN_BOOK)
                    .amount(1);
            if (user != null) {
                builder.user(user);
            }
            ItemStack stack = builder.build();
            if (stack == null || stack.isEmpty()) return null;

            stack.setComponent(ComponentTypes.WRITTEN_BOOK_CONTENT, content);
            if (logger != null) logger.info("[Legal book] WrittenBookContent set (1 page)");
            return stack;
        } catch (Throwable t) {
            if (logger != null) logger.info("[Legal book] buildLegalBookItem failed: {}", t.getMessage());
            return null;
        }
    }

}
