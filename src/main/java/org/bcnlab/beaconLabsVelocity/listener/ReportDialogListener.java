package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.command.ReportsCommand;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

public class ReportDialogListener {
    private final BeaconLabsVelocity plugin;
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("beaconlabs:report_dialog");

    public ReportDialogListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }

        // Only process messages coming from backend servers
        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }
        
        ServerConnection connection = (ServerConnection) event.getSource();
        Player player = connection.getPlayer();

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String action = in.readUTF();

            if ("FETCH_REPORTS".equals(action)) {
                // Emulate executing /reports list
                // Since this is just to show in chat as fallback from GUI request
                com.velocitypowered.api.command.CommandManager commandManager = plugin.getServer().getCommandManager();
                commandManager.executeAsync(player, "reports list");
            }
        } catch (Exception e) {
            plugin.getLogger().warn("Failed to parse report dialog message: " + e.getMessage());
        }
    }
}
