package org.bcnlab.beaconLabsVelocity.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service to manage server guard functionality, controlling which servers players can access
 */
public class ServerGuardService {

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final Logger logger;
    private ConfigurationNode config;
    
    private boolean defaultAllow = false;
    private List<String> alwaysAllowedServers = Collections.emptyList();
    private Map<String, String> serverPermissions = new HashMap<>();
    
    /**
     * Access action for the server guard
     */
    public enum GuardAction {
        ALLOW,
        BLOCK
    }
    
    public ServerGuardService(BeaconLabsVelocity plugin, ProxyServer server, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        loadConfig();
    }
    
    /**
     * Load the server guard configuration
     */
    public void loadConfig() {
        Path configFile = plugin.getDataDirectory().resolve("servers.yml");
        
        try {
            if (!Files.exists(configFile)) {
                Files.createDirectories(plugin.getDataDirectory());
                Files.copy(
                        getClass().getClassLoader().getResourceAsStream("servers.yml"),
                        configFile
                );
            }
            
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .path(configFile)
                    .build();
                    
            config = loader.load();
            
            // Parse default action
            String defaultActionStr = config.node("default-action").getString("BLOCK");
            defaultAllow = "ALLOW".equalsIgnoreCase(defaultActionStr);
            
            // Parse always allowed servers
            alwaysAllowedServers = config.node("always-allowed").getList(String.class, Collections.emptyList());
            
            // Parse permission mappings
            serverPermissions.clear();
            ConfigurationNode permissionsNode = config.node("permissions");
            
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : permissionsNode.childrenMap().entrySet()) {
                String serverName = entry.getKey().toString();
                String permission = entry.getValue().getString();
                
                if (permission != null && !permission.isEmpty()) {
                    serverPermissions.put(serverName.toLowerCase(), permission);
                }
            }
            
            logger.info("Loaded server guard configuration with {} permission rules", serverPermissions.size());
            
        } catch (IOException e) {
            logger.error("Failed to load server guard configuration", e);
        }
    }
    
    /**
     * Check if a player can access a specific server
     * 
     * @param player The player to check
     * @param serverName The name of the server
     * @return true if the player can access the server, false otherwise
     */
    public boolean canAccess(Player player, String serverName) {
        // Staff with admin permission can access any server
        if (player.hasPermission("beaconlabs.admin")) {
            return true;
        }
        
        // Check if server is in always allowed list
        String normalizedName = serverName.toLowerCase();
        if (alwaysAllowedServers.contains(normalizedName)) {
            return true;
        }
        
        // Check if server has a permission requirement
        String requiredPermission = serverPermissions.get(normalizedName);
        if (requiredPermission != null) {
            return player.hasPermission(requiredPermission);
        }
        
        // Return default action if no specific rules match
        return defaultAllow;
    }
    
    /**
     * Get the default action of the guard system
     * 
     * @return true if default is to allow, false if default is to block
     */
    public boolean isDefaultAllow() {
        return defaultAllow;
    }
    
    /**
     * Get server access status information for a specific server and player
     * 
     * @param player The player to check
     * @param serverName The name of the server
     * @return A GuardStatus object containing access information
     */
    public GuardStatus getServerStatus(Player player, String serverName) {
        String normalizedName = serverName.toLowerCase();
        
        // Check if server exists
        Optional<RegisteredServer> registeredServer = server.getServer(serverName);
        if (registeredServer.isEmpty()) {
            return new GuardStatus(serverName, GuardAction.BLOCK, "Server does not exist", null);
        }
        
        // Check if player has admin permission
        if (player.hasPermission("beaconlabs.admin")) {
            return new GuardStatus(serverName, GuardAction.ALLOW, "Admin permission", "beaconlabs.admin");
        }
        
        // Check if server is in always allowed list
        if (alwaysAllowedServers.contains(normalizedName)) {
            return new GuardStatus(serverName, GuardAction.ALLOW, "Always allowed server", null);
        }
        
        // Check if server has a permission requirement
        String requiredPermission = serverPermissions.get(normalizedName);
        if (requiredPermission != null) {
            boolean hasPermission = player.hasPermission(requiredPermission);
            return new GuardStatus(
                serverName,
                hasPermission ? GuardAction.ALLOW : GuardAction.BLOCK,
                hasPermission ? "Has required permission" : "Missing required permission",
                requiredPermission
            );
        }
        
        // Return default action if no specific rules match
        return new GuardStatus(
            serverName,
            defaultAllow ? GuardAction.ALLOW : GuardAction.BLOCK,
            "Default action",
            null
        );
    }
    
    /**
     * Get server access status information for a default player with no permissions
     * 
     * @param serverName The name of the server
     * @return A GuardStatus object containing access information
     */
    public GuardStatus getDefaultPlayerStatus(String serverName) {
        String normalizedName = serverName.toLowerCase();
        
        // Check if server exists
        Optional<RegisteredServer> registeredServer = server.getServer(serverName);
        if (registeredServer.isEmpty()) {
            return new GuardStatus(serverName, GuardAction.BLOCK, "Server does not exist", null);
        }
        
        // Check if server is in always allowed list (even a default player can access these)
        if (alwaysAllowedServers.contains(normalizedName)) {
            return new GuardStatus(serverName, GuardAction.ALLOW, "Always allowed server", null);
        }
        
        // Check if server has a permission requirement (default player won't have permissions)
        String requiredPermission = serverPermissions.get(normalizedName);
        if (requiredPermission != null) {
            return new GuardStatus(
                serverName,
                GuardAction.BLOCK,
                "Missing required permission",
                requiredPermission
            );
        }
        
        // Return default action if no specific rules match
        return new GuardStatus(
            serverName,
            defaultAllow ? GuardAction.ALLOW : GuardAction.BLOCK,
            "Default action",
            null
        );
    }
    
    /**
     * Check if a server exists and get its name
     * 
     * @param serverName The server name to check
     * @return The correctly cased server name if it exists, or empty if it doesn't
     */
    public Optional<String> getServerName(String serverName) {
        Optional<RegisteredServer> server = this.server.getServer(serverName);
        return server.map(registeredServer -> registeredServer.getServerInfo().getName());
    }
    
    /**
     * Get a list of all known server names
     * 
     * @return List of server names
     */
    public List<String> getAllServerNames() {
        return server.getAllServers().stream()
            .map(server -> server.getServerInfo().getName())
            .toList();
    }
    
    /**
     * Save the configuration to file
     * 
     * @return true if saved successfully, false otherwise
     */
    public boolean saveConfig() {
        try {
            Path configPath = plugin.getDataDirectory().resolve("servers.yml");
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(configPath)
                .build();
                
            loader.save(config);
            return true;
        } catch (IOException e) {
            logger.error("Failed to save server guard configuration", e);
            return false;
        }
    }
    
    /**
     * Represents the guard status of a server for a specific player
     */
    public static class GuardStatus {
        private final String serverName;
        private final GuardAction action;
        private final String reason;
        private final String permission;
        
        public GuardStatus(String serverName, GuardAction action, String reason, String permission) {
            this.serverName = serverName;
            this.action = action;
            this.reason = reason;
            this.permission = permission;
        }
        
        public String getServerName() {
            return serverName;
        }
        
        public GuardAction getAction() {
            return action;
        }
        
        public String getReason() {
            return reason;
        }
        
        public String getPermission() {
            return permission;
        }
    }
}
