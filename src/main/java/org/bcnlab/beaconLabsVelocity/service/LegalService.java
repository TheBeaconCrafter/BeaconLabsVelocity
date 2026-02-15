package org.bcnlab.beaconLabsVelocity.service;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.velocitypowered.api.scheduler.ScheduledTask;

/**
 * Legal (Terms of Service / Privacy) feature. Shows a book or chat interface and stores acceptance in the database.
 */
public class LegalService {

    private static final String SQL_INSERT = "INSERT INTO legal_acceptance (player_uuid, accepted_at) VALUES (?, ?) ON DUPLICATE KEY UPDATE accepted_at = ?";
    private static final String SQL_SELECT = "SELECT 1 FROM legal_acceptance WHERE player_uuid = ?";
    private static final String SQL_DELETE_ALL = "DELETE FROM legal_acceptance";

    private final BeaconLabsVelocity plugin;
    private final DatabaseManager databaseManager;
    private final Logger logger;
    /** Pending kick cancel actions (UUID -> cancel runnable) so we can cancel when they accept. */
    private final ConcurrentHashMap<UUID, Runnable> pendingKickCancels = new ConcurrentHashMap<>();
    /** Players currently viewing the legal book (must keep open until they accept). */
    private final Set<UUID> viewingLegalBook = ConcurrentHashMap.newKeySet();
    /** Repeating task per player that re-sends the book (handles ESC / no Close Window packet). */
    private final ConcurrentHashMap<UUID, ScheduledTask> legalBookReopenTasks = new ConcurrentHashMap<>();
    /** Last known hotbar contents (slots 36-44) from server SetSlot/WINDOW_ITEMS packets. */
    private final ConcurrentHashMap<UUID, ItemStack[]> hotbarBackup = new ConcurrentHashMap<>();
    /** Snapshot of hotbar at the moment we show the book; used for restore on accept so we don't use stale/empty backup. */
    private final ConcurrentHashMap<UUID, ItemStack[]> restoreSnapshot = new ConcurrentHashMap<>();

    private static final int REOPEN_BOOK_INTERVAL_SECONDS = 6;
    private static final int HOTBAR_SLOT_MIN = 36;
    private static final int HOTBAR_SLOT_MAX = 44;
    private static final int HOTBAR_SIZE = HOTBAR_SLOT_MAX - HOTBAR_SLOT_MIN + 1;

