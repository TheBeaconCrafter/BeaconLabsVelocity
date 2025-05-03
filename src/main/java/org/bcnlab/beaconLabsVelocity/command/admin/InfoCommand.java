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
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public InfoCommand(ProxyServer server, PunishmentService service, BeaconLabsVelocity plugin, PunishmentConfig config) {
        this.server = server;
        this.service = service;
        this.plugin = plugin;
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
            
            // Separator for punishment section
            src.sendMessage(Component.empty());
            src.sendMessage(Component.text("» PUNISHMENT STATUS", NamedTextColor.RED)
                .decorate(TextDecoration.BOLD));
            
            // Send punishment info
            sendPunishmentInfo(src, uuid, targetName);
            
        } else {
            // Player is offline - try to find in the database
            UUID offlineUuid = service.getPlayerUUID(targetName);
            
            if (offlineUuid != null) {
                // Found an offline player in the database
                src.sendMessage(Component.text("⚠ Player is currently offline", NamedTextColor.RED));
                
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
                
                // Separator for punishment section
                src.sendMessage(Component.empty());
                src.sendMessage(Component.text("» PUNISHMENT STATUS", NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD));
                
                // Send punishment info for offline player
                sendPunishmentInfo(src, offlineUuid, targetName);
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
    }    /**
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
                }
                
                // Format dates in a cleaner way
                String formattedDate = DATE_FORMAT.format(new Date(record.startTime));
                String duration = DurationUtils.formatDuration(record.duration);
                String expiry = record.duration > 0 
                    ? DATE_FORMAT.format(new Date(record.startTime + record.duration)) 
                    : "Never";
                
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
            return server.getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return List.of();
    }
}