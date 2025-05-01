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
import org.bcnlab.beaconLabsVelocity.command.LabsVelocityCommand;
import org.bcnlab.beaconLabsVelocity.command.chat.BroadcastCommand;
import org.bcnlab.beaconLabsVelocity.command.chat.ChatReportCommand;
import org.bcnlab.beaconLabsVelocity.command.server.LobbyCommand;
import org.bcnlab.beaconLabsVelocity.command.punishment.PunishmentCommandRegistrar;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.bcnlab.beaconLabsVelocity.listener.BanLoginListener;
import org.bcnlab.beaconLabsVelocity.listener.ChatFilterListener;
import org.bcnlab.beaconLabsVelocity.listener.FileChatLogger;
import org.bcnlab.beaconLabsVelocity.listener.PingListener;
import org.bcnlab.beaconLabsVelocity.listener.MuteListener;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

@Plugin(id = "beaconlabsvelocity", name = "BeaconLabsVelocity", version = "1.0.0", url = "bcnlab.org", authors = {"Vincent Wackler"})
public class BeaconLabsVelocity {

    @Inject
    @DataDirectory
    private Path dataDirectory;

    private ConfigurationNode config;

    private String prefix;
    private final String version = "1.0.0";

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer server;

    @Inject
    private CommandManager commandManager;

    private PunishmentService punishmentService;
    private PunishmentConfig punishmentConfig; // Add field for config
    private DatabaseManager databaseManager;
    
    @Inject
    public BeaconLabsVelocity(CommandManager commandManager) {
        // Core
        commandManager.register("labsvelocity", new LabsVelocityCommand(this));

        // Broadcast
        commandManager.register("broadcast", new BroadcastCommand(this));
        commandManager.register("bc", new BroadcastCommand(this));
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
            prefix = config.node("prefix").getString("&6BeaconLabs &8» ");

        } catch (IOException e) {
            logger.error("Failed to load config!", e);
            prefix = "&4ConfigError &8» ";
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
            server.getEventManager().register(this, new MuteListener(this, punishmentService, punishmentConfig, logger));
            server.getEventManager().register(this, new BanLoginListener(this, punishmentService, punishmentConfig, logger));
        } catch (IOException e) {
            logger.error("Failed to load punishments.yml or register punishment components", e);
        }

        // Other Listeners
        server.getEventManager().register(this, new ChatFilterListener(this, server));
        server.getEventManager().register(this, new FileChatLogger(getDataDirectory().toString()));
        server.getEventManager().register(this, new PingListener(this, server));

        // Other Commands
        commandManager.register("chatreport", new ChatReportCommand(new FileChatLogger(getDataDirectory().toString()), this, server));
        commandManager.register("lobby", new LobbyCommand(this, server));
        commandManager.register("l", new LobbyCommand(this, server));
        commandManager.register("hub", new LobbyCommand(this, server));
        commandManager.register("labsvelocity", new LabsVelocityCommand(this));

        logger.info("BeaconLabsVelocity is initialized!");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        // Disconnect DatabaseManager
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
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }
}
