package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.AntiBotService;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class IpInfoCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;
    private final AntiBotService antiBotService;
    private final ProxyServer server;
    private final PlayerStatsService playerStatsService;

    public IpInfoCommand(BeaconLabsVelocity plugin, AntiBotService antiBotService, ProxyServer server) {
        this.plugin = plugin;
        this.antiBotService = antiBotService;
        this.server = server;
        this.playerStatsService = plugin.getPlayerStatsService();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!src.hasPermission("beaconlabs.admin.ipinfo")) {
            src.sendMessage(plugin.getPrefix().append(Component.text("You don't have permission.", NamedTextColor.RED)));
            return;
        }

        if (args.length == 0) {
            src.sendMessage(plugin.getPrefix().append(Component.text("Usage: /ipinfo <user/ip> [refresh]", NamedTextColor.RED)));
            return;
        }

        String target = args[0];
        boolean refresh = args.length > 1 && args[1].equalsIgnoreCase("refresh");

        String targetIp = null;
        if (target.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            targetIp = target;
        } else {
            // Player
            Optional<com.velocitypowered.api.proxy.Player> p = server.getPlayer(target);
            if (p.isPresent()) {
                targetIp = p.get().getRemoteAddress().getAddress().getHostAddress();
            } else {
                UUID targetUuid = plugin.getPunishmentService().getPlayerUUID(target);
                if (targetUuid == null && plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                    targetUuid = plugin.getCrossProxyService().getPlayerUuidByName(target);
                }
                if (targetUuid == null && playerStatsService != null) {
                    PlayerStatsService.PlayerData pd = playerStatsService.getPlayerDataByName(target);
                    if (pd != null) targetUuid = pd.getPlayerId();
                }

                if (targetUuid == null) {
                    src.sendMessage(plugin.getPrefix().append(Component.text("Player not found: " + target, NamedTextColor.RED)));
                    return;
                }

                if (playerStatsService != null) {
                    List<PlayerStatsService.IpHistoryEntry> history = playerStatsService.getPlayerIpHistory(targetUuid);
                    if (!history.isEmpty()) {
                        targetIp = history.get(0).getIpAddress();
                    }
                }
            }
        }



        if (targetIp == null) {
            src.sendMessage(plugin.getPrefix().append(Component.text("Could not determine IP for target: " + target, NamedTextColor.RED)));
            return;
        }

        final String finalIp = targetIp;
        src.sendMessage(plugin.getPrefix().append(Component.text("Looking up info for IP: " + finalIp + "...", NamedTextColor.GRAY)));

        if (refresh) {
            antiBotService.refreshIpInfo(finalIp).thenAccept(result -> displayInfo(src, finalIp, result));
        } else {
            Optional<AntiBotService.IpCheckResult> cached = antiBotService.getCachedInfo(finalIp);
            if (cached.isPresent()) {
                displayInfo(src, finalIp, cached.get());
            } else {
                src.sendMessage(plugin.getPrefix().append(Component.text("No cached info found. Fetching...", NamedTextColor.GRAY)));
                antiBotService.refreshIpInfo(finalIp).thenAccept(result -> displayInfo(src, finalIp, result));
            }
        }
    }

    private void sendDivider(CommandSource src, NamedTextColor color) {
        src.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", color));
    }

    private void displayInfo(CommandSource src, String ip, AntiBotService.IpCheckResult result) {
        sendDivider(src, NamedTextColor.GOLD);
        
        Component header = Component.text()
            .append(Component.text("✦ ", NamedTextColor.GOLD))
            .append(Component.text("IP INTELLIGENCE: ", NamedTextColor.YELLOW).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            .append(Component.text(ip, NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            .append(Component.text(" ✦", NamedTextColor.GOLD))
            .build();
        src.sendMessage(header);
        
        src.sendMessage(Component.text("» ABUSE DB STATUS", NamedTextColor.AQUA).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));

        NamedTextColor scoreColor = result.confidenceScore >= 90 ? NamedTextColor.RED : (result.confidenceScore > 0 ? NamedTextColor.GOLD : NamedTextColor.GREEN);
        src.sendMessage(Component.text("  Confidence Score: ", NamedTextColor.YELLOW)
            .append(Component.text(result.confidenceScore + "%", scoreColor)));
            
        src.sendMessage(Component.text("  Usage Type: ", NamedTextColor.YELLOW)
            .append(Component.text(result.ipData != null && result.ipData.usageType != null && !result.ipData.usageType.isEmpty() ? result.ipData.usageType : "Unknown", NamedTextColor.WHITE)));

        if (result.ipData != null) {
            src.sendMessage(Component.text("  ISP: ", NamedTextColor.YELLOW).append(Component.text(result.ipData.isp.isEmpty() ? "Unknown" : result.ipData.isp, NamedTextColor.WHITE)));
            src.sendMessage(Component.text("  Domain: ", NamedTextColor.YELLOW).append(Component.text(result.ipData.domain.isEmpty() ? "None" : result.ipData.domain, NamedTextColor.WHITE)));
            src.sendMessage(Component.text("  Country: ", NamedTextColor.YELLOW).append(Component.text((result.ipData.countryName.isEmpty() ? "Unknown" : result.ipData.countryName) + (result.ipData.countryCode.isEmpty() ? "" : " (" + result.ipData.countryCode + ")"), NamedTextColor.WHITE)));
            src.sendMessage(Component.text("  Is Tor: ", NamedTextColor.YELLOW).append(Component.text(result.ipData.isTor ? "Yes" : "No", result.ipData.isTor ? NamedTextColor.RED : NamedTextColor.GREEN)));
            src.sendMessage(Component.text("  Total Reports: ", NamedTextColor.YELLOW).append(Component.text(result.ipData.totalReports, NamedTextColor.WHITE)));
            if (!result.ipData.lastReportedAt.isEmpty()) {
                src.sendMessage(Component.text("  Last Reported: ", NamedTextColor.YELLOW).append(Component.text(result.ipData.lastReportedAt, NamedTextColor.WHITE)));
            }
        }

        src.sendMessage(Component.empty());
        src.sendMessage(Component.text("» LOCAL OVERRIDES", NamedTextColor.AQUA).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
        
        src.sendMessage(Component.text("  Whitelisted: ", NamedTextColor.YELLOW)
            .append(Component.text(result.whitelisted ? "Yes" : "No", result.whitelisted ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
            
        src.sendMessage(Component.text("  Blacklisted: ", NamedTextColor.YELLOW)
            .append(Component.text(result.blacklisted ? "Yes" : "No", result.blacklisted ? NamedTextColor.RED : NamedTextColor.GRAY)));
            
        String actionStr = "ALLOWED";
        NamedTextColor actionColor = NamedTextColor.GREEN;
        if (result.action == AntiBotService.DefenseAction.BLOCK) {
            actionStr = "BLOCKED";
            actionColor = NamedTextColor.RED;
        } else if (result.action == AntiBotService.DefenseAction.SCREEN) {
            actionStr = "SCREENED";
            actionColor = NamedTextColor.YELLOW;
        }
            
        src.sendMessage(Component.text("  Action Taken: ", NamedTextColor.YELLOW)
            .append(Component.text(actionStr, actionColor)));
            
        if (playerStatsService != null) {
            List<PlayerStatsService.PlayerData> playersWithSameIp = playerStatsService.getPlayersWithSameIp(ip);
            if (!playersWithSameIp.isEmpty()) {
                src.sendMessage(Component.empty());
                src.sendMessage(Component.text("» KNOWN ACCOUNTS ON IP", NamedTextColor.DARK_AQUA).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
                for (PlayerStatsService.PlayerData pd : playersWithSameIp) {
                    boolean isOnline = server.getPlayer(pd.getPlayerId()).isPresent();
                    NamedTextColor nameColor = isOnline ? NamedTextColor.GREEN : NamedTextColor.WHITE;
                    
                    Component playerComp = Component.text(pd.getPlayerName(), nameColor);
                    if (isOnline) {
                        playerComp = playerComp.decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
                    }
                    
                    Component entry = Component.text("  • ", NamedTextColor.GRAY)
                        .append(playerComp
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/info " + pd.getPlayerName()))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click to view player info", NamedTextColor.YELLOW)))
                        );
                        
                    if (isOnline) {
                        entry = entry.append(Component.text(" (Online)", NamedTextColor.GREEN));
                    } else {
                        java.util.Date lastSeen = new java.util.Date(pd.getLastSeen());
                        String lastSeenStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(lastSeen);
                        entry = entry.append(Component.text(" (Last seen: " + lastSeenStr + ")", NamedTextColor.GRAY));
                    }
                    src.sendMessage(entry);
                }
            }
        }
            
        sendDivider(src, NamedTextColor.GOLD);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> suggestions = new ArrayList<>();
            server.getAllPlayers().forEach(p -> suggestions.add(p.getUsername()));
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                suggestions.addAll(plugin.getCrossProxyService().getOnlinePlayerNames());
            }
            return suggestions.stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            if ("refresh".startsWith(args[1].toLowerCase())) {
                return List.of("refresh");
            }
        }
        return List.of();
    }
}
