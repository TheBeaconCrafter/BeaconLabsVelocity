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
                String msgPrivacy = plugin.getPlayerSettingsService().getPlayerSetting(player.getUniqueId(), "msg_privacy", "everyone");
                String friendReq = plugin.getPlayerSettingsService().getPlayerSetting(player.getUniqueId(), "friend_requests", "everyone");
                String friendServer = plugin.getPlayerSettingsService().getPlayerSetting(player.getUniqueId(), "friend_server", "friends_only");
                String friendsAlert = plugin.getPlayerSettingsService().getPlayerSetting(player.getUniqueId(), "friends_join_alert", "on");
                String joinSum = plugin.getPlayerSettingsService().getPlayerSetting(player.getUniqueId(), "join_summary", "off");
                
                out.writeUTF(msgPrivacy);
                out.writeUTF(friendReq);
                out.writeUTF(friendServer);
                out.writeUTF(friendsAlert);
                out.writeUTF(joinSum);
                
                boolean sent = server.sendPluginMessage(identifier, b.toByteArray());
                if (!sent) {
                    player.sendMessage(Component.text("--- Settings ---", NamedTextColor.GOLD));
                    player.sendMessage(Component.text("MSG Privacy: ", NamedTextColor.GRAY).append(Component.text(msgPrivacy, getColorForValue(msgPrivacy))));
                    player.sendMessage(Component.text("Friend Requests: ", NamedTextColor.GRAY).append(Component.text(friendReq, getColorForValue(friendReq))));
                    player.sendMessage(Component.text("Friend Server: ", NamedTextColor.GRAY).append(Component.text(friendServer, getColorForValue(friendServer))));
                    player.sendMessage(Component.text("Friends Join Alert: ", NamedTextColor.GRAY).append(Component.text(friendsAlert, getColorForValue(friendsAlert))));
                    player.sendMessage(Component.text("Join Summary: ", NamedTextColor.GRAY).append(Component.text(joinSum, getColorForValue(joinSum))));
                    player.sendMessage(Component.text("(Use the Lobby GUI to change settings)", NamedTextColor.GRAY));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("You must be connected to a server.", NamedTextColor.RED)));
        }
    }

    private NamedTextColor getColorForValue(String value) {
        if (value == null) return NamedTextColor.GRAY;
        return switch (value.toLowerCase()) {
            case "everyone", "on", "true" -> NamedTextColor.GREEN;
            case "friends_only" -> NamedTextColor.GOLD;
            case "nobody", "off", "false" -> NamedTextColor.RED;
            default -> NamedTextColor.WHITE;
        };
    }
}
