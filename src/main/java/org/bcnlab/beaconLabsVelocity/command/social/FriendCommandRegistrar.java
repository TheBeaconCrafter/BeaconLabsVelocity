package org.bcnlab.beaconLabsVelocity.command.social;

import com.velocitypowered.api.command.CommandManager;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

public class FriendCommandRegistrar {
    public static void registerAll(BeaconLabsVelocity plugin, CommandManager commandManager) {
        commandManager.register(
            commandManager.metaBuilder("friend").aliases("f", "friends").build(),
            new FriendCommand(plugin)
        );
    }
}
