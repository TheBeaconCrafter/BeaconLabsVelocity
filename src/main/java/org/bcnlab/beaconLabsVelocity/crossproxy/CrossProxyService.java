package org.bcnlab.beaconLabsVelocity.crossproxy;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Optional cross-proxy sync via Redis Pub/Sub. When enabled, kick/ban/send and
 * duplicate-session handling work across multiple Velocity proxies. All messages
 * are verified with a shared secret so only authorized proxies act on messages.
 */
public class CrossProxyService {

    private static final String CHANNEL = "blv:crossproxy";
    private static final String ONLINE_KEY_PREFIX = "blv:online:";
    private static final String PROXIES_SET = "blv:proxies";
    private static final String PLIST_KEY_PREFIX = "blv:plist:";
    private static final String HEARTBEAT_KEY_PREFIX = "blv:proxyhb:";
    private static final int HEARTBEAT_TTL_SECONDS = 90;
    private static final int HEARTBEAT_REFRESH_INTERVAL_SECONDS = 30;
    private static final String PLAYER_SERVER_SEP = "\u001E";
    private static final String PLAYER_SERVER_PAIR_SEP = ":";

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final String proxyId;
    private final String sharedSecret;
    private final boolean enabled;
    private final boolean allowDoubleJoin;

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> pubConnection;
    private StatefulRedisPubSubConnection<String, String> subConnection;
    private Thread subscriberThread;
    private ScheduledTask heartbeatTask;

