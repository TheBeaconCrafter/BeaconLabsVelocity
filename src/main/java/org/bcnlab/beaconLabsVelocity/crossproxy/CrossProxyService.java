package org.bcnlab.beaconLabsVelocity.crossproxy;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import net.kyori.adventure.text.Component;
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

    /** Get which proxy this player is on (null if not on any proxy we know of). */
    public String getPlayerProxy(UUID playerUuid) {
        if (!enabled || pubConnection == null) return null;
        try {
            return pubConnection.sync().get(ONLINE_KEY_PREFIX + playerUuid.toString());
        } catch (Exception e) {
            logger.debug("Failed to get online proxy for {}: {}", playerUuid, e.getMessage());
            return null;
        }
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

            logger.info("Cross-proxy Redis connected (proxy-id: {}). Kick/ban/send and duplicate-session prevention are active.", proxyId);
        } catch (Exception e) {
            logger.error("Failed to connect to Redis for cross-proxy. Cross-proxy features disabled.", e);
            shutdown();
        }
    }

    public void shutdown() {
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
        server.getPlayer(targetUsername).ifPresent(player ->
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(legacy)));
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

    public void publishPrivateMsg(String targetUsername, String recipientMessageLegacy) {
        publish(CrossProxyMessage.privateMsg(targetUsername, recipientMessageLegacy, sharedSecret, proxyId));
    }

    public void publishBroadcast(String messageLegacy) {
        publish(CrossProxyMessage.broadcast(messageLegacy, sharedSecret, proxyId));
    }

    public void publishTeamChat(String messageLegacy) {
        publish(CrossProxyMessage.teamChat(messageLegacy, sharedSecret, proxyId));
    }
}
