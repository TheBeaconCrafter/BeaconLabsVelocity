package org.bcnlab.beaconLabsVelocity.command.util;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;

/**
 * Registers all utility commands
 */
public class UtilCommandRegistrar {
    private final CommandManager commandManager;
    private final ProxyServer server;
    private final BeaconLabsVelocity plugin;

    public UtilCommandRegistrar(CommandManager commandManager, BeaconLabsVelocity plugin, 
                                ProxyServer server, Logger logger) {
        this.commandManager = commandManager;
        this.plugin = plugin;
        this.server = server;
    }    public void registerAll() {
        // Ping command
        commandManager.register("ping", new PingCommand(plugin, server));
        
        // Skin command
        commandManager.register("skin", new SkinCommand(plugin, server));
        
        // Playtime command
        if (plugin.getPlayerStatsService() != null) {
            PlaytimeCommand playtimeCommand = new PlaytimeCommand(plugin, server, plugin.getPlayerStatsService());
            commandManager.register("playtime", playtimeCommand);
            commandManager.register("pt", playtimeCommand); // Alias
        }

        // Ente (duck) command
        commandManager.register("ente", new EnteCommand(plugin, server));
    }
}