    public CrossProxyService(BeaconLabsVelocity plugin, String proxyId, String sharedSecret, boolean enabled, boolean allowDoubleJoin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.logger = plugin.getLogger();
        this.proxyId = proxyId != null ? proxyId : "default";
        this.sharedSecret = sharedSecret != null ? sharedSecret : "";
        this.enabled = enabled;
        this.allowDoubleJoin = allowDoubleJoin;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getProxyId() {
        return proxyId;
    }

    /** When true, duplicate sessions are allowed (player can be on two proxies at once). */
    public boolean isAllowDoubleJoin() {
        return allowDoubleJoin;
    }

    /** Record that this player is on this proxy (for /info cross-proxy). */
    public void setPlayerProxy(UUID playerUuid, String onProxyId) {
        if (!enabled || pubConnection == null || onProxyId == null) return;
        try {
            pubConnection.sync().set(ONLINE_KEY_PREFIX + playerUuid.toString(), onProxyId);
        } catch (Exception e) {
            logger.debug("Failed to set online proxy for {}: {}", playerUuid, e.getMessage());
        }
    }

    /** Remove player from online proxy map (on disconnect). */
    public void removePlayerProxy(UUID playerUuid) {
        if (!enabled || pubConnection == null) return;
        try {
            pubConnection.sync().del(ONLINE_KEY_PREFIX + playerUuid.toString());
        } catch (Exception e) {
            logger.debug("Failed to remove online proxy for {}: {}", playerUuid, e.getMessage());
        }
    }

    /** Get which proxy this player is on (null if not on any proxy we know of, or if that proxy is dead). */
    public String getPlayerProxy(UUID playerUuid) {
        if (!enabled || pubConnection == null) return null;
        try {
            var sync = pubConnection.sync();
            String pid = sync.get(ONLINE_KEY_PREFIX + playerUuid.toString());
            if (pid == null || pid.isEmpty()) return null;
            Long hb = sync.exists(HEARTBEAT_KEY_PREFIX + pid);
            if (hb == null || hb == 0) {
                sync.del(ONLINE_KEY_PREFIX + playerUuid.toString());
                return null;
            }
            return pid;
        } catch (Exception e) {
            logger.debug("Failed to get online proxy for {}: {}", playerUuid, e.getMessage());
            return null;
        }
    }

    /** Register this proxy in the set of connected proxies (for /plist, /proxies) and set heartbeat. */
    public void registerProxy() {
        if (!enabled || pubConnection == null) return;
        try {
            var sync = pubConnection.sync();
            sync.sadd(PROXIES_SET, proxyId);
            refreshHeartbeat();
        } catch (Exception e) {
            logger.debug("Failed to register proxy: {}", e.getMessage());
        }
    }

    /** Refresh this proxy's heartbeat so others consider it alive. Call periodically and from updatePlayerList. */
    private void refreshHeartbeat() {
        if (!enabled || pubConnection == null) return;
        try {
            var sync = pubConnection.sync();
            sync.set(HEARTBEAT_KEY_PREFIX + proxyId, "1");
            sync.expire(HEARTBEAT_KEY_PREFIX + proxyId, HEARTBEAT_TTL_SECONDS);
        } catch (Exception e) {
            logger.debug("Failed to refresh heartbeat: {}", e.getMessage());
        }
    }

    /** Unregister this proxy on shutdown. */
    public void unregisterProxy() {
        if (!enabled || pubConnection == null) return;
        try {
            var sync = pubConnection.sync();
            sync.srem(PROXIES_SET, proxyId);
            sync.del(PLIST_KEY_PREFIX + proxyId);
            sync.del(HEARTBEAT_KEY_PREFIX + proxyId);
        } catch (Exception e) {
            logger.debug("Failed to unregister proxy: {}", e.getMessage());
        }
    }

    /** Update the player list for this proxy (call on join, leave, server switch). Format per entry: uuid:username:server for cross-proxy UUID lookup. */
    public void updatePlayerList() {
        if (!enabled || pubConnection == null) return;
        try {
            refreshHeartbeat();
            java.util.List<String> entries = new java.util.ArrayList<>();
            for (com.velocitypowered.api.proxy.Player p : server.getAllPlayers()) {
                String serverName = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("?");
                entries.add(p.getUniqueId().toString() + PLAYER_SERVER_PAIR_SEP + p.getUsername() + PLAYER_SERVER_PAIR_SEP + serverName);
            }
            String value = String.join(PLAYER_SERVER_SEP, entries);
            pubConnection.sync().set(PLIST_KEY_PREFIX + proxyId, value);
        } catch (Exception e) {
            logger.debug("Failed to update player list: {}", e.getMessage());
        }
    }

    /** Get all proxy IDs that are currently alive (heartbeat present). Dead proxies are excluded. */
    public java.util.Set<String> getProxyIds() {
        if (!enabled || pubConnection == null) return java.util.Collections.emptySet();
        try {
            var sync = pubConnection.sync();
            java.util.Set<String> all = sync.smembers(PROXIES_SET);
            java.util.Set<String> live = new java.util.HashSet<>();
            for (String id : all) {
                Long hb = sync.exists(HEARTBEAT_KEY_PREFIX + id);
                if (hb != null && hb > 0) {
                    live.add(id);
                }
            }
            return live;
        } catch (Exception e) {
            logger.debug("Failed to get proxy IDs: {}", e.getMessage());
            return java.util.Collections.emptySet();
        }
    }

    /** Get player list for a proxy: list of (playerName, serverName). Supports format "uuid:username:server" or legacy "username:server". */
    public java.util.List<java.util.Map.Entry<String, String>> getPlayerListForProxy(String proxyIdKey) {
        if (!enabled || pubConnection == null) return java.util.Collections.emptyList();
        try {
            String raw = pubConnection.sync().get(PLIST_KEY_PREFIX + proxyIdKey);
            if (raw == null || raw.isEmpty()) return java.util.Collections.emptyList();
            java.util.List<java.util.Map.Entry<String, String>> out = new java.util.ArrayList<>();
            for (String entry : raw.split(PLAYER_SERVER_SEP, -1)) {
                String[] parts = entry.split(PLAYER_SERVER_PAIR_SEP, 3);
                if (parts.length >= 3) {
                    out.add(new java.util.AbstractMap.SimpleEntry<>(parts[1], parts[2]));
                } else if (parts.length == 2) {
                    out.add(new java.util.AbstractMap.SimpleEntry<>(parts[0], parts[1]));
                }
            }
            return out;
        } catch (Exception e) {
            logger.debug("Failed to get player list for {}: {}", proxyIdKey, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /** Get UUID of an online player by name (case-insensitive) from any proxy's plist. Returns null if not found. */
    public UUID getPlayerUuidByName(String playerName) {
        if (playerName == null || playerName.isEmpty() || !enabled || pubConnection == null) return null;
        String lower = playerName.toLowerCase();
        try {
            for (String pid : getProxyIds()) {
                String raw = pubConnection.sync().get(PLIST_KEY_PREFIX + pid);
                if (raw == null || raw.isEmpty()) continue;
                for (String entry : raw.split(PLAYER_SERVER_SEP, -1)) {
                    String[] parts = entry.split(PLAYER_SERVER_PAIR_SEP, 3);
                    if (parts.length >= 3 && parts[1].equalsIgnoreCase(lower)) {
                        try {
                            return UUID.fromString(parts[0]);
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("getPlayerUuidByName failed: {}", e.getMessage());
        }
        return null;
    }

    /** Call after config is loaded. Connects and subscribes if enabled and config valid. */
    public void start(String host, int port, String password, int connectTimeoutMs, int reconnectIntervalMs) {
        if (!enabled) return;
        if (host == null || host.isEmpty()) {
            logger.warn("Cross-proxy Redis is enabled but redis.host is not set. Cross-proxy features disabled.");
            return;
        }
        if (sharedSecret == null || sharedSecret.isEmpty()) {
            logger.warn("Cross-proxy Redis is enabled but redis.shared-secret is empty. Set a secret for security. Cross-proxy disabled.");
            return;
        }

        RedisURI uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofMillis(connectTimeoutMs > 0 ? connectTimeoutMs : 5000))
                .build();
        if (password != null && !password.isEmpty()) {
            uri.setPassword(password);
        }

        try {
            redisClient = RedisClient.create(uri);
            pubConnection = redisClient.connect();
            subConnection = redisClient.connectPubSub();

            subConnection.addListener(new io.lettuce.core.pubsub.RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    if (!CHANNEL.equals(channel)) return;
                    handleIncoming(message);
                }
            });

            subscriberThread = new Thread(() -> {
                try {
                    RedisPubSubCommands<String, String> sync = subConnection.sync();
                    sync.subscribe(CHANNEL); // blocks until connection closed
                } catch (Exception e) {
                    if (!subConnection.isOpen()) return;
                    logger.warn("Redis subscriber ended: {}", e.getMessage());
                }
            }, "BeaconLabs-Redis-Subscriber");
            subscriberThread.setDaemon(true);
            subscriberThread.start();

            registerProxy();
            updatePlayerList();
            heartbeatTask = server.getScheduler().buildTask(plugin, this::refreshHeartbeat)
                    .repeat(HEARTBEAT_REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS)
                    .schedule();
            logger.info("Cross-proxy Redis connected (proxy-id: {}). Kick/ban/send and duplicate-session prevention are active.", proxyId);
        } catch (Exception e) {
            logger.error("Failed to connect to Redis for cross-proxy. Cross-proxy features disabled.", e);
            shutdown();
        }
    }

    public void shutdown() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        unregisterProxy();
        if (subscriberThread != null && subscriberThread.isAlive()) {
            subscriberThread.interrupt();
            subscriberThread = null;
        }
        if (subConnection != null) {
            try { subConnection.close(); } catch (Exception ignored) { }
            subConnection = null;
        }
        if (pubConnection != null) {
            try { pubConnection.close(); } catch (Exception ignored) { }
            pubConnection = null;
        }
        if (redisClient != null) {
            try { redisClient.shutdown(0, 0, TimeUnit.SECONDS); } catch (Exception ignored) { }
            redisClient = null;
        }
    }

    private void handleIncoming(String raw) {
        CrossProxyMessage msg = CrossProxyMessage.parse(raw);
        if (msg == null) return;
        if (!sharedSecret.equals(msg.getSecret())) {
            logger.debug("Ignoring cross-proxy message with invalid secret.");
            return;
        }

        // Run on Velocity main thread
        server.getScheduler().buildTask(plugin, () -> {
            try {
                switch (msg.getType()) {
                    case KICK:
                        handleKick(msg);
                        break;
                    case KICK_BY_NAME:
                        handleKickByName(msg);
                        break;
                    case SENDALL:
                        handleSendAll(msg);
                        break;
                    case PLAYER_CONNECT:
                        handlePlayerConnect(msg);
                        break;
                    case SEND_PLAYER:
                        handleSendPlayer(msg);
                        break;
                    case MUTE_APPLIED:
                        handleMuteApplied(msg);
                        break;
                    case PRIVATE_MSG:
                        handlePrivateMsg(msg);
                        break;
                    case BROADCAST:
                        handleBroadcast(msg);
                        break;
                    case TEAM_CHAT:
                        handleTeamChat(msg);
                        break;
                    case CHATREPORT_RESULT:
                        handleChatReportResult(msg);
                        break;
                    case CHATREPORT_REQUEST:
                        handleChatReportRequest(msg);
                        break;
                    case MAINTENANCE_SET:
                        handleMaintenanceSet(msg);
                        break;
                    case WHITELIST_SET:
                        handleWhitelistSet(msg);
                        break;
                    case JOINME_TO_PLAYER:
                        handleJoinMeToPlayer(msg);
                        break;
                    case JOINME_BROADCAST:
                        handleJoinMeBroadcast(msg);
                        break;
                    case REPORT_NOTIFY:
                        handleReportNotify(msg);
                        break;
                    case BADWORD_ALERT:
                        handleBadWordAlert(msg);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                logger.warn("Error handling cross-proxy message: {}", e.getMessage());
            }
        }).schedule();
    }

    private void handleKick(CrossProxyMessage msg) {
        if (proxyId != null && proxyId.equals(msg.getProxyId())) return; // sender proxy already kicked locally
        UUID uuid = msg.getUuidAsUUID();
        if (uuid == null) return;
        server.getPlayer(uuid).ifPresent(player -> {
            Component reason = msg.getReason() != null && !msg.getReason().isEmpty()
                    ? LegacyComponentSerializer.legacyAmpersand().deserialize(msg.getReason())
                    : Component.text("Kicked from the network.");
            player.disconnect(reason);
            logger.debug("Kicked player {} on cross-proxy request.", player.getUsername());
        });
    }

    private void handleKickByName(CrossProxyMessage msg) {
        String name = msg.getUsername();
        if (name == null || name.isEmpty()) return;
        server.getPlayer(name).ifPresent(player -> {
            Component reason = msg.getReason() != null && !msg.getReason().isEmpty()
                    ? LegacyComponentSerializer.legacyAmpersand().deserialize(msg.getReason())
                    : Component.text("Kicked from the network.");
            player.disconnect(reason);
            logger.debug("Kicked player {} on cross-proxy kick-by-name.", player.getUsername());
        });
    }

    private void handleSendAll(CrossProxyMessage msg) {
        String serverName = msg.getServerName();
        if (serverName == null || serverName.isEmpty()) return;
        Optional<RegisteredServer> target = server.getServer(serverName);
        if (target.isEmpty()) return;
        RegisteredServer rs = target.get();
        server.getAllPlayers().forEach(player ->
                player.createConnectionRequest(rs).connectWithIndication());
    }

    private void handlePlayerConnect(CrossProxyMessage msg) {
        if (allowDoubleJoin) return; // allow same account on multiple proxies
        if (proxyId != null && proxyId.equals(msg.getProxyId())) return; // don't kick ourselves
        UUID uuid = msg.getUuidAsUUID();
        if (uuid == null) return;
        server.getPlayer(uuid).ifPresent(player -> {
            player.disconnect(Component.text("You logged in from another location."));
            logger.debug("Kicked duplicate session for {} (now on proxy {}).", player.getUsername(), msg.getProxyId());
        });
    }

    private void handleSendPlayer(CrossProxyMessage msg) {
        UUID uuid = msg.getUuidAsUUID();
        if (uuid == null || msg.getServerName() == null) return;
        Optional<RegisteredServer> target = server.getServer(msg.getServerName());
        if (target.isEmpty()) return;
        server.getPlayer(uuid).ifPresent(player ->
                player.createConnectionRequest(target.get()).connectWithIndication());
    }

    private void handleMuteApplied(CrossProxyMessage msg) {
        UUID uuid = msg.getUuidAsUUID();
        if (uuid == null) return;
        server.getPlayer(uuid).ifPresent(player -> {
            String reason = msg.getReason() != null && !msg.getReason().isEmpty() ? msg.getReason() : "No reason specified";
            String duration = msg.getDurationFormatted() != null && !msg.getDurationFormatted().isEmpty() ? msg.getDurationFormatted() : "Permanent";
            Component comp = LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&c&lYou have been muted. &7Duration: &f" + duration + " &7| Reason: &f" + reason);
            player.sendMessage(plugin.getPrefix().append(comp));
        });
    }

    private void handlePrivateMsg(CrossProxyMessage msg) {
        String targetUsername = msg.getUsername();
        if (targetUsername == null || targetUsername.isEmpty()) return;
        String legacy = msg.getReason();
        if (legacy == null) return;
        server.getPlayer(targetUsername).ifPresent(player -> {
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(legacy));
            if (plugin.getMessageService() != null && msg.getUuidAsUUID() != null && msg.getServerName() != null && !msg.getServerName().isEmpty()) {
                plugin.getMessageService().setLastMessageSenderForReply(player.getUniqueId(), msg.getUuidAsUUID(), msg.getServerName());
            }
        });
    }

    private void handleBroadcast(CrossProxyMessage msg) {
        String legacy = msg.getReason();
        if (legacy == null) return;
        Component comp = LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
        server.getAllPlayers().forEach(p -> p.sendMessage(comp));
    }

    private void handleTeamChat(CrossProxyMessage msg) {
        String legacy = msg.getReason();
        if (legacy == null) return;
        Component comp = LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
        server.getAllPlayers().stream()
                .filter(p -> p.hasPermission("beaconlabs.teamchat"))
                .forEach(p -> p.sendMessage(comp));
    }

    private void handleChatReportResult(CrossProxyMessage msg) {
        String reporterName = msg.getUsername();
        String targetName = msg.getServerName();
        String pasteLink = msg.getReason();
        if (pasteLink == null || pasteLink.isEmpty()) return;
        Component linkMessage = Component.text()
                .append(plugin.getPrefix())
                .append(Component.text("Chat log for ", NamedTextColor.WHITE))
                .append(Component.text(targetName != null ? targetName : "?", NamedTextColor.GOLD))
                .append(Component.text(" (reported by ", NamedTextColor.WHITE))
                .append(Component.text(reporterName != null ? reporterName : "?", NamedTextColor.GRAY))
                .append(Component.text(") has been uploaded. ", NamedTextColor.WHITE))
                .append(Component.text("[Click here to view]", NamedTextColor.BLUE)
                        .clickEvent(ClickEvent.openUrl(pasteLink)))
                .build();
        // Only skip reporter when this proxy did the report (avoid duplicate for reporter); when report came from another proxy, reporter must get the link here
        final boolean skipReporter = (msg.getProxyId() != null && msg.getProxyId().equals(proxyId))
                && reporterName != null && !reporterName.isEmpty();
        server.getAllPlayers().stream()
                .filter(p -> p.hasPermission("beaconlabs.chat.chatreport"))
                .filter(p -> !skipReporter || !reporterName.equalsIgnoreCase(p.getUsername()))
                .forEach(p -> p.sendMessage(linkMessage));
    }

    private void publish(String message) {
        if (!enabled || pubConnection == null) return;
        try {
            pubConnection.async().publish(CHANNEL, message);
        } catch (Exception e) {
            logger.warn("Failed to publish cross-proxy message: {}", e.getMessage());
        }
    }

    public void publishKick(UUID uuid, String reason) {
        publish(CrossProxyMessage.kick(uuid, reason, sharedSecret, proxyId));
    }

    /** Ask other proxies to kick a player by name (used when player is not on this proxy). */
    public void publishKickByName(String username, String reason) {
        publish(CrossProxyMessage.kickByName(username, reason, sharedSecret, proxyId));
    }

    public void publishSendAll(String serverName) {
        publish(CrossProxyMessage.sendAll(serverName, sharedSecret, proxyId));
    }

    public void publishPlayerConnect(UUID uuid) {
        publish(CrossProxyMessage.playerConnect(proxyId, uuid, sharedSecret));
    }

    public void publishSendPlayer(UUID uuid, String serverName) {
        publish(CrossProxyMessage.sendPlayer(uuid, serverName, sharedSecret, proxyId));
    }

    public void publishMuteApplied(UUID uuid, String reason, String durationFormatted) {
        publish(CrossProxyMessage.muteApplied(uuid, reason, durationFormatted, sharedSecret, proxyId));
    }

    public void publishPrivateMsg(String targetUsername, String senderUuid, String senderUsername, String recipientMessageLegacy) {
        publish(CrossProxyMessage.privateMsg(targetUsername, senderUuid != null ? senderUuid : "", senderUsername, recipientMessageLegacy, sharedSecret, proxyId));
    }

    public void publishBroadcast(String messageLegacy) {
        publish(CrossProxyMessage.broadcast(messageLegacy, sharedSecret, proxyId));
    }

    public void publishTeamChat(String messageLegacy) {
        publish(CrossProxyMessage.teamChat(messageLegacy, sharedSecret, proxyId));
    }

    public void publishChatReportResult(String reporterName, String targetName, String pasteLink) {
        publish(CrossProxyMessage.chatReportResult(reporterName, targetName, pasteLink, sharedSecret, proxyId));
    }

    public void publishChatReportRequest(UUID targetUuid, String targetUsername, String reporterUsername) {
        publish(CrossProxyMessage.chatReportRequest(targetUuid != null ? targetUuid.toString() : "", targetUsername, reporterUsername, sharedSecret, proxyId));
    }

    private void handleChatReportRequest(CrossProxyMessage msg) {
        UUID targetUuid = msg.getUuidAsUUID();
        if (targetUuid == null) return;
        if (!server.getPlayer(targetUuid).isPresent()) return; // player not on this proxy
        String targetUsername = msg.getServerName();
        String reporterUsername = msg.getUsername();
        if (targetUsername == null) targetUsername = "Unknown";
        if (reporterUsername == null) reporterUsername = "Unknown";
        plugin.performChatReportForPlayer(targetUuid, targetUsername, reporterUsername);
    }

    private void handleMaintenanceSet(CrossProxyMessage msg) {
        boolean enable = "true".equalsIgnoreCase(msg.getServerName());
        String broadcastLegacy = msg.getReason();
        boolean isOriginator = msg.getProxyId() != null && msg.getProxyId().equals(proxyId);

        if (plugin.getMaintenanceService() == null) return;

        if (enable) {
            if (broadcastLegacy != null && !broadcastLegacy.isEmpty() && !isOriginator) {
                Component comp = LegacyComponentSerializer.legacyAmpersand().deserialize(broadcastLegacy);
                server.getAllPlayers().forEach(p -> p.sendMessage(comp));
            }
            // All proxies (including originator) run the countdown so the mid-screen alert shows everywhere
            plugin.getMaintenanceService().runRemoteMaintenanceCountdown(null);
        } else {
            plugin.getMaintenanceService().setMaintenanceFromRemote(false);
            if (!isOriginator && broadcastLegacy != null && !broadcastLegacy.isEmpty()) {
                Component comp = LegacyComponentSerializer.legacyAmpersand().deserialize(broadcastLegacy);
                server.getAllPlayers().forEach(p -> p.sendMessage(comp));
            }
        }
    }

    public void publishMaintenanceSet(boolean enabled, String broadcastMessageLegacy) {
        publish(CrossProxyMessage.maintenanceSet(enabled, broadcastMessageLegacy, sharedSecret, proxyId));
    }

    private void handleWhitelistSet(CrossProxyMessage msg) {
        if (msg.getProxyId() != null && msg.getProxyId().equals(proxyId)) return; // we are the originator
        if (plugin.getWhitelistService() == null) return;
        boolean enable = "true".equalsIgnoreCase(msg.getServerName());
        plugin.getWhitelistService().setWhitelistEnabledFromRemote(enable);
    }

    public void publishWhitelistSet(boolean enabled) {
        publish(CrossProxyMessage.whitelistSet(enabled, sharedSecret, proxyId));
    }

    /** Server name the player is on, or null if not found on any proxy. */
    public String getPlayerCurrentServer(String playerName) {
        if (playerName == null || playerName.isEmpty() || !enabled || pubConnection == null) return null;
        String lower = playerName.toLowerCase();
        for (String pid : getProxyIds()) {
            for (java.util.Map.Entry<String, String> e : getPlayerListForProxy(pid)) {
                if (e.getKey() != null && e.getKey().toLowerCase().equals(lower)) return e.getValue();
            }
        }
        return null;
    }

    /** All online player names across proxies (for suggestions etc.). */
    public java.util.Set<String> getOnlinePlayerNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (com.velocitypowered.api.proxy.Player p : server.getAllPlayers()) {
            names.add(p.getUsername());
        }
        if (!enabled || pubConnection == null) return names;
        for (String pid : getProxyIds()) {
            for (java.util.Map.Entry<String, String> e : getPlayerListForProxy(pid)) {
                if (e.getKey() != null && !e.getKey().isEmpty()) names.add(e.getKey());
            }
        }
        return names;
    }

    /** Total online player count across all proxies. */
    public int getTotalPlayerCount() {
        if (!enabled || pubConnection == null) return server.getPlayerCount();
        int total = 0;
        for (String pid : getProxyIds()) {
            total += getPlayerListForProxy(pid).size();
        }
        return total;
    }

    private void handleJoinMeToPlayer(CrossProxyMessage msg) {
        String targetUsername = msg.getUsername();
        // Parse: reason=serverName, serverName=senderUsername (see CrossProxyMessage parse)
        String senderUsername = msg.getServerName();
        String serverName = msg.getReason();
        if (targetUsername == null || targetUsername.isEmpty() || serverName == null) return;
        Component joinMe = buildJoinMeComponent(senderUsername != null ? senderUsername : "?", serverName);
        server.getPlayer(targetUsername).ifPresent(player -> player.sendMessage(joinMe));
    }

    private void handleJoinMeBroadcast(CrossProxyMessage msg) {
        if (msg.getProxyId() != null && msg.getProxyId().equals(proxyId)) return; // originator already sent to local players
        // Parse: reason=serverName, serverName=senderUsername
        String senderUsername = msg.getServerName();
        String serverName = msg.getReason();
        if (serverName == null) return;
        Component joinMe = buildJoinMeComponent(senderUsername != null ? senderUsername : "?", serverName);
        server.getAllPlayers().forEach(p -> p.sendMessage(joinMe));
    }

    private void handleReportNotify(CrossProxyMessage msg) {
        if (msg.getProxyId() != null && msg.getProxyId().equals(proxyId)) return; // originator already notified local staff
        String legacy = msg.getReason();
        if (legacy == null || legacy.isEmpty()) return;
        Component notification = LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
        server.getAllPlayers().stream()
                .filter(p -> p.hasPermission("beaconlabs.reports.notify"))
                .forEach(p -> p.sendMessage(notification));
    }

    private void handleBadWordAlert(CrossProxyMessage msg) {
        if (msg.getProxyId() != null && msg.getProxyId().equals(proxyId)) return; // originator already notified local admins
        String playerName = msg.getUsername();
        String message = msg.getReason();
        String badWord = msg.getServerName();
        if (playerName == null && message == null && badWord == null) return;
        Component notification = org.bcnlab.beaconLabsVelocity.listener.ChatFilterListener.buildBadWordAlertComponent(playerName, message, badWord);
        server.getAllPlayers().stream()
                .filter(p -> p.hasPermission("beaconlabs.chatfilter.alert"))
                .forEach(p -> p.sendMessage(notification));
    }

    private static Component buildJoinMeComponent(String senderUsername, String serverName) {
        Component border = Component.text("▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬", NamedTextColor.GOLD, TextDecoration.BOLD);
        Component header = Component.text("  ✦ JOIN ME INVITATION ✦  ", NamedTextColor.YELLOW, TextDecoration.BOLD);
        Component playerComponent = Component.text(senderUsername, NamedTextColor.AQUA, TextDecoration.BOLD);
        Component serverComponent = Component.text(serverName, NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/server " + serverName))
                .hoverEvent(HoverEvent.showText(Component.text("Click to join " + serverName, NamedTextColor.YELLOW)));
        return Component.empty()
                .append(Component.newline())
                .append(border).append(Component.newline())
                .append(header).append(Component.newline())
                .append(Component.text("Player: ", NamedTextColor.YELLOW)).append(playerComponent).append(Component.newline())
                .append(Component.text("Server: ", NamedTextColor.YELLOW)).append(serverComponent).append(Component.newline())
                .append(Component.text("Click on the server name to join!", NamedTextColor.GRAY, TextDecoration.ITALIC)).append(Component.newline())
                .append(border);
    }

    public void publishJoinMeToPlayer(String targetUsername, String senderUsername, String serverName) {
        publish(CrossProxyMessage.joinMeToPlayer(targetUsername, senderUsername, serverName, sharedSecret, proxyId));
    }

    public void publishJoinMeBroadcast(String senderUsername, String serverName) {
        publish(CrossProxyMessage.joinMeBroadcast(senderUsername, serverName, sharedSecret, proxyId));
    }

    public void publishReportNotify(String notificationLegacy) {
        publish(CrossProxyMessage.reportNotify(notificationLegacy, sharedSecret, proxyId));
    }

    public void publishBadWordAlert(String playerName, String messageContent, String badWord) {
        publish(CrossProxyMessage.badWordAlert(playerName, messageContent, badWord, sharedSecret, proxyId));
    }
}
