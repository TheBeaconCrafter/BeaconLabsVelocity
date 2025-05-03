package org.bcnlab.beaconLabsVelocity.command.punishment;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.command.admin.InfoCommand;
import org.bcnlab.beaconLabsVelocity.config.PunishmentConfig;
import org.bcnlab.beaconLabsVelocity.service.PunishmentService;
import org.slf4j.Logger;

public class PunishmentCommandRegistrar {
    private final CommandManager commandManager;
    private final ProxyServer server;
    private final BeaconLabsVelocity plugin;
    private final PunishmentConfig config;
    private final PunishmentService service;
    private final Logger logger;

    public PunishmentCommandRegistrar(CommandManager commandManager, PunishmentConfig config,
                                       PunishmentService service, BeaconLabsVelocity plugin,
                                       ProxyServer server, Logger logger) {
        this.commandManager = commandManager;
        this.server = server;
        this.plugin = plugin;
        this.config = config;
        this.service = service;
        this.logger = logger;
    }

    public void registerAll() {
        // Mute
        commandManager.register("mute", new MuteCommand(plugin, server, config, service));
        // Ban
        commandManager.register("ban", new BanCommand(plugin, server, config, service));
        // Kick
        commandManager.register("kick", new KickCommand(plugin, server, config, service));
        // Warn
        commandManager.register("warn", new WarnCommand(plugin, server, config, service));
        // Punishments history
        commandManager.register("punishments", new PunishmentsCommand(plugin, server, service, config));
        commandManager.register("banlog", new PunishmentsCommand(plugin, server, service, config));
        // Unban
        commandManager.register("unban", new UnbanCommand(plugin, server, service, config, logger));
        // Unmute
        commandManager.register("unmute", new UnmuteCommand(plugin, server, service, config, logger));
        // Clear punishments
        commandManager.register("cpunish", new ClearPunishmentCommand(plugin, server, config, service));
    }
}
