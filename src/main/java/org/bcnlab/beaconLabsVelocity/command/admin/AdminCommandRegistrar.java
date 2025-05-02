package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;

/**
 * Registers all admin commands
 */
public class AdminCommandRegistrar {
    private final CommandManager commandManager;
    private final ProxyServer server;
    private final BeaconLabsVelocity plugin;

    public AdminCommandRegistrar(CommandManager commandManager, BeaconLabsVelocity plugin, 
                                ProxyServer server, Logger logger) {
        this.commandManager = commandManager;
        this.plugin = plugin;
        this.server = server;
    }

    public void registerAll() {
        // GoTo command
        commandManager.register("goto", new GoToCommand(plugin, server));
    }
}
