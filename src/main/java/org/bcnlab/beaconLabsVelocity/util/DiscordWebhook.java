package org.bcnlab.beaconLabsVelocity.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility for sending webhook notifications to Discord.
 */
public class DiscordWebhook {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("DiscordWebhook");
    
    // Cooldown cache to prevent webhook spam (Key: Webhook URL + IP/Identifier, Value: Last sent timestamp)
    private static final Map<String, Long> cooldownCache = new ConcurrentHashMap<>();

    /**
     * Send a simple message to Discord.
     */
    public static void send(String message) {
        // Simple stub implementation left for backward compatibility, 
        // though normally you'd need the URL passed in or retrieved from a config.
    }

    /**
     * Send an AntiBot alert to Discord.
     * 
     * @param url The webhook URL
     * @param roleIdToPing The Discord role ID to ping (empty if none)
     * @param ip The IP address that triggered the alert
     * @param player The player UUID/name (optional)
     * @param score The confidence score
     * @param usageType The usage type from AbuseIPDB
     * @param cooldownMinutes How long to wait before sending another webhook for this IP
     */
    public static void sendAntiBotAlert(String url, String roleIdToPing, String ip, String player, int score, String usageType, int cooldownMinutes) {
        if (url == null || url.isEmpty()) {
            return;
        }

        String cacheKey = url + "_" + ip;
        long now = System.currentTimeMillis();
        long cooldownMs = cooldownMinutes * 60L * 1000L;

        if (cooldownCache.containsKey(cacheKey)) {
            long lastSent = cooldownCache.get(cacheKey);
            if (now - lastSent < cooldownMs) {
                // Cooldown active, don't spam
                return;
            }
        }

        cooldownCache.put(cacheKey, now);

        Thread thread = new Thread(() -> {
            try {
                URL endpoint = new URL(url);
                HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; utf-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setDoOutput(true);

                JsonObject json = new JsonObject();
                
                String content = "";
                if (roleIdToPing != null && !roleIdToPing.isEmpty()) {
                    content = "<@&" + roleIdToPing + "> ";
                }
                content += "🚨 **AntiBot Alert: Suspicious Connection Blocked**";
                
                json.addProperty("content", content);

                JsonArray embeds = new JsonArray();
                JsonObject embed = new JsonObject();
                embed.addProperty("title", "AbuseIPDB Flag");
                embed.addProperty("color", 16711680); // Red color
                
                String description = "**IP:** " + ip + "\n" +
                                     "**Player:** " + (player != null ? player : "Unknown") + "\n" +
                                     "**Confidence Score:** " + score + "%\n" +
                                     "**Usage Type:** " + (usageType != null ? usageType : "Unknown");
                embed.addProperty("description", description);
                embeds.add(embed);
                
                json.add("embeds", embeds);

                String jsonInputString = json.toString();

                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    LOGGER.warn("Discord webhook returned non-success code: " + responseCode);
                }

            } catch (Exception e) {
                LOGGER.error("Failed to send Discord webhook", e);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }
}
