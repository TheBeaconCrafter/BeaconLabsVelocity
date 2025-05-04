package org.bcnlab.beaconLabsVelocity.command;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;

/**
 * Registers core plugin commands
 */
public class CoreCommandRegistrar {
    private final CommandManager commandManager;
    private final ProxyServer server;
    private final BeaconLabsVelocity plugin;
    private final Logger logger;

    public CoreCommandRegistrar(CommandManager commandManager, BeaconLabsVelocity plugin, 
                               ProxyServer server, Logger logger) {
        this.commandManager = commandManager;
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
    }    public void registerAll() {
        // Core plugin command
        commandManager.register("labsvelocity", new LabsVelocityCommand(plugin));
        
        // Staff list command
        commandManager.register("staff", new StaffCommand(plugin));
        commandManager.register("team", new StaffCommand(plugin)); // Alias
        commandManager.register("joinme", new JoinMeCommand(plugin, server)); // Alias
        
        logger.info("Core commands registered.");
    }
}
