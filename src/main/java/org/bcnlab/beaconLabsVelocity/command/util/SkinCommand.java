package org.bcnlab.beaconLabsVelocity.command.util;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

public class SkinCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;

    public SkinCommand(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
    }    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        if (args.length != 1) {
            source.sendMessage(plugin.getPrefix().append(Component.text("Usage: /skin <player>", NamedTextColor.RED)));
            return;
        }

        String targetName = args[0];

        // Check permission if trying to view someone else's skin
        if (source instanceof Player && !((Player) source).getUsername().equalsIgnoreCase(targetName) && !source.hasPermission("beaconlabs.command.skin.other")) {
            source.sendMessage(plugin.getPrefix().append(Component.text("You don't have permission to view other players' skins.", NamedTextColor.RED)));
            return;
        }
        
        sendSkinLink(source, targetName);
    }
    
    private void sendSkinLink(CommandSource source, String username) {
        String url = "https://namemc.com/profile/" + username;
        
        Component message = Component.text()
                .append(plugin.getPrefix())
                .append(Component.text("Click here to view ", NamedTextColor.GREEN))
                .append(Component.text(username, NamedTextColor.GOLD))
                .append(Component.text("'s skin on NameMC", NamedTextColor.GREEN))
                .clickEvent(ClickEvent.openUrl(url))
                .build();
        
        source.sendMessage(message);
    }
}
