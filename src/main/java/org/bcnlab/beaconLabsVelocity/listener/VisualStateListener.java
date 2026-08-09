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
    private final Map<UUID, String> activeFakeRanks = new ConcurrentHashMap<>();

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

        plugin.getDependencyTracker().markSupported(((ServerConnection) event.getSource()).getServerInfo().getName());

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String uuidStr = in.readUTF();
            String action = in.readUTF();
            String nickname = in.readUTF();
            String skin = in.readUTF();
            
            String rank = "";
            if (in.available() > 0) {
                rank = in.readUTF();
            } else {
                rank = skin;
            }

            UUID uuid = UUID.fromString(uuidStr);

            if ("NICK_REQUEST".equalsIgnoreCase(action)) {
                if (nickname != null && !nickname.isBlank()) {
                    // Check if player with this name is already online and not the nicking player
                    Optional<com.velocitypowered.api.proxy.Player> onlineOwner = plugin.getServer().getPlayer(nickname);
                    if (onlineOwner.isPresent() && !onlineOwner.get().getUniqueId().equals(uuid)) {
                        // Deny the nick!
                        sendForceAction(uuid, "NICK_DENIED", nickname, rank);
                        return;
                    }
                    
                    // Check if another player is already nicked with this name
                    boolean nickInUse = false;
                    for (Map.Entry<UUID, String> entry : activeNicknames.entrySet()) {
                        if (entry.getValue().equalsIgnoreCase(nickname) && !entry.getKey().equals(uuid)) {
                            nickInUse = true;
                            break;
                        }
                    }
                    
                    if (nickInUse) {
                        sendForceAction(uuid, "NICK_DENIED", nickname, rank);
                        return;
                    }

                    activeNicknames.put(uuid, nickname);
                    activeFakeRanks.put(uuid, rank);
                    plugin.getLogger().info("[VisualState] Registered nickname " + nickname + " for " + uuid);
                    applyToTab(uuid, nickname, rank);
                    
                    // Accept and broadcast to backend
                    sendForceAction(uuid, "NICK_ACCEPTED", nickname, rank);
                }
            } else if ("NICK".equalsIgnoreCase(action)) {
                if (nickname != null && !nickname.isBlank()) {
                    activeNicknames.put(uuid, nickname);
                    activeFakeRanks.put(uuid, rank);
                    applyToTab(uuid, nickname, rank);
                } else {
                    activeNicknames.remove(uuid);
                    activeFakeRanks.remove(uuid);
                    applyToTab(uuid, null, null);
                }
            } else if ("STATE_REQUEST".equalsIgnoreCase(action)) {
                if (activeNicknames.containsKey(uuid)) {
                    String currentNick = activeNicknames.get(uuid);
                    String currentRank = activeFakeRanks.getOrDefault(uuid, "");
                    sendForceAction(uuid, "NICK", currentNick, currentRank);
                } else {
                    sendForceAction(uuid, "UNNICK", "", "");
                }
            } else if ("LINK_HELLO".equalsIgnoreCase(action)) {
                plugin.getDependencyTracker().markSupported(((ServerConnection) event.getSource()).getServerInfo().getName());
            }
        } catch (Exception e) {
            plugin.getLogger().warn("Failed to parse visual state message: " + e.getMessage());
        }
    }

    private void sendForceAction(UUID targetUuid, String action) {
        sendForceAction(targetUuid, action, "", "");
    }
    
    private void sendForceAction(UUID targetUuid, String action, String nickname, String skinSource) {
        Optional<com.velocitypowered.api.proxy.Player> playerOpt = plugin.getServer().getPlayer(targetUuid);
        if (playerOpt.isPresent()) {
            Optional<ServerConnection> serverOpt = playerOpt.get().getCurrentServer();
            if (serverOpt.isPresent()) {
                try {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    java.io.DataOutputStream data = new java.io.DataOutputStream(out);
                    data.writeUTF(targetUuid.toString());
                    data.writeUTF(action);
                    data.writeUTF(nickname == null ? "" : nickname);
                    data.writeUTF(skinSource == null ? "" : skinSource);
                    serverOpt.get().sendPluginMessage(CHANNEL, out.toByteArray());
                } catch (Exception e) {
                    plugin.getLogger().warn("Failed to send force action: " + e.getMessage());
                }
            }
        }
    }

    @Subscribe
    public void onLogin(com.velocitypowered.api.event.connection.LoginEvent event) {
        String realName = event.getPlayer().getUsername();
        for (Map.Entry<UUID, String> entry : activeNicknames.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(realName)) {
                // Someone is using this player's name as a nick, force re-nick them!
                sendForceAction(entry.getKey(), "FORCE_RENICK");
            }
        }
    }

    @Subscribe
    public void onServerPreConnect(com.velocitypowered.api.event.player.ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) return;
        com.velocitypowered.api.proxy.server.RegisteredServer server = event.getResult().getServer().orElse(null);
        if (server == null) return;
        
        UUID uuid = event.getPlayer().getUniqueId();
        if (activeNicknames.containsKey(uuid)) {
            if (!plugin.getDependencyTracker().isSupported(server)) {
                // Server doesn't support Link plugin, unnick the player
                activeNicknames.remove(uuid);
                activeFakeRanks.remove(uuid);
                applyToTab(uuid, null, null);
                event.getPlayer().sendMessage(net.kyori.adventure.text.Component.text("You have been unnicked because the server you joined does not support nicknames.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }

            String nickname = activeNicknames.get(uuid);
            String rank = activeFakeRanks.getOrDefault(uuid, "");
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream data = new java.io.DataOutputStream(out);
                data.writeUTF(uuid.toString());
                data.writeUTF("NICK_PRELOAD");
                data.writeUTF(nickname);
                data.writeUTF(rank);
                server.sendPluginMessage(CHANNEL, out.toByteArray());
            } catch (Exception e) {}
        }
    }

    @Subscribe
    public void onServerConnected(com.velocitypowered.api.event.player.ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (activeNicknames.containsKey(uuid)) {
            String nickname = activeNicknames.get(uuid);
            String rank = activeFakeRanks.getOrDefault(uuid, "");
            // Send the NICK state to the new server so it can apply the nick
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                try {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    java.io.DataOutputStream data = new java.io.DataOutputStream(out);
                    data.writeUTF(uuid.toString());
                    data.writeUTF("NICK");
                    data.writeUTF(nickname);
                    data.writeUTF(rank);
                    event.getServer().sendPluginMessage(CHANNEL, out.toByteArray());
                } catch (Exception e) {}
            }).delay(java.time.Duration.ofMillis(500)).schedule();
        }
    }

    private void applyToTab(UUID uuid, String nickname, String fakeRank) {
        if (!plugin.getServer().getPluginManager().isLoaded("tab")) return;
        
        try {
            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Object tabApi = tabApiClass.getMethod("getInstance").invoke(null);
            if (tabApi == null) return;
            
            Object tabPlayer = tabApiClass.getMethod("getPlayer", java.util.UUID.class).invoke(tabApi, uuid);
            if (tabPlayer == null) return;
            
            Object tabListMgr = tabApiClass.getMethod("getTabListFormatManager").invoke(tabApi);
            Object nameTagMgr = tabApiClass.getMethod("getNameTagManager").invoke(tabApi);
            
            String prefix = "";
            String name = nickname != null ? nickname : "";
            
            if (nickname != null) {
                if (fakeRank == null || fakeRank.isBlank()) fakeRank = "default";
                if (plugin.getServer().getPluginManager().isLoaded("luckperms")) {
                    try {
                        net.luckperms.api.LuckPerms luckPerms = net.luckperms.api.LuckPermsProvider.get();
                        net.luckperms.api.model.group.Group group = luckPerms.getGroupManager().getGroup(fakeRank.toLowerCase());
                        if (group != null) {
                            String lpPrefix = group.getCachedData().getMetaData(net.luckperms.api.query.QueryOptions.defaultContextualOptions()).getPrefix();
                            if (lpPrefix != null) prefix = lpPrefix;
                        }
                    } catch (Exception e) {}
                }
            } else {
                prefix = null;
                name = null;
            }
            
            Object prefixObj = getTabComponent(prefix);
            Object nameObj = getTabComponent(name);

            if (tabListMgr != null) {
                invokeMethod(tabListMgr, "setPrefix", tabPlayer, prefixObj);
                invokeMethod(tabListMgr, "setName", tabPlayer, nameObj);
            }
            if (nameTagMgr != null) {
                invokeMethod(nameTagMgr, "setPrefix", tabPlayer, prefixObj);
            }
        } catch (Throwable t) {
            plugin.getLogger().warn("[VS] Failed to update TAB plugin: " + t.getMessage());
        }
    }

    private Object getTabComponent(String text) {
        if (text == null) return null;
        try {
            Class<?> clazz = Class.forName("me.neznamy.tab.api.chat.TabComponent");
            return clazz.getMethod("optimized", String.class).invoke(null, text);
        } catch (Exception e1) {
            try {
                Class<?> clazz = Class.forName("me.neznamy.tab.shared.chat.TabComponent");
                return clazz.getMethod("optimized", String.class).invoke(null, text);
            } catch (Exception e2) {
                try {
                    Class<?> clazz = Class.forName("me.neznamy.tab.api.chat.IChatBaseComponent");
                    return clazz.getMethod("optimizedComponent", String.class).invoke(null, text);
                } catch (Exception e3) {
                    return text; // Fallback if it accepts String
                }
            }
        }
    }

    private void invokeMethod(Object manager, String methodName, Object tabPlayer, Object value) {
        try {
            for (java.lang.reflect.Method m : manager.getClass().getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 2) {
                    m.invoke(manager, tabPlayer, value);
                    return;
                }
            }
        } catch (Exception e) {}
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
