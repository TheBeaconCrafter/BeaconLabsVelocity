package org.bcnlab.beaconLabsVelocity.crossproxy;

import java.util.UUID;

/**
 * Parsed cross-proxy message. All messages include secret and proxyId for verification.
 */
public final class CrossProxyMessage {

    private static final String SEP = "\u001E"; // ASCII Record Separator

    public enum Type {
        KICK,
        SENDALL,
        PLAYER_CONNECT,
        SEND_PLAYER
    }

    private final Type type;
    private final String secret;
    private final String proxyId;
    private final String uuid;
    private final String reason;
    private final String serverName;

    private CrossProxyMessage(Type type, String secret, String proxyId, String uuid, String reason, String serverName) {
        this.type = type;
        this.secret = secret;
        this.proxyId = proxyId;
        this.uuid = uuid;
        this.reason = reason;
        this.serverName = serverName;
    }

    public Type getType() { return type; }
    public String getSecret() { return secret; }
    public String getProxyId() { return proxyId; }
    public String getUuid() { return uuid; }
    public String getReason() { return reason; }
    public String getServerName() { return serverName; }

    /** Build outbound KICK message (uuid, reason, secret, proxyId). */
    public static String kick(UUID uuid, String reason, String secret, String proxyId) {
        return "KICK" + SEP + uuid.toString() + SEP + (reason != null ? reason : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound SENDALL message. */
    public static String sendAll(String serverName, String secret, String proxyId) {
        return "SENDALL" + SEP + serverName + SEP + secret + SEP + proxyId;
    }

    /** Build outbound PLAYER_CONNECT message. */
    public static String playerConnect(String proxyId, UUID uuid, String secret) {
        return "PLAYER_CONNECT" + SEP + proxyId + SEP + uuid.toString() + SEP + secret;
    }

    /** Build outbound SEND_PLAYER message. */
    public static String sendPlayer(UUID uuid, String serverName, String secret, String proxyId) {
        return "SEND_PLAYER" + SEP + uuid.toString() + SEP + serverName + SEP + secret + SEP + proxyId;
    }

    /**
     * Parse an incoming message. Returns null if invalid or unknown type.
     * Reason field may contain SEP; we reassemble it from middle parts for KICK.
     */
    public static CrossProxyMessage parse(String raw) {
        if (raw == null || !raw.contains(SEP)) return null;
        String[] parts = raw.split(SEP, -1);
        try {
            String typeStr = parts[0];
            if ("KICK".equals(typeStr) && parts.length >= 5) {
                String reason = parts.length == 5 ? parts[2] : String.join(SEP, java.util.Arrays.copyOfRange(parts, 2, parts.length - 2));
                return new CrossProxyMessage(Type.KICK, parts[parts.length - 2], parts[parts.length - 1], parts[1], reason, null);
            }
            if ("SENDALL".equals(typeStr) && parts.length >= 4) {
                return new CrossProxyMessage(Type.SENDALL, parts[2], parts.length > 3 ? parts[3] : null, null, null, parts[1]);
            }
            if ("PLAYER_CONNECT".equals(typeStr) && parts.length >= 4) {
                return new CrossProxyMessage(Type.PLAYER_CONNECT, parts[3], parts[1], parts[2], null, null);
            }
            if ("SEND_PLAYER".equals(typeStr) && parts.length >= 5) {
                return new CrossProxyMessage(Type.SEND_PLAYER, parts[3], parts.length > 4 ? parts[4] : null, parts[1], null, parts[2]);
            }
        } catch (Exception ignored) { }
        return null;
    }

    public UUID getUuidAsUUID() {
        if (uuid == null || uuid.isEmpty()) return null;
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
