package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.ScreeningService;

import java.util.Optional;

public class ScreeningBypassCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;
    private final ScreeningService screeningService;

    public ScreeningBypassCommand(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
        this.screeningService = plugin.getScreeningService();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!source.hasPermission("beaconlabs.admin.bypassscreening")) {
            source.sendMessage(plugin.getPrefix().append(Component.text("You don't have permission.", NamedTextColor.RED)));
            return;
        }

        if (screeningService == null) {
            source.sendMessage(plugin.getPrefix().append(Component.text("Screening service is not enabled.", NamedTextColor.RED)));
            return;
        }

        Player target = null;
        if (args.length == 0) {
            if (source instanceof Player) {
                target = (Player) source;
            } else {
                source.sendMessage(plugin.getPrefix().append(Component.text("Usage: /scb <player>", NamedTextColor.RED)));
                return;
            }
        } else {
            Optional<Player> p = server.getPlayer(args[0]);
            if (p.isEmpty()) {
                source.sendMessage(plugin.getPrefix().append(Component.text("Player not found.", NamedTextColor.RED)));
                return;
            }
            target = p.get();
        }

        screeningService.bypassScreening(target);
        
        if (source != target) {
            source.sendMessage(plugin.getPrefix().append(Component.text("Bypassed screening for " + target.getUsername() + ".", NamedTextColor.GREEN)));
        }
    }
}
