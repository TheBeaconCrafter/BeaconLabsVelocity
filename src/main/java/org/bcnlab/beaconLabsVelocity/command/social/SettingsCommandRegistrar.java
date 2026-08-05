package org.bcnlab.beaconLabsVelocity.command.social;

import com.velocitypowered.api.command.CommandManager;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

public class SettingsCommandRegistrar {
    public static void registerAll(BeaconLabsVelocity plugin, CommandManager commandManager) {
        commandManager.register(
            commandManager.metaBuilder("settings").aliases("options", "prefs").build(),
            new SettingsCommand(plugin)
        );
    }
}
