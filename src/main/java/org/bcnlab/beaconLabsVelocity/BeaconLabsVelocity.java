package org.bcnlab.beaconLabsVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.command.JoinMeCommand;
import org.bcnlab.beaconLabsVelocity.command.LegalCommand;
import org.bcnlab.beaconLabsVelocity.command.ReportCommand;
import org.bcnlab.beaconLabsVelocity.command.ReportsCommand;
import org.bcnlab.beaconLabsVelocity.command.punishment.PunishmentCommandRegistrar;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.bcnlab.beaconLabsVelocity.listener.*;
import org.bcnlab.beaconLabsVelocity.command.chat.ChatReportCommand;

import java.util.UUID;
import org.bcnlab.beaconLabsVelocity.service.MaintenanceService;
import org.bcnlab.beaconLabsVelocity.service.MessageService;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.bcnlab.beaconLabsVelocity.service.LegalService;
import org.bcnlab.beaconLabsVelocity.service.ReportService;
import org.bcnlab.beaconLabsVelocity.service.ServerGuardService;
import org.bcnlab.beaconLabsVelocity.service.WhitelistService;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

@Plugin(id = "beaconlabsvelocity", name = "BeaconLabsVelocity", version = "1.2", url = "bcnlab.org", authors = {"Vincent Wackler"})
public class BeaconLabsVelocity {

    @Inject
    @DataDirectory
    private Path dataDirectory;

    private ConfigurationNode config;

    private String prefix;
    private final String version = "1.2";

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer server;

    @Inject
    private CommandManager commandManager;    private PunishmentService punishmentService;
    private PunishmentConfig punishmentConfig;
    private DatabaseManager databaseManager;
    private PlayerStatsService playerStatsService;    private MaintenanceService maintenanceService;
    private MessageService messageService;
    private WhitelistService whitelistService;
    private ReportService reportService;
    private LegalService legalService;
    private ServerGuardService serverGuardService;
    private org.bcnlab.beaconLabsVelocity.crossproxy.CrossProxyService crossProxyService;
    private FileChatLogger fileChatLogger;
    private volatile boolean featherDebug = false;
    @Inject
    public BeaconLabsVelocity(CommandManager commandManager) {
        // Commands are now registered in onProxyInitialization
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        Path configFile = dataDirectory.resolve("config.yml");

        try {
            if (!Files.exists(configFile)) {
                Files.createDirectories(dataDirectory);
                Files.copy(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("config.yml")), configFile);
            }

            ConfigurationLoader<?> loader = YamlConfigurationLoader.builder()
                    .path(configFile)
                    .build();

            config = loader.load();
            
            // Make sure config is not null before accessing it
            if (config != null) {
                prefix = config.node("prefix").getString("&6BeaconLabs &8» ");
            } else {
                logger.error("Failed to load config: Configuration is null");
                prefix = "&4ConfigError &8» ";
            }

        } catch (IOException e) {
            logger.error("Failed to load config!", e);
            prefix = "&4ConfigError &8» ";
        }

        // PacketEvents (bundled) - load early so it's ready for legal book GUI
        try {
            java.util.Optional<com.velocitypowered.api.plugin.PluginContainer> containerOpt = server.getPluginManager().fromInstance(this);
            if (containerOpt.isPresent()) {
                com.github.retrooper.packetevents.PacketEvents.setAPI(
                        io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder.build(
                                server, containerOpt.get(), logger, dataDirectory));
                com.github.retrooper.packetevents.PacketEvents.getAPI().load();
                logger.info("PacketEvents (bundled) loaded.");
            }
        } catch (Throwable t) {
            logger.warn("PacketEvents could not be loaded: {}", t.getMessage());
        }

        // DatabaseManager
        databaseManager = new DatabaseManager(this, logger);
        databaseManager.connect();

