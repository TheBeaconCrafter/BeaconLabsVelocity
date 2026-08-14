package org.bcnlab.beaconLabsVelocity.command.social;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.util.ColorParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class FriendCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;

    public FriendCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player)) {
            source.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) source;
        String[] args = invocation.arguments();

        if (args.length == 0) {
            if (invocation.alias().equalsIgnoreCase("friends")) {
                handleList(player, true);
            } else {
                sendHelp(player);
            }
            return;
        }

        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "add":
                if (args.length < 2) {
                    player.sendMessage(plugin.getPrefix(player).append(Component.text("Usage: /friend add <player>", NamedTextColor.GRAY)));
                    return;
                }
                handleAdd(player, args[1]);
                break;
            case "accept":
                if (args.length < 2) {
                    player.sendMessage(plugin.getPrefix(player).append(Component.text("Usage: /friend accept <player>", NamedTextColor.GRAY)));
                    return;
                }
                handleAccept(player, args[1]);
                break;
            case "deny":
                if (args.length < 2) {
                    player.sendMessage(plugin.getPrefix(player).append(Component.text("Usage: /friend deny <player>", NamedTextColor.GRAY)));
                    return;
                }
                handleDeny(player, args[1]);
                break;
            case "remove":
                if (args.length < 2) {
                    player.sendMessage(plugin.getPrefix(player).append(Component.text("Usage: /friend remove <player>", NamedTextColor.GRAY)));
                    return;
                }
                handleRemove(player, args[1]);
                break;
            case "list":
                handleList(player, false);
                break;
            case "requests":
                handleRequests(player);
                break;
            case "jump":
                if (args.length < 2) {
                    player.sendMessage(plugin.getPrefix(player).append(Component.text("Usage: /friend jump <player>", NamedTextColor.GRAY)));
                    return;
                }
                handleJump(player, args[1]);
                break;
            default:
                sendHelp(player);
                break;
        }
    }

    private void handleJump(Player player, String targetName) {
        UUID targetUuid = getPlayerUuid(targetName);
        if (targetUuid == null) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("Player not found.", NamedTextColor.RED)));
            return;
        }
        if (!plugin.getFriendService().areFriends(player.getUniqueId(), targetUuid)) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("You are not friends with " + targetName + ".", NamedTextColor.RED)));
            return;
        }
        
        Optional<Player> optTarget = plugin.getServer().getPlayer(targetUuid);
        if (optTarget.isPresent() && optTarget.get().getCurrentServer().isPresent()) {
            String serverName = optTarget.get().getCurrentServer().get().getServerInfo().getName();
            player.sendMessage(plugin.getPrefix(player).append(Component.text("Jumping to " + serverName + "...", NamedTextColor.GREEN)));
            player.createConnectionRequest(optTarget.get().getCurrentServer().get().getServer()).fireAndForget();
        } else if (plugin.getCrossProxyService() != null) {
            // Find server via CrossProxy
            String serverName = null;
            String proxyId = plugin.getCrossProxyService().getPlayerProxy(targetUuid);
            if (proxyId != null) {
                for (java.util.Map.Entry<String, String> entry : plugin.getCrossProxyService().getPlayerListForProxy(proxyId)) {
                    if (entry.getKey().equalsIgnoreCase(targetName)) {
                        serverName = entry.getValue();
                        break;
                    }
                }
            }
            if (serverName != null) {
                Optional<com.velocitypowered.api.proxy.server.RegisteredServer> server = plugin.getServer().getServer(serverName);
                if (server.isPresent()) {
                    player.sendMessage(plugin.getPrefix(player).append(Component.text("Jumping to " + serverName + "...", NamedTextColor.GREEN)));
                    player.createConnectionRequest(server.get()).fireAndForget();
                } else {
                    player.sendMessage(plugin.getPrefix(player).append(Component.text("Server " + serverName + " is not available on this proxy.", NamedTextColor.RED)));
                }
            } else {
                player.sendMessage(plugin.getPrefix(player).append(Component.text("Player is not online.", NamedTextColor.RED)));
            }
        } else {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("Player is not online.", NamedTextColor.RED)));
        }
    }

    private void handleAdd(Player player, String targetName) {
        UUID targetUuid = getPlayerUuid(targetName);
        if (targetUuid == null) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("Player not found.", NamedTextColor.RED)));
            return;
        }

        if (targetUuid.equals(player.getUniqueId())) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("You cannot add yourself.", NamedTextColor.RED)));
            return;
        }

        if (plugin.getFriendService().areFriends(player.getUniqueId(), targetUuid)) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("You are already friends with " + targetName + ".", NamedTextColor.RED)));
            return;
        }

        String privacy = "everyone";
        if (plugin.getPlayerSettingsService() != null) {
            privacy = plugin.getPlayerSettingsService().getPlayerSetting(targetUuid, "friend_requests", "everyone");
        }
        if ("nobody".equalsIgnoreCase(privacy)) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("This player is not accepting friend requests.", NamedTextColor.RED)));
            return;
        }

        plugin.getFriendService().sendFriendRequest(player.getUniqueId(), targetUuid);
        player.sendMessage(plugin.getPrefix(player).append(Component.text("Friend request sent to " + targetName + ".", NamedTextColor.GREEN)));
        
        // Notify target locally or via cross proxy
        Optional<Player> targetPlayer = plugin.getServer().getPlayer(targetUuid);
        if (targetPlayer.isPresent()) {
            targetPlayer.get().sendMessage(plugin.getPrefix().append(Component.text("You have a new friend request from ", NamedTextColor.GOLD))
                    .append(Component.text(player.getUsername(), NamedTextColor.GREEN))
                    .append(Component.text("! ", NamedTextColor.GOLD))
                    .append(Component.text("[Click to Accept]", NamedTextColor.GREEN)
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Accept " + player.getUsername() + "'s request")))
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/friend accept " + player.getUsername()))));
        } else if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            plugin.getCrossProxyService().publishFriendRequest(targetUuid, player.getUsername());
        }
    }

    private void handleAccept(Player player, String targetName) {
        UUID targetUuid = getPlayerUuid(targetName);
        if (targetUuid == null) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("Player not found.", NamedTextColor.RED)));
            return;
        }

        plugin.getFriendService().acceptFriendRequest(player.getUniqueId(), targetUuid);
        player.sendMessage(plugin.getPrefix(player).append(Component.text("You are now friends with " + targetName + ".", NamedTextColor.GREEN)));

        Optional<Player> targetPlayer = plugin.getServer().getPlayer(targetUuid);
        if (targetPlayer.isPresent()) {
            targetPlayer.get().sendMessage(plugin.getPrefix().append(Component.text(player.getUsername(), NamedTextColor.GREEN))
                    .append(Component.text(" has accepted your friend request!", NamedTextColor.GOLD)));
        } else if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            plugin.getCrossProxyService().publishFriendAccept(targetUuid, player.getUsername());
        }
    }

    private void handleDeny(Player player, String targetName) {
        UUID targetUuid = getPlayerUuid(targetName);
        if (targetUuid == null) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("Player not found.", NamedTextColor.RED)));
            return;
        }

        plugin.getFriendService().denyFriendRequest(player.getUniqueId(), targetUuid);
        player.sendMessage(plugin.getPrefix(player).append(Component.text("Denied friend request from " + targetName + ".", NamedTextColor.GOLD)));
    }

    private void handleRemove(Player player, String targetName) {
        UUID targetUuid = getPlayerUuid(targetName);
        if (targetUuid == null) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("Player not found.", NamedTextColor.RED)));
            return;
        }

        plugin.getFriendService().removeFriend(player.getUniqueId(), targetUuid);
        player.sendMessage(plugin.getPrefix(player).append(Component.text("Removed " + targetName + " from your friends list.", NamedTextColor.GOLD)));
    }

    private void handleList(Player player, boolean useGui) {
        if (useGui && player.getCurrentServer().isPresent() && plugin.getDependencyTracker().isSupported(player.getCurrentServer().get().getServerInfo().getName())) {
            try {
                com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier openId = com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from("beaconlabs:friend_gui_open");
                player.getCurrentServer().get().sendPluginMessage(openId, new byte[0]);
            } catch (Exception e) {}
        }
        
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            var friends = plugin.getFriendService().getDetailedFriends(player.getUniqueId());
            
            if (useGui && player.getCurrentServer().isPresent() && plugin.getDependencyTracker().isSupported(player.getCurrentServer().get())) {
                com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier openId = com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from("beaconlabs:friend_open");
                boolean canGui = player.getCurrentServer().get().sendPluginMessage(openId, new byte[]{});
                if (canGui) {
                    com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier identifier = com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from("beaconlabs:friend_dialog");
                    try {
                        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                        java.io.DataOutputStream out = new java.io.DataOutputStream(b);
                        out.writeInt(friends.size());
                        java.util.Set<String> onlineNames = plugin.getCrossProxyService() != null
                                ? plugin.getCrossProxyService().getOnlinePlayerNames() : java.util.Set.of();
                        for (var friend : friends) {
                            String name = getPlayerName(friend.uuid);
                            boolean isOnline = onlineNames.contains(name);
                            out.writeUTF(friend.uuid.toString());
                            out.writeUTF(name);
                            out.writeBoolean(isOnline);
                            out.writeLong(friend.friendsSince);
                            
                            long lastOnline = 0;
                            if (plugin.getPlayerStatsService() != null) {
                                lastOnline = plugin.getPlayerStatsService().getLastSeenTime(friend.uuid);
                            }
                            out.writeLong(lastOnline);
                            
                            // friend_server parsing
                            String friendServer = "";
                            if (isOnline) {
                                String friendServerPrivacy = plugin.getPlayerSettingsService() != null ? plugin.getPlayerSettingsService().getPlayerSetting(friend.uuid, "friend_server", "friends_only") : "friends_only";
                                if (friendServerPrivacy.equalsIgnoreCase("nobody")) {
                                    friendServer = "Hidden";
                                } else if (friendServerPrivacy.equalsIgnoreCase("friends_only") && !plugin.getFriendService().areFriends(friend.uuid, player.getUniqueId())) {
                                    friendServer = "Hidden";
                                } else {
                                    // Find server name
                                    java.util.Optional<com.velocitypowered.api.proxy.Player> localPlayer = plugin.getServer().getPlayer(friend.uuid);
                                    if (localPlayer.isPresent() && localPlayer.get().getCurrentServer().isPresent()) {
                                        friendServer = localPlayer.get().getCurrentServer().get().getServerInfo().getName();
                                    } else if (plugin.getCrossProxyService() != null) {
                                        String currentServer = plugin.getCrossProxyService().getPlayerCurrentServer(name);
                                        if (currentServer != null) friendServer = currentServer;
                                    }
                                }
                            }
                            out.writeUTF(friendServer);
                        }
                        player.getCurrentServer().get().sendPluginMessage(identifier, b.toByteArray());
                    } catch (Exception e) {}
                    return;
                }
            }
            
            player.sendMessage(ColorParser.heading("Friends List"));
            if (friends.isEmpty()) {
                player.sendMessage(Component.text("You have no friends added.", NamedTextColor.GRAY));
                return;
            }
            java.util.Set<String> onlineNames = plugin.getCrossProxyService() != null
                    ? plugin.getCrossProxyService().getOnlinePlayerNames() : java.util.Set.of();
            for (var friend : friends) {
                String name = getPlayerName(friend.uuid);
                boolean isOnline = onlineNames.contains(name);
                Component status;
                if (isOnline) {
                    String friendServer = "Unknown";
                    String privacy = plugin.getPlayerSettingsService().getPlayerSetting(friend.uuid, "friend_server", "friends_only");
                    if (privacy.equals("nobody")) {
                        friendServer = "Hidden";
                    } else {
                        java.util.Optional<com.velocitypowered.api.proxy.Player> localPlayer = plugin.getServer().getPlayer(friend.uuid);
                        if (localPlayer.isPresent() && localPlayer.get().getCurrentServer().isPresent()) {
                            friendServer = localPlayer.get().getCurrentServer().get().getServerInfo().getName();
                        } else if (plugin.getCrossProxyService() != null) {
                            String currentServer = plugin.getCrossProxyService().getPlayerCurrentServer(name);
                            if (currentServer != null) friendServer = currentServer;
                        }
                    }
                    status = Component.text(" [Online - " + friendServer + "]", NamedTextColor.GREEN);
                } else {
                    status = Component.text(" [Offline]", NamedTextColor.RED);
                }
                player.sendMessage(Component.text("- " + name, NamedTextColor.GRAY).append(status));
            }
        }).schedule();
    }

    private void handleRequests(Player player) {
        List<UUID> requests = plugin.getFriendService().getPendingRequests(player.getUniqueId());
        if (requests.isEmpty()) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("You have no pending friend requests.", NamedTextColor.GOLD)));
            return;
        }
        
        player.sendMessage(ColorParser.heading("Pending Friend Requests"));
        for (UUID requesterUuid : requests) {
            String name = getPlayerName(requesterUuid);
            player.sendMessage(Component.text("- " + name, NamedTextColor.GRAY)
                    .append(Component.text(" [Accept]", NamedTextColor.GREEN)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/friend accept " + name))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Accept friend request"))))
                    .append(Component.text(" [Deny]", NamedTextColor.RED)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/friend deny " + name))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Deny friend request")))));
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(ColorParser.heading("Friend Commands"));
        player.sendMessage(Component.text("/friend add <player>", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/friend accept <player>", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/friend deny <player>", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/friend remove <player>", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/friend list", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/friend requests", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/friend jump <player>", NamedTextColor.GRAY));
    }
    
    private UUID getPlayerUuid(String name) {
        Optional<Player> opt = plugin.getServer().getPlayer(name);
        if (opt.isPresent()) return opt.get().getUniqueId();
        if (plugin.getCrossProxyService() != null) {
            UUID id = plugin.getCrossProxyService().getPlayerUuidByName(name);
            if (id != null) return id;
        }
        if (plugin.getPlayerStatsService() != null) {
            var data = plugin.getPlayerStatsService().getPlayerDataByName(name);
            if (data != null) return data.getPlayerId();
        }
        return null;
    }
    
    private String getPlayerName(UUID uuid) {
        Optional<Player> opt = plugin.getServer().getPlayer(uuid);
        if (opt.isPresent()) return opt.get().getUsername();
        
        try (java.sql.Connection conn = plugin.getDatabaseManager().getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement("SELECT username FROM player_profiles WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("username");
            }
        } catch (Exception e) {}
        
        return uuid.toString();
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0 || args.length == 1) {
            String input = args.length == 0 ? "" : args[0].toLowerCase();
            return List.of("add", "accept", "deny", "remove", "list", "requests", "jump").stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList());
        } else if (args.length == 2 && List.of("add", "accept", "deny", "remove", "jump").contains(args[0].toLowerCase())) {
            String input = args[1].toLowerCase();
            if (plugin.getCrossProxyService() != null) {
                return plugin.getCrossProxyService().getOnlinePlayerNames().stream()
                        .filter(name -> name.toLowerCase().startsWith(input))
                        .collect(Collectors.toList());
            }
            return plugin.getServer().getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
