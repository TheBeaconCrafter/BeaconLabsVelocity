package org.bcnlab.beaconLabsVelocity.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FriendService {

    public static class FriendInfo {
        public final UUID uuid;
        public final long friendsSince;

        public FriendInfo(UUID uuid, long friendsSince) {
            this.uuid = uuid;
            this.friendsSince = friendsSince;
        }
    }

    private final BeaconLabsVelocity plugin;
    private final DatabaseManager databaseManager;
    private final Logger logger;
    private final ProxyServer proxy;
    private static final long FRIEND_CACHE_TTL_MS = 10_000L;
    private final Map<UUID, FriendCacheEntry> friendsCache = new ConcurrentHashMap<>();

    private record FriendCacheEntry(List<UUID> friends, long expiresAt) {}

    public FriendService(BeaconLabsVelocity plugin, DatabaseManager databaseManager, ProxyServer proxy, Logger logger) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.proxy = proxy;
        this.logger = logger;
    }

    public void sendFriendRequest(UUID sender, UUID target) {
        if (databaseManager == null || !databaseManager.isConnected()) return;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("INSERT INTO friends (player_uuid, friend_uuid, status, created_at) VALUES (?, ?, 'pending', ?) ON DUPLICATE KEY UPDATE status=status")) {
            stmt.setString(1, sender.toString());
            stmt.setString(2, target.toString());
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to send friend request from {} to {}", sender, target, e);
        }
    }

    public void acceptFriendRequest(UUID player, UUID friend) {
        if (databaseManager == null || !databaseManager.isConnected()) return;
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt1 = conn.prepareStatement("UPDATE friends SET status='accepted' WHERE player_uuid=? AND friend_uuid=?");
                 PreparedStatement stmt2 = conn.prepareStatement("INSERT INTO friends (player_uuid, friend_uuid, status, created_at) VALUES (?, ?, 'accepted', ?) ON DUPLICATE KEY UPDATE status='accepted'")) {
                
                stmt1.setString(1, friend.toString());
                stmt1.setString(2, player.toString());
                stmt1.executeUpdate();

                stmt2.setString(1, player.toString());
                stmt2.setString(2, friend.toString());
                stmt2.setLong(3, System.currentTimeMillis());
                stmt2.executeUpdate();
                invalidateFriendCache(player, friend);
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to accept friend request from {} to {}", friend, player, e);
        }
    }

    public void denyFriendRequest(UUID player, UUID friend) {
        if (databaseManager == null || !databaseManager.isConnected()) return;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM friends WHERE player_uuid=? AND friend_uuid=? AND status='pending'")) {
            stmt.setString(1, friend.toString());
            stmt.setString(2, player.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to deny friend request from {} to {}", friend, player, e);
        }
    }

    public void removeFriend(UUID player, UUID friend) {
        if (databaseManager == null || !databaseManager.isConnected()) return;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM friends WHERE (player_uuid=? AND friend_uuid=?) OR (player_uuid=? AND friend_uuid=?)")) {
            stmt.setString(1, player.toString());
            stmt.setString(2, friend.toString());
            stmt.setString(3, friend.toString());
            stmt.setString(4, player.toString());
            stmt.executeUpdate();
            invalidateFriendCache(player, friend);
        } catch (SQLException e) {
            logger.error("Failed to remove friend {} for {}", friend, player, e);
        }
    }

    public List<UUID> getFriends(UUID player) {
        long now = System.currentTimeMillis();
        FriendCacheEntry cached = friendsCache.get(player);
        if (cached != null && cached.expiresAt() > now) {
            return cached.friends();
        }
        if (databaseManager == null || !databaseManager.isConnected()) return List.of();

        List<UUID> friends = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT friend_uuid FROM friends WHERE player_uuid=? AND status='accepted'")) {
            stmt.setString(1, player.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    friends.add(UUID.fromString(rs.getString("friend_uuid")));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get friends for {}", player, e);
        }
        List<UUID> immutableFriends = List.copyOf(friends);
        friendsCache.put(player, new FriendCacheEntry(immutableFriends, now + FRIEND_CACHE_TTL_MS));
        return immutableFriends;
    }

    public List<FriendInfo> getDetailedFriends(UUID player) {
        if (databaseManager == null || !databaseManager.isConnected()) return List.of();
        List<FriendInfo> friends = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT friend_uuid, created_at FROM friends WHERE player_uuid=? AND status='accepted'")) {
            stmt.setString(1, player.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    friends.add(new FriendInfo(UUID.fromString(rs.getString("friend_uuid")), rs.getLong("created_at")));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get detailed friends for {}", player, e);
        }
        return friends;
    }

    public List<UUID> getPendingRequests(UUID player) {
        if (databaseManager == null || !databaseManager.isConnected()) return List.of();
        List<UUID> requests = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT player_uuid FROM friends WHERE friend_uuid=? AND status='pending'")) {
            stmt.setString(1, player.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    requests.add(UUID.fromString(rs.getString("player_uuid")));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get pending requests for {}", player, e);
        }
        return requests;
    }

    public boolean areFriends(UUID uuid1, UUID uuid2) {
        return getFriends(uuid1).contains(uuid2);
    }

    public void clearPlayerCache(UUID player) {
        friendsCache.remove(player);
    }

    private void invalidateFriendCache(UUID player, UUID friend) {
        friendsCache.remove(player);
        friendsCache.remove(friend);
    }

    public int getFriendCount(UUID player) {
        if (databaseManager == null || !databaseManager.isConnected()) return 0;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM friends WHERE player_uuid=? AND status='accepted'")) {
            stmt.setString(1, player.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get friend count for {}", player, e);
        }
        return 0;
    }
}
