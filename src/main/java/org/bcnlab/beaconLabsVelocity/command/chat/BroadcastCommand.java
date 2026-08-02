package org.bcnlab.beaconLabsVelocity.command.chat;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.util.ColorParser;

public class BroadcastCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;

    public BroadcastCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.arguments().length == 0) {
            invocation.source().sendMessage(Component.text("Usage: /broadcast <message>", NamedTextColor.RED));
            return;
        }
        String msg = String.join(" ", invocation.arguments());
        String customPrefixStr = "&4Broadcast &8» &f";
        String fullMsgLegacy = customPrefixStr + msg;
        Component fullMsg = ColorParser.parse(fullMsgLegacy);

        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            plugin.getCrossProxyService().publishBroadcast(fullMsgLegacy);
        } else {
            plugin.getServer().getAllPlayers().forEach(player -> player.sendMessage(fullMsg));
        }
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(fullMsg);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.broadcast");
    }
}
