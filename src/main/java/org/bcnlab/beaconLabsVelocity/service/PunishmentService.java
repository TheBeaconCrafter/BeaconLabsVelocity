package org.bcnlab.beaconLabsVelocity.service;

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
import java.util.List;
import java.util.UUID;

public class PunishmentService {
    private final BeaconLabsVelocity plugin;
    private final DatabaseManager db;
    private final PunishmentConfig config;
    private final Logger logger;

    public PunishmentService(BeaconLabsVelocity plugin, DatabaseManager db, PunishmentConfig config, Logger logger) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
        this.logger = logger;
    }

    private void expireOld() {
        String sql = "UPDATE punishments SET active=false WHERE active=true AND end_time>0 AND end_time<=?";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to expire old punishments", e);
        }
    }

    public void punish(UUID targetId, String targetName, UUID issuerId, String issuerName, String type, long durationMs, String reason) {
        long now = System.currentTimeMillis();
        long end = durationMs < 0 ? 0L : now + durationMs;
        String sql = "INSERT INTO punishments (player_uuid,player_name,issuer_uuid,issuer_name,type,reason,duration,start_time,end_time,active) VALUES (?,?,?,?,?,?,?,?,?,true)";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetId.toString());
            ps.setString(2, targetName);
            // Store issuer UUID or "CONSOLE" if null
            ps.setString(3, issuerId != null ? issuerId.toString() : "CONSOLE");
            ps.setString(4, issuerName);
            ps.setString(5, type);
            ps.setString(6, reason);
            ps.setLong(7, durationMs);
            ps.setTimestamp(8, new Timestamp(now));
            if (end>0) ps.setTimestamp(9, new Timestamp(end)); else ps.setNull(9, java.sql.Types.TIMESTAMP);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to record punishment", e);
        }
        // send webhook stub
        DiscordWebhook.send(String.format("%s punished %s: %s for %s", issuerName, targetName, type, reason));
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

    public static class PunishmentRecord {
        public final String issuerName, type, reason;
        public final long duration, startTime, endTime;
        public final boolean active;
        public PunishmentRecord(String issuerName, String type, String reason, long duration, long startTime, long endTime, boolean active) {
            this.issuerName=issuerName;this.type=type;this.reason=reason;this.duration=duration;this.startTime=startTime;this.endTime=endTime;this.active=active;
        }
    }
}
