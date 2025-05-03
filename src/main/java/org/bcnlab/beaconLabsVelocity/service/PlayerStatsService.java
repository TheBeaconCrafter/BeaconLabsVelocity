package org.bcnlab.beaconLabsVelocity.service;

import com.velocitypowered.api.proxy.Player;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service to handle player playtime tracking and IP history
 */
public class PlayerStatsService {
    private final BeaconLabsVelocity plugin;
    private final DatabaseManager db;
    private final Logger logger;
    
    // Cache to track current session playtime
    private final Map<UUID, Long> playerSessionStart = new ConcurrentHashMap<>();
    // Cache for player's playtime (to reduce DB queries)
    private final Map<UUID, Long> playerTotalPlaytime = new ConcurrentHashMap<>();
    
    public PlayerStatsService(BeaconLabsVelocity plugin, DatabaseManager db, Logger logger) {
        this.plugin = plugin;
        this.db = db;
        this.logger = logger;
        initializeTables();
        
        // Schedule periodic saving of online players' playtime
        plugin.getServer().getScheduler().buildTask(plugin, this::saveAllOnlinePlaytime)
            .repeat(5, TimeUnit.MINUTES)
            .schedule();
    }
    
    /**
     * Initialize database tables for player stats tracking
     */
    private void initializeTables() {
        if (!db.isConnected()) {
            logger.error("Database is not connected. Player stats tracking will not work!");
            return;
        }
        
        try (Connection conn = db.getConnection()) {
            // Create player_stats table
            String createPlayerStatsTable = "CREATE TABLE IF NOT EXISTS player_stats (" +
                    "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "player_name VARCHAR(16) NOT NULL, " +
                    "total_playtime BIGINT DEFAULT 0, " +
                    "first_join BIGINT, " +
                    "last_seen BIGINT" +
                    ")";
            
            // Create ip_history table with index on player_uuid
            String createIpHistoryTable = "CREATE TABLE IF NOT EXISTS ip_history (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "ip_address VARCHAR(45) NOT NULL, " +
                    "timestamp BIGINT NOT NULL, " +
                    "INDEX (player_uuid)" +
                    ")";
            
            try (var stmt = conn.createStatement()) {
                stmt.execute(createPlayerStatsTable);
                stmt.execute(createIpHistoryTable);
                logger.info("Successfully initialized player stats database tables");
            }
        } catch (SQLException e) {
            logger.error("Failed to initialize player stats tables", e);
        }
    }
    
    /**
     * Record a player's login, updating their stats and IP history
     */
    public void recordLogin(Player player) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getUsername();
        long currentTime = System.currentTimeMillis();
        String ipAddress = player.getRemoteAddress().getAddress().getHostAddress();
        
        // Start tracking session time
        playerSessionStart.put(playerId, currentTime);
        
        // Run database operations async
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try (Connection conn = db.getConnection()) {
                // Update or insert player stats
                String upsertPlayerStats = "INSERT INTO player_stats (player_uuid, player_name, first_join, last_seen) " +
                        "VALUES (?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE player_name = ?, last_seen = ?";
                
                try (PreparedStatement ps = conn.prepareStatement(upsertPlayerStats)) {
                    ps.setString(1, playerId.toString());
                    ps.setString(2, playerName);
                    ps.setLong(3, currentTime); // first_join
                    ps.setLong(4, currentTime); // last_seen
                    ps.setString(5, playerName); // update name
                    ps.setLong(6, currentTime); // update last_seen
                    ps.executeUpdate();
                }
                
                // Record IP address
                String insertIp = "INSERT INTO ip_history (player_uuid, ip_address, timestamp) VALUES (?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertIp)) {
                    ps.setString(1, playerId.toString());
                    ps.setString(2, ipAddress);
                    ps.setLong(3, currentTime);
                    ps.executeUpdate();
                }
                
                // Clean up old IP entries (keep only last 3)
                String cleanupIps = "DELETE FROM ip_history WHERE player_uuid = ? AND id NOT IN " +
                        "(SELECT id FROM (SELECT id FROM ip_history WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT 3) AS temp)";
                try (PreparedStatement ps = conn.prepareStatement(cleanupIps)) {
                    ps.setString(1, playerId.toString());
                    ps.setString(2, playerId.toString());
                    ps.executeUpdate();
                }
                
