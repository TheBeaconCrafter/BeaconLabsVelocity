package org.bcnlab.beaconLabsVelocity.brand;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Sends a custom client-bound {@code minecraft:brand} after Velocity has already forwarded the backend
 * brand, so F3 shows the configured text instead of the default {@code (Velocity)} suffix.
 *
 * <p>Uses {@link Player#sendPluginMessage} and the same string payload shape as the proxy
 * ({@code VarInt} UTF-8 byte length + UTF-8). Approach similar in spirit to
 * <a href="https://github.com/LoreSchaeffer/CustomF3Brand">CustomF3Brand</a>, without relying on
 * {@code velocity-proxy} at compile time.
 */
public final class F3BrandService {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();
    private static final MinecraftChannelIdentifier BRAND_MODERN = MinecraftChannelIdentifier.from("minecraft:brand");

    private final BeaconLabsVelocity plugin;
    private final Logger logger;
    private volatile boolean legacyProtocolWarned;

    public F3BrandService(BeaconLabsVelocity plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public boolean isEnabled() {
        ConfigurationNode root = plugin.getConfig();
        if (root == null) return false;
        String text = root.node("f3-brand", "text").getString("");
        return text != null && !text.isBlank();
    }

    public int delayMs() {
        ConfigurationNode root = plugin.getConfig();
        if (root == null) return 100;
        return Math.max(0, root.node("f3-brand", "delay-ms").getInt(100));
    }

    /**
     * Sends the configured brand to the player if enabled.
     */
    public void send(Player player) {
        if (!isEnabled() || player == null) return;
        ConfigurationNode root = plugin.getConfig();
        if (root == null) return;
        String template = root.node("f3-brand", "text").getString("");
        if (template == null || template.isBlank()) return;

        ProtocolVersion protocol = player.getProtocolVersion();
        if (protocol.compareTo(ProtocolVersion.MINECRAFT_1_13) < 0) {
            if (!legacyProtocolWarned) {
                legacyProtocolWarned = true;
                logger.warn("Custom f3-brand is not applied for protocol {} (1.13+ only).", protocol);
            }
            return;
        }

        try {
            String serverName = player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName())
                    .orElse("");
            String withPlaceholders = template
                    .replace("{name}", player.getUsername())
                    .replace("{server}", serverName);

            Component component = MINI_MESSAGE.deserialize(withPlaceholders);
            String legacy = LEGACY_SECTION.serialize(component) + "§r";

            byte[] payload = encodeMinecraftUtf8String(legacy);
            player.sendPluginMessage(BRAND_MODERN, payload);
        } catch (Throwable t) {
            logger.warn("Could not send custom f3-brand to {}: {}", player.getUsername(), t.getMessage());
        }
    }

    /** Same encoding as {@code ProtocolUtils.writeString}: var-int length + UTF-8 bytes. */
    private static byte[] encodeMinecraftUtf8String(String s) {
        byte[] utf = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(utf.length + 5);
        writeVarInt(out, utf.length);
        out.write(utf, 0, utf.length);
        return out.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while (true) {
            if ((v & ~0x7F) == 0) {
                out.write(v);
                return;
            }
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }
}
