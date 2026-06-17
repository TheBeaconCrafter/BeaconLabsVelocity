package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.AbuseConfig;
import org.bcnlab.beaconLabsVelocity.service.AntiBotService;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class AntiAbuseCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;
    private final AntiBotService antiBotService;
    private final ProxyServer server;
    private final PlayerStatsService playerStatsService;
    private final PunishmentService punishmentService;
    private final AbuseConfig abuseConfig;

    public AntiAbuseCommand(BeaconLabsVelocity plugin, AntiBotService antiBotService, AbuseConfig abuseConfig, ProxyServer server) {
        this.plugin = plugin;
        this.antiBotService = antiBotService;
        this.abuseConfig = abuseConfig;
        this.server = server;
        this.playerStatsService = plugin.getPlayerStatsService();
        this.punishmentService = plugin.getPunishmentService();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!src.hasPermission("beaconlabs.admin.antibot")) {
            src.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
            return;
        }

        if (args.length == 0) {
            sendHelp(src);
            return;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "whitelist":
            case "blacklist":
                handleList(src, args, sub.equals("whitelist"));
                break;
            case "requests":
                int req = antiBotService.getRequestsToday();
                sendDivider(src, NamedTextColor.GOLD);
                src.sendMessage(Component.text("✦ ", NamedTextColor.GOLD)
                    .append(Component.text("ABUSEIPDB QUOTA", NamedTextColor.YELLOW).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    .append(Component.text(" ✦", NamedTextColor.GOLD)));
                src.sendMessage(Component.empty());
                src.sendMessage(Component.text("  Requests Today: ", NamedTextColor.YELLOW).append(Component.text(req, NamedTextColor.AQUA)));
                sendDivider(src, NamedTextColor.GOLD);
                break;
            case "status":
                handleStatus(src);
                break;
            case "mode":
                handleMode(src, args);
                break;
            default:
                sendHelp(src);
                break;
        }
    }

    private void sendDivider(CommandSource src, NamedTextColor color) {
        src.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", color));
    }

    private void handleMode(CommandSource src, String[] args) {
        if (args.length < 2) {
            sendDivider(src, NamedTextColor.GOLD);
            src.sendMessage(Component.text("✦ ", NamedTextColor.GOLD)
                .append(Component.text("ABUSE DEFENSE MODE", NamedTextColor.YELLOW).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                .append(Component.text(" ✦", NamedTextColor.GOLD)));
            
            src.sendMessage(Component.empty());
            src.sendMessage(Component.text("» CURRENT: ", NamedTextColor.AQUA).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .append(Component.text(abuseConfig.getDefenseMode().toUpperCase(), NamedTextColor.GREEN)));
            src.sendMessage(Component.text("  Usage: ", NamedTextColor.YELLOW)
                .append(Component.text("/aa mode <normal|elevated|attack>", NamedTextColor.WHITE)));
            sendDivider(src, NamedTextColor.GOLD);
            return;
        }

        String mode = args[1].toLowerCase();
        if (mode.equals("normal") || mode.equals("elevated") || mode.equals("attack")) {
            abuseConfig.setDefenseMode(mode);
            String issuerName = (src instanceof Player) ? ((Player) src).getUsername() : "Console";
            Component comp = plugin.getPrefix().append(Component.text("Abuse Defense Mode updated to: ", NamedTextColor.AQUA).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .append(Component.text(mode.toUpperCase(), NamedTextColor.GREEN))
                .append(Component.text(" by " + issuerName, NamedTextColor.GRAY)));
            
            plugin.getLogger().info(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(comp));
            plugin.getServer().getAllPlayers().stream()
                .filter(p -> p.hasPermission("beaconlabs.antiabuse"))
                .forEach(p -> p.sendMessage(comp));
                
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                plugin.getCrossProxyService().publishDefenseModeUpdate(mode, issuerName);
            }
        } else {
            src.sendMessage(plugin.getPrefix().append(Component.text("Invalid mode. Use: normal, elevated, or attack.", NamedTextColor.RED)));
        }
    }

    private void sendHelp(CommandSource src) {
        sendDivider(src, NamedTextColor.GOLD);
        src.sendMessage(Component.text("✦ ", NamedTextColor.GOLD)
            .append(Component.text("ANTI-ABUSE COMMANDS", NamedTextColor.YELLOW).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            .append(Component.text(" ✦", NamedTextColor.GOLD)));
        
        src.sendMessage(Component.empty());
        src.sendMessage(Component.text("  • ", NamedTextColor.GRAY).append(Component.text("/aa whitelist <ip/player> [true/false]", NamedTextColor.AQUA)));
        src.sendMessage(Component.text("  • ", NamedTextColor.GRAY).append(Component.text("/aa blacklist <ip/player> [true/false]", NamedTextColor.AQUA)));
        src.sendMessage(Component.text("  • ", NamedTextColor.GRAY).append(Component.text("/aa requests", NamedTextColor.AQUA).append(Component.text(" - View AbuseIPDB daily requests", NamedTextColor.GRAY))));
        src.sendMessage(Component.text("  • ", NamedTextColor.GRAY).append(Component.text("/aa status", NamedTextColor.AQUA).append(Component.text(" - View AntiBot database stats", NamedTextColor.GRAY))));
        src.sendMessage(Component.text("  • ", NamedTextColor.GRAY).append(Component.text("/aa mode <normal|elevated|attack>", NamedTextColor.AQUA).append(Component.text(" - Change defense level", NamedTextColor.GRAY))));
        sendDivider(src, NamedTextColor.GOLD);
    }

    private void handleList(CommandSource src, String[] args, boolean isWhitelist) {
        if (args.length == 1 || (args.length == 2 && args[1].matches("\\d+"))) {
            int page = args.length == 2 ? Integer.parseInt(args[1]) : 1;
            showPaginatedList(src, isWhitelist, page);
            return;
        }
        
        String target = args[1];
        boolean state = true;
        if (args.length >= 3) {
            state = Boolean.parseBoolean(args[2]);
        }

        String targetIp = null;

        // Check if IP
        if (target.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            targetIp = target;
        } else {
            // It's a player
            UUID targetUuid = punishmentService.getPlayerUUID(target);
            if (targetUuid == null && plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                targetUuid = plugin.getCrossProxyService().getPlayerUuidByName(target);
            }
            if (targetUuid == null && playerStatsService != null) {
                PlayerStatsService.PlayerData pd = playerStatsService.getPlayerDataByName(target);
                if (pd != null) targetUuid = pd.getPlayerId();
            }

            if (targetUuid == null) {
                src.sendMessage(Component.text("Player not found: " + target, NamedTextColor.RED));
                return;
            }

            if (playerStatsService != null) {
                List<PlayerStatsService.IpHistoryEntry> history = playerStatsService.getPlayerIpHistory(targetUuid);
                if (!history.isEmpty()) {
                    targetIp = history.get(0).getIpAddress();
                }
            }
        }

        if (targetIp == null) {
            src.sendMessage(Component.text("Could not determine IP for target: " + target, NamedTextColor.RED));
            return;
        }

        if (isWhitelist) {
            antiBotService.setWhitelist(targetIp, state);
            src.sendMessage(plugin.getPrefix().append(Component.text("Whitelist for ", NamedTextColor.AQUA).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .append(Component.text(targetIp, NamedTextColor.YELLOW))
                .append(Component.text(" set to ", NamedTextColor.AQUA))
                .append(Component.text(state ? "TRUE" : "FALSE", state ? NamedTextColor.GREEN : NamedTextColor.RED))));
        } else {
            antiBotService.setBlacklist(targetIp, state);
            src.sendMessage(plugin.getPrefix().append(Component.text("Blacklist for ", NamedTextColor.AQUA).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                .append(Component.text(targetIp, NamedTextColor.YELLOW))
                .append(Component.text(" set to ", NamedTextColor.AQUA))
                .append(Component.text(state ? "TRUE" : "FALSE", state ? NamedTextColor.RED : NamedTextColor.GREEN))));
        }
    }

    private void showPaginatedList(CommandSource src, boolean isWhitelist, int page) {
        if (plugin.getDatabaseManager() == null || !plugin.getDatabaseManager().isConnected()) {
            src.sendMessage(Component.text("Database not connected.", NamedTextColor.RED));
            return;
        }
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement countStmt = conn.prepareStatement("SELECT COUNT(*) FROM antibot_ip_cache WHERE " + (isWhitelist ? "is_whitelisted" : "is_blacklisted") + " = true");
                 PreparedStatement stmt = conn.prepareStatement("SELECT ip_address FROM antibot_ip_cache WHERE " + (isWhitelist ? "is_whitelisted" : "is_blacklisted") + " = true LIMIT ? OFFSET ?")) {
                
                int totalEntries = 0;
                try (ResultSet rs = countStmt.executeQuery()) {
                    if (rs.next()) totalEntries = rs.getInt(1);
                }
                
                int perPage = 10;
                int maxPage = Math.max(1, (int) Math.ceil((double) totalEntries / perPage));
                int actualPage = Math.max(1, Math.min(page, maxPage));
                
                stmt.setInt(1, perPage);
                stmt.setInt(2, (actualPage - 1) * perPage);
                
                List<String> ips = new ArrayList<>();
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) ips.add(rs.getString(1));
                }
                
                String title = isWhitelist ? "WHITELISTED IPS" : "BLACKLISTED IPS";
                String cmdType = isWhitelist ? "whitelist" : "blacklist";
                NamedTextColor theme = isWhitelist ? NamedTextColor.GREEN : NamedTextColor.RED;
                
                sendDivider(src, NamedTextColor.GOLD);
                src.sendMessage(Component.text("✦ ", NamedTextColor.GOLD)
                    .append(Component.text(title, theme).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    .append(Component.text(" ✦", NamedTextColor.GOLD)));
                src.sendMessage(Component.empty());
                
                if (ips.isEmpty()) {
                    src.sendMessage(Component.text("  No entries found.", NamedTextColor.GRAY));
                } else {
                    for (String ip : ips) {
                        Component removeBtn = Component.text("[Remove]", NamedTextColor.RED)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/aa " + cmdType + " " + ip + " false"))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click to remove", NamedTextColor.YELLOW)));
                        
                        src.sendMessage(Component.text("  • ", NamedTextColor.GRAY)
                            .append(Component.text(ip, NamedTextColor.WHITE))
                            .append(Component.text(" "))
                            .append(removeBtn));
                    }
                }
                
                src.sendMessage(Component.empty());
                Component footer = Component.text("  Page " + actualPage + " of " + maxPage, NamedTextColor.GRAY);
                if (actualPage > 1) {
                    footer = footer.append(Component.text(" [«]", NamedTextColor.AQUA)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/aa " + cmdType + " " + (actualPage - 1))));
                }
                if (actualPage < maxPage) {
                    footer = footer.append(Component.text(" [»]", NamedTextColor.AQUA)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/aa " + cmdType + " " + (actualPage + 1))));
                }
                src.sendMessage(footer);
                sendDivider(src, NamedTextColor.GOLD);
                
            } catch (Exception e) {
                src.sendMessage(Component.text("⚠ Error fetching list.", NamedTextColor.RED));
                e.printStackTrace();
            }
        }).schedule();
    }

    private void handleStatus(CommandSource src) {
        if (plugin.getDatabaseManager() == null || !plugin.getDatabaseManager().isConnected()) {
            src.sendMessage(Component.text("Database not connected.", NamedTextColor.RED));
            return;
        }
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) AS total, SUM(CASE WHEN is_whitelisted THEN 1 ELSE 0 END) AS whitelisted, SUM(CASE WHEN is_blacklisted THEN 1 ELSE 0 END) AS blacklisted, SUM(CASE WHEN confidence_score >= ? THEN 1 ELSE 0 END) AS high_score FROM antibot_ip_cache")) {
            
            stmt.setInt(1, 90); // default threshold
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int total = rs.getInt("total");
                    int whitelisted = rs.getInt("whitelisted");
                    int blacklisted = rs.getInt("blacklisted");
                    int highScore = rs.getInt("high_score");
                    
                    sendDivider(src, NamedTextColor.GOLD);
                    src.sendMessage(Component.text("✦ ", NamedTextColor.GOLD)
                        .append(Component.text("ANTIBOT CACHE STATUS", NamedTextColor.YELLOW).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                        .append(Component.text(" ✦", NamedTextColor.GOLD)));
                    
                    src.sendMessage(Component.empty());
                    src.sendMessage(Component.text("» STATISTICS", NamedTextColor.AQUA).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
                    src.sendMessage(Component.text("  Total IPs Cached: ", NamedTextColor.YELLOW).append(Component.text(total, NamedTextColor.WHITE)));
                    src.sendMessage(Component.text("  Whitelisted: ", NamedTextColor.YELLOW).append(Component.text(whitelisted, NamedTextColor.GREEN)));
                    src.sendMessage(Component.text("  Blacklisted (Manual): ", NamedTextColor.YELLOW).append(Component.text(blacklisted, NamedTextColor.RED)));
                    src.sendMessage(Component.text("  Blocked (High Score >= 90): ", NamedTextColor.YELLOW).append(Component.text(highScore, NamedTextColor.RED)));
                    sendDivider(src, NamedTextColor.GOLD);
                }
            }
        } catch (Exception e) {
            src.sendMessage(Component.text("⚠ Error checking status.", NamedTextColor.RED));
            e.printStackTrace();
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return List.of("whitelist", "blacklist", "requests", "status", "mode").stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            return List.of("normal", "elevated", "attack").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("whitelist") || args[0].equalsIgnoreCase("blacklist"))) {
            String prefix = args[1].toLowerCase();
            List<String> suggestions = new ArrayList<>();
            server.getAllPlayers().forEach(p -> suggestions.add(p.getUsername()));
            if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                suggestions.addAll(plugin.getCrossProxyService().getOnlinePlayerNames());
            }
            return suggestions.stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("whitelist") || args[0].equalsIgnoreCase("blacklist"))) {
            return List.of("true", "false");
        }
        return List.of();
    }
}
