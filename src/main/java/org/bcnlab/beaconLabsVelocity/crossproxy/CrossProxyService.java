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
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import com.velocitypowered.api.proxy.Player;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bcnlab.beaconLabsVelocity.util.ColorParser;

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
    private static final String PROXY_HOST_KEY_PREFIX = "blv:proxyhost:";
    private static final String TRANSFER_PENDING_KEY_PREFIX = "blv:transfer:";
    private static final String STAFF_KEY_PREFIX = "blv:staff:";
    private static final String PREFIX_HASH_KEY = "blv:prefixes";
    private static final int HEARTBEAT_TTL_SECONDS = 90;
    /** TTL for pending transfer (player reconnected to backend after cross-proxy transfer). */
    private static final int TRANSFER_PENDING_TTL_SECONDS = 60;
    private static final int HEARTBEAT_REFRESH_INTERVAL_SECONDS = 20;
    /** TTL for plist key so dead proxies disappear; also used as fallback "live" signal when heartbeat is delayed (e.g. Redis replication). */
    private static final int PLIST_TTL_SECONDS = 120;
    private static final String PLAYER_SERVER_SEP = "\u001E";
    private static final String PLAYER_SERVER_PAIR_SEP = ":";

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final String proxyId;
    private final String sharedSecret;
    private final String publicHostname;
    private final boolean enabled;
    private final boolean allowDoubleJoin;

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> pubConnection;
    private StatefulRedisPubSubConnection<String, String> subConnection;
    private Thread subscriberThread;
    private ScheduledTask heartbeatTask;
    private ScheduledTask snapshotTask;
    private final java.util.Map<String, PendingPing> pendingPings = new ConcurrentHashMap<>();
    private final AtomicBoolean snapshotRefreshInProgress = new AtomicBoolean();

    private static final class PendingPing {
        private final CompletableFuture<Long> future;
        private volatile ScheduledTask timeoutTask;

        private PendingPing(CompletableFuture<Long> future) {
            this.future = future;
        }
    }

    private volatile CrossProxySnapshot snapshot = CrossProxySnapshot.empty();

    private static final class CrossProxySnapshot {
        private final java.util.Set<String> proxyIds;
        private final java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> playerLists;
        private final java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> staffLists;
        private final java.util.Map<String, UUID> playerUuidsByName;
        private final java.util.Map<UUID, String> proxyByPlayerUuid;
        private final java.util.Map<String, String> playerServersByName;
        private final java.util.Map<String, String> proxyHostnames;
        private final java.util.Map<String, String> prefixes;

        private CrossProxySnapshot(
                java.util.Set<String> proxyIds,
                java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> playerLists,
                java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> staffLists,
                java.util.Map<String, UUID> playerUuidsByName,
                java.util.Map<UUID, String> proxyByPlayerUuid,
                java.util.Map<String, String> playerServersByName,
                java.util.Map<String, String> proxyHostnames,
                java.util.Map<String, String> prefixes) {
            this.proxyIds = proxyIds;
            this.playerLists = playerLists;
            this.staffLists = staffLists;
            this.playerUuidsByName = playerUuidsByName;
            this.proxyByPlayerUuid = proxyByPlayerUuid;
            this.playerServersByName = playerServersByName;
            this.proxyHostnames = proxyHostnames;
            this.prefixes = prefixes;
        }

        private static CrossProxySnapshot empty() {
            return new CrossProxySnapshot(
                    java.util.Collections.emptySet(), java.util.Collections.emptyMap(),
                    java.util.Collections.emptyMap(), java.util.Collections.emptyMap(),
                    java.util.Collections.emptyMap(), java.util.Collections.emptyMap(),
                    java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
        }
    }

    public CrossProxyService(BeaconLabsVelocity plugin, String proxyId, String sharedSecret, String publicHostname, boolean enabled, boolean allowDoubleJoin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.logger = plugin.getLogger();
        this.proxyId = proxyId != null ? proxyId : "default";
        this.sharedSecret = sharedSecret != null ? sharedSecret : "";
        this.publicHostname = publicHostname != null ? publicHostname.trim() : "";
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
            pubConnection.async().set(ONLINE_KEY_PREFIX + playerUuid.toString(), onProxyId);
        } catch (Exception e) {
            logger.debug("Failed to set online proxy for {}: {}", playerUuid, e.getMessage());
        }
    }

    /** Remove player from online proxy map (on disconnect). */
    public void removePlayerProxy(UUID playerUuid) {
        if (!enabled || pubConnection == null) return;
        try {
            pubConnection.async().del(ONLINE_KEY_PREFIX + playerUuid.toString());
        } catch (Exception e) {
            logger.debug("Failed to remove online proxy for {}: {}", playerUuid, e.getMessage());
        }
    }

    /** Get which proxy this player is on without a command-time Redis round-trip. */
    public String getPlayerProxy(UUID playerUuid) {
        if (!enabled || pubConnection == null || playerUuid == null) return null;
        if (server.getPlayer(playerUuid).isPresent()) return proxyId;
        String pid = snapshot.proxyByPlayerUuid.get(playerUuid);
        return pid != null && snapshot.proxyIds.contains(pid) ? pid : null;
    }

    /** Register this proxy in the set of connected proxies (for /plist, /proxies) and set heartbeat. */
    public void registerProxy() {
        if (!enabled || pubConnection == null) return;
        try {
            var async = pubConnection.async();
            async.sadd(PROXIES_SET, proxyId);
            refreshHeartbeat();
            refreshProxyHostname();
        } catch (Exception e) {
            logger.debug("Failed to register proxy: {}", e.getMessage());
        }
    }

    /** Refresh this proxy's heartbeat so others consider it alive. Call periodically and from updatePlayerList. */
    private void refreshHeartbeat() {
        if (!enabled || pubConnection == null) return;
        try {
            pubConnection.async().setex(HEARTBEAT_KEY_PREFIX + proxyId, HEARTBEAT_TTL_SECONDS, "1");
        } catch (Exception e) {
            logger.debug("Failed to refresh heartbeat: {}", e.getMessage());
        }
    }

    /** Store this proxy's public hostname in Redis (for /proxies send transfer). */
    private void refreshProxyHostname() {
        if (!enabled || pubConnection == null || publicHostname.isEmpty()) return;
        try {
            pubConnection.async().setex(PROXY_HOST_KEY_PREFIX + proxyId, HEARTBEAT_TTL_SECONDS, publicHostname);
        } catch (Exception e) {
            logger.debug("Failed to set proxy hostname: {}", e.getMessage());
        }
    }

    /** Get public hostname for a proxy (for transfer). Returns null if not set or proxy unknown. */
    public String getProxyHostname(String targetProxyId) {
        if (!enabled || pubConnection == null || targetProxyId == null || targetProxyId.isEmpty()) return null;
        return snapshot.proxyHostnames.get(targetProxyId);
    }

    /** Set pending transfer: when this player connects to targetProxyId, send them to serverName. TTL 60s. */
    public void setPendingTransfer(String targetProxyId, UUID playerUuid, String serverName) {
        if (!enabled || pubConnection == null || targetProxyId == null || playerUuid == null || serverName == null || serverName.isEmpty()) return;
        try {
            pubConnection.sync().setex(TRANSFER_PENDING_KEY_PREFIX + targetProxyId + ":" + playerUuid.toString(), TRANSFER_PENDING_TTL_SECONDS, serverName);
        } catch (Exception e) {
            logger.debug("Failed to set pending transfer: {}", e.getMessage());
        }
    }

    /** Get and remove pending transfer for this player on this proxy. Returns server name to connect to, or null. */
    public String getAndClearPendingTransfer(UUID playerUuid) {
        if (!enabled || pubConnection == null || playerUuid == null) return null;
        try {
            String key = TRANSFER_PENDING_KEY_PREFIX + proxyId + ":" + playerUuid.toString();
            String serverName = pubConnection.sync().get(key);
            if (serverName != null && !serverName.isEmpty()) {
                pubConnection.sync().del(key);
                return serverName;
            }
        } catch (Exception e) {
            logger.debug("Failed to get pending transfer: {}", e.getMessage());
        }
        return null;
    }

    /** Unregister this proxy on shutdown. */
    public void unregisterProxy() {
        if (!enabled || pubConnection == null) return;
        try {
            var sync = pubConnection.sync();
            sync.srem(PROXIES_SET, proxyId);
            sync.del(PLIST_KEY_PREFIX + proxyId);
            sync.del(STAFF_KEY_PREFIX + proxyId);
            sync.del(HEARTBEAT_KEY_PREFIX + proxyId);
        } catch (Exception e) {
            logger.debug("Failed to unregister proxy: {}", e.getMessage());
        }
    }

    /** Update the player list for this proxy (call on join, leave, server switch). Format per entry: uuid:username:server for cross-proxy UUID lookup. Also syncs LuckPerms prefixes and staff set for /staff cross-proxy. */
    public void updatePlayerList() {
        if (!enabled || pubConnection == null) return;
        try {
            refreshHeartbeat();
            var async = pubConnection.async();
            java.util.List<String> entries = new java.util.ArrayList<>();
            java.util.Set<String> staffNames = new java.util.HashSet<>();
            for (com.velocitypowered.api.proxy.Player p : server.getAllPlayers()) {
                String serverName = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("?");
                entries.add(p.getUniqueId().toString() + PLAYER_SERVER_PAIR_SEP + p.getUsername() + PLAYER_SERVER_PAIR_SEP + serverName);
                if (p.hasPermission("beaconlabs.visual.staff")) {
                    staffNames.add(p.getUsername().toLowerCase());
                }
                try {
                    String prefix = getPlayerLuckPermsPrefix(p.getUniqueId());
                    if (prefix != null && !prefix.isEmpty()) {
                        async.hset(PREFIX_HASH_KEY, p.getUsername().toLowerCase(), prefix);
                    } else {
                        async.hdel(PREFIX_HASH_KEY, p.getUsername().toLowerCase());
                    }
                } catch (Exception ignored) {
                    // Don't let prefix sync failure block plist update
                }
            }
            String value = String.join(PLAYER_SERVER_SEP, entries);
            async.setex(PLIST_KEY_PREFIX + proxyId, PLIST_TTL_SECONDS, value);
            String staffValue = String.join(PLAYER_SERVER_SEP, staffNames);
            async.setex(STAFF_KEY_PREFIX + proxyId, PLIST_TTL_SECONDS, staffValue);
            // Notify peers after the writes on this Redis connection so their snapshots refresh immediately.
            publish(CrossProxyMessage.playerListUpdated(proxyId, sharedSecret));
        } catch (Exception e) {
            logger.debug("Failed to update player list: {}", e.getMessage());
        }
    }

    /** Get a player's LuckPerms prefix without reflection. */
    private String getPlayerLuckPermsPrefix(UUID playerUuid) {
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(playerUuid);
            if (user == null) return "";
            String prefix = user.getCachedData().getMetaData().getPrefix();
            return prefix != null ? prefix : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Get a player's prefix from the periodically refreshed Redis snapshot. */
    public String getPlayerPrefix(String playerName) {
        if (playerName == null || playerName.isEmpty() || !enabled || pubConnection == null) return "";
        return snapshot.prefixes.getOrDefault(playerName.toLowerCase(), "");
    }

    /** Remove a player's prefix from the shared Redis hash. */
    public void removePlayerPrefix(String playerName) {
        if (playerName == null || playerName.isEmpty() || !enabled || pubConnection == null) return;
        try {
            pubConnection.async().hdel(PREFIX_HASH_KEY, playerName.toLowerCase());
        } catch (Exception e) {
            logger.debug("Failed to remove prefix for {}: {}", playerName, e.getMessage());
        }
    }

    /** Returns lines of debug info for /proxies debug (Redis state, proxy discovery, plist/heartbeat per proxy). */
    public java.util.List<String> getDebugInfo() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!enabled) {
            out.add("[Cross-proxy] Enabled: false");
            return out;
        }
        out.add("[Cross-proxy] Enabled: true");
        out.add("[Cross-proxy] This proxy ID: " + proxyId);
        out.add("[Cross-proxy] Local player count: " + server.getAllPlayers().size());
        if (pubConnection == null) {
            out.add("[Cross-proxy] Redis: not connected");
            return out;
        }
        try {
            var sync = pubConnection.sync();
            out.add("[Cross-proxy] Redis: connected");
            java.util.Set<String> allInSet = sync.smembers(PROXIES_SET);
            out.add("[Cross-proxy] PROXIES_SET (blv:proxies) size: " + allInSet.size() + " -> " + String.join(", ", allInSet));
            java.util.Set<String> live = getProxyIds();
            out.add("[Cross-proxy] Live proxy IDs (used for /plist, tab completion): " + live.size() + " -> " + String.join(", ", live));
            out.add("[Cross-proxy] Total online names (getOnlinePlayerNames): " + getOnlinePlayerNames().size());
            for (String id : allInSet) {
                if (id == null || id.isEmpty()) continue;
                Long hb = sync.exists(HEARTBEAT_KEY_PREFIX + id);
                Long plist = sync.exists(PLIST_KEY_PREFIX + id);
                int plistSize = getPlayerListForProxy(id).size();
                boolean inLive = live.contains(id);
                String self = id.equals(proxyId) ? " (this proxy)" : "";
                out.add("  [" + id + "]" + self + " heartbeat=" + (hb != null && hb > 0) + " plist_key=" + (plist != null && plist > 0) + " plist_players=" + plistSize + " in_live_list=" + inLive);
            }
        } catch (Exception e) {
            out.add("[Cross-proxy] Redis error: " + e.getMessage());
        }
        return out;
    }

    /** Get all proxy IDs that are currently alive from the local snapshot. */
    public java.util.Set<String> getProxyIds() {
        if (!enabled || pubConnection == null) return java.util.Collections.emptySet();
        return snapshot.proxyIds;
    }

    /** Get player list for a proxy from the local snapshot. */
    public java.util.List<java.util.Map.Entry<String, String>> getPlayerListForProxy(String proxyIdKey) {
        if (!enabled || pubConnection == null || proxyIdKey == null) return java.util.Collections.emptyList();
        return snapshot.playerLists.getOrDefault(proxyIdKey, java.util.Collections.emptyList());
    }

    /** Get staff list for a proxy from the local snapshot. */
    public java.util.List<java.util.Map.Entry<String, String>> getStaffListForProxy(String proxyIdKey) {
        if (!enabled || pubConnection == null || proxyIdKey == null) return java.util.Collections.emptyList();
        return snapshot.staffLists.getOrDefault(proxyIdKey, java.util.Collections.emptyList());
    }

    /** Get UUID of an online player by name from the local snapshot. */
    public UUID getPlayerUuidByName(String playerName) {
        if (playerName == null || playerName.isEmpty() || !enabled || pubConnection == null) return null;
        return snapshot.playerUuidsByName.get(playerName.toLowerCase());
    }

    /** Start a snapshot refresh away from Velocity's main thread. */
    private void requestRemoteSnapshotRefresh() {
        if (!enabled || pubConnection == null || !snapshotRefreshInProgress.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(this::refreshRemoteSnapshotNow)
                .whenComplete((ignored, error) -> snapshotRefreshInProgress.set(false));
    }

    private void refreshRemoteSnapshotNow() {
        if (!enabled || pubConnection == null) return;
        try {
            var sync = pubConnection.sync();
            java.util.Set<String> ids = new java.util.HashSet<>(sync.smembers(PROXIES_SET));
            ids.add(proxyId);
            java.util.List<String> proxyList = new java.util.ArrayList<>(ids);
            String[] plistKeys = proxyList.stream().map(id -> PLIST_KEY_PREFIX + id).toArray(String[]::new);
            String[] heartbeatKeys = proxyList.stream().map(id -> HEARTBEAT_KEY_PREFIX + id).toArray(String[]::new);
            String[] hostnameKeys = proxyList.stream().map(id -> PROXY_HOST_KEY_PREFIX + id).toArray(String[]::new);
            java.util.Map<String, String> rawHostnames = new java.util.HashMap<>();
            java.util.Map<String, String> rawPlayerLists = new java.util.HashMap<>();
            java.util.Set<String> heartbeatPresent = new java.util.HashSet<>();
            for (var value : sync.mget(hostnameKeys)) {
                if (value.hasValue() && !value.getValue().isEmpty()) rawHostnames.put(value.getKey().substring(PROXY_HOST_KEY_PREFIX.length()), value.getValue());
            }
            for (var value : sync.mget(plistKeys)) {
                if (value.hasValue()) rawPlayerLists.put(value.getKey(), value.getValue());
            }
            for (var value : sync.mget(heartbeatKeys)) {
                if (value.hasValue()) heartbeatPresent.add(value.getKey());
            }

            java.util.Set<String> live = new java.util.HashSet<>();
            java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> players = new java.util.HashMap<>();
            java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> staff = new java.util.HashMap<>();
            java.util.Map<String, UUID> uuids = new java.util.HashMap<>();
            java.util.Map<UUID, String> proxyByUuid = new java.util.HashMap<>();
            java.util.Map<String, String> servers = new java.util.HashMap<>();
            for (String id : proxyList) {
                String raw = rawPlayerLists.get(PLIST_KEY_PREFIX + id);
                if (id.equals(proxyId) || heartbeatPresent.contains(HEARTBEAT_KEY_PREFIX + id) || raw != null) live.add(id);
                if (raw == null) continue;
                java.util.List<java.util.Map.Entry<String, String>> parsed = parsePlayerList(raw);
                players.put(id, parsed);
                for (String entry : raw.split(PLAYER_SERVER_SEP, -1)) {
                    String[] parts = entry.split(PLAYER_SERVER_PAIR_SEP, 3);
                    if (parts.length < 3) continue;
                    try {
                        UUID uuid = UUID.fromString(parts[0]);
                        String name = parts[1].toLowerCase();
                        uuids.put(name, uuid);
                        proxyByUuid.put(uuid, id);
                        servers.put(name, parts[2]);
                    } catch (IllegalArgumentException ignored) {
                        // Ignore legacy or malformed entries.
                    }
                }
            }

            String[] staffKeys = live.stream().map(id -> STAFF_KEY_PREFIX + id).toArray(String[]::new);
            java.util.Map<String, String> rawStaff = new java.util.HashMap<>();
            for (var value : sync.mget(staffKeys)) {
                if (value.hasValue()) rawStaff.put(value.getKey(), value.getValue());
            }
            for (String id : live) {
                java.util.List<java.util.Map.Entry<String, String>> list = players.get(id);
                if (list != null) staff.put(id, parseStaffList(rawStaff.get(STAFF_KEY_PREFIX + id), list));
            }

            snapshot = new CrossProxySnapshot(
                    java.util.Set.copyOf(live), immutableListMap(players), immutableListMap(staff),
                    java.util.Map.copyOf(uuids), java.util.Map.copyOf(proxyByUuid),
                    java.util.Map.copyOf(servers), java.util.Map.copyOf(rawHostnames),
                    java.util.Map.copyOf(sync.hgetall(PREFIX_HASH_KEY)));
        } catch (Exception e) {
            logger.debug("Failed to refresh cross-proxy snapshot: {}", e.getMessage());
        }
    }

    private static java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> immutableListMap(
            java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> source) {
        java.util.Map<String, java.util.List<java.util.Map.Entry<String, String>>> result = new java.util.HashMap<>();
        source.forEach((key, value) -> result.put(key, java.util.List.copyOf(value)));
        return java.util.Map.copyOf(result);
    }

    private static java.util.List<java.util.Map.Entry<String, String>> parsePlayerList(String raw) {
        if (raw == null || raw.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<java.util.Map.Entry<String, String>> result = new java.util.ArrayList<>();
        for (String entry : raw.split(PLAYER_SERVER_SEP, -1)) {
            String[] parts = entry.split(PLAYER_SERVER_PAIR_SEP, 3);
            if (parts.length >= 3) result.add(new java.util.AbstractMap.SimpleImmutableEntry<>(parts[1], parts[2]));
            else if (parts.length == 2) result.add(new java.util.AbstractMap.SimpleImmutableEntry<>(parts[0], parts[1]));
        }
        return result;
    }

    private static java.util.List<java.util.Map.Entry<String, String>> parseStaffList(
            String rawStaff, java.util.List<java.util.Map.Entry<String, String>> players) {
        if (rawStaff == null || rawStaff.isEmpty()) return java.util.Collections.emptyList();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String name : rawStaff.split(PLAYER_SERVER_SEP, -1)) names.add(name.toLowerCase());
        java.util.List<java.util.Map.Entry<String, String>> result = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> player : players) {
            if (names.contains(player.getKey().toLowerCase())) result.add(player);
        }
        return result;
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

        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofMillis(connectTimeoutMs > 0 ? connectTimeoutMs : 5000));
        if (password != null && !password.isEmpty()) {
            uriBuilder.withPassword(password);
        }
        RedisURI uri = uriBuilder.build();

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
            requestRemoteSnapshotRefresh();
            snapshotTask = server.getScheduler().buildTask(plugin, this::requestRemoteSnapshotRefresh)
                    .repeat(1, TimeUnit.SECONDS)
                    .schedule();
            heartbeatTask = server.getScheduler().buildTask(plugin, () -> {
                refreshProxyHostname();
                updatePlayerList(); // Keep plist key alive (TTL 120s); otherwise plist shows 0 after ~2 mins of no join/leave/switch
            })
                    .repeat(HEARTBEAT_REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS)
                    .schedule();
            logger.info("Cross-proxy Redis connected (proxy-id: {}). Kick/ban/send and duplicate-session prevention are active.", proxyId);
        } catch (Exception e) {
            logger.error("Failed to connect to Redis for cross-proxy. Cross-proxy features disabled.", e);
            shutdown();
        }
    }

    public void shutdown() {
        if (snapshotTask != null) {
            snapshotTask.cancel();
            snapshotTask = null;
        }
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
        if (msg.getType() == CrossProxyMessage.Type.PLAYER_LIST_UPDATED) {
            requestRemoteSnapshotRefresh();
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
                    case SEND_SERVER:
                        handleSendServer(msg);
                        break;
                    case PLAYER_CONNECT:
                        handlePlayerConnect(msg);
                        break;
                    case PLAYER_LIST_UPDATED:
                        requestRemoteSnapshotRefresh();
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
                    case PROXY_TRANSFER_REQUEST:
                        handleProxyTransferRequest(msg);
                        break;
                    case ENTE:
                        handleEnte(msg);
                        break;
                    case DEFENSE_MODE_UPDATE:
                        handleDefenseModeUpdate(msg);
                        break;
                    case FRIEND_REQUEST:
                        handleFriendRequest(msg);
                        break;
                    case FRIEND_ACCEPT:
                        handleFriendAccept(msg);
                        break;
                    case FRIEND_JOIN:
                        handleFriendJoin(msg);
                        break;
                    case FRIEND_LEAVE:
                        handleFriendLeave(msg);
                        break;
                    case PING_REQUEST:
                        handlePingRequest(msg);
                        break;
                    case PING_RESPONSE:
                        handlePingResponse(msg);
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
                    ? ColorParser.parse(msg.getReason())
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
                    ? ColorParser.parse(msg.getReason())
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

    private void handleSendServer(CrossProxyMessage msg) {
        String sourceServer = msg.getReason(); // mapped to reason in parse
        String targetServer = msg.getServerName();
        if (sourceServer == null || sourceServer.isEmpty() || targetServer == null || targetServer.isEmpty()) return;
        Optional<RegisteredServer> target = server.getServer(targetServer);
        if (target.isEmpty()) return;
        RegisteredServer rs = target.get();
        server.getAllPlayers().stream()
                .filter(p -> p.getCurrentServer().isPresent() && p.getCurrentServer().get().getServerInfo().getName().equalsIgnoreCase(sourceServer))
                .forEach(p -> p.createConnectionRequest(rs).connectWithIndication());
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
            Component comp = ColorParser.parse(
                    "&c&lYou have been muted. &7Duration: &f" + duration + " &7| Reason: &f" + reason);
            player.sendMessage(plugin.getPrefix(player).append(comp));
        });
    }

    private void handlePrivateMsg(CrossProxyMessage msg) {
        String targetUsername = msg.getUsername();
        if (targetUsername == null || targetUsername.isEmpty()) return;
        String serializedMessage = msg.getReason();
        if (serializedMessage == null) return;
        server.getPlayer(targetUsername).ifPresent(player -> {
            player.sendMessage(MiniMessage.miniMessage().deserialize(serializedMessage));
            if (plugin.getMessageService() != null && msg.getUuidAsUUID() != null && msg.getServerName() != null && !msg.getServerName().isEmpty()) {
                plugin.getMessageService().setLastMessageSenderForReply(player.getUniqueId(), msg.getUuidAsUUID(), msg.getServerName());
            }
        });
    }

    private void handleBroadcast(CrossProxyMessage msg) {
        String legacy = msg.getReason();
        if (legacy == null) return;
        Component comp = ColorParser.parse(legacy);
        server.getAllPlayers().forEach(p -> p.sendMessage(comp));
    }

    private void handleTeamChat(CrossProxyMessage msg) {
        String legacy = msg.getReason();
        if (legacy == null) return;
        Component comp = ColorParser.parse(legacy);
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
                .append(Component.text("Chat log for ", NamedTextColor.GRAY))
                .append(Component.text(targetName != null ? targetName : "?", NamedTextColor.GOLD))
                .append(Component.text(" (reported by ", NamedTextColor.GRAY))
                .append(Component.text(reporterName != null ? reporterName : "?", NamedTextColor.GRAY))
                .append(Component.text(") has been uploaded. ", NamedTextColor.GRAY))
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


    /** Ask other proxies to kick a player by name (used when player is not on this proxy). */
    public void publishKickByName(String username, String reason) {
        publish(CrossProxyMessage.kickByName(username, reason, sharedSecret, proxyId));
    }

    public void publishSendAll(String serverName) {
        publish(CrossProxyMessage.sendAll(serverName, sharedSecret, proxyId));
    }

    public void publishSendServer(String sourceServer, String targetServer) {
        publish(CrossProxyMessage.sendServer(sourceServer, targetServer, sharedSecret, proxyId));
    }

    public void publishKick(UUID uuid, String reason) {
        publish(CrossProxyMessage.kick(uuid, reason, sharedSecret, proxyId));
    }

    private void publish(String message) {
        if (!enabled || pubConnection == null) return;
        try {
            pubConnection.async().publish(CHANNEL, message);
        } catch (Exception e) {
            logger.warn("Failed to publish cross-proxy message: {}", e.getMessage());
        }
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

    /** Ask the proxy that has this player to show them the ente (duck) title. Used for /ente cross-proxy. */
    public void publishEnte(String targetUsername) {
        if (targetUsername == null || targetUsername.isEmpty()) return;
        publish(CrossProxyMessage.ente(targetUsername, sharedSecret, proxyId));
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
    
    /** Request a player's live ping from whichever proxy currently has them. */
    public CompletableFuture<Long> requestPlayerPing(UUID targetUuid) {
        if (!enabled || pubConnection == null || targetUuid == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cross-proxy is unavailable"));
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Long> future = new CompletableFuture<>();
        PendingPing pending = new PendingPing(future);
        pendingPings.put(requestId, pending);
        pending.timeoutTask = server.getScheduler().buildTask(plugin, () -> {
            PendingPing expired = pendingPings.remove(requestId);
            if (expired != null) expired.future.completeExceptionally(new java.util.concurrent.TimeoutException("Ping request timed out"));
        }).delay(3, TimeUnit.SECONDS).schedule();
        publish(CrossProxyMessage.pingRequest(targetUuid, requestId, sharedSecret, proxyId));
        return future;
    }

    private void handlePingRequest(CrossProxyMessage msg) {
        UUID targetUuid = msg.getUuidAsUUID();
        String requestId = msg.getServerName();
        String originProxyId = msg.getProxyId();
        if (targetUuid == null || requestId == null || requestId.isEmpty() || originProxyId == null || originProxyId.isEmpty()) return;
        server.getPlayer(targetUuid).ifPresent(target -> publish(CrossProxyMessage.pingResponse(
                requestId, target.getUsername(), target.getPing(), originProxyId, sharedSecret, proxyId)));
    }

    private void handlePingResponse(CrossProxyMessage msg) {
        if (msg.getDurationFormatted() == null || !proxyId.equals(msg.getDurationFormatted())) return;
        String requestId = msg.getServerName();
        if (requestId == null || requestId.isEmpty()) return;
        PendingPing pending = pendingPings.remove(requestId);
        if (pending == null) return;
        if (pending.timeoutTask != null) pending.timeoutTask.cancel();
        try {
            pending.future.complete(Long.parseLong(msg.getReason()));
        } catch (NumberFormatException e) {
            pending.future.completeExceptionally(e);
        }
    }

    // Friend System Handlers

    private void handleFriendRequest(CrossProxyMessage msg) {
        UUID targetUuid = msg.getUuidAsUUID();
        if (targetUuid == null) return;
        String senderName = msg.getUsername();
        server.getPlayer(targetUuid).ifPresent(player -> {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("You have a new friend request from ", NamedTextColor.GOLD))
                    .append(Component.text(senderName, NamedTextColor.GREEN))
                    .append(Component.text("! ", NamedTextColor.GOLD))
                    .append(Component.text("[Click to Accept]", NamedTextColor.GREEN)
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Accept " + senderName + "'s request")))
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/friend accept " + senderName))));
        });
    }

    private void handleFriendAccept(CrossProxyMessage msg) {
        UUID targetUuid = msg.getUuidAsUUID();
        if (targetUuid == null) return;
        String acceptorName = msg.getUsername();
        server.getPlayer(targetUuid).ifPresent(player -> {
            player.sendMessage(plugin.getPrefix(player).append(Component.text(acceptorName, NamedTextColor.GREEN))
                    .append(Component.text(" has accepted your friend request!", NamedTextColor.GOLD)));
        });
    }

    private void handleFriendJoin(CrossProxyMessage msg) {
        if (proxyId != null && proxyId.equals(msg.getProxyId())) return;
        UUID joinedUuid = msg.getUuidAsUUID();
        if (joinedUuid == null) return;
        String joinedName = msg.getUsername();
        server.getAllPlayers().forEach(player -> {
            if (plugin.getFriendService().areFriends(player.getUniqueId(), joinedUuid)) {
                player.sendMessage(plugin.getPrefix(player).append(Component.text("Friend ", NamedTextColor.GOLD))
                        .append(Component.text(joinedName, NamedTextColor.GREEN))
                        .append(Component.text(" has joined the network.", NamedTextColor.GOLD)));
            }
        });
    }

    private void handleFriendLeave(CrossProxyMessage msg) {
        if (proxyId != null && proxyId.equals(msg.getProxyId())) return;
        UUID leftUuid = msg.getUuidAsUUID();
        if (leftUuid == null) return;
        String leftName = msg.getUsername();
        server.getAllPlayers().forEach(player -> {
            if (plugin.getFriendService().areFriends(player.getUniqueId(), leftUuid)) {
                player.sendMessage(plugin.getPrefix(player).append(Component.text("Friend ", NamedTextColor.GOLD))
                        .append(Component.text(leftName, NamedTextColor.GREEN))
                        .append(Component.text(" has left the network.", NamedTextColor.GOLD)));
            }
        });
    }

    public void publishFriendRequest(UUID targetUuid, String senderName) {
        publish(CrossProxyMessage.friendRequest(targetUuid.toString(), senderName, sharedSecret, proxyId));
    }

    public void publishFriendAccept(UUID targetUuid, String acceptorName) {
        publish(CrossProxyMessage.friendAccept(targetUuid.toString(), acceptorName, sharedSecret, proxyId));
    }

    public void publishFriendJoin(UUID uuid, String name) {
        publish(CrossProxyMessage.friendJoin(uuid.toString(), name, sharedSecret, proxyId));
    }

    public void publishFriendLeave(UUID uuid, String name) {
        publish(CrossProxyMessage.friendLeave(uuid.toString(), name, sharedSecret, proxyId));
    }

    private void handleMaintenanceSet(CrossProxyMessage msg) {
        boolean enable = "true".equalsIgnoreCase(msg.getServerName());
        String broadcastLegacy = msg.getReason();
        boolean isOriginator = msg.getProxyId() != null && msg.getProxyId().equals(proxyId);

        if (plugin.getMaintenanceService() == null) return;

        if (enable) {
            if (broadcastLegacy != null && !broadcastLegacy.isEmpty() && !isOriginator) {
                Component comp = ColorParser.parse(broadcastLegacy);
                server.getAllPlayers().forEach(p -> p.sendMessage(comp));
            }
            // All proxies (including originator) run the countdown so the mid-screen alert shows everywhere
            plugin.getMaintenanceService().runRemoteMaintenanceCountdown(null);
        } else {
            plugin.getMaintenanceService().setMaintenanceFromRemote(false);
            if (!isOriginator && broadcastLegacy != null && !broadcastLegacy.isEmpty()) {
                Component comp = ColorParser.parse(broadcastLegacy);
                server.getAllPlayers().forEach(p -> p.sendMessage(comp));
            }
        }
    }

    public void publishDefenseModeUpdate(String mode, String issuerName) {
        publish(CrossProxyMessage.defenseModeUpdate(mode, issuerName, sharedSecret, proxyId));
    }

    private void handleDefenseModeUpdate(CrossProxyMessage msg) {
        if (proxyId != null && proxyId.equals(msg.getProxyId())) return; // skip originator
        String mode = msg.getReason(); // We use reason field for mode
        String issuerName = msg.getUsername();
        if (mode == null || mode.isEmpty()) return;
        if (plugin.getAbuseConfig() != null) {
            plugin.getAbuseConfig().setDefenseMode(mode);
            Component comp = plugin.getPrefix().append(Component.text("Abuse Defense Mode updated to: ", net.kyori.adventure.text.format.NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .append(Component.text(mode.toUpperCase(), net.kyori.adventure.text.format.NamedTextColor.GREEN))
                .append(Component.text(" by " + (issuerName != null ? issuerName : "Console"), net.kyori.adventure.text.format.NamedTextColor.GRAY)));

            logger.info(MiniMessage.miniMessage().serialize(comp));
            server.getAllPlayers().stream()
                    .filter(p -> p.hasPermission("beaconlabs.antiabuse"))
                    .forEach(p -> p.sendMessage(comp));
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
        return snapshot.playerServersByName.get(playerName.toLowerCase());
    }

    /** All online player names across proxies (for suggestions etc.). */
    public java.util.Set<String> getOnlinePlayerNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (com.velocitypowered.api.proxy.Player p : server.getAllPlayers()) names.add(p.getUsername());
        if (!enabled || pubConnection == null) return names;
        for (java.util.List<java.util.Map.Entry<String, String>> players : snapshot.playerLists.values()) {
            for (java.util.Map.Entry<String, String> entry : players) {
                if (entry.getKey() != null && !entry.getKey().isEmpty()) names.add(entry.getKey());
            }
        }
        return names;
    }

    /** Total online player count across all proxies. */
    public int getTotalPlayerCount() {
        if (!enabled || pubConnection == null) return server.getPlayerCount();
        int total = 0;
        for (java.util.List<java.util.Map.Entry<String, String>> players : snapshot.playerLists.values()) total += players.size();
        return total;
    }

    private void handleEnte(CrossProxyMessage msg) {
        String targetUsername = msg.getUsername();
        if (targetUsername == null || targetUsername.isEmpty()) return;
        server.getPlayer(targetUsername).ifPresent(player -> plugin.showEnteTitleTo(player));
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
        Component notification = ColorParser.parse(legacy);
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

    private void handleProxyTransferRequest(CrossProxyMessage msg) {
        UUID uuid = msg.getUuidAsUUID();
        String targetProxyId = msg.getReason();
        String backendServerName = msg.getServerName();
        if (uuid == null || targetProxyId == null || targetProxyId.isEmpty() || backendServerName == null) return;
        Optional<Player> opt = server.getPlayer(uuid);
        if (opt.isEmpty()) return;
        Player player = opt.get();
        setPendingTransfer(targetProxyId, uuid, backendServerName);
        String hostPort = getProxyHostname(targetProxyId);
        if (hostPort == null || hostPort.isEmpty()) {
            logger.warn("Proxy transfer for {} to {} failed: target proxy hostname not set.", player.getUsername(), targetProxyId);
            return;
        }
        performTransferToHost(player, hostPort).ifPresent(err ->
                player.sendMessage(plugin.getPrefix(player).append(Component.text(err, NamedTextColor.RED))));
    }

    /** Minecraft 1.20.5+ protocol (transfer packet support). */
    private static final int PROTOCOL_VERSION_TRANSFER = 766;

    /**
     * Transfer a player to another host (e.g. another proxy) using 1.20.5+ transfer packets.
     * @return empty if success, or error message if transfer could not be performed (e.g. version too old)
     */
    public Optional<String> performTransferToHost(Player player, String hostPort) {
        if (player == null || hostPort == null || hostPort.isEmpty()) return Optional.of("Invalid host.");
        InetSocketAddress addr = parseHostPort(hostPort);
        if (addr == null) return Optional.of("Invalid host format (use host or host:port).");
        try {
            int protocol = player.getProtocolVersion().getProtocol();
            if (protocol < PROTOCOL_VERSION_TRANSFER) {
                return Optional.of("A transfer was attempted but your game version is too old. Use 1.20.5 or newer for proxy transfer.");
            }
            Method m = player.getClass().getMethod("transferToHost", InetSocketAddress.class);
            m.invoke(player, addr);
            return Optional.empty();
        } catch (NoSuchMethodException e) {
            logger.warn("Transfer not supported by this Velocity version (transferToHost missing).");
            return Optional.of("Proxy transfer is not available on this server.");
        } catch (Exception e) {
            logger.debug("Transfer failed: {}", e.getMessage());
            return Optional.of("Transfer failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    private static InetSocketAddress parseHostPort(String hostPort) {
        String host = hostPort.trim();
        int port = 25565;
        int idx = host.lastIndexOf(':');
        if (idx >= 0 && idx < host.length() - 1) {
            try {
                port = Integer.parseInt(host.substring(idx + 1).trim());
                host = host.substring(0, idx).trim();
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (host.isEmpty()) return null;
        return new InetSocketAddress(host, port);
    }

    private static Component buildJoinMeComponent(String senderUsername, String serverName) {
        Component border = Component.text("▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬", NamedTextColor.GOLD, TextDecoration.BOLD);
        Component header = Component.text("  ✦ JOIN ME INVITATION ✦  ", NamedTextColor.GOLD, TextDecoration.BOLD);
        Component playerComponent = Component.text(senderUsername, NamedTextColor.GOLD, TextDecoration.BOLD);
        Component serverComponent = Component.text(serverName, NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/server " + serverName))
                .hoverEvent(HoverEvent.showText(Component.text("Click to join " + serverName, NamedTextColor.GOLD)));
        return Component.empty()
                .append(Component.newline())
                .append(border).append(Component.newline())
                .append(header).append(Component.newline())
                .append(Component.text("Player: ", NamedTextColor.GOLD)).append(playerComponent).append(Component.newline())
                .append(Component.text("Server: ", NamedTextColor.GOLD)).append(serverComponent).append(Component.newline())
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

    /** Ask the proxy that has this player to transfer them to targetProxyId (for /proxies send when player is on another proxy). */
    public void publishProxyTransferRequest(UUID playerUuid, String targetProxyId, String backendServerName) {
        if (playerUuid == null || targetProxyId == null || backendServerName == null) return;
        publish(CrossProxyMessage.proxyTransferRequest(playerUuid.toString(), targetProxyId, backendServerName, sharedSecret, proxyId));
    }
}