                // Load player's playtime into cache
                loadPlayerPlaytime(playerId);
                
            } catch (SQLException e) {
                logger.error("Failed to record player login: " + playerName, e);
            }
        }).schedule();
    }
    
    /**
     * Record a player's logout and update their total playtime
     */
    public void recordLogout(Player player) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getUsername();
        long currentTime = System.currentTimeMillis();
        
        // Calculate session time if we have a start time
        if (playerSessionStart.containsKey(playerId)) {
            long sessionStart = playerSessionStart.remove(playerId);
            long sessionDuration = currentTime - sessionStart;
            
            // Add to cached total
            long currentTotal = playerTotalPlaytime.getOrDefault(playerId, 0L);
            playerTotalPlaytime.put(playerId, currentTotal + sessionDuration);
            
            // Update in database
            updatePlaytime(playerId, playerName, currentTotal + sessionDuration, currentTime);
        } else {
            // Just update last seen time if no session start was recorded
            updateLastSeen(playerId, playerName, currentTime);
        }
    }
    
    /**
     * Save playtime for all online players
     */
    private void saveAllOnlinePlaytime() {
        long currentTime = System.currentTimeMillis();
        
        plugin.getServer().getAllPlayers().forEach(player -> {
            UUID playerId = player.getUniqueId();
            String playerName = player.getUsername();
            
            // If player has a session start time
            if (playerSessionStart.containsKey(playerId)) {
                long sessionStart = playerSessionStart.get(playerId);
                long sessionDuration = currentTime - sessionStart;
                
                // Update session start to current time for the next interval
                playerSessionStart.put(playerId, currentTime);
                
                // Add to cached total
                long currentTotal = playerTotalPlaytime.getOrDefault(playerId, 0L);
                long newTotal = currentTotal + sessionDuration;
                playerTotalPlaytime.put(playerId, newTotal);
                
                // Update in database
                updatePlaytime(playerId, playerName, newTotal, currentTime);
            }
        });
    }
    
    /**
     * Update a player's playtime and last seen time in the database
     */
    private void updatePlaytime(UUID playerId, String playerName, long totalPlaytime, long lastSeen) {
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try (Connection conn = db.getConnection()) {
                String updateSql = "UPDATE player_stats SET total_playtime = ?, last_seen = ? WHERE player_uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setLong(1, totalPlaytime);
                    ps.setLong(2, lastSeen);
                    ps.setString(3, playerId.toString());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                logger.error("Failed to update playtime for: " + playerName, e);
            }
        }).schedule();
    }
    
    /**
     * Update only the last seen time for a player in the database
     */
    private void updateLastSeen(UUID playerId, String playerName, long lastSeen) {
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try (Connection conn = db.getConnection()) {
                String updateSql = "UPDATE player_stats SET last_seen = ? WHERE player_uuid = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setLong(1, lastSeen);
                    ps.setString(2, playerId.toString());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                logger.error("Failed to update last seen for: " + playerName, e);
            }
        }).schedule();
    }
    
    /**
     * Load a player's total playtime from the database into the cache
     */
    private void loadPlayerPlaytime(UUID playerId) {
        try (Connection conn = db.getConnection()) {
            String sql = "SELECT total_playtime FROM player_stats WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        playerTotalPlaytime.put(playerId, rs.getLong("total_playtime"));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load playtime for: " + playerId, e);
        }
    }
    
    /**
     * Get a player's total playtime in milliseconds
     */
    public long getPlayerPlaytime(UUID playerId) {
        // Try to get from cache first
        if (playerTotalPlaytime.containsKey(playerId)) {
            long cachedPlaytime = playerTotalPlaytime.get(playerId);
            
            // Add current session time if player is online
            if (playerSessionStart.containsKey(playerId)) {
                long sessionDuration = System.currentTimeMillis() - playerSessionStart.get(playerId);
                return cachedPlaytime + sessionDuration;
            }
            
            return cachedPlaytime;
        }
        
        // Try to load from database if not in cache
        try (Connection conn = db.getConnection()) {
            String sql = "SELECT total_playtime FROM player_stats WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long dbPlaytime = rs.getLong("total_playtime");
                        playerTotalPlaytime.put(playerId, dbPlaytime);
                        
                        // Add current session time if player is online
                        if (playerSessionStart.containsKey(playerId)) {
                            long sessionDuration = System.currentTimeMillis() - playerSessionStart.get(playerId);
                            return dbPlaytime + sessionDuration;
                        }
                        
                        return dbPlaytime;
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get playtime for: " + playerId, e);
        }
        
        return 0; // No playtime found
    }
      /**
     * Get a player's last IP addresses (up to 3)
     */
    public List<IpHistoryEntry> getPlayerIpHistory(UUID playerId) {
        List<IpHistoryEntry> ipHistory = new ArrayList<>();
        
        try (Connection conn = db.getConnection()) {
            String sql = "SELECT ip_address, timestamp FROM ip_history WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT 3";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ipHistory.add(new IpHistoryEntry(
                            rs.getString("ip_address"),
                            rs.getLong("timestamp")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get IP history for: " + playerId, e);
        }
        
        return ipHistory;
    }
    
    /**
     * Get all of a player's IP addresses without limit
     */
    public List<IpHistoryEntry> getAllPlayerIpHistory(UUID playerId) {
        List<IpHistoryEntry> ipHistory = new ArrayList<>();
        
        try (Connection conn = db.getConnection()) {
            String sql = "SELECT ip_address, timestamp FROM ip_history WHERE player_uuid = ? ORDER BY timestamp DESC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ipHistory.add(new IpHistoryEntry(
                            rs.getString("ip_address"),
                            rs.getLong("timestamp")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get all IP history for: " + playerId, e);
        }
        
        return ipHistory;
    }
    
    /**
     * Get the top players by playtime
     */
    public List<PlayerPlaytimeEntry> getTopPlaytimePlayers(int limit) {
        List<PlayerPlaytimeEntry> topPlayers = new ArrayList<>();
        
        try (Connection conn = db.getConnection()) {
            String sql = "SELECT player_uuid, player_name, total_playtime FROM player_stats ORDER BY total_playtime DESC LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID playerId = UUID.fromString(rs.getString("player_uuid"));
                        long playtime = rs.getLong("total_playtime");
                        
                        // Add current session time if player is online
                        if (playerSessionStart.containsKey(playerId)) {
                            playtime += System.currentTimeMillis() - playerSessionStart.get(playerId);
                        }
                        
                        topPlayers.add(new PlayerPlaytimeEntry(
                            playerId,
                            rs.getString("player_name"),
                            playtime
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get top playtime players", e);
        }
        
        return topPlayers;
    }
    
    /**
     * Format a duration in milliseconds to a human-readable string
     */
    public static String formatPlaytime(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        hours %= 24;
        minutes %= 60;
        
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("m");
        } else {
            sb.append("less than 1m");
        }
        
        return sb.toString().trim();
    }
    
    /**
     * Class to hold IP history data
     */
    public static class IpHistoryEntry {
        private final String ipAddress;
        private final long timestamp;
        
        public IpHistoryEntry(String ipAddress, long timestamp) {
            this.ipAddress = ipAddress;
            this.timestamp = timestamp;
        }
        
        public String getIpAddress() {
            return ipAddress;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
    }
    
    /**
     * Class to hold player playtime data
     */
    public static class PlayerPlaytimeEntry {
        private final UUID playerId;
        private final String playerName;
        private final long playtime;
        
        public PlayerPlaytimeEntry(UUID playerId, String playerName, long playtime) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.playtime = playtime;
        }
        
        public UUID getPlayerId() {
            return playerId;
        }
        
        public String getPlayerName() {
            return playerName;
        }
        
        public long getPlaytime() {
            return playtime;
        }
        
        public String getFormattedPlaytime() {
            return formatPlaytime(playtime);
        }
    }
}
