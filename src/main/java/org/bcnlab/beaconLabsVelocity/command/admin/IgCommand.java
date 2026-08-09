package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService.PunishmentRecord;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.Date;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import org.bcnlab.beaconLabsVelocity.service.PlayerStatsService;
import java.util.UUID;

public class IgCommand implements SimpleCommand {
    private final ProxyServer server;
    private final PunishmentService service;
    private final BeaconLabsVelocity plugin;
    private final PunishmentConfig config;
    private final InfoCommand fallbackInfoCommand;

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("beaconlabs:info_dialog");

    public IgCommand(ProxyServer server, PunishmentService service, BeaconLabsVelocity plugin, PunishmentConfig config) {
        this.server = server;
        this.service = service;
        this.plugin = plugin;
        this.config = config;
        this.fallbackInfoCommand = new InfoCommand(server, service, plugin, config);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (!(src instanceof Player player)) {
            src.sendMessage(Component.text("Only players can use the GUI command. Falling back to /info.", NamedTextColor.RED));
            fallbackInfoCommand.execute(invocation);
            return;
        }

        if (!src.hasPermission("beaconlabs.punish.info")) {
            src.sendMessage(plugin.getPrefix(src).append(
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(config.getMessage("no-permission"))));
            return;
        }

        if (args.length < 1) {
            src.sendMessage(plugin.getPrefix(src).append(
                Component.text("Usage: /ig <player>", NamedTextColor.RED)));
            return;
        }

        Optional<ServerConnection> serverOptional = player.getCurrentServer();
        if (serverOptional.isPresent()) {
            if (!plugin.getDependencyTracker().isSupported(serverOptional.get())) {
                player.sendMessage(plugin.getPrefix(player).append(Component.text("GUI commands are not supported on your current server. Falling back to /info.", NamedTextColor.RED)));
                fallbackInfoCommand.execute(invocation);
                return;
            }
        } else {
            return;
        }

        String targetName = args[0];
        Optional<Player> optionalTarget = server.getPlayer(targetName);
        
        UUID targetUuid = null;
        String realName = targetName;
        boolean isNickname = false;

        if (optionalTarget.isPresent()) {
            targetUuid = optionalTarget.get().getUniqueId();
            realName = optionalTarget.get().getUsername();
        } else {
            if (plugin.getVisualStateListener() != null) {
                targetUuid = plugin.getVisualStateListener().getUuidByNickname(targetName);
                if (targetUuid != null) {
                    optionalTarget = server.getPlayer(targetUuid);
                    isNickname = true;
                    if (optionalTarget.isPresent()) {
                        realName = optionalTarget.get().getUsername();
                    }
                }
            }
        }

        if (targetUuid == null) {
            // Attempt to resolve from database for offline player
            org.bcnlab.beaconLabsVelocity.service.PlayerStatsService.PlayerData stats = plugin.getPlayerStatsService().getPlayerDataByName(targetName);
            if (stats != null) {
                targetUuid = stats.getPlayerId();
                realName = stats.getPlayerName();
            } else {
                src.sendMessage(plugin.getPrefix(src).append(Component.text("Player not found online or in database.", NamedTextColor.RED)));
                return;
            }
        }

        
        
        // Gather info to send to backend
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);

            data.writeUTF(targetUuid.toString());
            data.writeUTF(realName);
            data.writeBoolean(isNickname);
            data.writeUTF(isNickname ? targetName : ""); // nickname
            
            // Profile
            long playtimeMs = plugin.getPlayerStatsService().getPlayerPlaytime(targetUuid);
            data.writeLong(playtimeMs);
            
            long lastSeenMs = plugin.getPlayerStatsService().getLastSeenTime(targetUuid);
            data.writeLong(lastSeenMs);
            
            // Connection
            boolean online = optionalTarget.isPresent();
            data.writeBoolean(online);
            if (online) {
                if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
                    data.writeUTF(plugin.getCrossProxyService().getProxyId());
                } else {
                    data.writeUTF("Unknown");
                }
                data.writeUTF(optionalTarget.get().getCurrentServer().map(sc -> sc.getServerInfo().getName()).orElse("Unknown"));
                data.writeLong(optionalTarget.get().getPing());
                data.writeUTF(optionalTarget.get().getClientBrand() != null ? optionalTarget.get().getClientBrand() : "Unknown");
            } else {
                String proxyId = (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled())
                        ? plugin.getCrossProxyService().getPlayerProxy(targetUuid) : null;
                data.writeUTF(proxyId != null ? proxyId : "");
            }
            
            long activeBans = 0;
            long activeMutes = 0;
            for (PunishmentRecord r : service.getHistory(targetUuid)) {
                if (r.active) {
                    if (r.type.equalsIgnoreCase("BAN")) activeBans++;
                    if (r.type.equalsIgnoreCase("MUTE")) activeMutes++;
                }
            }
            data.writeLong(activeBans);
            data.writeLong(activeMutes);
            
            // Permissions for submenus
            data.writeBoolean(src.hasPermission("beaconlabs.punish.ipinfo"));
            data.writeBoolean(src.hasPermission("beaconlabs.punish.history"));
            data.writeBoolean(src.hasPermission("beaconlabs.punish.goto"));
            
            Optional<ServerConnection> connection = player.getCurrentServer();
            if (connection.isPresent()) {
                boolean sent = connection.get().sendPluginMessage(CHANNEL, out.toByteArray());
                if (!sent) {
                    // Fallback
                    fallbackInfoCommand.execute(invocation);
                }
            } else {
                fallbackInfoCommand.execute(invocation);
            }


        } catch (Exception e) {
            plugin.getLogger().warn("Failed to encode info_dialog payload: " + e.getMessage());
            fallbackInfoCommand.execute(invocation);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return fallbackInfoCommand.suggest(invocation);
    }
}
