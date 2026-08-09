package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class ProtocolSyncListener {

    private final BeaconLabsVelocity plugin;
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("beaconlabs:protocol_version");

    public ProtocolSyncListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        int protocol = player.getProtocolVersion().getProtocol();
        if (plugin.getServer().getPluginManager().isLoaded("viaversion")) {
            try {
                protocol = com.viaversion.viaversion.api.Via.getAPI().getPlayerVersion(player.getUniqueId());
            } catch (Exception e) {}
        }

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        try {
            out.writeUTF(player.getUniqueId().toString());
            out.writeInt(protocol);
            event.getServer().sendPluginMessage(CHANNEL, b.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
