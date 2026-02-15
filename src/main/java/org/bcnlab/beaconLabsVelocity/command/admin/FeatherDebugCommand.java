package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

/**
 * Console-only command to toggle Feather debug logging.
 * When on, logs when a player with Feather joins and all info from the Feather API (PlayerHelloEvent):
 * player name, platform (Forge/Fabric), enabled mods, etc.
 * Permission: beaconlabs.command.feather.debug
 */
public class FeatherDebugCommand implements SimpleCommand {

    private static final String PERMISSION = "beaconlabs.command.feather.debug";

    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;

    public FeatherDebugCommand(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();
        if (source != server.getConsoleCommandSource()) {
            source.sendMessage(Component.text("This command can only be run from the console.", NamedTextColor.RED));
            return;
        }
        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }

        String[] args = invocation.arguments();
        boolean turnOn;
        if (args.length > 0) {
            String arg = args[0].trim().toLowerCase();
            if ("on".equals(arg) || "true".equals(arg) || "1".equals(arg)) {
                turnOn = true;
            } else if ("off".equals(arg) || "false".equals(arg) || "0".equals(arg)) {
                turnOn = false;
            } else {
                source.sendMessage(Component.text("Usage: featherdebug [on|off]  (no args = toggle)", NamedTextColor.GRAY));
                return;
            }
        } else {
            turnOn = !plugin.isFeatherDebug();
        }

        plugin.setFeatherDebug(turnOn);
        if (turnOn) {
            source.sendMessage(Component.text("[Feather debug] ON. When a Feather player joins, player name, platform, and enabled mods will be logged to console.", NamedTextColor.GREEN));
            plugin.getLogger().info("[Feather debug] Logging enabled. Feather PlayerHelloEvent details will be logged on join.");
        } else {
            source.sendMessage(Component.text("[Feather debug] OFF.", NamedTextColor.YELLOW));
            plugin.getLogger().info("[Feather debug] Logging disabled.");
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
