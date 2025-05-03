package org.bcnlab.beaconLabsVelocity.command.admin;

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
    }

    public void registerAll() {
        // GoTo command
        commandManager.register("goto", new GoToCommand(plugin, server));
        // Info
        commandManager.register("info", new InfoCommand(server, service, plugin, config));
    }
}