        // Load punishment configuration and register commands/listeners
        try {
            punishmentConfig = new PunishmentConfig(dataDirectory, logger); // Assign to field
            // Initialize PunishmentService
            punishmentService = new PunishmentService(this, databaseManager, punishmentConfig, logger);
            // Register punishment commands
            new PunishmentCommandRegistrar(commandManager, punishmentConfig, punishmentService, this, server, logger).registerAll();
            // Register Listeners
            // Register the mute listener that handles all chat blocking for muted players
            server.getEventManager().register(this, new MuteListener(this, punishmentService, punishmentConfig, logger));
                  // Register the ban login listener to prevent banned players from joining
            server.getEventManager().register(this, new BanLoginListener(this, punishmentService, punishmentConfig, logger));
        } catch (IOException e) {
            logger.error("Failed to load punishments.yml or register punishment components", e);
        }
          // Initialize PlayerStatsService for playtime tracking and IP history
        if (databaseManager != null && databaseManager.isConnected()) {
            playerStatsService = new PlayerStatsService(this, databaseManager, logger);
            server.getEventManager().register(this, new PlayerStatsListener(this, playerStatsService, logger));
            logger.info("Player stats tracking has been enabled.");
        } else {
            logger.warn("Database is not connected. Player stats tracking will be disabled.");
        }
          // Initialize MaintenanceService
        maintenanceService = new MaintenanceService(this, server, logger);
        server.getEventManager().register(this, new MaintenanceListener(maintenanceService));
        logger.info("Maintenance service has been enabled.");        // Initialize MessageService for private messaging
        messageService = new MessageService(this, server, logger);
        server.getEventManager().register(this, new MessageListener(messageService));
        logger.info("Message service has been enabled.");        // Initialize WhitelistService and register WhitelistListener if database is connected
        if (databaseManager != null && databaseManager.isConnected()) {
            whitelistService = new WhitelistService(this, server, databaseManager, logger);
            server.getEventManager().register(this, new WhitelistListener(this, whitelistService));
            logger.info("Whitelist service has been enabled.");
        } else {
            logger.warn("Database is not connected. Whitelist service will be disabled.");
        }
        
        // Initialize ReportService for player reporting system if database is connected
        if (databaseManager != null && databaseManager.isConnected()) {
            reportService = new ReportService(this, databaseManager, logger);
            
            // Register report commands
            commandManager.register("report", new ReportCommand(this, reportService));
            commandManager.register("reports", new ReportsCommand(this, reportService));
            
            logger.info("Report service has been enabled.");
        } else {
            logger.warn("Database is not connected. Report service will be disabled.");
        }
        
        // Initialize ReportService for player reporting system if database is connected
        if (databaseManager != null && databaseManager.isConnected()) {
            reportService = new ReportService(this, databaseManager, logger);
            
            // Register report commands
            commandManager.register("report", new ReportCommand(this, reportService));
            commandManager.register("reports", new ReportsCommand(this, reportService));
            
            logger.info("Report service has been enabled.");
        } else {
            logger.warn("Database is not connected. Report service will be disabled.");
        }

        // Legal (TOS/Privacy) - only when DB connected and legal enabled in config
        if (databaseManager != null && databaseManager.isConnected()) {
            legalService = new LegalService(this, databaseManager, logger);
            if (legalService.isEnabled()) {
                commandManager.register("legal", new LegalCommand(this, legalService));
                server.getEventManager().register(this, new LegalListener(this, legalService));
                if (com.github.retrooper.packetevents.PacketEvents.getAPI() != null) {
                    com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(
                            new org.bcnlab.beaconLabsVelocity.legal.LegalBookPacketListener(this, legalService));
                }
                logger.info("Legal (TOS/Privacy) feature has been enabled.");
            }
        }

        // Initialize ServerGuardService
        serverGuardService = new ServerGuardService(this, server, logger);
        server.getEventManager().register(this, new ServerGuardListener(this, serverGuardService));
        logger.info("Server guard system has been enabled.");

        // Cross-proxy (Redis)
        ConfigurationNode redisNode = config != null ? config.node("redis") : null;
        boolean redisEnabled = redisNode != null && redisNode.node("enabled").getBoolean(false);
        String proxyId = redisNode != null ? redisNode.node("proxy-id").getString("na") : "na";
        String sharedSecret = redisNode != null ? redisNode.node("shared-secret").getString("") : "";
        String publicHostname = redisNode != null ? redisNode.node("public-hostname").getString("") : "";
        boolean allowDoubleJoin = redisNode != null && redisNode.node("allow-double-join").getBoolean(false);
        crossProxyService = new org.bcnlab.beaconLabsVelocity.crossproxy.CrossProxyService(this, proxyId, sharedSecret, publicHostname, redisEnabled, allowDoubleJoin);
        if (redisEnabled) {
            crossProxyService.start(
                    redisNode.node("host").getString("localhost"),
                    redisNode.node("port").getInt(6379),
                    redisNode.node("password").getString(""),
                    redisNode.node("connect-timeout-ms").getInt(5000),
                    redisNode.node("reconnect-interval-ms").getInt(5000)
            );
            server.getEventManager().register(this, new CrossProxyLoginListener(this));
            server.getEventManager().register(this, new CrossProxyDisconnectListener(this));
            server.getEventManager().register(this, new CrossProxyServerSwitchListener(this));
        }
        
