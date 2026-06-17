package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;

public class StatsCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;

    public StatsCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();

        if (plugin.getPlayerStatsService() == null) {
            src.sendMessage(plugin.getPrefix().append(Component.text("PlayerStatsService is disabled.", NamedTextColor.RED)));
            return;
        }

        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            PlayerStatsService.DailyStats daily = plugin.getPlayerStatsService().getDailyStats();
            PlayerStatsService.MonthlyStats monthly = plugin.getPlayerStatsService().getMonthlyStats();

            sendDivider(src, NamedTextColor.GOLD);
            src.sendMessage(Component.text("✦ ", NamedTextColor.GOLD)
                    .append(Component.text("NETWORK STATS", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                    .append(Component.text(" ✦", NamedTextColor.GOLD)));
            src.sendMessage(Component.empty());

            src.sendMessage(Component.text("» Today's Joins", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD));
            src.sendMessage(Component.text("  Total: ", NamedTextColor.GRAY).append(Component.text(daily.totalJoins, NamedTextColor.AQUA)));
            src.sendMessage(Component.text("  Unique: ", NamedTextColor.GRAY).append(Component.text(daily.uniqueJoins, NamedTextColor.AQUA)));
            src.sendMessage(Component.text("  Screened: ", NamedTextColor.GRAY).append(Component.text(daily.screenedPlayers, NamedTextColor.AQUA)));
            src.sendMessage(Component.empty());

            src.sendMessage(Component.text("» Monthly Playtime", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD));
            src.sendMessage(Component.text("  Cumulative: ", NamedTextColor.GRAY).append(Component.text(PlayerStatsService.formatPlaytime(monthly.cumulativePlaytime), NamedTextColor.AQUA)));
            src.sendMessage(Component.text("  Top Player: ", NamedTextColor.GRAY).append(Component.text(monthly.topPlayerName, NamedTextColor.AQUA))
                    .append(Component.text(" (", NamedTextColor.GRAY))
                    .append(Component.text(PlayerStatsService.formatPlaytime(monthly.topPlayerPlaytime), NamedTextColor.AQUA))
                    .append(Component.text(")", NamedTextColor.GRAY)));

            sendDivider(src, NamedTextColor.GOLD);
        }).schedule();
    }

    private void sendDivider(CommandSource src, NamedTextColor color) {
        src.sendMessage(Component.text("                                        ", color).decorate(TextDecoration.STRIKETHROUGH));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.admin.stats");
    }
}
