package org.bcnlab.beaconLabsVelocity.feather;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Optional integration with the Feather Server API (Velocity plugin).
 * Requires the Feather Server API Velocity plugin to be installed in the proxy's plugins folder.
 * When present, provides: server list background, Discord Rich Presence, and Feather debug (featherdebug command).
 * Uses reflection so no compile-time dependency on the API is required.
 */
public final class FeatherIntegration {

    private static boolean available = false;
    private static BeaconLabsVelocity plugin;
    private static Logger logger;
    private static Object metaService; // Feather MetaService
    private static final ExecutorService async = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BeaconLabsVelocity-Feather");
        t.setDaemon(true);
        return t;
    });

    private FeatherIntegration() {}

    /**
     * Call once during proxy init. If the Feather API is available, subscribes to PlayerHelloEvent for debug.
     * If feather.enabled in config, also loads server list background and Discord Rich Presence.
     */
    /** Feather Server API (Velocity) uses net.digitalingot.feather.serverapi with .api, .common, .messaging, .velocity; we use .velocity. */
    private static final String[] FEATHER_API_CLASS_NAMES = {
            "net.digitalingot.feather.serverapi.velocity.FeatherAPI",
            "net.digitalingot.feather.serverapi.api.FeatherAPI",
            "net.digitalingot.feather.serverapi.FeatherAPI"
    };

    /** Only API event class works: Feather maps PlayerHelloEvent -> VelocityPlayerHelloEvent internally. Passing concrete class causes NPE. */
    private static final String[] PLAYER_HELLO_EVENT_CLASS_NAMES = {
            "net.digitalingot.feather.serverapi.api.event.player.PlayerHelloEvent",
            "net.digitalingot.feather.serverapi.api.event.PlayerHelloEvent",
            "net.digitalingot.feather.event.PlayerHelloEvent",
            "net.digitalingot.feather.PlayerHelloEvent"
    };

    private static final String[] DISCORD_ACTIVITY_CLASS_NAMES = {
            "net.digitalingot.feather.serverapi.api.meta.DiscordActivity",
            "net.digitalingot.feather.serverapi.velocity.meta.DiscordActivity",
            "net.digitalingot.feather.serverapi.api.DiscordActivity",
            "net.digitalingot.feather.DiscordActivity"
    };

    private static boolean featherRetryScheduled = false;

    public static void init(BeaconLabsVelocity pl) {
        init(pl, false);
    }

    private static void init(BeaconLabsVelocity pl, boolean isRetry) {
        plugin = pl;
        logger = pl.getLogger();
        ConfigurationNode featherNode = pl.getConfig() != null ? pl.getConfig().node("feather") : null;
        boolean featureEnabled = featherNode != null && featherNode.node("enabled").getBoolean(false);

        Class<?> apiClass = null;
        for (String className : FEATHER_API_CLASS_NAMES) {
            try {
                apiClass = Class.forName(className);
                if (pl.isFeatherDebug()) logger.info("[Feather debug] Found API class: {}", className);
                break;
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
        if (apiClass == null) {
            if (pl.isFeatherDebug()) logger.info("[Feather] API class not found. Integration skipped.");
            return;
        }
        metaService = obtainMetaService(apiClass);
        if (metaService == null) {
            if (!isRetry && !featherRetryScheduled) {
                featherRetryScheduled = true;
                pl.getServer().getScheduler().buildTask(pl, () -> init(pl, true)).delay(2, TimeUnit.SECONDS).schedule();
                if (pl.isFeatherDebug()) logger.info("[Feather] Service not ready. Retrying in 2 seconds.");
            } else if (pl.isFeatherDebug()) {
                logger.warn("[Feather] Could not obtain MetaService. Integration disabled.");
            }
            return;
        }
        available = true;

        subscribePlayerHelloEvent(pl);
        if (featureEnabled) {
            loadServerListBackground();
            boolean discordEnabled = featherNode.node("discord").node("enabled").getBoolean(false);
            if (discordEnabled) {
                pl.getServer().getEventManager().register(pl, new FeatherIntegration());
            }
            if (pl.isFeatherDebug()) {
                logger.info("[Feather] Integration enabled (server list background{}).", discordEnabled ? " + Discord RPC" : "");
            }
        }
    }

    /** Get MetaService: try getFeatherService().getMetaService() first (Velocity registers this), then getMetaService() on API. */
    private static Object obtainMetaService(Class<?> apiClass) {
        try {
            Method getFeather = apiClass.getMethod("getFeatherService");
            Object featherService = getFeather.invoke(null);
            if (featherService != null) {
                Method getMeta = featherService.getClass().getMethod("getMetaService");
                Object meta = getMeta.invoke(featherService);
                if (meta != null) return meta;
            }
        } catch (Exception e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? ((java.lang.reflect.InvocationTargetException) e).getCause() : null;
            if (cause != null) {
                logger.debug("[Feather] getFeatherService().getMetaService() failed: {} - {}", cause.getClass().getSimpleName(), cause.getMessage());
            }
        }
        try {
            Method getMeta = apiClass.getMethod("getMetaService");
            Object meta = getMeta.invoke(null);
            return meta;
        } catch (Exception e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? ((java.lang.reflect.InvocationTargetException) e).getCause() : null;
            logger.debug("[Feather] getMetaService() failed: {} - {}", cause != null ? cause.getClass().getSimpleName() : e.getClass().getSimpleName(), cause != null ? cause.getMessage() : e.getMessage());
            return null;
        }
    }

    /** Subscribe to Feather's PlayerHelloEvent; when featherDebug is on, log player, platform, and enabled mods. */
    @SuppressWarnings("unchecked")
    private static void subscribePlayerHelloEvent(BeaconLabsVelocity pl) {
        boolean debug = pl.isFeatherDebug();
        try {
            Class<?> apiClass = null;
            for (String name : FEATHER_API_CLASS_NAMES) {
                try {
                    apiClass = Class.forName(name);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }
            if (apiClass == null) return;
            Method getEventService = apiClass.getMethod("getEventService");
            Object eventService = getEventService.invoke(null);
            if (eventService == null) {
                logger.warn("[Feather] getEventService() returned null; cannot subscribe to PlayerHelloEvent.");
                return;
            }
            logger.info("[Feather] EventService: {}", eventService.getClass().getName());
            Method subscribeMethod2 = null;
            Method subscribeMethod3 = null;
            for (Method m : eventService.getClass().getMethods()) {
                if ("subscribe".equals(m.getName())) {
                    if (m.getParameterCount() == 2) subscribeMethod2 = m;
                    else if (m.getParameterCount() == 3) subscribeMethod3 = m;
                }
            }
            if (subscribeMethod2 == null) {
                if (debug) logger.warn("[Feather debug] No subscribe(Class, Consumer) method found.");
                return;
            }
            if (debug) logger.info("[Feather debug] Subscribe method: {}({}).", subscribeMethod2.getName(), java.util.Arrays.toString(subscribeMethod2.getParameterTypes()));
            InvocationHandler handler = (proxy, method, args) -> {
                if (args != null && args.length == 1 && args[0] != null && isPlayerHelloEvent(args[0])) {
                    Object event = args[0];
                    if (pl.isFeatherDebug()) logger.info("[Feather debug] PlayerHelloEvent received (callback via {}).", method.getName());
                    logPlayerHelloEvent(event);
                    updateDiscordActivityForFeatherPlayer(event);
                }
                return null;
            };
            Class<?>[] paramTypes = subscribeMethod2.getParameterTypes();
            Class<?> listenerType = paramTypes.length >= 2 && paramTypes[1].isInterface() ? paramTypes[1] : java.util.function.Consumer.class;
            Object listener = Proxy.newProxyInstance(
                    FeatherIntegration.class.getClassLoader(),
                    new Class<?>[] { listenerType },
                    handler);
            int subscribed = 0;
            for (String name : PLAYER_HELLO_EVENT_CLASS_NAMES) {
                Class<?> eventClass = loadClassFromAnyLoader(name, eventService.getClass().getClassLoader(), apiClass.getClassLoader());
                if (eventClass == null) {
                    if (debug) logger.info("[Feather debug] Could not load event class: {}", name);
                    continue;
                }
                boolean ok = trySubscribe(eventService, eventClass, listener, subscribeMethod2, subscribeMethod3, pl, name);
                if (ok) {
                    if (debug) logger.info("[Feather debug] Subscribed to {}.", eventClass.getName());
                    subscribed++;
                }
            }
            if (subscribed == 0 && debug) {
                logger.warn("[Feather debug] No PlayerHelloEvent type could be subscribed.");
            }
        } catch (Exception e) {
            if (debug) logger.warn("[Feather debug] Could not subscribe to PlayerHelloEvent: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** Try 2-arg subscribe, then 3-arg subscribe(Class, Consumer, Object) with null then plugin. Returns true if subscribed. */
    private static boolean trySubscribe(Object eventService, Class<?> eventClass, Object listener,
                                        Method subscribe2, Method subscribe3, BeaconLabsVelocity pl, String name) {
        try {
            setAccessible(subscribe2);
            subscribe2.invoke(eventService, eventClass, listener);
            return true;
        } catch (Exception e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException
                    ? ((java.lang.reflect.InvocationTargetException) e).getCause() : e;
            String msg = cause != null ? cause.getMessage() : e.getMessage();
            if (msg == null) msg = cause != null ? cause.getClass().getSimpleName() : e.getClass().getSimpleName();
            if (pl.isFeatherDebug()) logger.warn("[Feather debug] Subscribe(2) failed for {}: {} - {}", name, cause != null ? cause.getClass().getSimpleName() : e.getClass().getSimpleName(), msg);
        }
        if (subscribe3 != null) {
            for (Object thirdArg : new Object[]{null, pl}) {
                try {
                    setAccessible(subscribe3);
                    subscribe3.invoke(eventService, eventClass, listener, thirdArg);
                    return true;
                } catch (Exception e) {
                    Throwable cause = e instanceof java.lang.reflect.InvocationTargetException
                            ? ((java.lang.reflect.InvocationTargetException) e).getCause() : e;
                    String msg = cause != null ? cause.getMessage() : e.getMessage();
                    if (msg == null) msg = cause != null ? cause.getClass().getSimpleName() : e.getClass().getSimpleName();
                    if (pl.isFeatherDebug()) logger.warn("[Feather debug] Subscribe(3) failed for {}: {} - {}", name, cause != null ? cause.getClass().getSimpleName() : e.getClass().getSimpleName(), msg);
                }
            }
        }
        return false;
    }

    /** Try to load a class from the given loader and all its parents, then from apiLoader, then context, then ours. */
    private static Class<?> loadClassFromAnyLoader(String className, ClassLoader primary, ClassLoader apiLoader) {
        for (ClassLoader cl = primary; cl != null; cl = cl.getParent()) {
            try {
                return Class.forName(className, true, cl);
            } catch (ClassNotFoundException ignored) {}
        }
        if (apiLoader != null && apiLoader != primary) {
            try {
                return Class.forName(className, true, apiLoader);
            } catch (ClassNotFoundException ignored) {}
        }
        try {
            return Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException ignored) {}
        try {
            return Class.forName(className, true, FeatherIntegration.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {}
        return null;
    }

    private static void setAccessible(AccessibleObject o) {
        try {
            o.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private static boolean isPlayerHelloEvent(Object o) {
        if (o == null) return false;
        try {
            o.getClass().getMethod("getPlayer");
            o.getClass().getMethod("getPlatform");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static void logPlayerHelloEvent(Object event) {
        if (plugin == null || !plugin.isFeatherDebug()) return;
        try {
            Method getPlayer = event.getClass().getMethod("getPlayer");
            Object featherPlayer = getPlayer.invoke(event);
            String playerName = null;
            String playerUuid = null;
            if (featherPlayer != null) {
                try {
                    Method getName = featherPlayer.getClass().getMethod("getName");
                    Object n = getName.invoke(featherPlayer);
                    playerName = n != null ? n.toString() : null;
                } catch (Exception ignored) {}
                try {
                    Method getUuid = featherPlayer.getClass().getMethod("getUniqueId");
                    Object u = getUuid.invoke(featherPlayer);
                    playerUuid = u != null ? u.toString() : null;
                } catch (Exception ignored) {}
            }
            String platform = "?";
            try {
                Method getPlatform = event.getClass().getMethod("getPlatform");
                Object p = getPlatform.invoke(event);
                if (p != null) platform = p.toString();
            } catch (Exception ignored) {}
            StringBuilder mods = new StringBuilder();
            try {
                Method getMods = event.getClass().getMethod("getFeatherMods");
                Object modList = getMods.invoke(event);
                if (modList instanceof Collection<?> col) {
                    for (Object mod : col) {
                        if (mod != null) {
                            try {
                                Method getModName = mod.getClass().getMethod("getName");
                                Object name = getModName.invoke(mod);
                                if (mods.length() > 0) mods.append(", ");
                                mods.append(name != null ? name.toString() : mod.toString());
                            } catch (Exception e) {
                                if (mods.length() > 0) mods.append(", ");
                                mods.append(mod.toString());
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            logger.info("[Feather debug] Player joined with Feather: name={} uuid={} platform={} enabledMods=[{}]",
                    playerName != null ? playerName : "?", playerUuid != null ? playerUuid : "?", platform, mods);
            // Log any other getters on the event (e.g. version, client info) for extra debug info
            for (Method m : event.getClass().getMethods()) {
                if (!m.getName().startsWith("get") || m.getParameterCount() != 0) continue;
                if ("getPlayer".equals(m.getName()) || "getPlatform".equals(m.getName()) || "getFeatherMods".equals(m.getName()))
                    continue;
                if ("getClass".equals(m.getName())) continue;
                try {
                    Object val = m.invoke(event);
                    if (val != null && !(val instanceof Collection<?>) && !val.getClass().isArray()) {
                        logger.info("[Feather debug]   {} = {}", m.getName(), val);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.warn("[Feather debug] Failed to log PlayerHelloEvent: {}", e.getMessage());
        }
    }

    private static void loadServerListBackground() {
        ConfigurationNode feather = plugin.getConfig() != null ? plugin.getConfig().node("feather") : null;
        if (feather == null) return;
        String filename = feather.node("server-list-background").getString("");
        if (filename == null || filename.isBlank()) return;

        Path dir = plugin.getDataDirectory().resolve("feather");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            logger.warn("Could not create feather directory: {}", e.getMessage());
            return;
        }
        Path file = dir.resolve(filename);
        if (!Files.isRegularFile(file)) {
            logger.warn("Feather server list background file not found: {}", file);
            return;
        }

        async.execute(() -> {
            try {
                Method getFactory = metaService.getClass().getMethod("getServerListBackgroundFactory");
                setAccessible(getFactory);
                Object factory = getFactory.invoke(metaService);
                if (factory == null) return;
                Method byPath = factory.getClass().getMethod("byPath", Path.class);
                setAccessible(byPath);
                Object background = byPath.invoke(factory, file);
                if (background == null) return;
                Object bg = background;
                String fn = filename;
                // Run setServerListBackground on Velocity main thread; Feather plugin may require it
                plugin.getServer().getScheduler().buildTask(plugin, () -> {
                    try {
                        for (Method m : metaService.getClass().getMethods()) {
                            if ("setServerListBackground".equals(m.getName()) && m.getParameterCount() == 1) {
                                setAccessible(m);
                                m.invoke(metaService, bg);
                                if (plugin.isFeatherDebug()) logger.info("[Feather debug] Server list background set: {}", fn);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to set Feather server list background on main thread: {}", e.getMessage());
                    }
                }).schedule();
            } catch (Exception e) {
                logger.warn("Failed to load Feather server list background: {}", e.getMessage());
            }
        });
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onPostLogin(PostLoginEvent event) {
        if (!available || metaService == null) return;
        updateDiscordActivity(event.getPlayer());
        scheduleRefreshAllDiscordActivities();
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (!available || metaService == null || event.getPlayer().getCurrentServer().isEmpty()) return;
        updateDiscordActivity(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (!available || metaService == null) return;
        clearDiscordActivity(event.getPlayer());
        scheduleRefreshAllDiscordActivities();
    }

    /** Schedules a refresh of Discord activity for all online players (so %players% / %max_players% stay current). */
    private void scheduleRefreshAllDiscordActivities() {
        plugin.getServer().getScheduler().buildTask(plugin, this::refreshAllDiscordActivities).delay(1, TimeUnit.SECONDS).schedule();
    }

    private void refreshAllDiscordActivities() {
        if (!available || metaService == null) return;
        ConfigurationNode discord = plugin.getConfig() != null ? plugin.getConfig().node("feather").node("discord") : null;
        if (discord == null || !discord.node("enabled").getBoolean(false)) return;
        for (Player p : plugin.getServer().getAllPlayers()) {
            updateDiscordActivity(p);
        }
    }

    /** Set Discord activity for a FeatherPlayer (from PlayerHelloEvent). API expects FeatherPlayer, not Velocity Player. */
    private static void updateDiscordActivityForFeatherPlayer(Object event) {
        if (metaService == null || plugin.getConfig() == null) return;
        ConfigurationNode discord = plugin.getConfig().node("feather").node("discord");
        if (discord == null || !discord.node("enabled").getBoolean(false)) return;
        Object featherPlayer;
        try {
            Method getPlayer = event.getClass().getMethod("getPlayer");
            featherPlayer = getPlayer.invoke(event);
        } catch (Exception e) {
            return;
        }
        if (featherPlayer == null) return;
        DiscordPlaceholders ctx = resolveDiscordPlaceholders(featherPlayer, null);
        String image = resolvePlaceholders(discord.node("image").getString(""), ctx);
        String imageText = resolvePlaceholders(discord.node("image-text").getString(""), ctx);
        String state = resolvePlaceholders(discord.node("state").getString(""), ctx);
        String details = resolvePlaceholders(discord.node("details").getString(""), ctx);
        if (image.isEmpty() && imageText.isEmpty() && state.isEmpty() && details.isEmpty()) return;
        try {
            ClassLoader featherLoader = metaService.getClass().getClassLoader();
            Class<?> activityClass = null;
            for (String name : DISCORD_ACTIVITY_CLASS_NAMES) {
                activityClass = loadClassFromAnyLoader(name, featherLoader, null);
                if (activityClass != null) break;
            }
            if (activityClass == null) return;
            Object builder = activityClass.getMethod("builder").invoke(null);
            if (builder == null) return;
            Class<?> builderClass = builder.getClass();
            if (image != null && !image.isEmpty()) invoke(builderClass, builder, "withImage", image);
            if (imageText != null && !imageText.isEmpty()) invoke(builderClass, builder, "withImageText", imageText);
            if (state != null && !state.isEmpty()) invoke(builderClass, builder, "withState", state);
            if (details != null && !details.isEmpty()) invoke(builderClass, builder, "withDetails", details);
            Object activity = builderClass.getMethod("build").invoke(builder);
            if (activity != null) {
                for (Method m : metaService.getClass().getMethods()) {
                    if ("updateDiscordActivity".equals(m.getName()) && m.getParameterCount() == 2) {
                        setAccessible(m);
                        m.invoke(metaService, featherPlayer, activity);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (plugin.isFeatherDebug()) logger.debug("Feather updateDiscordActivity (FeatherPlayer) failed: {}", e.getMessage());
        }
    }

    /** Placeholder context for Discord Rich Presence. */
    private static final class DiscordPlaceholders {
        final int players;
        final int maxPlayers;
        final String serverName;
        final String playerName;
        final String dimension;

        DiscordPlaceholders(int players, int maxPlayers, String serverName, String playerName, String dimension) {
            this.players = players;
            this.maxPlayers = maxPlayers;
            this.serverName = serverName != null ? serverName : "";
            this.playerName = playerName != null ? playerName : "";
            this.dimension = dimension != null ? dimension : "?";
        }
    }

    private static DiscordPlaceholders resolveDiscordPlaceholders(Object featherPlayer, Player velocityPlayer) {
        int players = plugin.getServer().getPlayerCount();
        int maxPlayers = plugin.getConfig() != null ? plugin.getConfig().node("motd", "max-players").getInt(100) : 100;
        String serverName = "";
        String playerName = "";
        if (velocityPlayer != null) {
            serverName = velocityPlayer.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
            playerName = velocityPlayer.getUsername();
        } else if (featherPlayer != null) {
            try {
                Method getName = featherPlayer.getClass().getMethod("getName");
                Object n = getName.invoke(featherPlayer);
                playerName = n != null ? n.toString() : "";
                Method getUuid = featherPlayer.getClass().getMethod("getUniqueId");
                Object u = getUuid.invoke(featherPlayer);
                if (u instanceof UUID uuid) {
                    velocityPlayer = plugin.getServer().getPlayer(uuid).orElse(null);
                    if (velocityPlayer != null) {
                        serverName = velocityPlayer.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
                    }
                }
            } catch (Exception ignored) {}
        }
        String dimension = resolveDimension(featherPlayer, velocityPlayer);
        return new DiscordPlaceholders(players, maxPlayers, serverName, playerName, dimension);
    }

    /** Try to resolve dimension from FeatherPlayer (e.g. getDimension/getWorld). Proxy has no dimension; remains "?" if unavailable. */
    private static String resolveDimension(Object featherPlayer, Player velocityPlayer) {
        if (featherPlayer == null) return "?";
        for (String methodName : new String[]{"getDimension", "getWorld", "getCurrentWorld", "getDimensionName"}) {
            try {
                Method m = featherPlayer.getClass().getMethod(methodName);
                Object v = m.invoke(featherPlayer);
                if (v != null) {
                    String s = v instanceof String ? (String) v : v.toString();
                    if (!s.isEmpty()) return s;
                }
            } catch (NoSuchMethodException | SecurityException ignored) {
            } catch (Exception e) {
                if (plugin != null && plugin.isFeatherDebug()) logger.debug("Feather dimension {}: {}", methodName, e.getMessage());
            }
        }
        return "?";
    }

    /** Replace %placeholder% in template. Supported: %players%, %max_players%, %server%, %player%, %dimension%. */
    private static String resolvePlaceholders(String template, DiscordPlaceholders ctx) {
        if (template == null || template.isEmpty()) return template;
        String s = template;
        s = s.replace("%players%", String.valueOf(ctx.players));
        s = s.replace("%max_players%", String.valueOf(ctx.maxPlayers));
        s = s.replace("%server%", ctx.serverName);
        s = s.replace("%player%", ctx.playerName);
        s = s.replace("%dimension%", ctx.dimension);
        return s;
    }

    private void updateDiscordActivity(Player player) {
        ConfigurationNode discord = plugin.getConfig() != null ? plugin.getConfig().node("feather").node("discord") : null;
        if (discord == null) return;
        DiscordPlaceholders ctx = resolveDiscordPlaceholders(null, player);
        String image = resolvePlaceholders(discord.node("image").getString(""), ctx);
        String imageText = resolvePlaceholders(discord.node("image-text").getString(""), ctx);
        String state = resolvePlaceholders(discord.node("state").getString(""), ctx);
        String details = resolvePlaceholders(discord.node("details").getString(""), ctx);
        if (image.isEmpty() && imageText.isEmpty() && state.isEmpty() && details.isEmpty()) return;

        try {
            ClassLoader featherLoader = metaService.getClass().getClassLoader();
            Class<?> activityClass = null;
            for (String name : DISCORD_ACTIVITY_CLASS_NAMES) {
                activityClass = loadClassFromAnyLoader(name, featherLoader, null);
                if (activityClass != null) break;
            }
            if (activityClass == null) return;
            Object builder = activityClass.getMethod("builder").invoke(null);
            if (builder == null) return;
            Class<?> builderClass = builder.getClass();
            if (image != null && !image.isEmpty()) invoke(builderClass, builder, "withImage", image);
            if (imageText != null && !imageText.isEmpty()) invoke(builderClass, builder, "withImageText", imageText);
            if (state != null && !state.isEmpty()) invoke(builderClass, builder, "withState", state);
            if (details != null && !details.isEmpty()) invoke(builderClass, builder, "withDetails", details);
            Object activity = builderClass.getMethod("build").invoke(builder);
            if (activity != null) {
                for (Method m : metaService.getClass().getMethods()) {
                    if ("updateDiscordActivity".equals(m.getName()) && m.getParameterCount() == 2) {
                        setAccessible(m);
                        m.invoke(metaService, player, activity);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Feather updateDiscordActivity failed: {}", e.getMessage());
        }
    }

    private static void invoke(Class<?> builderClass, Object builder, String methodName, String value) throws Exception {
        Method m = builderClass.getMethod(methodName, String.class);
        m.invoke(builder, value);
    }

    private void clearDiscordActivity(Player player) {
        try {
            for (Method m : metaService.getClass().getMethods()) {
                if ("clearDiscordActivity".equals(m.getName()) && m.getParameterCount() == 1) {
                    setAccessible(m);
                    m.invoke(metaService, player);
                    break;
                }
            }
        } catch (Exception e) {
            logger.debug("Feather clearDiscordActivity failed: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() {
        return available;
    }
}
