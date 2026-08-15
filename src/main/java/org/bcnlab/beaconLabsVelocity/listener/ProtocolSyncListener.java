package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public class ProtocolSyncListener {

    private final BeaconLabsVelocity plugin;
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("beaconlabs:protocol_version");
    public static final MinecraftChannelIdentifier REQUEST_CHANNEL = MinecraftChannelIdentifier.from("beaconlabs:protocol_request");

    public ProtocolSyncListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        RegisteredServer server = event.getServer();
        plugin.getLogger().debug("[Dim proxy-debug] Backend connected for {} -> {}; sending protocol handshake.", player.getUsername(), server.getServerInfo().getName());
        sendProtocol(player, server);
    }

    private void sendProtocol(Player player, RegisteredServer server) {
        int velocityProtocol = player.getProtocolVersion().getProtocol();
        int protocol = velocityProtocol;
        String protocolSource = "Velocity";
        if (plugin.getServer().getPluginManager().isLoaded("viaversion")) {
            try {
                int viaProtocol = com.viaversion.viaversion.api.Via.getAPI().getPlayerVersion(player.getUniqueId());
                if (viaProtocol > 0) {
                    protocol = viaProtocol;
                    protocolSource = "ViaVersion";
                }
            } catch (Exception ignored) {
                // Fall back to Velocity's handshake version when ViaVersion is
                // present but has not populated its player connection yet.
            }
        }

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(player.getUniqueId().toString());
            output.writeInt(protocol);
            output.flush();
            boolean accepted = player.sendPluginMessage(CHANNEL, bytes.toByteArray());
            plugin.getLogger().debug("[Dim proxy-debug] Sent {} through {}'s connection to {} for {}: protocol={} (source={}, velocityProtocol={}, accepted={}).",
                    CHANNEL.getId(), player.getUsername(), server.getServerInfo().getName(), player.getUsername(), protocol, protocolSource, velocityProtocol, accepted);
            if (!accepted) {
                plugin.getLogger().warn("[Dim proxy-debug] Velocity did not accept the Dim protocol payload for {} on {}.", player.getUsername(), server.getServerInfo().getName());
            }
        } catch (IOException exception) {
            plugin.getLogger().warn("Failed to send Dim protocol data for {}.", player.getUsername(), exception);
        }
    }

    @Subscribe
    public void onClientPluginMessage(PluginMessageEvent event) {
        if (REQUEST_CHANNEL.equals(event.getIdentifier())) {
            // A backend request is trusted only because it arrived from a
            // registered server connection. Never forward it to a player.
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            if (event.getSource() instanceof ServerConnection backend) {
                plugin.getLogger().info("[Dim proxy-debug] Received protocol request from backend {} (bytes={}).", backend.getServerInfo().getName(), event.getData().length);
                handleProtocolRequest(event.getData(), backend);
            } else {
                plugin.getLogger().warn("[Dim proxy-debug] Rejected protocol request from non-backend source {}.", event.getSource().getClass().getName());
            }
            return;
        }

        if (!CHANNEL.equals(event.getIdentifier()) || !(event.getSource() instanceof Player)) {
            return;
        }

        // The client can send arbitrary plugin messages. Never forward this
        // channel, otherwise it could spoof a different protocol before the
        // proxy's authoritative message reaches the backend.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    private void handleProtocolRequest(byte[] data, ServerConnection backend) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            UUID uuid = UUID.fromString(input.readUTF());
            plugin.getServer().getPlayer(uuid).ifPresent(player -> {
                if (player.getCurrentServer().map(connection -> connection.getServer().equals(backend.getServer())).orElse(false)) {
                    plugin.getLogger().info("[Dim proxy-debug] Request UUID {} matched {}; sending response.", uuid, player.getUsername());
                    sendProtocol(player, backend.getServer());
                } else {
                    plugin.getLogger().warn("[Dim proxy-debug] Protocol request UUID {} is not connected to requesting backend {}.", uuid, backend.getServerInfo().getName());
                }
            });
        } catch (Exception exception) {
            plugin.getLogger().warn("Failed to process a Dim protocol request from {}.", backend.getServerInfo().getName(), exception);
        }
    }
}