        // Other Listeners
        server.getEventManager().register(this, new ChatFilterListener(this, server));
        fileChatLogger = new FileChatLogger(getDataDirectory().toString());
        server.getEventManager().register(this, fileChatLogger);
        server.getEventManager().register(this, new PingListener(this, server));

        // Feather Server API integration (server list background + Discord Rich Presence)
        org.bcnlab.beaconLabsVelocity.feather.FeatherIntegration.init(this);

        // Register server commands
        new org.bcnlab.beaconLabsVelocity.command.server.ServerCommandRegistrar(
                commandManager, this, server, logger).registerAll();

        // Register core commands
        new org.bcnlab.beaconLabsVelocity.command.CoreCommandRegistrar(
                commandManager, this, server, logger).registerAll();

        // Register chat commands
        new org.bcnlab.beaconLabsVelocity.command.chat.ChatCommandRegistrar(
                commandManager, this, server, logger).registerAll();

        // Register utility commands
        new org.bcnlab.beaconLabsVelocity.command.util.UtilCommandRegistrar(
            commandManager, this, server, logger).registerAll();
          // Register admin commands
        new org.bcnlab.beaconLabsVelocity.command.admin.AdminCommandRegistrar(
            commandManager, punishmentConfig, punishmentService, this, server, logger).registerAll();        // Register JoinMeCommand 
        commandManager.register("joinme", new JoinMeCommand(this, server));

        // PacketEvents init (must be after load())
        try {
            if (com.github.retrooper.packetevents.PacketEvents.getAPI() != null) {
                com.github.retrooper.packetevents.PacketEvents.getAPI().init();
                logger.info("PacketEvents initialized.");
            }
        } catch (Throwable t) {
            logger.warn("PacketEvents init failed: {}", t.getMessage());
        }

        logger.info("BeaconLabsVelocity is initialized!");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        try {
            if (com.github.retrooper.packetevents.PacketEvents.getAPI() != null) {
                com.github.retrooper.packetevents.PacketEvents.getAPI().terminate();
            }
        } catch (Throwable ignored) {}
        if (crossProxyService != null) {
            crossProxyService.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        logger.info("BeaconLabsVelocity is shutting down.");
    }

    public Component getPrefix() {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix);
    }

    public String getVersion() {
        return version;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ConfigurationNode getConfig() {
        return config;
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }    public Path getDataDirectory() {
        return dataDirectory;
    }
    
    /**
     * Saves the current configuration to file
     * @return true if saved successfully, false otherwise
     */
    public boolean saveConfig() {
        try {
            if (config == null) {
                logger.error("Cannot save config: Configuration is null");
                return false;
            }
            
            Path configPath = dataDirectory.resolve("config.yml");
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(configPath)
                .build();
                
            loader.save(config);
            return true;
        } catch (IOException e) {
            logger.error("Failed to save config", e);
            return false;
        }
    }    public PlayerStatsService getPlayerStatsService() {
        return playerStatsService;
    }
    
    public MaintenanceService getMaintenanceService() {
        return maintenanceService;
    }
      public MessageService getMessageService() {
        return messageService;
    }
    
    public WhitelistService getWhitelistService() {
        return whitelistService;
    }

    public LegalService getLegalService() {
        return legalService;
    }

    public ServerGuardService getServerGuardService() {
        return serverGuardService;
    }

    public PunishmentService getPunishmentService() {
        return punishmentService;
    }

    public org.bcnlab.beaconLabsVelocity.crossproxy.CrossProxyService getCrossProxyService() {
        return crossProxyService;
    }

    public FileChatLogger getFileChatLogger() {
        return fileChatLogger;
    }

    /** When true, Feather join events (PlayerHelloEvent) are logged with player, platform, and enabled mods. */
    public boolean isFeatherDebug() {
        return featherDebug;
    }

    public void setFeatherDebug(boolean featherDebug) {
        this.featherDebug = featherDebug;
    }

    /**
     * Perform a chat report for a player on this proxy (read log, upload, publish result).
     * Used when another proxy requests a report via CHATREPORT_REQUEST.
     */
    public void performChatReportForPlayer(UUID targetUuid, String targetName, String reporterName) {
        if (fileChatLogger == null || crossProxyService == null || !crossProxyService.isEnabled()) return;
        try {
            String chatLog = fileChatLogger.readChatLog(targetUuid);
            String pasteLink = ChatReportCommand.uploadToPastebinWithFallback(chatLog);
            crossProxyService.publishChatReportResult(reporterName != null ? reporterName : "Unknown", targetName != null ? targetName : "Unknown", pasteLink);
        } catch (Exception e) {
            logger.warn("Failed to perform cross-proxy chat report for {}: {}", targetName, e.getMessage());
        }
    }
}
