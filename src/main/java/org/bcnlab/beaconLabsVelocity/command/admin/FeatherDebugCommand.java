package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Console-only command to toggle Feather plugin-message debug logging.
 * When on, all plugin messages on the Feather channel are logged (channel, source, payload hex, messageId, etc.).
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
            ConfigurationNode feather = plugin.getConfig() != null ? plugin.getConfig().node("feather") : null;
            String channel = feather != null ? feather.node("channel").getString("feather:server_api") : "feather:server_api";
            int reqId = feather != null ? feather.node("message-id-request").getInt(6) : 6;
            int respId = feather != null ? feather.node("message-id-response").getInt(10) : 10;
            source.sendMessage(Component.text("[Feather debug] Logging ON. Channel: " + channel
                    + " | requestId=" + reqId + " responseId=" + respId
                    + ". Connect with a Feather client to see incoming/outgoing messages.", NamedTextColor.GREEN));
            plugin.getLogger().info("[Feather debug] Logging enabled by console. Channel={} requestId={} responseId={}", channel, reqId, respId);
        } else {
            source.sendMessage(Component.text("[Feather debug] Logging OFF.", NamedTextColor.YELLOW));
            plugin.getLogger().info("[Feather debug] Logging disabled by console.");
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
