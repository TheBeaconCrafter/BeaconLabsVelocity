package org.bcnlab.beaconLabsVelocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;

public class LabsVelocityCommand implements SimpleCommand {

    private final BeaconLabsVelocity plugin;

    public LabsVelocityCommand(BeaconLabsVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        source.sendMessage(
                plugin.getPrefix()
                        .append(Component.text("BeaconLabsVelocity Version ", NamedTextColor.RED))
                        .append(Component.text(plugin.getVersion() + " ", NamedTextColor.GOLD))
                        .append(Component.text("by ItsBeacon", NamedTextColor.RED))
        );
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
