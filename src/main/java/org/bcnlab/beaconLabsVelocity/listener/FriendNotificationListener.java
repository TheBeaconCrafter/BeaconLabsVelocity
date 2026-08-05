package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.crossproxy.CrossProxyService;

public class FriendNotificationListener {

    private final BeaconLabsVelocity plugin;

    public FriendNotificationListener(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        
        // Notify local friends
        plugin.getServer().getAllPlayers().forEach(onlinePlayer -> {
            if (plugin.getFriendService().areFriends(onlinePlayer.getUniqueId(), player.getUniqueId())) {
                onlinePlayer.sendMessage(plugin.getPrefix().append(Component.text("Friend ", NamedTextColor.YELLOW))
                        .append(Component.text(player.getUsername(), NamedTextColor.GREEN))
                        .append(Component.text(" has joined the network.", NamedTextColor.YELLOW)));
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
            player.sendMessage(plugin.getPrefix().append(Component.text("You have " + pendingCount + " pending friend request(s)! Use /friend requests to view.", NamedTextColor.YELLOW)));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        
        // Notify local friends
        plugin.getServer().getAllPlayers().forEach(onlinePlayer -> {
            if (plugin.getFriendService().areFriends(onlinePlayer.getUniqueId(), player.getUniqueId())) {
                onlinePlayer.sendMessage(plugin.getPrefix().append(Component.text("Friend ", NamedTextColor.YELLOW))
                        .append(Component.text(player.getUsername(), NamedTextColor.GREEN))
                        .append(Component.text(" has left the network.", NamedTextColor.YELLOW)));
            }
        });

        // Notify cross-proxy friends
        CrossProxyService crossProxyService = plugin.getCrossProxyService();
        if (crossProxyService != null && crossProxyService.isEnabled()) {
            crossProxyService.publishFriendLeave(player.getUniqueId(), player.getUsername());
        }
    }
}
