package org.bcnlab.beaconLabsVelocity.command.social;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import com.velocitypowered.api.proxy.Player;

public class NicklistCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;

    public NicklistCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        
        if (source instanceof Player && !((Player) source).hasPermission("beaconlabs.nick.list")) {
            source.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return;
        }

        source.sendMessage(Component.text("--- Nicked Players ---", NamedTextColor.GOLD));
        
        boolean found = false;
        for (Player p : plugin.getServer().getAllPlayers()) {
            String nick = plugin.getVisualStateListener().getNickname(p.getUniqueId());
            if (nick != null) {
                source.sendMessage(Component.text(p.getUsername(), NamedTextColor.GRAY)
                        .append(Component.text(" is nicked as ", NamedTextColor.GOLD))
                        .append(Component.text(nick, NamedTextColor.GREEN)));
                found = true;
            }
        }
        
        if (!found) {
            source.sendMessage(Component.text("No players are currently nicked.", NamedTextColor.GRAY));
        }
    }
}
