package org.bcnlab.beaconLabsVelocity.service;

import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class PlayerSettingsService {

    private final BeaconLabsVelocity plugin;
    private final DatabaseManager databaseManager;
    private final Logger logger;
    private final Map<UUID, Map<String, String>> cache = new ConcurrentHashMap<>();

    public PlayerSettingsService(BeaconLabsVelocity plugin, DatabaseManager databaseManager, Logger logger) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.logger = logger;
    }

    public void loadPlayerSettings(UUID uuid) {
        Map<String, String> settings = new ConcurrentHashMap<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT setting_key, setting_value FROM player_settings WHERE uuid=?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    settings.put(rs.getString("setting_key"), rs.getString("setting_value"));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load settings for {}", uuid, e);
        }
        cache.put(uuid, settings);
    }

    public void savePlayerSetting(UUID uuid, String key, String value) {
        Map<String, String> settings = cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        settings.put(key, value);

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("INSERT INTO player_settings (uuid, setting_key, setting_value) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE setting_value=?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, key);
            stmt.setString(3, value);
            stmt.setString(4, value);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save setting {} for {}", key, uuid, e);
        }
    }

    public String getPlayerSetting(UUID uuid, String key, String defaultValue) {
        Map<String, String> settings = cache.get(uuid);
        if (settings != null && settings.containsKey(key)) {
            return settings.get(key);
        }
        // Fallback to DB if not cached
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT setting_value FROM player_settings WHERE uuid=? AND setting_key=?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString("setting_value");
                    if (settings != null) {
                        settings.put(key, value);
                    }
                    return value;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get setting {} for {}", key, uuid, e);
        }
        return defaultValue;
    }

    public void removePlayer(UUID uuid) {
        cache.remove(uuid);
    }
}
