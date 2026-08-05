package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import com.velocitypowered.api.proxy.ServerConnection;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

public class VisualStateListener {

    private final BeaconLabsVelocity plugin;
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("beaconlabs:visual_state");
    
    // Store active nicknames
    private final Map<UUID, String> activeNicknames = new ConcurrentHashMap<>();

    public VisualStateListener(BeaconLabsVelocity plugin) {
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

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String uuidStr = in.readUTF();
            String action = in.readUTF();
            String nickname = in.readUTF();
            String skin = in.readUTF();

            UUID uuid = UUID.fromString(uuidStr);

            if ("NICK".equalsIgnoreCase(action)) {
                if (nickname != null && !nickname.isBlank()) {
                    activeNicknames.put(uuid, nickname);
                    plugin.getLogger().info("[VisualState] Registered nickname " + nickname + " for " + uuid);
                } else {
                    activeNicknames.remove(uuid);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warn("Failed to parse visual state message: " + e.getMessage());
        }
    }
    
    public String getNickname(UUID uuid) {
        return activeNicknames.get(uuid);
    }
    
    @Subscribe
    public void onCommandExecute(com.velocitypowered.api.event.command.CommandExecuteEvent event) {
        if (!event.getResult().isAllowed()) return;
        
        String command = event.getCommand();
        if (command == null || command.isEmpty()) return;
        
        String[] parts = command.split(" ", 3);
        if (parts.length < 2) return;
        
        String cmdLabel = parts[0].toLowerCase();
        
        // Commands where args[0] is a player name
        if (java.util.List.of("msg", "tell", "w", "whisper", "m", "ban", "kick", "mute", "unban", "punishments").contains(cmdLabel)) {
            String target = parts[1];
            UUID realUuid = getUuidByNickname(target);
            if (realUuid != null) {
                Optional<com.velocitypowered.api.proxy.Player> p = plugin.getServer().getPlayer(realUuid);
                if (p.isPresent()) {
                    String realName = p.get().getUsername();
                    String newCommand = cmdLabel + " " + realName;
                    if (parts.length > 2) newCommand += " " + parts[2];
                    
                    event.setResult(com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult.command(newCommand));
                }
            }
        }
        
        // Friend command (e.g. /friend add <name>)
        if (java.util.List.of("friend", "f", "friends").contains(cmdLabel) && parts.length > 2) {
            String subCmd = parts[1].toLowerCase();
            if (java.util.List.of("add", "accept", "deny", "remove").contains(subCmd)) {
                String target = parts[2].split(" ")[0]; // Just in case there are more args
                UUID realUuid = getUuidByNickname(target);
                if (realUuid != null) {
                    Optional<com.velocitypowered.api.proxy.Player> p = plugin.getServer().getPlayer(realUuid);
                    if (p.isPresent()) {
                        String realName = p.get().getUsername();
                        String newCommand = cmdLabel + " " + subCmd + " " + realName;
                        event.setResult(com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult.command(newCommand));
                    }
                }
            }
        }
    }
    
    @Subscribe
    public void onTabComplete(com.velocitypowered.api.event.player.TabCompleteEvent event) {
        java.util.List<String> suggestions = event.getSuggestions();
        boolean changed = false;
        
        for (int i = 0; i < suggestions.size(); i++) {
            String suggestion = suggestions.get(i);
            
            // If the suggestion matches a real username of a nicked player, replace it
            for (Map.Entry<UUID, String> entry : activeNicknames.entrySet()) {
                Optional<com.velocitypowered.api.proxy.Player> p = plugin.getServer().getPlayer(entry.getKey());
                if (p.isPresent() && p.get().getUsername().equalsIgnoreCase(suggestion)) {
                    suggestions.set(i, entry.getValue());
                    changed = true;
                    break;
                }
            }
        }
        
        if (changed) {
            event.getSuggestions().clear();
            event.getSuggestions().addAll(suggestions);
        }
    }


    public UUID getUuidByNickname(String nickname) {
        for (Map.Entry<UUID, String> entry : activeNicknames.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(nickname)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
