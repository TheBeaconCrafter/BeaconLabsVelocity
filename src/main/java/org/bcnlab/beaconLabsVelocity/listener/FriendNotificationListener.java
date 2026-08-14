package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.crossproxy.CrossProxyService;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class FriendNotificationListener {

    private static final DateTimeFormatter LAST_LOGIN_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private final BeaconLabsVelocity plugin;

    public FriendNotificationListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().buildTask(plugin, () -> notifyPostLogin(player)).schedule();
    }

    private void notifyPostLogin(Player player) {
        // Notify local friends
        plugin.getServer().getAllPlayers().forEach(onlinePlayer -> {
            if (plugin.getFriendService().areFriends(onlinePlayer.getUniqueId(), player.getUniqueId())) {
                String friendAlert = plugin.getPlayerSettingsService().getPlayerSetting(onlinePlayer.getUniqueId(), "friends_join_alert", "on");
                if ("on".equalsIgnoreCase(friendAlert) || "true".equalsIgnoreCase(friendAlert)) {
                    onlinePlayer.sendMessage(plugin.getPrefix(onlinePlayer).append(Component.text("Friend ", NamedTextColor.GOLD))
                            .append(Component.text(player.getUsername(), NamedTextColor.GREEN))
                            .append(Component.text(" has joined the network.", NamedTextColor.GOLD)));
                }
            }
        });

        // Notify cross-proxy friends
        CrossProxyService crossProxyService = plugin.getCrossProxyService();
        if (crossProxyService != null && crossProxyService.isEnabled()) {
            crossProxyService.publishFriendJoin(player.getUniqueId(), player.getUsername());
        }
        
        // Notify player if they have pending requests
        int pendingCount = plugin.getFriendService().getPendingRequests(player.getUniqueId()).size();
        if (pendingCount > 0) {
            player.sendMessage(plugin.getPrefix(player).append(Component.text("You have " + pendingCount + " pending friend request(s)! Use /friend requests to view.", NamedTextColor.GOLD)));
        }
        
        // Join Summary
        String joinSummary = plugin.getPlayerSettingsService().getPlayerSetting(player.getUniqueId(), "join_summary", "off");
        if ("on".equalsIgnoreCase(joinSummary) || "true".equalsIgnoreCase(joinSummary)) {
            sendJoinSummary(player);
        }
    }

    private void sendJoinSummary(Player player) {
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            PlayerStatsService statsService = plugin.getPlayerStatsService();
            String lastLogin = "Unknown";
            if (statsService != null) {
                PlayerStatsService.PlayerData pd = statsService.getPlayerDataByName(player.getUsername());
                if (pd != null && pd.getLastSeen() > 0) {
                    lastLogin = LAST_LOGIN_FORMAT.format(Instant.ofEpochMilli(pd.getLastSeen()));
                }
            }
            
            List<UUID> friends = plugin.getFriendService().getFriends(player.getUniqueId());
            int totalFriends = friends.size();
            long onlineFriends = friends.stream().filter(uuid -> plugin.getServer().getPlayer(uuid).isPresent()).count();
            
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("Welcome back, ", NamedTextColor.GRAY).append(Component.text(player.getUsername(), NamedTextColor.GOLD)).append(Component.text(".", NamedTextColor.GRAY)));
            player.sendMessage(Component.text("» ", NamedTextColor.DARK_GRAY).append(Component.text("Last login: ", NamedTextColor.GRAY)).append(Component.text(lastLogin, NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("» ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("Of your ", NamedTextColor.GRAY))
                    .append(Component.text(totalFriends, NamedTextColor.YELLOW))
                    .append(Component.text(" friends ", NamedTextColor.GRAY))
                    .append(Component.text(onlineFriends, NamedTextColor.GREEN))
                    .append(Component.text(" are online. ", NamedTextColor.GRAY))
                    .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("/friend list", NamedTextColor.YELLOW)));
            player.sendMessage(Component.empty());
        }).delay(java.time.Duration.ofSeconds(2)).schedule(); // Delay slightly so it doesn't get buried
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().buildTask(plugin, () -> notifyDisconnect(player)).schedule();
    }

    private void notifyDisconnect(Player player) {
        // Notify local friends
        plugin.getServer().getAllPlayers().forEach(onlinePlayer -> {
            if (plugin.getFriendService().areFriends(onlinePlayer.getUniqueId(), player.getUniqueId())) {
                String friendAlert = plugin.getPlayerSettingsService().getPlayerSetting(onlinePlayer.getUniqueId(), "friends_join_alert", "on");
                if ("on".equalsIgnoreCase(friendAlert) || "true".equalsIgnoreCase(friendAlert)) {
                    onlinePlayer.sendMessage(plugin.getPrefix(onlinePlayer).append(Component.text("Friend ", NamedTextColor.GOLD))
                            .append(Component.text(player.getUsername(), NamedTextColor.GREEN))
                            .append(Component.text(" has left the network.", NamedTextColor.GOLD)));
                }
            }
        });

        // Notify cross-proxy friends
        CrossProxyService crossProxyService = plugin.getCrossProxyService();
        if (crossProxyService != null && crossProxyService.isEnabled()) {
            crossProxyService.publishFriendLeave(player.getUniqueId(), player.getUsername());
        }
    }
}
