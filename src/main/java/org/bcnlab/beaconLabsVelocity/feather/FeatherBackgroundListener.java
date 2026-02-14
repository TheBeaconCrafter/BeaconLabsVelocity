package org.bcnlab.beaconLabsVelocity.feather;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Feather client server list background: when a Feather client sends
 * C2SRequestServerBackground on the Feather plugin channel, we reply with
 * S2CServerBackground (Action.DATA + PNG bytes) if we have a configured image.
 * Image must be PNG, max 1009×202 px, max 512 KB.
 */
public class FeatherBackgroundListener {

    private static final int MAX_WIDTH = 1009;
    private static final int MAX_HEIGHT = 202;
    private static final int MAX_SIZE_BYTES = 512 * 1024;

    private final BeaconLabsVelocity plugin;
    private final Logger logger;

    private final ChannelIdentifier channelId;
    private final int messageIdRequest;
    private final int messageIdResponse;

    private byte[] imageBytes;
    private final Set<UUID> sentToPlayers = ConcurrentHashMap.newKeySet();

    public FeatherBackgroundListener(BeaconLabsVelocity plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        ConfigurationNode feather = plugin.getConfig() != null ? plugin.getConfig().node("feather") : null;
        String channelName = feather != null ? feather.node("channel").getString("feather:client") : "feather:client";
        this.channelId = MinecraftChannelIdentifier.from(channelName);
        this.messageIdRequest = feather != null ? feather.node("message-id-request").getInt(6) : 6;
        this.messageIdResponse = feather != null ? feather.node("message-id-response").getInt(10) : 10;
    }

    /**
     * Load and validate the server list background image. Call once after config is loaded.
     * @return true if a valid image is loaded (or no image configured), false on validation error.
     */
    public boolean loadImage() {
        ConfigurationNode feather = plugin.getConfig() != null ? plugin.getConfig().node("feather") : null;
        if (feather == null) {
            imageBytes = null;
            return true;
        }
        String filename = feather.node("server-list-background").getString("");
        if (filename == null || filename.isBlank()) {
            imageBytes = null;
            return true;
        }
        Path dir = plugin.getDataDirectory().resolve("feather");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.warn("Could not create feather directory: {}", e.getMessage());
        }
        Path file = dir.resolve(filename);
        if (!Files.isRegularFile(file)) {
            logger.warn("Feather server list background file not found: {}", file);
            imageBytes = null;
            return true;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_SIZE_BYTES) {
                logger.warn("Feather server list background exceeds 512 KB: {} bytes", bytes.length);
                imageBytes = null;
                return false;
            }
            BufferedImage img = ImageIO.read(Files.newInputStream(file));
            if (img == null) {
                logger.warn("Feather server list background is not a valid image: {}", file);
                imageBytes = null;
                return false;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            if (w > MAX_WIDTH || h > MAX_HEIGHT) {
                logger.warn("Feather server list background dimensions exceed 1009x202: {}x{}", w, h);
                imageBytes = null;
                return false;
            }
            imageBytes = bytes;
            logger.info("Feather server list background loaded: {} ({}x{}, {} bytes)", filename, w, h, bytes.length);
            return true;
        } catch (IOException e) {
            logger.warn("Failed to load Feather server list background: {}", e.getMessage());
            imageBytes = null;
            return false;
        }
    }

    public ChannelIdentifier getChannelId() {
        return channelId;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channelId)) {
            return;
        }
        boolean debug = plugin.isFeatherDebug();
        byte[] data = event.getData();
        if (data == null) {
            data = new byte[0];
        }
        boolean fromPlayer = event.getSource() instanceof Player;
        Player player = fromPlayer ? (Player) event.getSource() : null;

        if (debug) {
            String sourceDesc = fromPlayer ? (player.getUsername() + " " + player.getUniqueId()) : event.getSource().getClass().getSimpleName();
            logger.info("[Feather debug] IN  channel={} from={} dataLength={} hex={}",
                    channelId.getId(), sourceDesc, data.length, bytesToHex(data, 64));
        }

        if (!fromPlayer) {
            return;
        }
        if (data.length == 0) {
            if (debug) {
                logger.info("[Feather debug] Ignored: empty payload from player");
            }
            return;
        }
        int[] offset = { 0 };
        int messageId;
        try {
            messageId = readVarInt(data, offset);
        } catch (Exception e) {
            if (debug) {
                logger.info("[Feather debug] Failed to parse varint messageId: {}", e.getMessage());
            }
            return;
        }

        if (debug) {
            logger.info("[Feather debug] Parsed messageId={} (expect requestId={}) payloadAfterVarint={} bytes",
                    messageId, messageIdRequest, data.length - offset[0]);
        }

        if (messageId != messageIdRequest) {
            if (debug) {
                logger.info("[Feather debug] Ignored: messageId {} != requestId {}", messageId, messageIdRequest);
            }
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (imageBytes == null) {
            if (debug) {
                logger.info("[Feather debug] C2SRequestServerBackground received but no image loaded; not sending.");
            }
            return;
        }
        if (sentToPlayers.contains(player.getUniqueId())) {
            if (debug) {
                logger.info("[Feather debug] C2SRequestServerBackground received but already sent to this player; not sending again.");
            }
            return;
        }

        byte[] payload = encodeS2CServerBackground(imageBytes);
        if (payload == null) {
            if (debug) {
                logger.warn("[Feather debug] encodeS2CServerBackground returned null");
            }
            return;
        }
        player.sendPluginMessage(channelId, payload);
        sentToPlayers.add(player.getUniqueId());
        if (debug) {
            logger.info("[Feather debug] OUT Sent S2CServerBackground to {} ({} bytes total, image {} bytes)",
                    player.getUsername(), payload.length, imageBytes.length);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        sentToPlayers.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Encode S2CServerBackground: messageId (varint) + Action.DATA ordinal (varint) + byte array (varint length + bytes).
     */
    private byte[] encodeS2CServerBackground(byte[] imageData) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeVarInt(out, messageIdResponse);
            out.write(1); // Action.DATA ordinal (single byte as in Feather MessageWriter.writeEnum)
            writeVarInt(out, imageData.length);
            out.write(imageData);
            return out.toByteArray();
        } catch (IOException e) {
            logger.warn("Failed to encode Feather S2CServerBackground: {}", e.getMessage());
            return null;
        }
    }

    private static int readVarInt(byte[] data, int[] offset) {
        int value = 0;
        int shift = 0;
        int i = offset[0];
        while (i < data.length) {
            int b = data[i++] & 0xFF;
            value |= (b & 0x7F) << shift;
            offset[0] = i;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift >= 35) {
                throw new IllegalArgumentException("VarInt too large");
            }
        }
        return value;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private static String bytesToHex(byte[] data, int maxBytes) {
        if (data == null || data.length == 0) return "";
        int len = Math.min(data.length, maxBytes);
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        if (data.length > maxBytes) {
            sb.append("...");
        }
        return sb.toString();
    }
}
