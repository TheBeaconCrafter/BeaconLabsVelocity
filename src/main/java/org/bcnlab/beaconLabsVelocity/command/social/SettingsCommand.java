package org.bcnlab.beaconLabsVelocity.command.social;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.Optional;

public class SettingsCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;

    public SettingsCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        if (!(source instanceof Player)) {
            source.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) source;
        Optional<ServerConnection> serverOptional = player.getCurrentServer();

        if (serverOptional.isPresent()) {
            ServerConnection server = serverOptional.get();
            com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier identifier = com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from("beaconlabs:settings_dialog");
            
            try {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream out = new java.io.DataOutputStream(b);
                out.writeUTF(player.getUniqueId().toString());
                out.writeUTF(plugin.getPlayerSettingsService().getPlayerSetting(player.getUniqueId(), "msg_privacy", "everyone"));
                out.writeUTF(plugin.getPlayerSettingsService().getPlayerSetting(player.getUniqueId(), "friend_requests", "everyone"));
                out.writeUTF(plugin.getPlayerSettingsService().getPlayerSetting(player.getUniqueId(), "friend_server", "everyone"));
                
                server.sendPluginMessage(identifier, b.toByteArray());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("You must be connected to a server.", NamedTextColor.RED)));
        }
    }
}