    public LegalService(BeaconLabsVelocity plugin, DatabaseManager databaseManager, Logger logger) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.logger = logger;
    }

    public boolean isEnabled() {
        ConfigurationNode node = plugin.getConfig() != null ? plugin.getConfig().node("legal") : null;
        return node != null && node.node("enabled").getBoolean(false);
    }

    private ConfigurationNode legalNode() {
        return plugin.getConfig() != null ? plugin.getConfig().node("legal") : null;
    }

    public boolean isShowOnFirstJoin() {
        ConfigurationNode n = legalNode();
        return n != null && n.node("show-on-first-join").getBoolean(true);
    }

    public boolean isEnforceAccept() {
        ConfigurationNode n = legalNode();
        return n != null && n.node("enforce-accept").getBoolean(false);
    }

    public String getKickMessage() {
        ConfigurationNode n = legalNode();
        return n != null ? n.node("kick-message").getString("") : "";
    }

    public String getDenyMessage() {
        ConfigurationNode n = legalNode();
        return n != null ? n.node("deny-message").getString("") : "";
    }

    public String getTopMessage() {
        ConfigurationNode n = legalNode();
        return n != null ? n.node("top-message").getString("") : "";
    }

    /** Cancel the scheduled kick for this player (call when they accept). */
    public void cancelPendingKick(UUID playerUuid) {
        Runnable cancel = pendingKickCancels.remove(playerUuid);
        if (cancel != null) cancel.run();
    }

    /** Register a cancel runnable for the pending kick (e.g. task.cancel()). */
    public void registerPendingKickCancel(UUID playerUuid, Runnable cancelAction) {
        pendingKickCancels.put(playerUuid, cancelAction);
    }

    public boolean hasPendingKick(UUID playerUuid) {
        return pendingKickCancels.containsKey(playerUuid);
    }

    /** Remove pending kick entry without running cancel (e.g. when the kick task runs). */
    public void clearPendingKick(UUID playerUuid) {
        pendingKickCancels.remove(playerUuid);
    }

    /** Mark player as viewing the legal book (re-open on close; keep slots until accept). */
    public void addViewingLegalBook(UUID playerUuid) {
        viewingLegalBook.add(playerUuid);
        // Periodic re-open so that if they press ESC (client doesn't send Close Window for book), book reopens
        ScheduledTask existing = legalBookReopenTasks.get(playerUuid);
        if (existing != null) existing.cancel();
        ScheduledTask task = plugin.getServer().getScheduler().buildTask(plugin, () -> {
            if (!viewingLegalBook.contains(playerUuid)) return;
            Optional<Player> opt = plugin.getServer().getPlayer(playerUuid);
            if (opt.isEmpty() || !opt.get().isActive()) return;
            reOpenBookFor(opt.get());
        }).repeat(REOPEN_BOOK_INTERVAL_SECONDS, TimeUnit.SECONDS).schedule();
        legalBookReopenTasks.put(playerUuid, task);
    }

    /** Remove player from viewing legal book (after accept or disconnect). */
    public void removeViewingLegalBook(UUID playerUuid) {
        viewingLegalBook.remove(playerUuid);
        ScheduledTask task = legalBookReopenTasks.remove(playerUuid);
        if (task != null) task.cancel();
    }

    public boolean isViewingLegalBook(UUID playerUuid) {
        return viewingLegalBook.contains(playerUuid);
    }

    /** Re-send book to hotbar and open it (call when they close the book or try to drop it). */
    public void reOpenBookFor(Player player) {
        ConfigurationNode legal = legalNode();
        if (legal == null) return;
        org.bcnlab.beaconLabsVelocity.legal.LegalBookPacketHelper.reOpenBook(player, legal, logger);
    }

    /** Save hotbar slot content from server SetSlot (window 0, slots 36-44). Called by packet listener. */
    public void saveHotbarSlot(UUID playerUuid, int slot, ItemStack item) {
        if (slot < HOTBAR_SLOT_MIN || slot > HOTBAR_SLOT_MAX) return;
        ItemStack[] arr = hotbarBackup.computeIfAbsent(playerUuid, u -> new ItemStack[HOTBAR_SIZE]);
        ItemStack copy = (item == null || item.isEmpty()) ? null : item.copy();
        arr[slot - HOTBAR_SLOT_MIN] = copy;
    }

    /** Get last known hotbar contents (slots 36-44). Null or empty slots mean no cached value. */
    public ItemStack[] getHotbarBackup(UUID playerUuid) {
        return hotbarBackup.get(playerUuid);
    }

    /** Snapshot current hotbar backup for this player (call right before showing the book). */
    public void saveRestoreSnapshot(UUID playerUuid) {
        ItemStack[] current = hotbarBackup.get(playerUuid);
        if (current == null) return;
        ItemStack[] copy = new ItemStack[HOTBAR_SIZE];
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            ItemStack s = current[i];
            copy[i] = (s == null || s.isEmpty()) ? null : s.copy();
        }
        restoreSnapshot.put(playerUuid, copy);
    }

    /** Take the restore snapshot for this player (used when they accept). Returns null if none. */
    public ItemStack[] takeRestoreSnapshot(UUID playerUuid) {
        return restoreSnapshot.remove(playerUuid);
    }

    /** Clear restore snapshot (e.g. on disconnect). */
    public void clearRestoreSnapshot(UUID playerUuid) {
        restoreSnapshot.remove(playerUuid);
    }

    /** Clear the legal book from the player's hotbar and restore previous hotbar (call when they accept). */
    public void clearLegalBookFromHotbar(Player player) {
        ItemStack[] backup = takeRestoreSnapshot(player.getUniqueId());
        if (backup == null) backup = getHotbarBackup(player.getUniqueId());
        org.bcnlab.beaconLabsVelocity.legal.LegalBookPacketHelper.clearLegalBookFromHotbar(player, backup);
    }

    /** Check if the player has accepted (from DB). */
    public CompletableFuture<Boolean> hasAcceptedAsync(UUID playerUuid) {
        if (!databaseManager.isConnected()) return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SQL_SELECT)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                logger.debug("Legal hasAccepted failed: {}", e.getMessage());
                return false;
            }
        });
    }

    /** Record that the player accepted. Returns true if saved. */
    public CompletableFuture<Boolean> setAcceptedAsync(UUID playerUuid) {
        if (!databaseManager.isConnected()) return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                long now = System.currentTimeMillis();
                ps.setString(1, playerUuid.toString());
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                logger.warn("Legal setAccepted failed: {}", e.getMessage());
                return false;
            }
        });
    }

    /** Reset legal acceptance for everyone. Returns the number of rows deleted, or -1 on error. */
    public CompletableFuture<Integer> resetAllAcceptancesAsync() {
        if (!databaseManager.isConnected()) return CompletableFuture.completedFuture(-1);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SQL_DELETE_ALL)) {
                return ps.executeUpdate();
            } catch (SQLException e) {
                logger.warn("Legal resetAll failed: {}", e.getMessage());
                return -1;
            }
        });
    }

    /** Show the legal interface to the player (book if available, else chat with links). */
    public void showLegalTo(Player player) {
        ConfigurationNode legal = legalNode();
        if (legal == null) return;

        String topMessage = getTopMessage();
        if (topMessage != null && !topMessage.isEmpty()) {
            player.sendMessage(plugin.getPrefix().append(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(topMessage)));
        }

        try {
            // Snapshot hotbar backup now (before we add viewing) so we restore exactly what was there before the book
            saveRestoreSnapshot(player.getUniqueId());
            addViewingLegalBook(player.getUniqueId());
            if (org.bcnlab.beaconLabsVelocity.legal.LegalBookPacketHelper.showBook(player, legal, logger, plugin)) {
                return;
            }
            removeViewingLegalBook(player.getUniqueId()); // showBook failed, don't keep viewing
        } catch (Throwable t) {
            removeViewingLegalBook(player.getUniqueId());
            logger.debug("PacketEvents legal book failed, trying fallback: {}", t.getMessage());
        }
        try {
            if (tryShowBook(player, legal)) return;
        } catch (Throwable t) {
            logger.debug("Legal book not available, using chat: {}", t.getMessage());
        }

        showLegalChat(player, legal);
    }

    private boolean tryShowBook(Player player, ConfigurationNode legal) {
        try {
            Class<?> bookClass = Class.forName("net.kyori.adventure.inventory.Book");
            java.lang.reflect.Method bookMethod = bookClass.getMethod("book", net.kyori.adventure.text.Component.class, String.class, net.kyori.adventure.text.Component[].class);

            Component title = Component.text("Legal", NamedTextColor.GOLD, TextDecoration.BOLD);
            String author = "Server";

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
            if (isEnforceAccept()) {
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

            Object book = bookMethod.invoke(null, title, author, new Object[]{ page1 });
            player.getClass().getMethod("showBook", bookClass).invoke(player, book);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            logger.debug("Show book failed: {}", e.getMessage());
            return false;
        }
    }

    private void showLegalChat(Player player, ConfigurationNode legal) {
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("━━━ Legal ━━━", NamedTextColor.GOLD, TextDecoration.BOLD));

        ConfigurationNode tos = legal.node("tos");
        if (tos.node("enabled").getBoolean(true)) {
            String link = tos.node("link").getString("");
            if (!link.isEmpty()) {
                player.sendMessage(Component.text("Terms of Service: ", NamedTextColor.GRAY)
                        .append(Component.text("[Open Link]", NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.openUrl(link))
                                .hoverEvent(HoverEvent.showText(Component.text(link, NamedTextColor.GRAY)))));
            } else {
                player.sendMessage(Component.text("Terms of Service (see link in config)", NamedTextColor.GRAY));
            }
        }

        ConfigurationNode privacy = legal.node("privacy");
        if (privacy.node("enabled").getBoolean(true)) {
            String link = privacy.node("link").getString("");
            if (!link.isEmpty()) {
                player.sendMessage(Component.text("Privacy Policy: ", NamedTextColor.GRAY)
                        .append(Component.text("[Open Link]", NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.openUrl(link))
                                .hoverEvent(HoverEvent.showText(Component.text(link, NamedTextColor.GRAY)))));
            } else {
                player.sendMessage(Component.text("Privacy Policy (see link in config)", NamedTextColor.GRAY));
            }
        }

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Accept to continue: ", NamedTextColor.YELLOW)
                .append(Component.text("[Accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/legal accept"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to accept", NamedTextColor.GREEN))))
                .append(Component.text("  ", NamedTextColor.WHITE))
                .append(Component.text("[Deny]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/legal deny"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to decline and disconnect", NamedTextColor.RED)))));
        player.sendMessage(Component.empty());
    }
}
