package org.bcnlab.beaconLabsVelocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.service.LegalService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * /legal - show legal (TOS/Privacy) interface.
 * /legal accept - accept terms and record in database.
 * /legal reset - show confirmation to reset everyone's acceptance.
 * /legal reset confirm - reset legal acceptance for everyone (requires permission).
 * Only registered when legal feature is enabled in config.
 */
public class LegalCommand implements SimpleCommand {

    private static final String PERMISSION_RESET = "beaconlabs.command.legal.reset";

    private final BeaconLabsVelocity plugin;
    private final LegalService legalService;

    public LegalCommand(BeaconLabsVelocity plugin, LegalService legalService) {
        this.plugin = plugin;
        this.legalService = legalService;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!legalService.isEnabled()) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("Legal feature is disabled.", NamedTextColor.RED)));
            return;
        }

        String[] args = invocation.arguments();

        // /legal reset [confirm] - console or player with permission
        if (args.length >= 1 && "reset".equalsIgnoreCase(args[0])) {
            if (!invocation.source().hasPermission(PERMISSION_RESET)) {
                invocation.source().sendMessage(plugin.getPrefix().append(
                        Component.text("You do not have permission to reset legal acceptance.", NamedTextColor.RED)));
                return;
            }
            if (args.length >= 2 && "confirm".equalsIgnoreCase(args[1])) {
                invocation.source().sendMessage(plugin.getPrefix().append(
                        Component.text("Resetting legal acceptance for everyone...", NamedTextColor.YELLOW)));
                legalService.resetAllAcceptancesAsync().thenAccept(count -> {
                    plugin.getServer().getScheduler().buildTask(plugin, () -> {
                        if (count >= 0) {
                            invocation.source().sendMessage(plugin.getPrefix().append(
                                    Component.text("Legal acceptance has been reset for " + count + " player(s). Everyone must accept again.", NamedTextColor.GREEN)));
                        } else {
                            invocation.source().sendMessage(plugin.getPrefix().append(
                                    Component.text("Failed to reset legal acceptance. Check the console.", NamedTextColor.RED)));
                        }
                    }).schedule();
                });
            } else {
                invocation.source().sendMessage(plugin.getPrefix().append(
                        Component.text("This will clear legal acceptance for everyone. Run ", NamedTextColor.YELLOW))
                        .append(Component.text("/legal reset confirm", NamedTextColor.GOLD))
                        .append(Component.text(" to confirm.", NamedTextColor.YELLOW)));
            }
            return;
        }

        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(plugin.getPrefix().append(
                    Component.text("Only players can use this command.", NamedTextColor.RED)));
            return;
        }

        if (args.length > 0 && "accept".equalsIgnoreCase(args[0])) {
            legalService.removeViewingLegalBook(player.getUniqueId());
            legalService.cancelPendingKick(player.getUniqueId());
            legalService.clearLegalBookFromHotbar(player);
            legalService.setAcceptedAsync(player.getUniqueId()).thenAccept(ok -> {
                plugin.getServer().getScheduler().buildTask(plugin, () -> {
                    if (ok) {
                        player.sendMessage(plugin.getPrefix().append(
                                Component.text("You have accepted the Terms of Service and Privacy Policy. Thank you!", NamedTextColor.GREEN)));
                    } else {
                        player.sendMessage(plugin.getPrefix().append(
                                Component.text("Could not save acceptance. Please try again or contact an administrator.", NamedTextColor.RED)));
                    }
                }).schedule();
            });
            return;
        }

        if (args.length > 0 && "deny".equalsIgnoreCase(args[0])) {
            String denyMsg = legalService.getDenyMessage();
            if (denyMsg == null || denyMsg.isEmpty()) {
                denyMsg = legalService.getKickMessage();
            }
            player.disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(denyMsg));
            return;
        }

        legalService.showLegalTo(player);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            String a = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            if ("accept".startsWith(a)) out.add("accept");
            if ("deny".startsWith(a)) out.add("deny");
            if ("reset".startsWith(a)) out.add("reset");
            return out;
        }
        if (args.length == 2 && "reset".equalsIgnoreCase(args[0]) && "confirm".startsWith(args[1].toLowerCase())) {
            return List.of("confirm");
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
