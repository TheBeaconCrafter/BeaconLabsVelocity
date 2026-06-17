package org.bcnlab.beaconLabsVelocity.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.config.AbuseConfig;
import org.bcnlab.beaconLabsVelocity.util.CaptchaGenerator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ScreeningService {

    private final BeaconLabsVelocity plugin;
    private final AbuseConfig config;
    private final ProxyServer server;
    
    private final Map<UUID, ScreeningSession> sessions = new ConcurrentHashMap<>();

    private static class ScreeningSession {
        RegisteredServer originalServer;
        final String captchaText;
        ScheduledTask timeoutTask;
        boolean passed;

        ScreeningSession(RegisteredServer originalServer, String captchaText) {
            this.originalServer = originalServer;
            this.captchaText = captchaText;
        }
    }

    public ScreeningService(BeaconLabsVelocity plugin, AbuseConfig config, ProxyServer server) {
        this.plugin = plugin;
        this.config = config;
        this.server = server;
    }

    private CaptchaGenerator.CaptchaResult tryGenerateCaptcha() {
        try {
            return CaptchaGenerator.generate();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Fontconfig")) {
                plugin.getLogger().error("=====================================================");
                plugin.getLogger().error("CRITICAL ERROR: Fontconfig is missing on your server!");
                plugin.getLogger().error("Please install 'fontconfig' and basic fonts to use Screening.");
                plugin.getLogger().error("Ubuntu/Debian: apt-get install fontconfig fonts-dejavu");
                plugin.getLogger().error("Alpine (Docker): apk add fontconfig ttf-dejavu");
                plugin.getLogger().error("CentOS/RHEL: dnf install fontconfig dejavu-sans-fonts");
                plugin.getLogger().error("=====================================================");
            } else {
                plugin.getLogger().error("Failed to generate captcha for screening", e);
            }
            return null;
        }
    }

    public void triggerScreening(Player player) {
        Optional<RegisteredServer> limboServer = server.getServer(config.getScreeningServer());
        if (limboServer.isPresent() && player.getCurrentServer().map(s -> s.getServer()).orElse(null) != limboServer.get()) {
            CaptchaGenerator.CaptchaResult captcha = tryGenerateCaptcha();
            if (captcha == null) return; // Silent fail if captcha can't be generated
            ScreeningSession session = new ScreeningSession(player.getCurrentServer().map(s -> s.getServer()).orElse(null), captcha.text);
            sessions.put(player.getUniqueId(), session);
            
            if (player.getCurrentServer().isPresent()) {
                player.createConnectionRequest(limboServer.get()).connect();
            }
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!config.isModuleEnabled()) return;
        
        Player player = event.getPlayer();
        
        // Only trigger on initial connect
        if (event.getPreviousServer() != null) {
            return;
        }

        String mode = config.getDefenseMode();
        boolean shouldScreen = false;

        if ("attack".equalsIgnoreCase(mode)) {
            shouldScreen = true;
        } else if ("elevated".equalsIgnoreCase(mode)) {
            // Screen new players/IPs. A simple way: check if their history is empty
            if (plugin.getPlayerStatsService() != null) {
                // If getPlayerDataByName returns null, they have never joined before.
                if (plugin.getPlayerStatsService().getPlayerDataByName(player.getUsername()) == null) {
                    shouldScreen = true;
                }
            } else {
                shouldScreen = true;
            }
        }

        if (shouldScreen || sessions.containsKey(player.getUniqueId())) {
            Optional<RegisteredServer> limboServer = server.getServer(config.getScreeningServer());
            if (limboServer.isPresent() && event.getOriginalServer() != limboServer.get()) {
                ScreeningSession existing = sessions.get(player.getUniqueId());
                if (existing != null && existing.originalServer == null) {
                    // Update original server if it was null from async trigger
                    sessions.put(player.getUniqueId(), new ScreeningSession(event.getOriginalServer(), existing.captchaText));
                } else if (existing == null) {
                    CaptchaGenerator.CaptchaResult captcha = tryGenerateCaptcha();
                    if (captcha == null) return;
                    ScreeningSession session = new ScreeningSession(event.getOriginalServer(), captcha.text);
                    sessions.put(player.getUniqueId(), session);
                }

                event.setResult(ServerPreConnectEvent.ServerResult.allowed(limboServer.get()));
            }
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        ScreeningSession session = sessions.get(player.getUniqueId());
        
        if (session == null) return;
        
        // If they successfully connected to the limbo server
        if (event.getServer().getServerInfo().getName().equalsIgnoreCase(config.getScreeningServer())) {
            
            // Re-generate map specifically to get the byte array
            CaptchaGenerator.CaptchaResult captcha = tryGenerateCaptcha();
            if (captcha == null) return; // Shouldn't happen if they made it here, but just in case
            session = new ScreeningSession(session.originalServer, captcha.text);
            sessions.put(player.getUniqueId(), session);

            // Send Map Packets using PacketEvents after a tiny delay to ensure they spawned
            final String finalCaptchaText = captcha.text;
            final byte[] mapColors = captcha.mapColors;

            server.getScheduler().buildTask(plugin, () -> {
                if (PacketEvents.getAPI() == null || player == null || !player.isActive()) return;

                com.github.retrooper.packetevents.protocol.player.User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
                if (user == null) return;
                
                player.sendMessage(Component.empty());
                player.sendMessage(Component.text("--- ANTI-BOT SCREENING ---", NamedTextColor.RED, TextDecoration.BOLD));
                player.sendMessage(Component.text("Please type the code shown on the map in your hotbar into the chat.", NamedTextColor.YELLOW));
                player.sendMessage(Component.text("You have " + config.getScreeningTimeout() + " seconds to respond.", NamedTextColor.GRAY));
                player.sendMessage(Component.empty());

                int mapId = 9999; // Arbitrary high map ID

                // 1. Send Map Data
                WrapperPlayServerMapData mapDataPacket = new WrapperPlayServerMapData(
                        mapId,
                        (byte) 0, // scale
                        false, // tracking position
                        false, // locked
                        Collections.emptyList(), // decorations
                        128, // columns
                        128, // rows
                        0, // x
                        0, // z
                        mapColors // data
                );
                user.sendPacket(mapDataPacket);

                // 2. Give the map item
                ItemStack mapItem = ItemStack.builder()
                        .type(ItemTypes.FILLED_MAP)
                        .amount(1)
                        .component(ComponentTypes.MAP_ID, mapId)
                        .build();

                // Slot 36 is the first hotbar slot (index 0 in Minecraft inventory usually means hotbar for SetSlot, but for Window ID 0:
                // Hotbar is 36-44
                int hotbarSlot = 36 + player.getCurrentServer().map(s -> 0).orElse(0); // Actually we can just send to slot 36
                // Even better, send to all hotbar slots so they can't miss it
                for (int slot = 36; slot <= 44; slot++) {
                    WrapperPlayServerSetSlot setSlot = new WrapperPlayServerSetSlot(0, 0, slot, mapItem);
                    user.sendPacket(setSlot);
                }

            }).delay(Duration.ofMillis(1000)).schedule();

            // Schedule Kick
            session.timeoutTask = server.getScheduler().buildTask(plugin, () -> {
                if (!sessions.containsKey(player.getUniqueId())) return;
                ScreeningSession s = sessions.get(player.getUniqueId());
                if (s != null && !s.passed) {
                    player.disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize("&cScreening timeout. Please try connecting again."));
                    sessions.remove(player.getUniqueId());
                }
            }).delay(Duration.ofSeconds(config.getScreeningTimeout())).schedule();
        } else {
            // Player is in sessions but connected to a non-limbo server (e.g. from async trigger check Ip taking too long)
            if (!session.passed) {
                Optional<RegisteredServer> limboServer = server.getServer(config.getScreeningServer());
                if (limboServer.isPresent()) {
                    session.originalServer = event.getServer(); // Update their original server to where they just connected
                    player.createConnectionRequest(limboServer.get()).connect();
                }
            }
        }
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        ScreeningSession session = sessions.get(player.getUniqueId());

        if (session != null) {
            // We cannot deny the chat event in 1.19.1+ without kicking the player due to signed chat.
            // We just let the message pass to NanoLimbo (which will ignore it) and read its contents.

            String msg = event.getMessage().trim();
            if (msg.equalsIgnoreCase(session.captchaText)) {
                // Passed!
                session.passed = true;
                if (session.timeoutTask != null) {
                    session.timeoutTask.cancel();
                }
                sessions.remove(player.getUniqueId());

                player.sendMessage(Component.text("Screening passed! Connecting...", NamedTextColor.GREEN));
                
                // Save to database
                if (plugin.getDatabaseManager() != null && plugin.getDatabaseManager().isConnected()) {
                    String ip = player.getRemoteAddress() != null ? player.getRemoteAddress().getAddress().getHostAddress() : null;
                    if (ip != null) {
                        try (Connection conn = plugin.getDatabaseManager().getConnection();
                             PreparedStatement stmt = conn.prepareStatement("INSERT INTO screening_passes (player_uuid, ip_address, timestamp) VALUES (?, ?, ?)")) {
                            stmt.setString(1, player.getUniqueId().toString());
                            stmt.setString(2, ip);
                            stmt.setLong(3, System.currentTimeMillis());
                            stmt.executeUpdate();
                        } catch (Exception e) {
                            plugin.getLogger().error("Failed to record screening pass", e);
                        }
                    }
                }

                if (session.originalServer != null) {
                    player.createConnectionRequest(session.originalServer).connectWithIndication();
                } else {
                    java.util.List<String> attemptOrder = server.getConfiguration().getAttemptConnectionOrder();
                    if (!attemptOrder.isEmpty()) {
                        String defaultServerName = attemptOrder.get(0);
                        Optional<RegisteredServer> defaultServer = server.getServer(defaultServerName);
                        if (defaultServer.isPresent()) {
                            player.createConnectionRequest(defaultServer.get()).connectWithIndication();
                        } else {
                            player.disconnect(Component.text("Failed to find default server after screening.", NamedTextColor.RED));
                        }
                    } else {
                        player.disconnect(Component.text("No fallback servers configured.", NamedTextColor.RED));
                    }
                }
            } else {
                player.sendMessage(Component.text("Incorrect code. Try again.", NamedTextColor.RED));
            }
        }
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (event.getCommandSource() instanceof Player) {
            Player player = (Player) event.getCommandSource();
            if (sessions.containsKey(player.getUniqueId())) {
                String cmd = event.getCommand().split(" ")[0].toLowerCase();
                if (cmd.equals("scb") || cmd.equals("screeningbypass")) {
                    return;
                }
                
                if (!player.hasPermission("beaconlabs.admin.bypassscreening")) {
                    player.sendMessage(Component.text("You cannot use commands while being screened.", NamedTextColor.RED));
                    event.setResult(CommandExecuteEvent.CommandResult.denied());
                }
            }
        }
    }

    public void bypassScreening(Player player) {
        ScreeningSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.passed = true;
            if (session.timeoutTask != null) {
                session.timeoutTask.cancel();
            }
            sessions.remove(player.getUniqueId());

            player.sendMessage(Component.text("Screening bypassed! Connecting...", NamedTextColor.GREEN));

            if (session.originalServer != null) {
                player.createConnectionRequest(session.originalServer).connectWithIndication();
            }
        }
    }
}
