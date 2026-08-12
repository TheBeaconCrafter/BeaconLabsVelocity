package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.ServerConnection;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

public class ServerInfoListener {

    private final BeaconLabsVelocity plugin;
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("beaconlabs:server_info");

    public ServerInfoListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }

        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String subchannel = in.readUTF();
            if (subchannel.equals("Request")) {
                String serverName = in.readUTF();
                Optional<RegisteredServer> targetOpt = plugin.getServer().getServer(serverName);
                if (targetOpt.isPresent()) {
                    RegisteredServer target = targetOpt.get();
                    target.ping().whenComplete((ping, throwable) -> {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        DataOutputStream out = new DataOutputStream(b);
                        try {
                            out.writeUTF("Response");
                            out.writeUTF(serverName);
                            if (throwable != null || ping == null) {
                                out.writeBoolean(false); // offline
                                out.writeInt(0);
                                out.writeInt(0);
                            } else {
                                out.writeBoolean(true); // online
                                out.writeInt(ping.getPlayers().map(p -> p.getOnline()).orElse(target.getPlayersConnected().size()));
                                out.writeInt(ping.getPlayers().map(p -> p.getMax()).orElse(0));
                            }
                            if (event.getSource() instanceof ServerConnection) {
                                ((ServerConnection) event.getSource()).getServer().sendPluginMessage(CHANNEL, b.toByteArray());
                            }
                        } catch (IOException e) {
                            plugin.getLogger().error("Failed to write server info response", e);
                        }
                    });
                }
            }
        } catch (IOException e) {
            plugin.getLogger().error("Failed to parse server info request", e);
        }
    }
}
