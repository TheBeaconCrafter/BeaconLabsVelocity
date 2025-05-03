package org.bcnlab.beaconLabsVelocity.command.chat;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.command.chat.BroadcastCommand;
import org.bcnlab.beaconLabsVelocity.command.chat.ChatReportCommand;
import org.bcnlab.beaconLabsVelocity.command.chat.MessageCommand;
import org.bcnlab.beaconLabsVelocity.command.chat.ReplyCommand;
import org.bcnlab.beaconLabsVelocity.listener.FileChatLogger;
import org.slf4j.Logger;

/**
 * Registers all chat-related commands
 */
public class ChatCommandRegistrar {
    private final CommandManager commandManager;
    private final ProxyServer server;
    private final BeaconLabsVelocity plugin;
    private final Logger logger;

    public ChatCommandRegistrar(CommandManager commandManager, BeaconLabsVelocity plugin, 
                               ProxyServer server, Logger logger) {
        this.commandManager = commandManager;
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
    }    public void registerAll() {
        // ChatReport command
        commandManager.register("chatreport", new ChatReportCommand(
            new FileChatLogger(plugin.getDataDirectory().toString()), plugin, server));
        
        // Broadcast command with aliases
        BroadcastCommand broadcastCommand = new BroadcastCommand(plugin);
        commandManager.register("broadcast", broadcastCommand);
        commandManager.register("bc", broadcastCommand);
        
        // Private messaging commands
        MessageCommand messageCommand = new MessageCommand(plugin, plugin.getMessageService());
        commandManager.register("msg", messageCommand);
        commandManager.register("tell", messageCommand);
        commandManager.register("w", messageCommand);
        commandManager.register("whisper", messageCommand);
        commandManager.register("m", messageCommand);
          // Reply command
        ReplyCommand replyCommand = new ReplyCommand(plugin, plugin.getMessageService());
        commandManager.register("r", replyCommand);
        commandManager.register("reply", replyCommand);
        
        // Team chat command
        TeamChatCommand teamChatCommand = new TeamChatCommand(plugin, plugin.getMessageService());
        commandManager.register("teamchat", teamChatCommand);
        commandManager.register("tc", teamChatCommand);
    }
}
