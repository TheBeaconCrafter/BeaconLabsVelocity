package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService.IpHistoryEntry;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService.PunishmentRecord;
import org.bcnlab.beaconLabsVelocity.util.DiscordWebhook;
import org.bcnlab.beaconLabsVelocity.util.DurationUtils;

import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /info <player> - show detailed player information and punishment status
 * Permission: beaconlabs.punish.info
 */
public class InfoCommand implements SimpleCommand {
    private final ProxyServer server;
    private final PunishmentService service;
    private final PunishmentConfig config;
    private final BeaconLabsVelocity plugin;
    private final PlayerStatsService playerStatsService;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public InfoCommand(ProxyServer server, PunishmentService service, BeaconLabsVelocity plugin, PunishmentConfig config) {
        this.server = server;
        this.service = service;
        this.plugin = plugin;
        this.playerStatsService = plugin.getPlayerStatsService();
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        
        // Permission check
        if (!src.hasPermission("beaconlabs.punish.info")) {
            src.sendMessage(plugin.getPrefix().append(
                LegacyComponentSerializer.legacyAmpersand().deserialize(config.getMessage("no-permission"))));
            return;
        }
        
        // Usage check
        if (args.length < 1) {
            src.sendMessage(plugin.getPrefix().append(
                Component.text("Usage: /info <player>", NamedTextColor.RED)));
            return;
        }        // Get target player
        String targetName = args[0];
        Optional<Player> optionalTarget = server.getPlayer(targetName);
        
        // Send elegant header
        sendDivider(src, NamedTextColor.GOLD);
        
        Component playerHeader = Component.text()
            .append(Component.text("✦ ", NamedTextColor.GOLD))
            .append(Component.text("PLAYER INFO: ", NamedTextColor.YELLOW)
                .decorate(TextDecoration.BOLD))
            .append(Component.text(targetName, NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD))
            .append(Component.text(" ✦", NamedTextColor.GOLD))
            .build();
        src.sendMessage(playerHeader);
        
        // If online player, show detailed info
        if (optionalTarget.isPresent()) {
            Player target = optionalTarget.get();
            UUID uuid = target.getUniqueId();
            
            // Separator for profile section
            src.sendMessage(Component.text("» PROFILE", NamedTextColor.AQUA)
                .decorate(TextDecoration.BOLD));
            
            // Send player info in an organized way
            sendProfileInfo(src, target);
            
            // Separator for connection section
            src.sendMessage(Component.empty());
            src.sendMessage(Component.text("» CONNECTION", NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD));
            
            // Send connection info
            sendConnectionInfo(src, target);
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                src.sendMessage(Component.text("Proxy: ", NamedTextColor.YELLOW)
                        .append(Component.text(plugin.getCrossProxyService().getProxyId(), NamedTextColor.AQUA)));
            }

            // Separator for punishment section
            src.sendMessage(Component.empty());
            src.sendMessage(Component.text("» PUNISHMENT STATUS", NamedTextColor.RED)
                .decorate(TextDecoration.BOLD));
            
            // Send punishment info
            sendPunishmentInfo(src, uuid, targetName);
            
        } else {
            // Player is offline - try to find in the database
            UUID offlineUuid = service.getPlayerUUID(targetName);
            String effectivePlayerName = targetName; // Name used for display, defaults to input

            if (offlineUuid == null) {
                // If not found via PunishmentService, try PlayerStatsService (e.g., from player_stats table)
                // This ensures players who have joined but never been punished can still be looked up.
                PlayerStatsService.PlayerData playerDataFromStats = playerStatsService.getPlayerDataByName(targetName);
                if (playerDataFromStats != null) {
                    offlineUuid = playerDataFromStats.getPlayerId();
                    effectivePlayerName = playerDataFromStats.getPlayerName(); // Use canonical name from DB
                    
                    // If the name found is different (e.g. case) from the input, print a clarification.
                    if (!effectivePlayerName.equalsIgnoreCase(targetName)) {
                        src.sendMessage(Component.text("(Showing info for player: ", NamedTextColor.GRAY)
                                          .append(Component.text(effectivePlayerName, NamedTextColor.GOLD))
                                          .append(Component.text(")", NamedTextColor.GRAY)));
                    }
                }
            }
            
            if (offlineUuid != null) {
                String onlineProxyId = (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled())
                        ? plugin.getCrossProxyService().getPlayerProxy(offlineUuid) : null;

                if (onlineProxyId != null) {
                    src.sendMessage(Component.text("● Online on proxy: ", NamedTextColor.GREEN)
                            .append(Component.text(onlineProxyId, NamedTextColor.AQUA).decorate(TextDecoration.BOLD)));
                    String currentServer = (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled())
                            ? plugin.getCrossProxyService().getPlayerCurrentServer(effectivePlayerName) : null;
                    if (currentServer != null && !currentServer.isEmpty()) {
                        src.sendMessage(Component.text("Server: ", NamedTextColor.YELLOW)
                                .append(Component.text(currentServer, NamedTextColor.AQUA)
                                        .clickEvent(ClickEvent.runCommand("/server " + currentServer))
                                        .hoverEvent(HoverEvent.showText(Component.text("Click to connect to this server", NamedTextColor.GRAY)))));
                    }
                } else {
                    src.sendMessage(Component.text("⚠ Player is currently offline.", NamedTextColor.GOLD));
                    if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                        src.sendMessage(Component.text("(Player may be online on another proxy.)", NamedTextColor.GRAY));
                    }
                }

                // Show offline player profile
                src.sendMessage(Component.text("» PROFILE", NamedTextColor.AQUA)
                    .decorate(TextDecoration.BOLD));
                  // Show UUID
                Component uuidComponent = Component.text()
                    .append(Component.text("UUID: ", NamedTextColor.YELLOW))
                    .append(Component.text(offlineUuid.toString(), NamedTextColor.WHITE)
                        .clickEvent(ClickEvent.copyToClipboard(offlineUuid.toString()))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to copy UUID", NamedTextColor.GRAY))))
                    .build();
                src.sendMessage(uuidComponent);

                // Show username (canonical name)
                src.sendMessage(Component.text("Username: ", NamedTextColor.YELLOW)
                    .append(Component.text(effectivePlayerName, NamedTextColor.WHITE)));

                  // Show playtime for offline player
                long playtimeMs = playerStatsService.getPlayerPlaytime(offlineUuid);
                String formattedPlaytime = PlayerStatsService.formatPlaytime(playtimeMs);
                src.sendMessage(Component.text("Playtime: ", NamedTextColor.YELLOW)
                    .append(Component.text(formattedPlaytime, NamedTextColor.WHITE)));
                
                // Show last seen information for offline player
                long lastSeenTime = playerStatsService.getLastSeenTime(offlineUuid);
                if (lastSeenTime > 0) {
                    Date lastSeenDate = new Date(lastSeenTime);
                    String lastSeenStr = DATE_FORMAT.format(lastSeenDate);
                    src.sendMessage(Component.text("Last seen: ", NamedTextColor.YELLOW)
                        .append(Component.text(lastSeenStr, NamedTextColor.WHITE)));
                } else {
                    src.sendMessage(Component.text("Last seen: ", NamedTextColor.YELLOW)
                        .append(Component.text("Unknown", NamedTextColor.GRAY)));
                }
                
                // Make offlineUuid effectively final for use in lambda
                final UUID finalOfflineUuidForLambda = offlineUuid;

                // Show IP history for offline player (for admins only)
                if (src.hasPermission("beaconlabs.admin.viewips")) {
                    List<IpHistoryEntry> ipHistory = playerStatsService.getPlayerIpHistory(finalOfflineUuidForLambda);
                    if (!ipHistory.isEmpty()) {
                        src.sendMessage(Component.empty());
                        src.sendMessage(Component.text("Last Known IPs:", NamedTextColor.YELLOW)
                            .decorate(TextDecoration.UNDERLINED));
                        
                        int count = 0;
                        for (IpHistoryEntry entry : ipHistory) {
                            count++;
                            if (count > 3) break; // Only show last 3
                            
                            String historyIp = entry.getIpAddress();
                            Date timestamp = new Date(entry.getTimestamp());
                            String dateStr = DATE_FORMAT.format(timestamp);
                            
                            Component historyComponent = Component.text("  " + historyIp, NamedTextColor.WHITE)
                                .clickEvent(ClickEvent.copyToClipboard(historyIp))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to copy IP", NamedTextColor.GRAY)));
                            
                            src.sendMessage(historyComponent
                                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                                .append(Component.text(dateStr, NamedTextColor.GRAY)));
                        }                    }
                    
                    // Get the most recent IP address for this offline player
                    if (!ipHistory.isEmpty() && src.hasPermission("beaconlabs.admin.viewips")) {
                        String lastIp = ipHistory.get(0).getIpAddress();
                        
                        // Find other players who used this IP
                        List<PlayerStatsService.PlayerData> playersWithSameIp = playerStatsService.getPlayersWithSameIp(lastIp);
                        
                        // Remove the current player from the list
                        playersWithSameIp.removeIf(p -> p.getPlayerId().equals(finalOfflineUuidForLambda));
                        
                        if (!playersWithSameIp.isEmpty()) {
                            src.sendMessage(Component.empty());
                            src.sendMessage(Component.text("» PLAYERS WITH SAME IP", NamedTextColor.DARK_AQUA)
                                .decorate(TextDecoration.BOLD));
                                
                            for (PlayerStatsService.PlayerData player : playersWithSameIp) {
                                boolean isOnline = server.getPlayer(player.getPlayerId()).isPresent();
                                NamedTextColor nameColor = isOnline ? NamedTextColor.GREEN : NamedTextColor.WHITE;
                                TextDecoration nameDeco = isOnline ? TextDecoration.BOLD : null;
                                
                                Component playerComp = Component.text(player.getPlayerName(), nameColor);
                                if (nameDeco != null) {
                                    playerComp = playerComp.decorate(nameDeco);
                                }
                                
                                Component entry = Component.text("  • ", NamedTextColor.GRAY)
                                    .append(playerComp
                                        .clickEvent(ClickEvent.runCommand("/info " + player.getPlayerName()))
                                        .hoverEvent(HoverEvent.showText(Component.text("Click to view player info", NamedTextColor.YELLOW)))
                                    );
                                    
                                // Add online/offline status
                                if (isOnline) {
                                    entry = entry.append(Component.text(" (Online)", NamedTextColor.GREEN));
                                } else {
                                    Date lastSeen = new Date(player.getLastSeen());
                                    String lastSeenStr = DATE_FORMAT.format(lastSeen);
                                    entry = entry.append(Component.text(" (Last seen: " + lastSeenStr + ")", NamedTextColor.GRAY));
                                }
                                
                                src.sendMessage(entry);
                            }
                        }
                    }
                }
                
                if (onlineProxyId != null) {
                    src.sendMessage(Component.empty());
                    src.sendMessage(Component.text("» CONNECTION", NamedTextColor.GREEN)
                            .decorate(TextDecoration.BOLD));
                    src.sendMessage(Component.text("Proxy: ", NamedTextColor.YELLOW)
                            .append(Component.text(onlineProxyId, NamedTextColor.AQUA)));
                }

                // Separator for punishment section
                src.sendMessage(Component.empty());
                src.sendMessage(Component.text("» PUNISHMENT STATUS", NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD));
                
                // Send punishment info for offline player, using the effective (canonical) name
                sendPunishmentInfo(src, offlineUuid, effectivePlayerName);
            } else {
                // Completely unknown player
                src.sendMessage(Component.text("⚠ Player has never been seen on this server", NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD));
                
                src.sendMessage(Component.text("No information is available for this player.", NamedTextColor.GRAY));
            }
        }
        
