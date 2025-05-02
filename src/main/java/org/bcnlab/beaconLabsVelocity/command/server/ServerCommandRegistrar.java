package org.bcnlab.beaconLabsVelocity.command.server;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;

/**
 * Registers all server-related commands
 */
public class ServerCommandRegistrar {
    private final CommandManager commandManager;
    private final ProxyServer server;
    private final BeaconLabsVelocity plugin;
    private final Logger logger;

    public ServerCommandRegistrar(CommandManager commandManager, BeaconLabsVelocity plugin, 
                                  ProxyServer server, Logger logger) {
        this.commandManager = commandManager;
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
    }

    public void registerAll() {
        // Lobby command with aliases
        LobbyCommand lobbyCommand = new LobbyCommand(plugin, server);
        commandManager.register("lobby", lobbyCommand);
        commandManager.register("l", lobbyCommand);
        commandManager.register("hub", lobbyCommand);
    }
}
