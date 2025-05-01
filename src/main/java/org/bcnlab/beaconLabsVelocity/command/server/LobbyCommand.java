package org.bcnlab.beaconLabsVelocity.command.server;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class LobbyCommand implements SimpleCommand {
    private final BeaconLabsVelocity plugin;
    private final ProxyServer server;

    public LobbyCommand(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("This command can only be executed by players.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) invocation.source();
        String lobbyServerName = plugin.getConfig().node("lobby-server").getString("lobby");
        
        Optional<RegisteredServer> targetServer = server.getServer(lobbyServerName);
        
        if (targetServer.isEmpty()) {
            player.sendMessage(plugin.getPrefix().append(
                    Component.text("The lobby server is not available.", NamedTextColor.RED)));
            return;
        }

        player.sendMessage(plugin.getPrefix().append(
                Component.text("Connecting to the lobby server...", NamedTextColor.GREEN)));
                
        player.createConnectionRequest(targetServer.get()).connectWithIndication().thenAccept(result -> {
            if (!result) {
                player.sendMessage(plugin.getPrefix().append(
                        Component.text("Failed to connect to the lobby server.", NamedTextColor.RED)));
            }
        });
    }
}
