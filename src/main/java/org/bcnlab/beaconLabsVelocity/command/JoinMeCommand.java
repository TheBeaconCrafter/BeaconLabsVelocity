package org.bcnlab.beaconLabsVelocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Command that allows players to invite others to join their server
 */
public class JoinMeCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
      // Permissions
    private final String usePermission;
    private final String cooldownBypassPermission;
    
    // Cooldowns (player UUID -> timestamp of last use)
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final long cooldownMillis;
      public JoinMeCommand(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
        
        // Get permission values from config
        this.usePermission = plugin.getConfig().node("joinme", "permissions", "use")
            .getString("beaconlabs.command.joinme");
        this.cooldownBypassPermission = plugin.getConfig().node("joinme", "permissions", "bypass-cooldown")
            .getString("beaconlabs.command.joinme.bypass");
        
        // Get cooldown value from config (default to 5 minutes if not specified)
        int cooldownSeconds = plugin.getConfig().node("joinme", "cooldown-seconds").getInt(300);
        this.cooldownMillis = cooldownSeconds * 1000L;
    }
    
    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        // Check if the source is a player
        if (!(source instanceof Player)) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Only players can use this command.", NamedTextColor.RED)
            ));
            return;
        }
        
        Player player = (Player) source;
          // Check permission
        if (!player.hasPermission(usePermission)) {
            player.sendMessage(plugin.getPrefix().append(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Check if player is on a server
        Optional<ServerConnection> currentServer = player.getCurrentServer();
        if (currentServer.isEmpty()) {
            player.sendMessage(plugin.getPrefix().append(
                Component.text("You must be connected to a server to use this command.", NamedTextColor.RED)
            ));
            return;
        }
        
        String serverName = currentServer.get().getServer().getServerInfo().getName();
          // Check cooldown (if no bypass permission)
        if (!player.hasPermission(cooldownBypassPermission)) {
            if (isOnCooldown(player)) {
                long timeLeft = getRemainingCooldown(player);
                player.sendMessage(plugin.getPrefix().append(
                    Component.text("You can use this command again in " + 
                        formatTimeRemaining(timeLeft) + ".", NamedTextColor.RED)
                ));
                return;
            }
        }
        
        // If a player name is provided, send the joinme to that specific player
        if (args.length > 0) {
            String targetName = args[0];
            Optional<Player> targetPlayer = server.getPlayer(targetName);
            
            if (targetPlayer.isPresent()) {
                sendJoinMeMessage(player, serverName, targetPlayer.get());
                player.sendMessage(plugin.getPrefix().append(
                    Component.text("Sent a join request to ", NamedTextColor.GREEN))
                    .append(Component.text(targetName, NamedTextColor.YELLOW))
                );
            } else if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()
                    && plugin.getCrossProxyService().getPlayerCurrentServer(targetName) != null) {
                plugin.getCrossProxyService().publishJoinMeToPlayer(targetName, player.getUsername(), serverName);
                player.sendMessage(plugin.getPrefix().append(
                    Component.text("Sent a join request to ", NamedTextColor.GREEN))
                    .append(Component.text(targetName, NamedTextColor.YELLOW))
                );
            } else {
                player.sendMessage(plugin.getPrefix().append(
                    Component.text("Player " + targetName + " is not online.", NamedTextColor.RED)
                ));
                return;
            }
        } else {
            // Broadcast joinme to all players (local + other proxies)
            broadcastJoinMe(player, serverName);
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                plugin.getCrossProxyService().publishJoinMeBroadcast(player.getUsername(), serverName);
            }
            player.sendMessage(plugin.getPrefix().append(
                Component.text("Broadcast join request to all players.", NamedTextColor.GREEN)
            ));
        }
          // Apply cooldown if player doesn't have bypass permission
        if (!player.hasPermission(cooldownBypassPermission)) {
            applyCooldown(player);
        }
    }
    
    /**
     * Broadcast join message to all players
     * 
     * @param player The player sending the invite
     * @param serverName The server to join
     */
    private void broadcastJoinMe(Player player, String serverName) {
        Component message = createJoinMeMessage(player, serverName);
        
        // Send to all players except the sender
        for (Player onlinePlayer : server.getAllPlayers()) {
            if (!onlinePlayer.getUniqueId().equals(player.getUniqueId())) {
                onlinePlayer.sendMessage(message);
            }
        }
    }
    
    /**
     * Send join message to a specific player
     * 
     * @param player The player sending the invite
     * @param serverName The server to join
     * @param target The player receiving the invite
     */
    private void sendJoinMeMessage(Player player, String serverName, Player target) {
        Component message = createJoinMeMessage(player, serverName);
        target.sendMessage(message);
    }
    
    /**
     * Create a stylized joinme message
     * 
     * @param player The player sending the invite
     * @param serverName The server to join
     * @return Formatted message component
     */
    private Component createJoinMeMessage(Player player, String serverName) {
        // Top border
        Component border = Component.text("▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬", NamedTextColor.GOLD, TextDecoration.BOLD);
        
        // Header with icon
        Component header = Component.text("  ✦ JOIN ME INVITATION ✦  ", NamedTextColor.YELLOW, TextDecoration.BOLD);
        
        // Player name with possible rank prefix
        // Note: You'd need to integrate with your permission system to get actual prefix
        Component playerComponent = Component.text(player.getUsername(), NamedTextColor.AQUA, TextDecoration.BOLD);
        
        // Server component with click and hover effects
        Component serverComponent = Component.text(serverName, NamedTextColor.GREEN, TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand("/server " + serverName))
            .hoverEvent(HoverEvent.showText(Component.text("Click to join " + serverName, NamedTextColor.YELLOW)));
        
        // Build the complete message
        return Component.empty()
            .append(Component.newline())
            .append(border).append(Component.newline())
            .append(header).append(Component.newline())
            .append(Component.text("Player: ", NamedTextColor.YELLOW)).append(playerComponent).append(Component.newline())
            .append(Component.text("Server: ", NamedTextColor.YELLOW)).append(serverComponent).append(Component.newline())
            .append(Component.text("Click on the server name to join!", NamedTextColor.GRAY, TextDecoration.ITALIC)).append(Component.newline())
            .append(border);
    }
    
    /**
     * Check if a player is on cooldown
     * 
     * @param player The player to check
     * @return true if on cooldown
     */    private boolean isOnCooldown(Player player) {
        if (!cooldowns.containsKey(player.getUniqueId())) {
            return false;
        }
        
        long lastUsed = cooldowns.get(player.getUniqueId());
        return System.currentTimeMillis() - lastUsed < cooldownMillis;
    }
    
    /**
     * Get how much time is left on a player's cooldown in milliseconds
     * 
     * @param player The player to check
     * @return Remaining time in milliseconds
     */    private long getRemainingCooldown(Player player) {
        if (!cooldowns.containsKey(player.getUniqueId())) {
            return 0;
        }
        
        long lastUsed = cooldowns.get(player.getUniqueId());
        long timeElapsed = System.currentTimeMillis() - lastUsed;
        return Math.max(0, cooldownMillis - timeElapsed);
    }
    
    /**
     * Apply cooldown to a player
     * 
     * @param player The player to apply cooldown to
     */
    private void applyCooldown(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }
    
    /**
     * Format time remaining in a human-readable format
     * 
     * @param timeMillis Time in milliseconds
     * @return Formatted string like "2m 30s"
     */
    private String formatTimeRemaining(long timeMillis) {
        long seconds = timeMillis / 1000;
        long minutes = seconds / 60;
        seconds %= 60;
        
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        } else {
            return seconds + "s";
        }
    }
    
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                return plugin.getCrossProxyService().getOnlinePlayerNames().stream()
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
            }
            return server.getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase().startsWith(input))
                .collect(Collectors.toList());
        }
        return List.of();
    }
      @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(usePermission);
    }
}
