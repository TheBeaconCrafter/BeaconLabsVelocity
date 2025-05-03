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
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService.IpHistoryEntry;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Command to view all stored IP addresses for a player
 * Usage: /ips <player>
 * Permission: beaconlabs.admin.ips
 */
public class IpsCommand implements SimpleCommand {
    private final ProxyServer server;
    private final PlayerStatsService playerStatsService;
    private final PunishmentService punishmentService; // For UUID lookups
    private final BeaconLabsVelocity plugin;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public IpsCommand(ProxyServer server, PlayerStatsService playerStatsService, 
                      PunishmentService punishmentService, BeaconLabsVelocity plugin) {
        this.server = server;
        this.playerStatsService = playerStatsService;
        this.punishmentService = punishmentService;
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        
        // Permission check
        if (!src.hasPermission("beaconlabs.admin.ips")) {
            src.sendMessage(plugin.getPrefix().append(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)));
            return;
        }
        
        // Usage check
        if (args.length < 1) {
            src.sendMessage(plugin.getPrefix().append(
                Component.text("Usage: /ips <player>", NamedTextColor.RED)));
            return;
        }
        
        String targetName = args[0];
        Optional<Player> optionalTarget = server.getPlayer(targetName);
        UUID targetUuid;
        
        // Get UUID from online player or database
        if (optionalTarget.isPresent()) {
            targetUuid = optionalTarget.get().getUniqueId();
        } else {
            // Try to get UUID from punishment service
            targetUuid = punishmentService.getPlayerUUID(targetName);
            
            if (targetUuid == null) {
                // Player not found at all
                src.sendMessage(plugin.getPrefix().append(
                    Component.text("Player not found: " + targetName, NamedTextColor.RED)));
                return;
            }
        }
        
