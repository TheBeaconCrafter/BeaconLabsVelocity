package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.PlayerSettingsService;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class SettingsUpdateListener {
    private final BeaconLabsVelocity plugin;
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("beaconlabs:settings_update");

    public SettingsUpdateListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;

        if (!(event.getSource() instanceof com.velocitypowered.api.proxy.ServerConnection)) return;
        
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String uuidStr = in.readUTF();
            String key = in.readUTF();
            String value = in.readUTF();
            
            UUID uuid = UUID.fromString(uuidStr);
            PlayerSettingsService settingsService = plugin.getPlayerSettingsService();
            settingsService.savePlayerSetting(uuid, key, value);
            
            Optional<Player> playerOpt = plugin.getServer().getPlayer(uuid);
            if (playerOpt.isPresent()) {
                playerOpt.get().sendMessage(plugin.getPrefix().append(Component.text("Setting " + key + " updated to " + value, NamedTextColor.GREEN)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
