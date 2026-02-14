package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.slf4j.Logger;

/**
 * Registers all admin commands
 */
public class AdminCommandRegistrar {
    private final CommandManager commandManager;
    private final ProxyServer server;
    private final BeaconLabsVelocity plugin;
    private final PunishmentConfig config;
    private final PunishmentService service;
    private final Logger logger;

    public AdminCommandRegistrar(CommandManager commandManager, PunishmentConfig config,
                                      PunishmentService service, BeaconLabsVelocity plugin,
                                      ProxyServer server, Logger logger) {
        this.commandManager = commandManager;
        this.server = server;
        this.plugin = plugin;
        this.config = config;
        this.service = service;
        this.logger = logger;
    }    public void registerAll() {
        // GoTo command
        commandManager.register("goto", new GoToCommand(plugin, server));
        // Send / proxysend (cross-proxy when Redis enabled)
        SendCommand sendCommand = new SendCommand(plugin, server);
        commandManager.register("send", sendCommand);
        commandManager.register("proxysend", sendCommand);
        commandManager.register("psend", sendCommand);
        // Info
        commandManager.register("info", new InfoCommand(server, service, plugin, config));
        // IP history command - only register if PlayerStatsService is available
        if (plugin.getPlayerStatsService() != null) {
            commandManager.register("ips", new IpsCommand(server, plugin.getPlayerStatsService(), service, plugin));
        }
          // Server metrics command
        ServerMetricsCommand serverMetricsCommand = new ServerMetricsCommand(plugin);
        commandManager.register("servermetrics", serverMetricsCommand);
        commandManager.register("sm", serverMetricsCommand);
        logger.info("Server metrics commands registered.");
          // Server guard command
        if (plugin.getServerGuardService() != null) {
            ServerGuardCommand serverGuardCommand = new ServerGuardCommand(plugin, server, plugin.getServerGuardService());
            commandManager.register("serverguard", serverGuardCommand);
            commandManager.register("sg", serverGuardCommand);
            logger.info("Server guard commands registered.");
        }
        
        // Maintenance command - only register if maintenance service is available
        if (plugin.getMaintenanceService() != null) {
            commandManager.register("maintenance", new MaintenanceCommand(plugin, plugin.getMaintenanceService()));
        }
          // Whitelist command - only register if whitelist service is available
        if (plugin.getWhitelistService() != null) {
            ProxyWhitelistCommand whitelistCommand = new ProxyWhitelistCommand(plugin, plugin.getWhitelistService());
            commandManager.register("proxywhitelist", whitelistCommand);
            commandManager.register("pwhitelist", whitelistCommand);
            commandManager.register("pw", whitelistCommand);
            logger.info("Proxy whitelist commands registered.");
        } else {
            logger.warn("WhitelistService is not available. Proxy whitelist commands will not be registered.");
        }
    }
}
