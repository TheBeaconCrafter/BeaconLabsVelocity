package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Optional;
import java.util.UUID;

public class ProxyCommandListener {
    private final BeaconLabsVelocity plugin;
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("beaconlabs:proxy_command");

    public ProxyCommandListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;
        
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String uuidStr = in.readUTF();
            String command = in.readUTF();
            UUID uuid = UUID.fromString(uuidStr);
            
            Optional<Player> playerOpt = plugin.getServer().getPlayer(uuid);
            if (playerOpt.isPresent()) {
                Player player = playerOpt.get();
                // Execute the proxy command logic directly
                plugin.getServer().getCommandManager().executeAsync(player, command);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