        // Bottom divider for clean look
        sendDivider(src, NamedTextColor.GOLD);
        
        // Send webhook notification
        DiscordWebhook.send("Info viewed for " + targetName + " by " + 
            (src instanceof Player ? ((Player) src).getUsername() : "Console"));
    }
      /**
     * Sends profile information section for a player
     */
    private void sendProfileInfo(CommandSource src, Player target) {
        UUID uuid = target.getUniqueId();
        
        // UUID with clickable copy in modern format
        Component uuidComponent = Component.text()
            .append(Component.text("UUID: ", NamedTextColor.YELLOW))
            .append(Component.text(uuid.toString(), NamedTextColor.WHITE)
                .clickEvent(ClickEvent.copyToClipboard(uuid.toString()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy UUID", NamedTextColor.GRAY))))
            .build();
        src.sendMessage(uuidComponent);
        
        // Premium account status with icon
        String accountIcon = target.isOnlineMode() ? "✓ " : "✗ ";
        NamedTextColor accountColor = target.isOnlineMode() ? NamedTextColor.GREEN : NamedTextColor.GOLD;
        
        src.sendMessage(Component.text("Account: ", NamedTextColor.YELLOW)
            .append(Component.text(accountIcon + (target.isOnlineMode() ? "Premium" : "Non-Premium"), accountColor)));
          
        // Protocol with elegant formatting
        src.sendMessage(Component.text("Version: ", NamedTextColor.YELLOW)
            .append(Component.text("Protocol " + target.getProtocolVersion().getProtocol(), NamedTextColor.WHITE)));
          // Playtime information
        long playtimeMs = playerStatsService.getPlayerPlaytime(uuid);
        String formattedPlaytime = PlayerStatsService.formatPlaytime(playtimeMs);
        
        src.sendMessage(Component.text("Playtime: ", NamedTextColor.YELLOW)
            .append(Component.text(formattedPlaytime, NamedTextColor.WHITE)));
            
        // Last seen information - for online players, this is "Online now"
        src.sendMessage(Component.text("Last seen: ", NamedTextColor.YELLOW)
            .append(Component.text("Online now", NamedTextColor.GREEN)
                .decorate(TextDecoration.ITALIC)));
    }
      /**
     * Sends connection information section for a player
     */
    private void sendConnectionInfo(CommandSource src, Player target) {
        // Current server with interactive component
        target.getCurrentServer().ifPresentOrElse(serverConnection -> {
            String serverName = serverConnection.getServer().getServerInfo().getName();
            src.sendMessage(Component.text("Server: ", NamedTextColor.YELLOW)
                .append(Component.text(serverName, NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/server " + serverName))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to connect to this server", NamedTextColor.GRAY)))));
        }, () -> {
            src.sendMessage(Component.text("Server: ", NamedTextColor.YELLOW)
                .append(Component.text("Not connected", NamedTextColor.RED)));
        });
        
        // IP Address with copy feature and masked display for security
        InetSocketAddress address = target.getRemoteAddress();
        String ipAddress = (address != null) ? address.getAddress().getHostAddress() : "Unknown";
        
        Component ipComponent = Component.text("IP: ", NamedTextColor.YELLOW)
            .append(Component.text(ipAddress, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.copyToClipboard(ipAddress))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy IP address", NamedTextColor.GRAY))));
        src.sendMessage(ipComponent);
          // Client brand with fancy formatting
        String clientBrand = target.getClientBrand() != null ? target.getClientBrand() : "Unknown";
        src.sendMessage(Component.text("Client: ", NamedTextColor.YELLOW)
            .append(Component.text(clientBrand, NamedTextColor.WHITE)));
        
        // Previous IP addresses (if admin has permission)
        if (src.hasPermission("beaconlabs.admin.viewips")) {
            List<IpHistoryEntry> ipHistory = playerStatsService.getPlayerIpHistory(target.getUniqueId());
            
            // Remove the current IP if it's in the history to avoid duplication
            ipHistory.removeIf(entry -> entry.getIpAddress().equals(ipAddress));
            
            if (!ipHistory.isEmpty()) {
                src.sendMessage(Component.empty());
                src.sendMessage(Component.text("Previous IPs:", NamedTextColor.YELLOW)
                    .decorate(TextDecoration.UNDERLINED));
                
                int count = 0;
                for (IpHistoryEntry entry : ipHistory) {
                    count++;
                    if (count > 3) break; // Only show last 3
                    
                    String historyIp = entry.getIpAddress();
                    Date timestamp = new Date(entry.getTimestamp());
                    String dateStr = DATE_FORMAT.format(timestamp);
                    
                    Component historyComponent = Component.text("  " + historyIp, NamedTextColor.WHITE)
                        .clickEvent(ClickEvent.copyToClipboard(historyIp))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to copy IP", NamedTextColor.GRAY)));
                    
                    src.sendMessage(historyComponent
                        .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(dateStr, NamedTextColor.GRAY)));
                }
            }
        }
        
        // Ping with color gradient based on quality
        long ping = target.getPing();
        NamedTextColor pingColor = NamedTextColor.GREEN;
        String pingQuality = "Excellent";
        
        if (ping < 60) {
            pingColor = NamedTextColor.GREEN;
            pingQuality = "Excellent";
        } else if (ping < 120) {
            pingColor = NamedTextColor.DARK_GREEN;
            pingQuality = "Good";
        } else if (ping < 180) {
            pingColor = NamedTextColor.YELLOW;
            pingQuality = "Average";
        } else if (ping < 300) {
            pingColor = NamedTextColor.GOLD;
            pingQuality = "Poor";
        } else {
            pingColor = NamedTextColor.RED;
            pingQuality = "Very Poor";
        }
          src.sendMessage(Component.text("Ping: ", NamedTextColor.YELLOW)
            .append(Component.text(ping + "ms ", pingColor))
            .append(Component.text("(" + pingQuality + ")", NamedTextColor.GRAY)));
              // Players with the same IP (if admin has permission)
        if (src.hasPermission("beaconlabs.admin.viewips")) {
            // Use the already extracted IP address from above
            String playerIp = address != null ? address.getAddress().getHostAddress() : null;
              if (playerIp != null) {
                List<PlayerStatsService.PlayerData> playersWithSameIp = playerStatsService.getPlayersWithSameIp(playerIp);
                
                // Remove the current player from the list
                playersWithSameIp.removeIf(p -> p.getPlayerId().equals(target.getUniqueId()));
                
                if (!playersWithSameIp.isEmpty()) {
                    src.sendMessage(Component.empty());
                    src.sendMessage(Component.text("» PLAYERS WITH SAME IP", NamedTextColor.DARK_AQUA)
                        .decorate(TextDecoration.BOLD));
                        
                    for (PlayerStatsService.PlayerData player : playersWithSameIp) {
                        boolean isOnline = server.getPlayer(player.getPlayerId()).isPresent();
                        NamedTextColor nameColor = isOnline ? NamedTextColor.GREEN : NamedTextColor.WHITE;
                        TextDecoration nameDeco = isOnline ? TextDecoration.BOLD : null;
                        
                        Component playerComp = Component.text(player.getPlayerName(), nameColor);
                        if (nameDeco != null) {
                            playerComp = playerComp.decorate(nameDeco);
                        }
                        
                        Component entry = Component.text("  • ", NamedTextColor.GRAY)
                            .append(playerComp
                                .clickEvent(ClickEvent.runCommand("/info " + player.getPlayerName()))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to view player info", NamedTextColor.YELLOW)))
                            );
                            
                        // Add online/offline status
                        if (isOnline) {
                            entry = entry.append(Component.text(" (Online)", NamedTextColor.GREEN));
                        } else {
                            Date lastSeen = new Date(player.getLastSeen());
                            String lastSeenStr = DATE_FORMAT.format(lastSeen);
                            entry = entry.append(Component.text(" (Last seen: " + lastSeenStr + ")", NamedTextColor.GRAY));
                        }
                        
                        src.sendMessage(entry);
                    }
                }
            }
        }
    }/**
     * Sends punishment information section for a player using UUID and name
     * This works for both online and offline players
     */
    private void sendPunishmentInfo(CommandSource src, UUID uuid, String playerName) {
        boolean banned = service.isBanned(uuid);
        boolean muted = service.isMuted(uuid);
        
        // Status indicators with modern symbols
        String banSymbol = banned ? "⛔ " : "✓ ";
        String muteSymbol = muted ? "🔇 " : "✓ ";
        
        // Status with color coding
        Component banStatus = Component.text("Ban: ", NamedTextColor.YELLOW)
            .append(banned ? 
                Component.text(banSymbol + "BANNED", NamedTextColor.RED).decorate(TextDecoration.BOLD) : 
                Component.text(banSymbol + "Not Banned", NamedTextColor.GREEN));
        
        Component muteStatus = Component.text("Mute: ", NamedTextColor.YELLOW)
            .append(muted ? 
                Component.text(muteSymbol + "MUTED", NamedTextColor.RED).decorate(TextDecoration.BOLD) : 
                Component.text(muteSymbol + "Not Muted", NamedTextColor.GREEN));
        
        src.sendMessage(banStatus);
        src.sendMessage(muteStatus);
        
        // Active punishments with better formatting
        List<PunishmentRecord> history = service.getHistory(uuid);
        boolean hasActivePunishments = false;
        
        for (PunishmentRecord record : history) {
            if (record.active) {
                if (!hasActivePunishments) {
                    src.sendMessage(Component.empty());
                    src.sendMessage(Component.text("Active Punishments:", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD));
                    hasActivePunishments = true;
                }                // Format dates in a cleaner way
                String formattedDate;
                String duration = DurationUtils.formatDuration(record.duration);
                String expiry;
                  try {
                    // Use the utility method to handle various timestamp formats
                    Date startDate = PunishmentService.parseTimestamp(record.startTime);
                    formattedDate = DATE_FORMAT.format(startDate);
                    
                    if (record.duration <= 0) {
                        expiry = "Never";
                    } else {
                        // Calculate expiry time by adding duration to start time
                        Date expiryDate = new Date(startDate.getTime() + record.duration);
                        expiry = DATE_FORMAT.format(expiryDate);
                    }
                } catch (Exception e) {
                    formattedDate = "Invalid date";
                    expiry = "Invalid date";
                    plugin.getServer().getConsoleCommandSource().sendMessage(
                        Component.text("Error formatting punishment dates: " + e.getMessage(), NamedTextColor.RED)
                    );
                }
                
                // Type badge with appropriate color
                NamedTextColor typeColor = record.type.equalsIgnoreCase("ban") ? NamedTextColor.DARK_RED : 
                                          record.type.equalsIgnoreCase("mute") ? NamedTextColor.GOLD : 
                                          NamedTextColor.YELLOW;
                
                // Send punishment entry with better structure
                src.sendMessage(Component.text()
                    .append(Component.text("[" + record.type.toUpperCase() + "] ", typeColor)
                        .decorate(TextDecoration.BOLD))
                    .build());
                
                src.sendMessage(Component.text()
                    .append(Component.text("  Reason: ", NamedTextColor.YELLOW))
                    .append(Component.text(record.reason, NamedTextColor.WHITE))
                    .build());
                
                src.sendMessage(Component.text()
                    .append(Component.text("  Started: ", NamedTextColor.YELLOW))
                    .append(Component.text(formattedDate, NamedTextColor.WHITE))
                    .build());
                
                src.sendMessage(Component.text()
                    .append(Component.text("  Duration: ", NamedTextColor.YELLOW))
                    .append(Component.text(duration, NamedTextColor.WHITE))
                    .build());
                
                src.sendMessage(Component.text()
                    .append(Component.text("  Expires: ", NamedTextColor.YELLOW))
                    .append(Component.text(expiry, 
                        record.duration > 0 ? NamedTextColor.WHITE : NamedTextColor.RED))
                    .build());
                
                src.sendMessage(Component.text()
                    .append(Component.text("  Issued by: ", NamedTextColor.YELLOW))
                    .append(Component.text(record.issuerName, NamedTextColor.LIGHT_PURPLE))
                    .build());
                
                src.sendMessage(Component.empty());
            }
        }
        
        if (!hasActivePunishments) {
            src.sendMessage(Component.text("✓ No active punishments", NamedTextColor.GREEN));
        }
    }
    
    /**
     * Sends a clean divider line
     */
    private void sendDivider(CommandSource src, NamedTextColor color) {
        src.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", color));
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
}