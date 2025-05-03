package org.bcnlab.beaconLabsVelocity.service;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.bcnlab.beaconLabsVelocity.util.DurationUtils;
import org.bcnlab.beaconLabsVelocity.util.DiscordWebhook;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class PunishmentService {
    private static final java.text.SimpleDateFormat DATE_FORMAT = new java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss z");
    private final BeaconLabsVelocity plugin;
    private final DatabaseManager db;
    private final PunishmentConfig config;
    private final Logger logger;

    /**
     * Parses a timestamp that might be in different formats:
     * 1. Long format like 20250503113632 (yyyyMMddHHmmss)
     * 2. Milliseconds since epoch (large long values)
     * 3. Seconds since epoch (smaller long values)
     * 
     * @param timestamp The timestamp to parse
     * @return Date object representing the parsed time
     */
    public static Date parseTimestamp(long timestamp) {
        String timestampStr = String.valueOf(timestamp);

        // If the timestamp is 14 digits (yyyyMMddHHmmss format)
        if (timestampStr.length() == 14) {
            try {
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyyMMddHHmmss");
                return format.parse(timestampStr);
            } catch (Exception e) {
                // Log error but continue with fallback approach
                System.err.println("Failed to parse timestamp as yyyyMMddHHmmss format: " + timestamp);
                e.printStackTrace();
            }
        }

        // If it's a large number (milliseconds since epoch)
        if (timestamp > 1000000000000L) {
            return new Date(timestamp);
        }

        // Otherwise assume it's seconds since epoch
        return new Date(timestamp * 1000);
    }

    public PunishmentService(BeaconLabsVelocity plugin, DatabaseManager db, PunishmentConfig config, Logger logger) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Attempts to find the UUID of a player based on their last known username in
     * the punishments table.
     * Case-insensitive search. Prioritizes online players.
     * 
     * @param username The username to search for.
     * @return The UUID if found, otherwise null.
     */
    public UUID getPlayerUUID(String username) {
        // Prioritize online players first for case sensitivity and freshness
        UUID onlineUUID = plugin.getServer().getPlayer(username).map(Player::getUniqueId).orElse(null);
        if (onlineUUID != null) {
            return onlineUUID;
        }

        // If offline, check the database (case-insensitive)
        String sql = "SELECT player_uuid FROM punishments WHERE LOWER(player_name) = LOWER(?) ORDER BY start_time DESC LIMIT 1";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return UUID.fromString(rs.getString("player_uuid"));
            }
        } catch (Exception e) {
            logger.error("Failed to get UUID for username: " + username, e);
        }
        return null; // Not found
    }

    private void expireOld() {
        String sql = "UPDATE punishments SET active=false WHERE active=true AND end_time>0 AND end_time<=?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis()); // Use current time in ms format
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to expire old punishments", e);
        }
    }

    public void punish(UUID targetId, String targetName, UUID issuerId, String issuerName, String type, long durationMs, String reason) {
        long now = System.currentTimeMillis();
        long end = durationMs < 0 ? 0L : now + durationMs;
        // Insert punishment; kicks are recorded as inactive
        String sql = "INSERT INTO punishments (player_uuid,player_name,issuer_uuid,issuer_name,type,reason,duration,start_time,end_time,active) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetId.toString());
            ps.setString(2, targetName);
            ps.setString(3, issuerId != null ? issuerId.toString() : "CONSOLE");
            ps.setString(4, issuerName);
            ps.setString(5, type);
            ps.setString(6, reason);            ps.setLong(7, durationMs);
            ps.setLong(8, now); // Store as milliseconds timestamp
            if (end > 0) ps.setLong(9, end); else ps.setNull(9, java.sql.Types.BIGINT);
            // Kicks should be inactive immediately
            boolean active = !"kick".equalsIgnoreCase(type);
            ps.setBoolean(10, active);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to record punishment", e);
        }        // send webhook stub
        DiscordWebhook.send(String.format("%s punished %s: %s for %s", issuerName, targetName, type, reason));
        
        // Broadcast punishment to players with notify permission
        String broadcastTemplate = config.getMessage(type + "-broadcast");
        if (broadcastTemplate != null) {
            String formattedDuration = DurationUtils.formatDuration(durationMs);
            String expiry = (end <= 0) ? "Never" : DATE_FORMAT.format(new java.util.Date(end));
            String broadcastMsg = broadcastTemplate
                    .replace("{player}", targetName)
                    .replace("{issuer}", issuerName)
                    .replace("{reason}", reason)
                    .replace("{duration}", formattedDuration)
                    .replace("{expiry}", expiry);
            
            // Send with plugin prefix
            Component comp = plugin.getPrefix().append(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(broadcastMsg)
            );
            
            // Send to players with permission
            plugin.getServer().getAllPlayers().stream()
                    .filter(p -> p.hasPermission("beaconlabs.punish.notify"))
                    .forEach(p -> p.sendMessage(comp));
            
            // Log to console as well
            logger.info(LegacyComponentSerializer.legacySection().serialize(comp));
        }
    }

    /**
     * Unban an active ban.
     */
    public boolean unban(UUID targetId) {
        return unpunishInternal(targetId, "ban");
    }

    /**
     * Unmute an active mute.
     */
    public boolean unmute(UUID targetId) {
        return unpunishInternal(targetId, "mute");
    }

    /**
     * Generic internal unpunish by type (ban or mute).
     */
    private boolean unpunishInternal(UUID targetId, String type) {
        String sql = "UPDATE punishments SET active=false, end_time=? WHERE player_uuid=? AND type=? AND active=true";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
            ps.setString(2, targetId.toString());
            ps.setString(3, type); // Use type parameter
            int updated = ps.executeUpdate();
            if (updated > 0) {
                DiscordWebhook.send(String.format("%s player %s", type.equals("ban") ? "Unbanned" : "Unmuted", targetId));
            }
            return updated > 0;
        } catch (Exception e) {
            logger.error("Failed to " + (type.equals("ban") ? "unban" : "unmute") + " player " + targetId, e);
            return false;
        }
    }

    public boolean isBanned(UUID targetId) {
        expireOld();
        String sql = "SELECT COUNT(1) FROM punishments WHERE player_uuid=? AND type='ban' AND active=true";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetId.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1)>0;
        } catch (Exception e) {
            logger.error("Failed to check ban status", e);
        }
        return false;
    }

    /**
     * Gets the details of the active ban for a player, if any.
     * Returns null if not banned.
     */
    public PunishmentRecord getActiveBan(UUID targetId) {
        expireOld();
        String sql = "SELECT reason, duration, end_time FROM punishments WHERE player_uuid=? AND type='ban' AND active=true ORDER BY start_time DESC LIMIT 1";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetId.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                // Create a dummy record just for ban details
                return new PunishmentRecord(null, "ban", rs.getString("reason"),
                                            rs.getLong("duration"), 0, rs.getLong("end_time"), true);
            }
        } catch (Exception e) {
            logger.error("Failed to get active ban details for " + targetId, e);
        }
        return null;
    }

    /**
     * Check if a player has an active mute.
     */
    public boolean isMuted(UUID targetId) {
        expireOld(); // Ensure expired mutes are not considered
        String sql = "SELECT COUNT(1) FROM punishments WHERE player_uuid=? AND type='mute' AND active=true";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetId.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            logger.error("Failed to check mute status for " + targetId, e);
        }
        return false;
    }

    /**
     * Gets the details of the active mute for a player, if any.
     * Returns null if not muted.
     */
    public PunishmentRecord getActiveMute(UUID targetId) {
        expireOld();
        String sql = "SELECT reason, duration, end_time FROM punishments WHERE player_uuid=? AND type='mute' AND active=true ORDER BY start_time DESC LIMIT 1";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetId.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                // Create a record just for mute details
                return new PunishmentRecord(null, "mute", rs.getString("reason"),
                                            rs.getLong("duration"), 0, rs.getLong("end_time"), true);
            }
        } catch (Exception e) {
            logger.error("Failed to get active mute details for " + targetId, e);
        }
        return null;
    }

    public List<PunishmentRecord> getHistory(UUID targetId) {
        expireOld();
        List<PunishmentRecord> list = new ArrayList<>();
        String sql = "SELECT issuer_name,type,reason,duration,start_time,end_time,active FROM punishments WHERE player_uuid=? ORDER BY start_time DESC";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetId.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new PunishmentRecord(
                    rs.getString("issuer_name"), rs.getString("type"), rs.getString("reason"),
                    rs.getLong("duration"), rs.getLong("start_time"), // Changed getTimestamp to getLong
                    rs.getLong("end_time"), // Changed getTimestamp to getLong
                    rs.getBoolean("active")
                ));
            }
        } catch (Exception e) {
            logger.error("Failed to fetch punishment history", e);
        }
        return list;
    }

    /**
     * Clear all punishments for a player (both active and inactive)
     * @param targetId The UUID of the player to clear punishments for
     * @return The number of punishments cleared
     */
    public int clearPunishments(UUID targetId) {
        String sql = "DELETE FROM punishments WHERE player_uuid=?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetId.toString());
            int count = ps.executeUpdate();
            
            if (count > 0) {
                DiscordWebhook.send(String.format("Cleared all punishments for player %s (%d removed)", targetId, count));
            }
            return count;
        } catch (Exception e) {
            logger.error("Failed to clear punishments for player " + targetId, e);
            return 0;
        }
    }

    public static class PunishmentRecord {
        public final String issuerName, type, reason;
        public final long duration, startTime, endTime;
        public final boolean active;

        public PunishmentRecord(String issuerName, String type, String reason, long duration, long startTime,
                long endTime, boolean active) {
            this.issuerName = issuerName;
            this.type = type;
            this.reason = reason;
            this.duration = duration;
            this.startTime = startTime;
            this.endTime = endTime;
            this.active = active;
        }
    }
}
