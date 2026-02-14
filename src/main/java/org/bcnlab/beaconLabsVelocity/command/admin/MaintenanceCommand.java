package org.bcnlab.beaconLabsVelocity.command.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.MaintenanceService;

import java.util.List;

/**
 * Command to toggle server maintenance mode
 * Usage: /maintenance [on|off]
 * Permission: beaconlabs.command.maintenance
 */
public class MaintenanceCommand implements SimpleCommand {
    
    private final BeaconLabsVelocity plugin;
    private final MaintenanceService maintenanceService;
    
    public MaintenanceCommand(BeaconLabsVelocity plugin, MaintenanceService maintenanceService) {
        this.plugin = plugin;
        this.maintenanceService = maintenanceService;
    }
    
    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        
        // Check permission
        if (!src.hasPermission("beaconlabs.command.maintenance")) {
            src.sendMessage(plugin.getPrefix().append(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Check current status if no args
        if (args.length < 1) {
            boolean currentState = maintenanceService.isMaintenanceMode();
            Component statusMessage = Component.text()
                .append(plugin.getPrefix())
                .append(Component.text("Maintenance mode is currently "))
                .append(currentState ? 
                    Component.text("ENABLED", NamedTextColor.RED, TextDecoration.BOLD) : 
                    Component.text("DISABLED", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text("."))
                .append(Component.newline())
                .append(Component.text("Use /maintenance <on|off> to change.", NamedTextColor.GRAY))
                .build();
                
            src.sendMessage(statusMessage);
            return;
        }
        
        // Parse command
        String subCommand = args[0].toLowerCase();
        boolean enable;
        
        if (subCommand.equals("on") || subCommand.equals("enable") || subCommand.equals("true")) {
            enable = true;
        } else if (subCommand.equals("off") || subCommand.equals("disable") || subCommand.equals("false")) {
            enable = false;
        } else {
            src.sendMessage(plugin.getPrefix().append(
                Component.text("Invalid option. Use: /maintenance <on|off>", NamedTextColor.RED)
            ));
            return;
        }
        
        // Success message (build first so we can use for cross-proxy broadcast)
        Component resultMessage = Component.text()
            .append(plugin.getPrefix())
            .append(Component.text("Maintenance mode "))
            .append(enable ?
                Component.text("enabled", NamedTextColor.RED, TextDecoration.BOLD) :
                Component.text("disabled", NamedTextColor.GREEN, TextDecoration.BOLD))
            .build();
        String broadcastLegacy = LegacyComponentSerializer.legacyAmpersand().serialize(resultMessage);

        boolean crossProxy = plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled();

        if (enable && crossProxy) {
            // Publish only: all proxies (including this one) will receive MAINTENANCE_SET and run the countdown together
            plugin.getCrossProxyService().publishMaintenanceSet(true, broadcastLegacy);
            src.sendMessage(resultMessage);
            return;
        }

        // Toggle locally: when enabling (no cross-proxy), countdown runs first; when disabling, immediate
        boolean success = maintenanceService.toggleMaintenance(enable, null);
        if (!success) {
            src.sendMessage(plugin.getPrefix().append(
                Component.text("Maintenance mode toggle is on cooldown. Please wait.", NamedTextColor.RED)
            ));
            return;
        }
        src.sendMessage(resultMessage);
        if (!enable && crossProxy) {
            plugin.getCrossProxyService().publishMaintenanceSet(false, broadcastLegacy);
        }
    }
    
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            if ("on".startsWith(input)) {
                return List.of("on");
            } else if ("off".startsWith(input)) {
                return List.of("off");
            } else if (input.isEmpty()) {
                return List.of("on", "off");
            }
        }
        
        return List.of();
    }
}
