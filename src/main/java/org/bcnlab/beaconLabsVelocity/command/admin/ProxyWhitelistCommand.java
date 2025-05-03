package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.bcnlab.beaconLabsVelocity.service.WhitelistService;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Command to manage the proxy-wide whitelist
 */
public class ProxyWhitelistCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final DatabaseManager databaseManager;
    private static final String WHITELIST_PERMISSION = "beaconlabs.command.whitelist";
    private static final String WHITELIST_BYPASS_PERMISSION = "beaconlabs.whitelist.bypass";

    public ProxyWhitelistCommand(BeaconLabsVelocity plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        
        // Initialize whitelist table
        initWhitelistTable();
    }
    
    private void initWhitelistTable() {
        if (!databaseManager.isConnected()) {
            plugin.getLogger().warn("Database is not connected. Proxy whitelist will not be able to store player data.");
            return;
        }
        
        String createTable = "CREATE TABLE IF NOT EXISTS proxy_whitelist (" +
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                "player_uuid VARCHAR(36) NOT NULL UNIQUE, " +
                "player_name VARCHAR(16) NOT NULL, " +
                "added_by VARCHAR(36), " +
                "added_at BIGINT NOT NULL" +
                ")";
                
        CompletableFuture.runAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(createTable)) {
                stmt.execute();
                plugin.getLogger().info("Proxy whitelist table initialized.");
            } catch (SQLException e) {
                plugin.getLogger().error("Failed to create proxy whitelist table", e);
            }
        });
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        // Check permission
        if (!source.hasPermission(WHITELIST_PERMISSION)) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            ));
            return;
        }
        
        // No arguments provided
        if (args.length == 0) {
            sendUsage(source);
            return;
        }
        
        // Handle whitelist commands
        switch (args[0].toLowerCase()) {
            case "on":
                setWhitelistEnabled(true, source);
                break;
            case "off":
                setWhitelistEnabled(false, source);
                break;
            case "add":
                if (args.length < 2) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Please specify a player name.", NamedTextColor.RED)
                    ));
                    return;
                }
                addPlayer(source, args[1]);
                break;
            case "remove":
            case "del":
            case "delete":
                if (args.length < 2) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Please specify a player name.", NamedTextColor.RED)
                    ));
                    return;
                }
                removePlayer(source, args[1]);
                break;
            case "list":
                listPlayers(source);
                break;
            case "status":
                checkStatus(source);
                break;
            default:
                sendUsage(source);
                break;
        }
    }
    
    private void sendUsage(CommandSource source) {
        source.sendMessage(plugin.getPrefix().append(
            Component.text("Whitelist Commands:", NamedTextColor.GOLD)
        ));
        source.sendMessage(Component.text("  /proxywhitelist on - Enable the whitelist", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /proxywhitelist off - Disable the whitelist", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /proxywhitelist add <player> - Add a player to the whitelist", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /proxywhitelist remove <player> - Remove a player from the whitelist", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /proxywhitelist list - List all whitelisted players", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /proxywhitelist status - Check if whitelist is enabled", NamedTextColor.YELLOW));
    }
      private void setWhitelistEnabled(boolean enabled, CommandSource source) {
        try {
            // Update config
            ConfigurationNode config = plugin.getConfig();
            config.node("whitelist", "enabled").set(enabled);
            plugin.saveConfig();
            
            String status = enabled ? "enabled" : "disabled";
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Whitelist has been " + status + ".", NamedTextColor.GREEN)
            ));
            
            // If whitelist is being enabled, kick non-whitelisted players
            if (enabled) {
                kickNonWhitelistedPlayers(source);
            }
            
            // Log the action
            String sourceName = source instanceof Player ? ((Player) source).getUsername() : "Console";
            plugin.getLogger().info("Whitelist {} by {}", status, sourceName);
            
        } catch (SerializationException e) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Failed to update whitelist status in config.", NamedTextColor.RED)
            ));
            plugin.getLogger().error("Failed to update whitelist status", e);
        }
    }
    
    /**
     * Kick all players who are not whitelisted or don't have bypass permission
     */
    private void kickNonWhitelistedPlayers(CommandSource source) {
        if (!databaseManager.isConnected()) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Database is not connected. Cannot check whitelist status for players.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Get configurable kick message
        String kickMessageStr = plugin.getConfig().node("whitelist", "kick-message")
            .getString("&4BeaconLabs &r&8| &c&lWHITELIST ONLY\n&7You are not on the whitelist.\n&7Please contact an administrator to gain access.");
        Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(kickMessageStr);
        
        int kickCount = 0;
        for (Player player : plugin.getServer().getAllPlayers()) {
            // Skip players with bypass permission
            if (player.hasPermission(WHITELIST_BYPASS_PERMISSION)) {
                continue;
            }
            
            // Check if player is whitelisted
            if (!isPlayerWhitelisted(player.getUsername())) {
                player.disconnect(kickMessage);
                kickCount++;
            }
        }
        
        if (kickCount > 0) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Kicked " + kickCount + " non-whitelisted players.", NamedTextColor.YELLOW)
            ));
        }
    }
    
    /**
     * Check if a player is whitelisted by username
     * 
     * @param username The username to check
     * @return True if the player is whitelisted, false otherwise
     */
    private boolean isPlayerWhitelisted(String username) {
        if (!databaseManager.isConnected()) {
            return false;
        }
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM proxy_whitelist WHERE LOWER(player_name) = LOWER(?)")) {
            
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next(); // If there's a result, player is whitelisted
            }
            
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to check whitelist status for player {}", username, e);
            return false;
        }
    }
      private void addPlayer(CommandSource source, String playerName) {
        if (!databaseManager.isConnected()) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Database is not connected. Cannot add player to the whitelist.", NamedTextColor.RED)
            ));
            return;
        }
        
        // First check if the player is already whitelisted (case-insensitive)
        if (isPlayerWhitelisted(playerName)) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Player is already in the whitelist.", NamedTextColor.YELLOW)
            ));
            return;
        }
        
        // Try to find the player first (online or offline)
        plugin.getServer().getPlayer(playerName).ifPresentOrElse(
            player -> {
                // Player is online, use their actual username (preserving case)
                addPlayerToWhitelist(player.getUsername(), source);
            },
            () -> {
                // Player is offline, use their name as provided
                addPlayerToWhitelist(playerName, source);
            }
        );
    }
    
    private void addPlayerToWhitelist(String playerName, CommandSource source) {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO proxy_whitelist (player_uuid, player_name, added_by, added_at) VALUES (?, ?, ?, ?)")) {
                
                // Generate UUID for storage, but we'll be using name lookups for checking
                UUID playerUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes());
                String addedBy = source instanceof Player ? ((Player) source).getUniqueId().toString() : "console";
                long currentTime = System.currentTimeMillis();
                
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, playerName); // Store with original case
                stmt.setString(3, addedBy);
                stmt.setLong(4, currentTime);
                
                int affectedRows = stmt.executeUpdate();
                
                if (affectedRows > 0) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Player " + playerName + " has been added to the whitelist.", NamedTextColor.GREEN)
                    ));
                    
                    // Log the action
                    String sourceName = source instanceof Player ? ((Player) source).getUsername() : "Console";
                    plugin.getLogger().info("Player {} added to whitelist by {}", playerName, sourceName);
                } else {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Failed to add player to the whitelist.", NamedTextColor.RED)
                    ));
                }
            } catch (SQLException e) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Database error: Failed to add player to whitelist.", NamedTextColor.RED)
                ));
                plugin.getLogger().error("Failed to add player to whitelist", e);
            }
        });
    }
    
    private void removePlayer(CommandSource source, String playerName) {
        if (!databaseManager.isConnected()) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Database is not connected. Cannot remove player from the whitelist.", NamedTextColor.RED)
            ));
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM proxy_whitelist WHERE LOWER(player_name) = LOWER(?)")) {
                
                stmt.setString(1, playerName);
                int affectedRows = stmt.executeUpdate();
                
                if (affectedRows > 0) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Player " + playerName + " has been removed from the whitelist.", NamedTextColor.GREEN)
                    ));
                    
                    // Log the action
                    String sourceName = source instanceof Player ? ((Player) source).getUsername() : "Console";
                    plugin.getLogger().info("Player {} removed from whitelist by {}", playerName, sourceName);
                } else {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Player " + playerName + " is not on the whitelist.", NamedTextColor.YELLOW)
                    ));
                }
            } catch (SQLException e) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Database error: Failed to remove player from whitelist.", NamedTextColor.RED)
                ));
                plugin.getLogger().error("Failed to remove player from whitelist", e);
            }
        });
    }
    
    private void listPlayers(CommandSource source) {
        if (!databaseManager.isConnected()) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Database is not connected. Cannot list whitelisted players.", NamedTextColor.RED)
            ));
            return;
        }
        
        CompletableFuture.supplyAsync(() -> {
            List<String> players = new ArrayList<>();
            
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                    "SELECT player_name FROM proxy_whitelist ORDER BY player_name");
                 ResultSet rs = stmt.executeQuery()) {
                
                while (rs.next()) {
                    players.add(rs.getString("player_name"));
                }
            } catch (SQLException e) {
                plugin.getLogger().error("Failed to fetch whitelist players", e);
            }
            
            return players;
        }).thenAcceptAsync(players -> {
            if (players.isEmpty()) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("No players are whitelisted.", NamedTextColor.YELLOW)
                ));
            } else {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Whitelisted players (" + players.size() + "):", NamedTextColor.GREEN)
                ));
                
                // Group players in blocks of 5 per line
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < players.size(); i++) {
                    sb.append(players.get(i));
                    if (i != players.size() - 1) {
                        sb.append(", ");
                    }
                    
                    // Send message every 5 players or on the last player
                    if ((i + 1) % 5 == 0 || i == players.size() - 1) {
                        source.sendMessage(Component.text("  " + sb.toString(), NamedTextColor.WHITE));
                        sb = new StringBuilder();
                    }
                }
            }
        });
    }
    
    private void checkStatus(CommandSource source) {
        boolean enabled = plugin.getConfig().node("whitelist", "enabled").getBoolean(false);
        String status = enabled ? "enabled" : "disabled";
        NamedTextColor color = enabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        
        source.sendMessage(plugin.getPrefix().append(
            Component.text("Whitelist is currently " + status + ".", color)
        ));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(WHITELIST_PERMISSION);
    }
    
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            List<String> commands = List.of("on", "off", "add", "remove", "list", "status");
            return commands.stream()
                .filter(cmd -> cmd.startsWith(input))
                .collect(Collectors.toList());
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            if (subCommand.equals("add")) {
                // Suggest online players that are not whitelisted
                String input = args[1].toLowerCase();
                return plugin.getServer().getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
            } else if (subCommand.equals("remove") || subCommand.equals("del") || subCommand.equals("delete")) {
                // Ideally we would suggest whitelisted players, but that would require a DB query
                // For simplicity, we'll just suggest online players
                String input = args[1].toLowerCase();
                return plugin.getServer().getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
            }
        }
        
        return List.of();
    }
      // The static method has been replaced with instance methods that check by username instead of UUID
}
