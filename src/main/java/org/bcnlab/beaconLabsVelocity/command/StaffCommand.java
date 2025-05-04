package org.bcnlab.beaconLabsVelocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.cacheddata.CachedMetaData;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.Collection;

/**
 * Command to display online staff members
 */
public class StaffCommand implements SimpleCommand {
    
    private final BeaconLabsVelocity plugin;
    private static final String PERMISSION = "beaconlabs.visual.staff";
    
    public StaffCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        
        // Check permission
        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Get all online players
        Collection<Player> onlinePlayers = plugin.getServer().getAllPlayers();
        
        // Start building the staff list message
        Component staffList = Component.text("Staff Online:", NamedTextColor.GREEN);
        
        boolean hasStaff = false;        // Add each staff member to the list
        for (Player onlinePlayer : onlinePlayers) {
            if (onlinePlayer.hasPermission(PERMISSION)) {
                Component displayName = getDisplayName(onlinePlayer);
                
                // Get the player's current server name
                String serverName = onlinePlayer.getCurrentServer()
                    .map(serverConnection -> serverConnection.getServerInfo().getName())
                    .orElse("Unknown");
                
                // Add hover event to show current server
                Component hoverText = Component.text("Server: " + serverName, NamedTextColor.GOLD);
                
                // Add click event to run the '/server' command
                Component playerComponent = displayName
                    .hoverEvent(HoverEvent.showText(hoverText))
                    .clickEvent(ClickEvent.runCommand("/server " + serverName));
                  // Add a new line before each staff member with server info
                staffList = staffList.append(Component.newline())
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(playerComponent)
                    .append(Component.text(" [", NamedTextColor.DARK_GRAY))
                    .append(Component.text(serverName, NamedTextColor.GREEN))
                    .append(Component.text("]", NamedTextColor.DARK_GRAY));
                
                hasStaff = true;
            }
        }
        
        // If no staff is online, add a message
        if (!hasStaff) {
            staffList = staffList.append(Component.newline())
                .append(Component.text(" No staff members are currently online.", NamedTextColor.YELLOW));
        }
        
        // Send the message to the command source
        source.sendMessage(plugin.getPrefix().append(staffList));
    }
      /**
     * Get the display name of a player, including their LuckPerms prefix if available
     * 
     * @param player The player to get the display name for
     * @return The player's display name as a Component
     */    private Component getDisplayName(Player player) {
        try {
            // Get the user from LuckPerms
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            
            if (user != null) {
                // Get the user's metadata (which contains prefix/suffix)
                CachedMetaData metaData = user.getCachedData().getMetaData();
                String prefix = metaData.getPrefix();
                
                // If they have a prefix, use it
                if (prefix != null && !prefix.isEmpty()) {
                    // Parse the prefix as a component (supports legacy color codes like &c)
                    Component prefixComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(prefix);
                    
                    // Combine the prefix with the player's username
                    return prefixComponent.append(Component.text(player.getUsername()));
                }
            }
            
            // Fallback to color-based display name if LuckPerms isn't available or user has no prefix
            if (player.hasPermission("beaconlabs.visual.admin")) {
                return Component.text(player.getUsername(), NamedTextColor.RED);
            } else if (player.hasPermission("beaconlabs.visual.mod")) {
                return Component.text(player.getUsername(), NamedTextColor.GOLD);
            } else {
                return Component.text(player.getUsername(), NamedTextColor.AQUA);
            }
        } catch (Exception e) {
            // If LuckPerms API isn't available or throws an exception, fall back to default
            plugin.getLogger().warn("Failed to get LuckPerms prefix for player {}: {}", 
                player.getUsername(), e.getMessage());
            
            return Component.text(player.getUsername(), NamedTextColor.AQUA);
        }
    }
    
    @Override
    public boolean hasPermission(final Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