        // Get and display all IP history for the player
        displayAllIpHistory(src, targetUuid, targetName);
    }
      /**
     * Display the full IP history for a player
     */
    private void displayAllIpHistory(CommandSource src, UUID playerId, String playerName) {
        List<IpHistoryEntry> ipHistory = playerStatsService.getAllPlayerIpHistory(playerId);
        
        if (ipHistory.isEmpty()) {
            src.sendMessage(plugin.getPrefix().append(
                Component.text("No IP history found for " + playerName, NamedTextColor.RED)));
            return;
        }
        
        // Send header
        sendDivider(src, NamedTextColor.GOLD);
        
        Component header = Component.text()
            .append(Component.text("✦ ", NamedTextColor.GOLD))
            .append(Component.text("IP HISTORY: ", NamedTextColor.YELLOW)
                .decorate(TextDecoration.BOLD))
            .append(Component.text(playerName, NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD))
            .append(Component.text(" ✦", NamedTextColor.GOLD))
            .build();
        src.sendMessage(header);
          // Group IPs by address for cleaner display
        Map<String, List<Long>> ipGroups = new HashMap<>();
        
        // Group timestamps by IP address
        for (IpHistoryEntry entry : ipHistory) {
            String ipAddress = entry.getIpAddress();
            ipGroups.computeIfAbsent(ipAddress, k -> new ArrayList<>())
                   .add(entry.getTimestamp());
        }
          // Sort IPs by most recent timestamp
        List<String> sortedIps = new ArrayList<>(ipGroups.keySet());
        sortedIps.sort((ip1, ip2) -> {
            long mostRecent1 = Collections.max(ipGroups.get(ip1));
            long mostRecent2 = Collections.max(ipGroups.get(ip2));
            return Long.compare(mostRecent2, mostRecent1);  // Descending order
        });
        
        // Group IPs by subnet for additional info
        Map<String, List<String>> subnetGroups = new HashMap<>();
        for (String ip : sortedIps) {
            if (ip.contains(".")) { // Only for IPv4
                String subnet = ip.substring(0, ip.lastIndexOf('.'));
                subnetGroups.computeIfAbsent(subnet, k -> new ArrayList<>()).add(ip);
            }
        }
        
        // Display IPs with timestamps
        int count = 0;
        for (String ipAddress : sortedIps) {
            count++;
            List<Long> timestamps = ipGroups.get(ipAddress);
            
            // Sort timestamps in descending order (newest first)
            timestamps.sort(Collections.reverseOrder());
            
            // Create IP component with copy functionality
            Component ipComponent = Component.text(ipAddress, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.copyToClipboard(ipAddress))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy IP address", NamedTextColor.GRAY)));
              // Format main entry with IP and latest date
            Date latestTimestamp = new Date(timestamps.get(0));
            String latestDateStr = DATE_FORMAT.format(latestTimestamp);
            
            // Check if there are other IPs on the same subnet
            String subnetInfo = "";
            if (ipAddress.contains(".")) { // Only for IPv4
                String subnet = ipAddress.substring(0, ipAddress.lastIndexOf('.'));
                List<String> relatedIps = subnetGroups.get(subnet);
                
                if (relatedIps != null && relatedIps.size() > 1) {
                    // There are related IPs on the same subnet
                    int relatedCount = relatedIps.size();
                    if (relatedCount > 1) {
                        subnetInfo = " [Subnet: " + subnet + ".* - " + relatedCount + " related IPs]";
                    }
                }
            }
            
            src.sendMessage(Component.text()
                .append(Component.text(count + ". ", NamedTextColor.GOLD))
                .append(ipComponent)
                .append(Component.text(" - Last seen: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(latestDateStr, NamedTextColor.AQUA))
                .append(!subnetInfo.isEmpty() ? 
                    Component.text(subnetInfo, NamedTextColor.DARK_GREEN)
                    : Component.empty())
                .build());
            
            // If there are multiple dates, show them indented
            if (timestamps.size() > 1) {
                Component historyLabel = Component.text("   History: ", NamedTextColor.GRAY);
                
                // Show only the first 5 timestamps if there are many
                int displayLimit = Math.min(timestamps.size(), 5);
                List<Component> dateComponents = new ArrayList<>();
                
                for (int i = 0; i < displayLimit; i++) {
                    Date timestamp = new Date(timestamps.get(i));
                    String dateStr = DATE_FORMAT.format(timestamp);
                    dateComponents.add(Component.text(dateStr, NamedTextColor.WHITE));
                }
                  // Join with commas
                Component historyComponent = null;
                if (!dateComponents.isEmpty()) {
                    historyComponent = dateComponents.get(0);
                    for (int i = 1; i < dateComponents.size(); i++) {
                        historyComponent = historyComponent.append(Component.text(", ", NamedTextColor.DARK_GRAY))
                                                          .append(dateComponents.get(i));
                    }
                } else {
                    historyComponent = Component.empty();
                }
                
                // Show count of additional dates if there are more than the limit
                if (timestamps.size() > displayLimit) {
                    historyComponent = historyComponent.append(
                        Component.text(" (+" + (timestamps.size() - displayLimit) + " more)", NamedTextColor.DARK_GRAY)
                    );
                }
                
                src.sendMessage(historyLabel.append(historyComponent));
            }
        }
          // Check for subnet changes that might be suspicious
        if (sortedIps.size() >= 2) {
            List<String> ipv4Addresses = sortedIps.stream()
                .filter(ip -> ip.contains("."))
                .collect(Collectors.toList());
                
            if (ipv4Addresses.size() >= 2) {
                boolean hasSubnetChanges = false;
                String previousIp = ipv4Addresses.get(0);
                
                for (int i = 1; i < ipv4Addresses.size(); i++) {
                    String currentIp = ipv4Addresses.get(i);
                    if (!isSameSubnet(previousIp, currentIp)) {
                        hasSubnetChanges = true;
                        break;
                    }
                    previousIp = currentIp;
                }
                
                if (hasSubnetChanges) {
                    src.sendMessage(Component.empty());
                    src.sendMessage(Component.text()
                        .append(Component.text("⚠ ", NamedTextColor.GOLD))
                        .append(Component.text("Notice: ", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                        .append(Component.text("This player has connected from multiple different subnets", 
                                NamedTextColor.GOLD))
                        .build());
                }
            }
        }
        
        // Add total count at the bottom
        src.sendMessage(Component.empty());
        src.sendMessage(Component.text()
            .append(Component.text("Total unique IPs: ", NamedTextColor.YELLOW))
            .append(Component.text(ipGroups.size(), NamedTextColor.GREEN))
            .build());
        
        // Bottom divider
        sendDivider(src, NamedTextColor.GOLD);
    }
      /**
     * Sends a clean divider line
     */
    private void sendDivider(CommandSource src, NamedTextColor color) {
        src.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", color));
    }
    
    /**
     * Checks if two IP addresses are on the same /24 subnet
     * This is a simple check to identify potentially related connections
     */
    private boolean isSameSubnet(String ip1, String ip2) {
        try {
            // Only compare IPv4 addresses
            if (!ip1.contains(".") || !ip2.contains(".")) {
                return false;
            }
            
            // Get the first three octets (xxx.xxx.xxx) for comparison
            String subnet1 = ip1.substring(0, ip1.lastIndexOf('.'));
            String subnet2 = ip2.substring(0, ip2.lastIndexOf('.'));
            
            return subnet1.equals(subnet2);
        } catch (Exception e) {
            // If any error occurs during comparison, they're not the same
            return false;
        }
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
