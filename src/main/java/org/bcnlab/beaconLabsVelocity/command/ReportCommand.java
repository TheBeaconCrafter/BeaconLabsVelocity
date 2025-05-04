package org.bcnlab.beaconLabsVelocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.ReportService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Command for players to report other players
 */
public class ReportCommand implements SimpleCommand {
    
    private final BeaconLabsVelocity plugin;
    private final ReportService reportService;
    
    // Permission
    private static final String PERMISSION = "beaconlabs.command.report";
    
    // Cooldowns map to prevent spam
    private final List<ReportCooldown> cooldowns = new ArrayList<>();
    
    // Default cooldown in seconds
    private static final int DEFAULT_COOLDOWN = 60;
    
    public ReportCommand(BeaconLabsVelocity plugin, ReportService reportService) {
        this.plugin = plugin;
        this.reportService = reportService;
    }
    
    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        // Check if the source is a player
        if (!(source instanceof Player)) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("Only players can report other players.", NamedTextColor.RED)
            ));
            return;
        }
        
        Player player = (Player) source;
        
        // Check for permission
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(plugin.getPrefix().append(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Check for proper usage
        if (args.length < 2) {
            sendUsage(player);
            return;
        }
        
        // Get the reported player name
        String targetName = args[0];
        
        // Check if player is reporting themselves
        if (targetName.equalsIgnoreCase(player.getUsername())) {
            player.sendMessage(plugin.getPrefix().append(
                Component.text("You cannot report yourself.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Check if the target player exists/is online
        Optional<Player> targetOptional = plugin.getServer().getPlayer(targetName);
        
        // If the target isn't online, warn the player but still allow the report
        if (targetOptional.isEmpty()) {
            player.sendMessage(plugin.getPrefix().append(
                Component.text("Warning: ", NamedTextColor.YELLOW, TextDecoration.BOLD)
                    .append(Component.text("The player you're reporting is not online. Your report will still be submitted.", NamedTextColor.YELLOW, TextDecoration.ITALIC))
            ));
        }
        
        // Check cooldown for this player
        if (isOnCooldown(player.getUniqueId().toString())) {
            player.sendMessage(plugin.getPrefix().append(
                Component.text("You must wait before submitting another report.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Build reason from remaining arguments
        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) reasonBuilder.append(" ");
            reasonBuilder.append(args[i]);
        }
        String reason = reasonBuilder.toString().trim();
        
        // Get current server
        String serverName = player.getCurrentServer()
            .map(ServerConnection::getServerInfo)
            .map(server -> server.getName())
            .orElse("Unknown");
            
        // Get UUID for reported player or generate a placeholder for offline players
        String targetUuid = targetOptional
            .map(target -> target.getUniqueId().toString())
            .orElse("offline:" + targetName);
        
        // Submit the report
        reportService.createReport(
            targetUuid, 
            targetName, 
            player.getUniqueId().toString(), 
            player.getUsername(), 
            reason,
            serverName
        ).thenAccept(reportId -> {
            if (reportId > 0) {
                // Success
                player.sendMessage(plugin.getPrefix().append(
                    Component.text("Your report has been submitted. Report ID: ", NamedTextColor.GREEN)
                        .append(Component.text("#" + reportId, NamedTextColor.GOLD, TextDecoration.BOLD))
                ));
                
                // Notify online staff
                notifyStaff(targetName, player.getUsername(), reason, serverName, reportId);
                
                // Add cooldown
                addCooldown(player.getUniqueId().toString());
            } else {
                // Failed
                player.sendMessage(plugin.getPrefix().append(
                    Component.text("Failed to submit your report. Please try again later.", NamedTextColor.RED)
                ));
            }
        });
    }
    
    /**
     * Send usage information to the player
     * 
     * @param player The player to send usage information to
     */
    private void sendUsage(Player player) {
        player.sendMessage(plugin.getPrefix().append(
            Component.text("Usage: ", NamedTextColor.GOLD)
                .append(Component.text("/report <player> <reason>", NamedTextColor.YELLOW))
        ));
        player.sendMessage(Component.text("Report a player for breaking the rules.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("Example: /report BadPlayer hacking in game", NamedTextColor.GRAY));
    }
    
    /**
     * Check if a player is on cooldown
     * 
     * @param playerUuid The UUID of the player to check
     * @return True if on cooldown, false otherwise
     */
    private boolean isOnCooldown(String playerUuid) {
        // Clean up expired cooldowns first
        cooldowns.removeIf(cooldown -> cooldown.isExpired());
        
        // Check if the player has a cooldown
        return cooldowns.stream().anyMatch(cooldown -> cooldown.getPlayerUuid().equals(playerUuid));
    }
    
    /**
     * Add a cooldown for a player
     * 
     * @param playerUuid The UUID of the player to add a cooldown for
     */
    private void addCooldown(String playerUuid) {
        // Get cooldown time from config or use default
        int cooldownSeconds = plugin.getConfig()
            .node("reports", "cooldown-seconds")
            .getInt(DEFAULT_COOLDOWN);
        
        cooldowns.add(new ReportCooldown(playerUuid, cooldownSeconds));
    }
    
    /**
     * Notify online staff about a new report
     * 
     * @param reportedName The reported player's name
     * @param reporterName The reporting player's name
     * @param reason The reason for the report
     * @param serverName The server where the report was made
     * @param reportId The report ID
     */
    private void notifyStaff(String reportedName, String reporterName, String reason, String serverName, int reportId) {
        Component notification = Component.text("【REPORT】", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text(" New player report #" + reportId + ":", NamedTextColor.GOLD))
            .append(Component.newline())
            .append(Component.text("  Reported: ", NamedTextColor.YELLOW))
            .append(Component.text(reportedName, NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("  By: ", NamedTextColor.YELLOW))
            .append(Component.text(reporterName, NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("  Server: ", NamedTextColor.YELLOW))
            .append(Component.text(serverName, NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("  Reason: ", NamedTextColor.YELLOW))
            .append(Component.text(reason, NamedTextColor.WHITE));
        
        // Send to all staff with the reports.notify permission
        for (Player staffMember : plugin.getServer().getAllPlayers()) {
            if (staffMember.hasPermission("beaconlabs.reports.notify")) {
                staffMember.sendMessage(notification);
            }
        }
    }
    
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        
        // For the first argument, suggest online players
        if (args.length == 1) {
            String partialName = args[0].toLowerCase();
            return plugin.getServer().getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase().startsWith(partialName))
                .filter(name -> !name.equalsIgnoreCase(((Player) invocation.source()).getUsername()))  // Exclude self
                .collect(Collectors.toList());
        }
        
        // For the second argument, suggest common report reasons
        if (args.length == 2) {
            List<String> reasons = new ArrayList<>();
            reasons.add("hacking");
            reasons.add("spam");
            reasons.add("harassment");
            reasons.add("inappropriate language");
            reasons.add("griefing");
            reasons.add("abuse");
            
            String partialReason = args[1].toLowerCase();
            return reasons.stream()
                .filter(reason -> reason.startsWith(partialReason))
                .collect(Collectors.toList());
        }
        
        return List.of();
    }
    
    @Override
    public boolean hasPermission(final Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
    
    /**
     * Class to track report cooldowns
     */
    private static class ReportCooldown {
        private final String playerUuid;
        private final long expiresAt;
        
        public ReportCooldown(String playerUuid, int cooldownSeconds) {
            this.playerUuid = playerUuid;
            this.expiresAt = System.currentTimeMillis() + (cooldownSeconds * 1000L);
        }
        
        public String getPlayerUuid() {
            return playerUuid;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
