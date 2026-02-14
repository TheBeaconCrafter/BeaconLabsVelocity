package org.bcnlab.beaconLabsVelocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.ReportService;
import org.bcnlab.beaconLabsVelocity.service.ReportService.Report;
import org.bcnlab.beaconLabsVelocity.service.ReportService.ReportStatus;
import org.bcnlab.beaconLabsVelocity.util.DurationUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Command for staff to manage player reports
 */
public class ReportsCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;
    private final ReportService reportService;
    
    // Permission
    private static final String PERMISSION = "beaconlabs.command.reports";
    
    // Default page size for report listings
    private static final int PAGE_SIZE = 7;
    
    // Date time formatter for human-readable timestamps
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                                                     .withZone(ZoneId.systemDefault());
    
    public ReportsCommand(BeaconLabsVelocity plugin, ReportService reportService) {
        this.plugin = plugin;
        this.reportService = reportService;
    }
    
    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        // Check for permission
        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(plugin.getPrefix().append(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            ));
            return;
        }
          // Handle subcommands
        if (args.length == 0) {
            // Default behavior: show both open and in-progress reports
            listActiveReports(source, 1);
            return;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "list":
                // Handle filters for report listing
                if (args.length >= 2) {
                    String statusArg = args[1].toUpperCase();
                    ReportStatus status;
                    try {
                        status = ReportStatus.valueOf(statusArg);
                    } catch (IllegalArgumentException e) {
                        source.sendMessage(plugin.getPrefix().append(
                            Component.text("Invalid status. Use: OPEN, IN_PROGRESS, RESOLVED, REJECTED, or ALL", NamedTextColor.RED)
                        ));
                        return;
                    }
                    
                    int page = 1;
                    if (args.length >= 3) {
                        try {
                            page = Integer.parseInt(args[2]);
                            if (page < 1) page = 1;
                        } catch (NumberFormatException e) {
                            source.sendMessage(plugin.getPrefix().append(
                                Component.text("Invalid page number.", NamedTextColor.RED)
                            ));
                            return;
                        }
                    }
                      // List reports with the specific status
                    listReports(source, status.equals(ReportStatus.valueOf("ALL")) ? null : status, page);
                } else {
                    // Default to listing active reports
                    listActiveReports(source, 1);
                }
                break;
                
            case "view":
                if (args.length < 2) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Please specify a report ID.", NamedTextColor.RED)
                    ));
                    return;
                }
                
                int reportId;
                try {
                    reportId = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Invalid report ID. Must be a number.", NamedTextColor.RED)
                    ));
                    return;
                }
                
                // View the specific report
                viewReport(source, reportId);
                break;
                
            case "player":
                if (args.length < 2) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Please specify a player name.", NamedTextColor.RED)
                    ));
                    return;
                }
                
                // Get reports for the specified player
                listPlayerReports(source, args[1]);
                break;
                
            case "status":
                if (args.length < 3) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Usage: /reports status <id> <new-status> [note]", NamedTextColor.RED)
                    ));
                    return;
                }
                
                int statusReportId;
                try {
                    statusReportId = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Invalid report ID. Must be a number.", NamedTextColor.RED)
                    ));
                    return;
                }
                
                String statusArg = args[2].toUpperCase();
                ReportStatus newStatus;
                try {
                    newStatus = ReportStatus.valueOf(statusArg);
                } catch (IllegalArgumentException e) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Invalid status. Use: OPEN, IN_PROGRESS, RESOLVED, REJECTED", NamedTextColor.RED)
                    ));
                    return;
                }
                
                // Build note from remaining arguments if any
                String note = "";
                if (args.length > 3) {
                    StringBuilder noteBuilder = new StringBuilder();
                    for (int i = 3; i < args.length; i++) {
                        if (i > 3) noteBuilder.append(" ");
                        noteBuilder.append(args[i]);
                    }
                    note = noteBuilder.toString().trim();
                }
                
                // Update the report status
                updateReportStatus(source, statusReportId, newStatus, note);
                break;
                
            case "help":
                // Show help information
                sendHelp(source);
                break;
                
            default:
                // Try to interpret as a report ID
                try {
                    int viewReportId = Integer.parseInt(subCommand);
                    viewReport(source, viewReportId);
                } catch (NumberFormatException e) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Unknown subcommand. Use /reports help for usage information.", NamedTextColor.RED)
                    ));
                }
                break;
        }
    }
    
    /**
     * List reports with pagination
     * 
     * @param source The command source
     * @param status The status to filter by, or null for all reports
     * @param page The page number (1-based)
     */
    private void listReports(CommandSource source, ReportStatus status, int page) {
        int offset = (page - 1) * PAGE_SIZE;
        
        reportService.getReports(status, PAGE_SIZE + 1, offset).thenAccept(reports -> {
            if (reports.isEmpty()) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("No reports found.", NamedTextColor.YELLOW)
                ));
                return;
            }
            
            // Determine if there are more pages
            boolean hasNextPage = reports.size() > PAGE_SIZE;
            if (hasNextPage) {
                // Remove the extra item we fetched to check for more pages
                reports.remove(reports.size() - 1);
            }
            
            // Build header based on status
            String statusDisplay = status != null ? status.getDisplayName() : "All";
            Component header = Component.text("Player Reports - " + statusDisplay, NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" (Page " + page + ")", NamedTextColor.YELLOW));
                
            source.sendMessage(Component.empty());
            source.sendMessage(header);
            source.sendMessage(Component.text("-------------------------------", NamedTextColor.DARK_GRAY));
            
            // Display reports
            for (Report report : reports) {
                NamedTextColor statusColor;
                switch (report.getStatus()) {
                    case OPEN:
                        statusColor = NamedTextColor.RED;
                        break;
                    case IN_PROGRESS:
                        statusColor = NamedTextColor.YELLOW;
                        break;
                    case RESOLVED:
                        statusColor = NamedTextColor.GREEN;
                        break;
                    case REJECTED:
                        statusColor = NamedTextColor.GRAY;
                        break;
                    default:
                        statusColor = NamedTextColor.WHITE;
                }
                
                Component reportEntry = Component.text("#" + report.getId(), NamedTextColor.GOLD)
                    .append(Component.text(" [", NamedTextColor.DARK_GRAY))
                    .append(Component.text(report.getStatus().getDisplayName(), statusColor))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(report.getReportedName(), NamedTextColor.WHITE))
                    .append(Component.text(" reported by ", NamedTextColor.GRAY))
                    .append(Component.text(report.getReporterName(), NamedTextColor.WHITE))
                    .append(Component.text(" (", NamedTextColor.GRAY))
                    .append(Component.text(formatTimeAgo(report.getReportTime()), NamedTextColor.AQUA))
                    .append(Component.text(")", NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.runCommand("/reports view " + report.getId()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to view details", NamedTextColor.YELLOW)));
                
                source.sendMessage(reportEntry);
            }
            
            // Navigation footer
            Component footer = Component.empty();
            
            if (page > 1) {
                footer = footer.append(
                    Component.text("[Previous]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/reports list " + (status != null ? status.name() : "ALL") + " " + (page - 1)))
                        .hoverEvent(HoverEvent.showText(Component.text("Go to previous page", NamedTextColor.YELLOW)))
                );
            } else {
                footer = footer.append(Component.text("[Previous]", NamedTextColor.DARK_GRAY));
            }
            
            footer = footer.append(Component.text(" | ", NamedTextColor.GRAY));
            
            if (hasNextPage) {
                footer = footer.append(
                    Component.text("[Next]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/reports list " + (status != null ? status.name() : "ALL") + " " + (page + 1)))
                        .hoverEvent(HoverEvent.showText(Component.text("Go to next page", NamedTextColor.YELLOW)))
                );
            } else {
                footer = footer.append(Component.text("[Next]", NamedTextColor.DARK_GRAY));
            }
            
            source.sendMessage(Component.text("-------------------------------", NamedTextColor.DARK_GRAY));
            source.sendMessage(footer);
        });
    }
    
    /**
     * View details of a specific report
     * 
     * @param source The command source
     * @param reportId The report ID to view
     */
    private void viewReport(CommandSource source, int reportId) {
        reportService.getReport(reportId).thenAccept(report -> {
            if (report == null) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Report #" + reportId + " not found.", NamedTextColor.RED)
                ));
                return;
            }
            
            source.sendMessage(Component.empty());
            source.sendMessage(Component.text("Report #" + reportId + " Details", NamedTextColor.GOLD, TextDecoration.BOLD));
            source.sendMessage(Component.text("-------------------------------", NamedTextColor.DARK_GRAY));
            
            // Status with color
            NamedTextColor statusColor;
            switch (report.getStatus()) {
                case OPEN:
                    statusColor = NamedTextColor.RED;
                    break;
                case IN_PROGRESS:
                    statusColor = NamedTextColor.YELLOW;
                    break;
                case RESOLVED:
                    statusColor = NamedTextColor.GREEN;
                    break;
                case REJECTED:
                    statusColor = NamedTextColor.GRAY;
                    break;
                default:
                    statusColor = NamedTextColor.WHITE;
            }
            
            source.sendMessage(Component.text("Status: ", NamedTextColor.YELLOW)
                .append(Component.text(report.getStatus().getDisplayName(), statusColor)));
            
            // Reported player with clickable name
            Component reportedPlayer = Component.text("Reported: ", NamedTextColor.YELLOW)
                .append(Component.text(report.getReportedName(), NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.suggestCommand("/reports player " + report.getReportedName()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to view all reports for this player", NamedTextColor.AQUA))));
            
            source.sendMessage(reportedPlayer);
            
            // Reporter and server
            source.sendMessage(Component.text("Reporter: ", NamedTextColor.YELLOW)
                .append(Component.text(report.getReporterName(), NamedTextColor.WHITE)));
            source.sendMessage(Component.text("Server: ", NamedTextColor.YELLOW)
                .append(Component.text(report.getServerName(), NamedTextColor.WHITE)));
            
            // Timestamps
            String reportTime = FORMATTER.format(Instant.ofEpochSecond(report.getReportTime()));
            source.sendMessage(Component.text("Reported: ", NamedTextColor.YELLOW)
                .append(Component.text(reportTime + " (" + formatTimeAgo(report.getReportTime()) + ")", NamedTextColor.WHITE)));
            
            // Reason
            source.sendMessage(Component.text("Reason: ", NamedTextColor.YELLOW)
                .append(Component.text(report.getReason(), NamedTextColor.WHITE)));
            
            // Resolution information if available
            if (report.getStatus() == ReportStatus.RESOLVED || report.getStatus() == ReportStatus.REJECTED) {
                source.sendMessage(Component.empty());
                source.sendMessage(Component.text("Resolution Information", NamedTextColor.GOLD));
                source.sendMessage(Component.text("Handled by: ", NamedTextColor.YELLOW)
                    .append(Component.text(report.getHandledByName() != null ? report.getHandledByName() : "N/A", NamedTextColor.WHITE)));
                
                if (report.getResolutionTime() != null) {
                    String resolutionTime = FORMATTER.format(Instant.ofEpochSecond(report.getResolutionTime()));
                    source.sendMessage(Component.text("Resolved on: ", NamedTextColor.YELLOW)
                        .append(Component.text(resolutionTime + " (" + formatTimeAgo(report.getResolutionTime()) + ")", NamedTextColor.WHITE)));
                }
                
                source.sendMessage(Component.text("Note: ", NamedTextColor.YELLOW)
                    .append(Component.text(report.getResolutionNote() != null ? report.getResolutionNote() : "No notes provided", NamedTextColor.WHITE)));
            }
            
            // Action buttons if report is not resolved/rejected
            if (report.getStatus() == ReportStatus.OPEN || report.getStatus() == ReportStatus.IN_PROGRESS) {
                source.sendMessage(Component.empty());
                source.sendMessage(Component.text("Actions:", NamedTextColor.GOLD));
                
                // Status change buttons
                Component statusButtons = Component.empty();
                
                if (report.getStatus() == ReportStatus.OPEN) {
                    statusButtons = statusButtons.append(
                        Component.text("[In Progress]", NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.suggestCommand("/reports status " + reportId + " IN_PROGRESS "))
                            .hoverEvent(HoverEvent.showText(Component.text("Mark as in progress", NamedTextColor.YELLOW)))
                    );
                } else {
                    statusButtons = statusButtons.append(
                        Component.text("[Open]", NamedTextColor.RED)
                            .clickEvent(ClickEvent.suggestCommand("/reports status " + reportId + " OPEN "))
                            .hoverEvent(HoverEvent.showText(Component.text("Mark as open", NamedTextColor.RED)))
                    );
                }
                
                statusButtons = statusButtons.append(Component.text(" "));
                
                statusButtons = statusButtons.append(
                    Component.text("[Resolve]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.suggestCommand("/reports status " + reportId + " RESOLVED "))
                        .hoverEvent(HoverEvent.showText(Component.text("Mark as resolved", NamedTextColor.GREEN)))
                );
                
                statusButtons = statusButtons.append(Component.text(" "));
                
                statusButtons = statusButtons.append(
                    Component.text("[Reject]", NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.suggestCommand("/reports status " + reportId + " REJECTED "))
                        .hoverEvent(HoverEvent.showText(Component.text("Mark as rejected", NamedTextColor.GRAY)))
                );
                
                // Teleport to server button if it's a valid server
                if (!report.getServerName().equalsIgnoreCase("Unknown")) {
                    statusButtons = statusButtons.append(Component.text(" "));
                    statusButtons = statusButtons.append(
                        Component.text("[Server]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/server " + report.getServerName()))
                            .hoverEvent(HoverEvent.showText(Component.text("Connect to " + report.getServerName(), NamedTextColor.AQUA)))
                    );
                }
                
                source.sendMessage(statusButtons);
            }
            
            source.sendMessage(Component.text("-------------------------------", NamedTextColor.DARK_GRAY));
        });
    }
    
    /**
     * List all reports for a specific player
     * 
     * @param source The command source
     * @param playerName The player's name to search for
     */
    private void listPlayerReports(CommandSource source, String playerName) {
        reportService.getPlayerReports(playerName).thenAccept(reports -> {
            if (reports.isEmpty()) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("No reports found for player '" + playerName + "'.", NamedTextColor.YELLOW)
                ));
                return;
            }
            
            source.sendMessage(Component.empty());
            source.sendMessage(Component.text("Reports for '" + playerName + "'", NamedTextColor.GOLD, TextDecoration.BOLD));
            source.sendMessage(Component.text("-------------------------------", NamedTextColor.DARK_GRAY));
            
            // Display report summaries
            for (Report report : reports) {
                NamedTextColor statusColor;
                switch (report.getStatus()) {
                    case OPEN:
                        statusColor = NamedTextColor.RED;
                        break;
                    case IN_PROGRESS:
                        statusColor = NamedTextColor.YELLOW;
                        break;
                    case RESOLVED:
                        statusColor = NamedTextColor.GREEN;
                        break;
                    case REJECTED:
                        statusColor = NamedTextColor.GRAY;
                        break;
                    default:
                        statusColor = NamedTextColor.WHITE;
                }
                
                Component reportEntry = Component.text("#" + report.getId(), NamedTextColor.GOLD)
                    .append(Component.text(" [", NamedTextColor.DARK_GRAY))
                    .append(Component.text(report.getStatus().getDisplayName(), statusColor))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("by " + report.getReporterName() + ": ", NamedTextColor.WHITE))
                    .append(Component.text(shortenReason(report.getReason()), NamedTextColor.YELLOW))
                    .append(Component.text(" (" + formatTimeAgo(report.getReportTime()) + ")", NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.runCommand("/reports view " + report.getId()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to view details", NamedTextColor.YELLOW)));
                
                source.sendMessage(reportEntry);
            }
            
            source.sendMessage(Component.text("-------------------------------", NamedTextColor.DARK_GRAY));
            source.sendMessage(Component.text("Total: ", NamedTextColor.YELLOW)
                .append(Component.text(reports.size() + " reports", NamedTextColor.WHITE)));
        });
    }
    
    /**
     * Update the status of a report
     * 
     * @param source The command source
     * @param reportId The report ID to update
     * @param newStatus The new status to set
     * @param note Resolution note (if any)
     */
    private void updateReportStatus(CommandSource source, int reportId, ReportStatus newStatus, String note) {
        // Get the staff member's information
        String staffUuid;
        String staffName;
        
        if (source instanceof Player) {
            Player player = (Player) source;
            staffUuid = player.getUniqueId().toString();
            staffName = player.getUsername();
        } else {
            staffUuid = "console";
            staffName = "Console";
        }
        
        // First get the current report to check if it exists
        reportService.getReport(reportId).thenAccept(report -> {
            if (report == null) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Report #" + reportId + " not found.", NamedTextColor.RED)
                ));
                return;
            }
            
            // Don't update if the status is the same
            if (report.getStatus() == newStatus) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("Report #" + reportId + " is already marked as " + newStatus.getDisplayName() + ".", NamedTextColor.YELLOW)
                ));
                return;
            }
            
            // Update the report status
            reportService.updateReportStatus(reportId, newStatus, staffUuid, staffName, note).thenAccept(success -> {
                if (success) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Report #" + reportId + " status updated to " + newStatus.getDisplayName() + ".", NamedTextColor.GREEN)
                    ));
                    
                    // View the updated report
                    viewReport(source, reportId);
                } else {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("Failed to update report status. Please try again.", NamedTextColor.RED)
                    ));
                }
            });
        });
    }
    
    /**
     * Send help information to the command source
     * 
     * @param source The command source
     */    private void sendHelp(CommandSource source) {
        source.sendMessage(Component.empty());
        source.sendMessage(Component.text("Reports Command Help", NamedTextColor.GOLD, TextDecoration.BOLD));
        source.sendMessage(Component.text("-------------------------------", NamedTextColor.DARK_GRAY));
        
        sendHelpLine(source, "/reports", "Show all active reports (OPEN and IN_PROGRESS)");
        sendHelpLine(source, "/reports list <status> [page]", "List reports with status filter (OPEN, IN_PROGRESS, RESOLVED, REJECTED, ALL)");
        sendHelpLine(source, "/reports view <id>", "View details of a specific report");
        sendHelpLine(source, "/reports <id>", "Shorthand for view command");
        sendHelpLine(source, "/reports player <name>", "View all reports for a specific player");
        sendHelpLine(source, "/reports status <id> <status> [note]", "Update a report's status");
        
        source.sendMessage(Component.text("-------------------------------", NamedTextColor.DARK_GRAY));
    }
    
    /**
     * Helper to send a formatted help line
     * 
     * @param source The command source
     * @param command The command syntax
     * @param description The command description
     */
    private void sendHelpLine(CommandSource source, String command, String description) {
        source.sendMessage(
            Component.text(command, NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to use this command", NamedTextColor.GRAY)))
                .append(Component.text(" - ", NamedTextColor.GRAY))
                .append(Component.text(description, NamedTextColor.WHITE))
        );
    }
    
    /**
     * Format a unix timestamp as a human-readable "time ago" string
     * 
     * @param timestamp Unix timestamp in seconds
     * @return Formatted string like "5 minutes ago"
     */
    private String formatTimeAgo(long timestamp) {
        long currentTime = Instant.now().getEpochSecond();
        long diff = currentTime - timestamp;
        
        return DurationUtils.formatTimeAgo(diff);
    }
    
    /**
     * Shorten a reason string if it's too long
     * 
     * @param reason The full reason
     * @return Shortened reason with ellipsis if needed
     */
    private String shortenReason(String reason) {
        int maxLength = 30;
        if (reason.length() <= maxLength) {
            return reason;
        }
        return reason.substring(0, maxLength - 3) + "...";
    }
    
    /**
     * List all active reports (OPEN and IN_PROGRESS) with pagination
     * 
     * @param source The command source
     * @param page The page number (1-based)
     */
    private void listActiveReports(CommandSource source, int page) {
        int offset = (page - 1) * PAGE_SIZE;
        
        // First, fetch all the active reports (we'll need to do this in two steps)
        CompletableFuture<List<Report>> openReportsFuture = reportService.getReports(ReportStatus.OPEN, PAGE_SIZE + 1, offset);
        CompletableFuture<List<Report>> inProgressReportsFuture = reportService.getReports(ReportStatus.IN_PROGRESS, PAGE_SIZE + 1, offset);
        
        // When both futures complete, merge and display the reports
        CompletableFuture.allOf(openReportsFuture, inProgressReportsFuture).thenRun(() -> {
            try {
                List<Report> openReports = openReportsFuture.get();
                List<Report> inProgressReports = inProgressReportsFuture.get();
                
                // Merge the reports
                List<Report> reports = new ArrayList<>();
                reports.addAll(openReports);
                reports.addAll(inProgressReports);
                
                // Sort by report time (newest first)
                reports.sort((r1, r2) -> Long.compare(r2.getReportTime(), r1.getReportTime()));
                
                if (reports.isEmpty()) {
                    source.sendMessage(plugin.getPrefix().append(
                        Component.text("No active reports found.", NamedTextColor.YELLOW)
                    ));
                    return;
                }
                
                // Determine if there are more reports than we can display on one page
                boolean hasNextPage = reports.size() > PAGE_SIZE;
                if (hasNextPage) {
                    // Keep only the reports that fit on this page
                    reports = reports.subList(0, PAGE_SIZE);
                }
                
                // Build header
                Component header = Component.text("Active Reports", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text(" (Page " + page + ")", NamedTextColor.YELLOW));
                    
                source.sendMessage(Component.empty());
                source.sendMessage(header);
                source.sendMessage(Component.text("-------------------------------", NamedTextColor.DARK_GRAY));
                
                // Display reports
                for (Report report : reports) {
                    NamedTextColor statusColor;
                    switch (report.getStatus()) {
                        case OPEN:
                            statusColor = NamedTextColor.RED;
                            break;
                        case IN_PROGRESS:
                            statusColor = NamedTextColor.YELLOW;
                            break;
                        default:
                            statusColor = NamedTextColor.WHITE;
                    }
                    
                    Component reportEntry = Component.text("#" + report.getId(), NamedTextColor.GOLD)
                        .append(Component.text(" [", NamedTextColor.DARK_GRAY))
                        .append(Component.text(report.getStatus().getDisplayName(), statusColor))
                        .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(report.getReportedName(), NamedTextColor.WHITE))
                        .append(Component.text(" reported by ", NamedTextColor.GRAY))
                        .append(Component.text(report.getReporterName(), NamedTextColor.WHITE))
                        .append(Component.text(" (", NamedTextColor.GRAY))
                        .append(Component.text(formatTimeAgo(report.getReportTime()), NamedTextColor.AQUA))
                        .append(Component.text(")", NamedTextColor.GRAY))
                        .clickEvent(ClickEvent.runCommand("/reports view " + report.getId()))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to view details", NamedTextColor.YELLOW)));
                    
                    source.sendMessage(reportEntry);
                }
                
                // Navigation footer
                Component footer = Component.empty();
                
                if (page > 1) {
                    footer = footer.append(
                        Component.text("[Previous]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/reports list ALL " + (page - 1)))
                            .hoverEvent(HoverEvent.showText(Component.text("Go to previous page", NamedTextColor.YELLOW)))
                    );
                } else {
                    footer = footer.append(Component.text("[Previous]", NamedTextColor.DARK_GRAY));
                }
                
                footer = footer.append(Component.text(" | ", NamedTextColor.GRAY));
                
                if (hasNextPage) {
                    footer = footer.append(
                        Component.text("[Next]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/reports list ALL " + (page + 1)))
                            .hoverEvent(HoverEvent.showText(Component.text("Go to next page", NamedTextColor.YELLOW)))
                    );
                } else {
                    footer = footer.append(Component.text("[Next]", NamedTextColor.DARK_GRAY));
                }
                
                source.sendMessage(Component.text("-------------------------------", NamedTextColor.DARK_GRAY));
                source.sendMessage(footer);            } catch (Exception e) {
                source.sendMessage(plugin.getPrefix().append(
                    Component.text("An error occurred while fetching reports.", NamedTextColor.RED)
                ));
            }
        });
    }
      @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        
        // No arguments, suggest subcommands
        if (args.length == 0) {
            return List.of("list", "view", "player", "status", "help");
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "list":
                if (args.length == 2) {
                    return Arrays.stream(ReportStatus.values())
                        .map(ReportStatus::name)
                        .collect(Collectors.toList());
                }
                break;
                
            case "view":
                // Could suggest recent report IDs, but that would require caching them
                break;
                
            case "player":
                if (args.length == 2) {
                    String partialName = args[1].toLowerCase();
                    if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                        return plugin.getCrossProxyService().getOnlinePlayerNames().stream()
                            .filter(name -> name.toLowerCase().startsWith(partialName))
                            .collect(Collectors.toList());
                    }
                    return plugin.getServer().getAllPlayers().stream()
                        .map(Player::getUsername)
                        .filter(name -> name.toLowerCase().startsWith(partialName))
                        .collect(Collectors.toList());
                }
                break;
                
            case "status":
                if (args.length == 3) {
                    return Arrays.stream(ReportStatus.values())
                        .map(ReportStatus::name)
                        .collect(Collectors.toList());
                } else if (args.length == 4) {
                    // Common resolution notes suggestions
                    return List.of(
                        "Resolved after investigation",
                        "Player warned",
                        "Player banned",
                        "No evidence found",
                        "False report"
                    );
                }
                break;
                
            default:
                // First argument, suggest subcommands
                return List.of("list", "view", "player", "status", "help")
                    .stream()
                    .filter(cmd -> cmd.startsWith(subCommand))
                    .collect(Collectors.toList());
        }
        
        return List.of();
    }
    
    @Override
    public boolean hasPermission(final Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
