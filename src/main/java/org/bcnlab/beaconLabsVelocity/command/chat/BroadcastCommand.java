package org.bcnlab.beaconLabsVelocity.command.chat;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

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

        String customPrefixStr = "&4Broadcast &8» ";
        Component customPrefix = LegacyComponentSerializer.legacyAmpersand().deserialize(customPrefixStr);
        Component fullMsg = customPrefix.append(Component.text(msg, NamedTextColor.LIGHT_PURPLE));

        plugin.getServer().getAllPlayers().forEach(player -> player.sendMessage(fullMsg));

        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(fullMsg);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.broadcast");
    }
}
