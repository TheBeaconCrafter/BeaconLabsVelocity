package org.bcnlab.beaconLabsVelocity.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.AbuseConfig;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.bcnlab.beaconLabsVelocity.util.DiscordWebhook;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Calendar;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class AntiBotService {

    private final BeaconLabsVelocity plugin;
    private final DatabaseManager databaseManager;
    private final AbuseConfig config;
    private final Logger logger;
    private final ProxyServer server;
    private final Gson gson = new Gson();
    
    private final AtomicInteger dailyRequests = new AtomicInteger(0);
    private int currentDayOfYear = -1;

    private static final long CACHE_TTL_MS = 3L * 24L * 60L * 60L * 1000L; // 3 days

    public AntiBotService(BeaconLabsVelocity plugin, DatabaseManager databaseManager, AbuseConfig config, Logger logger, ProxyServer server) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.config = config;
        this.logger = logger;
        this.server = server;
        resetDailyCounterIfNeeded();
    }

    private void resetDailyCounterIfNeeded() {
        int today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        if (today != currentDayOfYear) {
            currentDayOfYear = today;
            dailyRequests.set(0);
        }
    }

    public int getRequestsToday() {
        resetDailyCounterIfNeeded();
        return dailyRequests.get();
    }

    public enum DefenseAction { ALLOW, SCREEN, BLOCK }

    public static class IpData {
        public String usageType = "";
        public String isp = "";
        public String domain = "";
        public String countryCode = "";
        public String countryName = "";
        public boolean isTor = false;
        public int totalReports = 0;
        public String lastReportedAt = "";
    }

    public static class IpCheckResult {
        public DefenseAction action;
        public int confidenceScore;
        public IpData ipData;
        public boolean whitelisted;
        public boolean blacklisted;
        public String rawJson;
        
        public IpCheckResult(DefenseAction action, int score, IpData ipData, boolean whitelisted, boolean blacklisted, String rawJson) {
            this.action = action;
            this.confidenceScore = score;
            this.ipData = ipData;
            this.whitelisted = whitelisted;
            this.blacklisted = blacklisted;
            this.rawJson = rawJson;
        }
    }

    public CompletableFuture<IpCheckResult> checkIpAsync(String ipAddress, UUID playerUuid, String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            if (!config.isModuleEnabled()) return new IpCheckResult(DefenseAction.ALLOW, 0, new IpData(), false, false, "{}");

            // Check if player is whitelisted by UUID (manually handled or via plugin's WhitelistService)
            // But we have our own manual whitelist/blacklist for AntiBot
            
            // Check cache first
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT * FROM antibot_ip_cache WHERE ip_address = ?")) {
                stmt.setString(1, ipAddress);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        long lastChecked = rs.getLong("last_checked");
                        boolean whitelisted = rs.getBoolean("is_whitelisted");
                        boolean blacklisted = rs.getBoolean("is_blacklisted");
                        int score = rs.getInt("confidence_score");
                        String dataJson = rs.getString("data_json");
                        
                        IpData ipData = parseIpDataFromJson(dataJson);
                        if (whitelisted) {
                            return new IpCheckResult(DefenseAction.ALLOW, score, ipData, true, false, dataJson);
                        }
                        if (blacklisted) {
                            kickPlayer(playerUuid, ipAddress);
                            return new IpCheckResult(DefenseAction.BLOCK, score, ipData, false, true, dataJson);
                        }

                        // Check TTL
                        if (System.currentTimeMillis() - lastChecked < CACHE_TTL_MS) {
                            DefenseAction action = getDefenseAction(score, ipData.usageType, ipData.countryCode, playerUuid, ipAddress);
                            if (action == DefenseAction.BLOCK) {
                                kickPlayer(playerUuid, ipAddress);
                                fireWebhook(ipAddress, playerName, score, ipData.usageType);
                            } else if (action == DefenseAction.SCREEN) {
                                triggerScreening(playerUuid);
                            }
                            return new IpCheckResult(action, score, ipData, false, false, dataJson);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error checking AntiBot IP cache", e);
            }

            // Not in cache or expired, fetch from AbuseIPDB
            return fetchFromAbuseIpDb(ipAddress, playerUuid, playerName, false);
        });
    }

    private void triggerScreening(UUID playerUuid) {
        if (playerUuid != null && plugin.getScreeningService() != null) {
            Optional<Player> p = server.getPlayer(playerUuid);
            p.ifPresent(player -> plugin.getScreeningService().triggerScreening(player));
        }
    }

    private boolean hasPassedScreeningBefore(UUID playerUuid, String ip) {
        if (playerUuid == null || ip == null) return false;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM screening_passes WHERE player_uuid = ? AND ip_address = ?")) {
            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, ip);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            logger.error("Failed to check screening passes", e);
        }
        return false;
    }

    private IpData parseIpDataFromJson(String jsonStr) {
        IpData res = new IpData();
        if (jsonStr == null || jsonStr.isEmpty()) return res;
        try {
            JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();
            if (root.has("data")) {
                JsonObject data = root.getAsJsonObject("data");
                if (data.has("usageType") && !data.get("usageType").isJsonNull()) res.usageType = data.get("usageType").getAsString();
                if (data.has("isp") && !data.get("isp").isJsonNull()) res.isp = data.get("isp").getAsString();
                if (data.has("domain") && !data.get("domain").isJsonNull()) res.domain = data.get("domain").getAsString();
                if (data.has("countryCode") && !data.get("countryCode").isJsonNull()) res.countryCode = data.get("countryCode").getAsString();
                if (data.has("countryName") && !data.get("countryName").isJsonNull()) res.countryName = data.get("countryName").getAsString();
                if (data.has("isTor") && !data.get("isTor").isJsonNull()) res.isTor = data.get("isTor").getAsBoolean();
                if (data.has("totalReports") && !data.get("totalReports").isJsonNull()) res.totalReports = data.get("totalReports").getAsInt();
                if (data.has("lastReportedAt") && !data.get("lastReportedAt").isJsonNull()) res.lastReportedAt = data.get("lastReportedAt").getAsString();
            }
        } catch (Exception e) {
            // ignore
        }
        return res;
    }

    private DefenseAction getDefenseAction(int score, String usageType, String countryCode, UUID playerUuid, String ip) {
        if (hasPassedScreeningBefore(playerUuid, ip)) {
            return DefenseAction.ALLOW;
        }
        if (score >= config.getForceBanScore()) return DefenseAction.BLOCK;
        if (score >= config.getScreeningScore()) return DefenseAction.SCREEN;
        if (config.isScreenDataCenters() && usageType != null && usageType.contains("Data Center")) return DefenseAction.SCREEN;
        if (countryCode != null && config.getScreenCountries().contains(countryCode.toUpperCase())) return DefenseAction.SCREEN;
        return DefenseAction.ALLOW;
    }

    private IpCheckResult fetchFromAbuseIpDb(String ip, UUID playerUuid, String playerName, boolean silent) {
        resetDailyCounterIfNeeded();
        if (dailyRequests.get() >= config.getDailyLimit()) {
            logger.warn("AbuseIPDB daily limit reached! Skipping check for " + ip);
            return new IpCheckResult(DefenseAction.ALLOW, 0, new IpData(), false, false, "{}");
        }

        try {
            String apiKey = config.getApiKey();
            if (apiKey == null || apiKey.isEmpty() || apiKey.equals("YOUR_API_KEY_HERE")) {
                return new IpCheckResult(DefenseAction.ALLOW, 0, new IpData(), false, false, "{}");
            }

            dailyRequests.incrementAndGet();

            URL url = new URL("https://api.abuseipdb.com/api/v2/check?ipAddress=" + URLEncoder.encode(ip, StandardCharsets.UTF_8) + "&maxAgeInDays=90&verbose");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Key", apiKey);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                JsonObject responseJson;
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    responseJson = JsonParser.parseReader(reader).getAsJsonObject();
                }

                JsonObject data = responseJson.getAsJsonObject("data");
                int score = data.get("abuseConfidenceScore").getAsInt();
                String rawJson = responseJson.toString();
                IpData ipData = parseIpDataFromJson(rawJson);
                
                DefenseAction action = getDefenseAction(score, ipData.usageType, ipData.countryCode, playerUuid, ip);

                // Save to cache
                saveToCache(ip, score, false, false, rawJson);

                if (!silent) {
                    if (action == DefenseAction.BLOCK) {
                        kickPlayer(playerUuid, ip);
                        fireWebhook(ip, playerName, score, ipData.usageType);
                    } else if (action == DefenseAction.SCREEN) {
                        triggerScreening(playerUuid);
                    }
                }

                return new IpCheckResult(action, score, ipData, false, false, rawJson);
            } else {
                logger.warn("AbuseIPDB returned code " + responseCode + " for IP " + ip);
            }
        } catch (Exception e) {
            logger.error("Failed to query AbuseIPDB for " + ip, e);
        }
        return new IpCheckResult(DefenseAction.ALLOW, 0, new IpData(), false, false, "{}");
    }

    private void saveToCache(String ip, int score, boolean whitelisted, boolean blacklisted, String dataJson) {
        if (!databaseManager.isConnected()) return;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO antibot_ip_cache (ip_address, confidence_score, is_whitelisted, is_blacklisted, data_json, last_checked) " +
                     "VALUES (?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE confidence_score=?, is_whitelisted=?, is_blacklisted=?, data_json=?, last_checked=?")) {
            
            long now = System.currentTimeMillis();
            stmt.setString(1, ip);
            stmt.setInt(2, score);
            stmt.setBoolean(3, whitelisted);
            stmt.setBoolean(4, blacklisted);
            stmt.setString(5, dataJson);
            stmt.setLong(6, now);
            
            stmt.setInt(7, score);
            stmt.setBoolean(8, whitelisted);
            stmt.setBoolean(9, blacklisted);
            stmt.setString(10, dataJson);
            stmt.setLong(11, now);
            
            stmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to save AntiBot IP cache", e);
        }
    }

    public void setWhitelist(String ip, boolean whitelisted) {
        if (!databaseManager.isConnected()) return;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO antibot_ip_cache (ip_address, confidence_score, is_whitelisted, is_blacklisted, data_json, last_checked) " +
                     "VALUES (?, 0, ?, false, '{}', ?) " +
                     "ON DUPLICATE KEY UPDATE is_whitelisted=?, is_blacklisted=false")) {
            long now = System.currentTimeMillis();
            stmt.setString(1, ip);
            stmt.setBoolean(2, whitelisted);
            stmt.setLong(3, now);
            stmt.setBoolean(4, whitelisted);
            stmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to set IP whitelist", e);
        }
    }

    public void setBlacklist(String ip, boolean blacklisted) {
        if (!databaseManager.isConnected()) return;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO antibot_ip_cache (ip_address, confidence_score, is_whitelisted, is_blacklisted, data_json, last_checked) " +
                     "VALUES (?, 100, false, ?, '{}', ?) " +
                     "ON DUPLICATE KEY UPDATE is_blacklisted=?, is_whitelisted=false")) {
            long now = System.currentTimeMillis();
            stmt.setString(1, ip);
            stmt.setBoolean(2, blacklisted);
            stmt.setLong(3, now);
            stmt.setBoolean(4, blacklisted);
            stmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to set IP blacklist", e);
        }
    }

    public Optional<IpCheckResult> getCachedInfo(String ip) {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM antibot_ip_cache WHERE ip_address = ?")) {
            stmt.setString(1, ip);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    IpData ipData = parseIpDataFromJson(rs.getString("data_json"));
                    return Optional.of(new IpCheckResult(
                            getDefenseAction(rs.getInt("confidence_score"), ipData.usageType, ipData.countryCode, null, ip),
                            rs.getInt("confidence_score"),
                            ipData,
                            rs.getBoolean("is_whitelisted"),
                            rs.getBoolean("is_blacklisted"),
                            rs.getString("data_json")
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error reading AntiBot IP cache", e);
        }
        return Optional.empty();
    }
    
    public CompletableFuture<IpCheckResult> refreshIpInfo(String ip) {
        return CompletableFuture.supplyAsync(() -> fetchFromAbuseIpDb(ip, null, "Unknown", true));
    }

    private void kickPlayer(UUID playerUuid, String ip) {
        if (playerUuid != null) {
            Optional<Player> p = server.getPlayer(playerUuid);
            p.ifPresent(player -> {
                player.disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(config.getKickMessage()));
            });
        }
        
        // Also cross proxy disconnect
        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            if (playerUuid != null) {
                plugin.getCrossProxyService().publishKick(playerUuid, config.getKickMessage());
            }
        }
    }

    private void fireWebhook(String ip, String playerName, int score, String usageType) {
        DiscordWebhook.sendAntiBotAlert(
                config.getWebhookUrl(),
                config.getRoleIdToPing(),
                ip,
                playerName,
                score,
                usageType,
                config.getWebhookCooldownMinutes()
        );
    }
}
