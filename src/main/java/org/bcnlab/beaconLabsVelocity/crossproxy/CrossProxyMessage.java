package org.bcnlab.beaconLabsVelocity.crossproxy;

import java.util.UUID;

/**
 * Parsed cross-proxy message. All messages include secret and proxyId for verification.
 */
public final class CrossProxyMessage {

    private static final String SEP = "\u001E"; // ASCII Record Separator

    public enum Type {
        KICK,
        KICK_BY_NAME,
        SENDALL,
        PLAYER_CONNECT,
        SEND_PLAYER,
        MUTE_APPLIED,
        PRIVATE_MSG,
        BROADCAST,
        TEAM_CHAT,
        CHATREPORT_RESULT,
        CHATREPORT_REQUEST,
        MAINTENANCE_SET,
        WHITELIST_SET,
        JOINME_TO_PLAYER,
        JOINME_BROADCAST,
        REPORT_NOTIFY,
        BADWORD_ALERT
    }

    private final Type type;
    private final String secret;
    private final String proxyId;
    private final String uuid;
    private final String reason;
    private final String serverName;
    private final String username;
    private final String durationFormatted;

    private CrossProxyMessage(Type type, String secret, String proxyId, String uuid, String reason, String serverName, String username, String durationFormatted) {
        this.type = type;
        this.secret = secret;
        this.proxyId = proxyId;
        this.uuid = uuid;
        this.reason = reason;
        this.serverName = serverName;
        this.username = username;
        this.durationFormatted = durationFormatted;
    }

    public Type getType() { return type; }
    public String getSecret() { return secret; }
    public String getProxyId() { return proxyId; }
    public String getUuid() { return uuid; }
    public String getReason() { return reason; }
    public String getServerName() { return serverName; }
    /** For KICK_BY_NAME, the target player name. */
    public String getUsername() { return username; }
    /** For MUTE_APPLIED, the formatted duration string. */
    public String getDurationFormatted() { return durationFormatted; }

    /** Build outbound KICK message (uuid, reason, secret, proxyId). */
    public static String kick(UUID uuid, String reason, String secret, String proxyId) {
        return "KICK" + SEP + uuid.toString() + SEP + (reason != null ? reason : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound KICK_BY_NAME message (for players on another proxy). */
    public static String kickByName(String username, String reason, String secret, String proxyId) {
        return "KICK_BY_NAME" + SEP + (username != null ? username : "") + SEP + (reason != null ? reason : "") + SEP + secret + SEP + proxyId;
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

    /** Build outbound MUTE_APPLIED message (notify player on another proxy they were muted). */
    public static String muteApplied(UUID uuid, String reason, String durationFormatted, String secret, String proxyId) {
        return "MUTE_APPLIED" + SEP + uuid.toString() + SEP + (reason != null ? reason : "") + SEP + (durationFormatted != null ? durationFormatted : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound PRIVATE_MSG (target username, sender uuid, sender username, preformatted recipient message legacy string). */
    public static String privateMsg(String targetUsername, String senderUuid, String senderUsername, String recipientMessageLegacy, String secret, String proxyId) {
        return "PRIVATE_MSG" + SEP + (targetUsername != null ? targetUsername : "") + SEP + (senderUuid != null ? senderUuid : "") + SEP + (senderUsername != null ? senderUsername : "") + SEP + (recipientMessageLegacy != null ? recipientMessageLegacy : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound BROADCAST (message legacy string). */
    public static String broadcast(String messageLegacy, String secret, String proxyId) {
        return "BROADCAST" + SEP + (messageLegacy != null ? messageLegacy : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound TEAM_CHAT (formatted message legacy string). */
    public static String teamChat(String messageLegacy, String secret, String proxyId) {
        return "TEAM_CHAT" + SEP + (messageLegacy != null ? messageLegacy : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound CHATREPORT_RESULT (reporter, target, link). */
    public static String chatReportResult(String reporterName, String targetName, String pasteLink, String secret, String proxyId) {
        return "CHATREPORT_RESULT" + SEP + (reporterName != null ? reporterName : "") + SEP + (targetName != null ? targetName : "") + SEP + (pasteLink != null ? pasteLink : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound CHATREPORT_REQUEST (target uuid, target username, reporter username). */
    public static String chatReportRequest(String targetUuid, String targetUsername, String reporterUsername, String secret, String proxyId) {
        return "CHATREPORT_REQUEST" + SEP + (targetUuid != null ? targetUuid : "") + SEP + (targetUsername != null ? targetUsername : "") + SEP + (reporterUsername != null ? reporterUsername : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound MAINTENANCE_SET (enabled "true"/"false", broadcast message legacy). */
    public static String maintenanceSet(boolean enabled, String broadcastMessageLegacy, String secret, String proxyId) {
        return "MAINTENANCE_SET" + SEP + (enabled ? "true" : "false") + SEP + (broadcastMessageLegacy != null ? broadcastMessageLegacy : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound WHITELIST_SET (enabled "true"/"false"). */
    public static String whitelistSet(boolean enabled, String secret, String proxyId) {
        return "WHITELIST_SET" + SEP + (enabled ? "true" : "false") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound JOINME_TO_PLAYER (target, sender username, server name) so receiver can build clickable message. */
    public static String joinMeToPlayer(String targetUsername, String senderUsername, String serverName, String secret, String proxyId) {
        return "JOINME_TO_PLAYER" + SEP + (targetUsername != null ? targetUsername : "") + SEP + (senderUsername != null ? senderUsername : "") + SEP + (serverName != null ? serverName : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound JOINME_BROADCAST (sender username, server name) so receiver can build clickable message. */
    public static String joinMeBroadcast(String senderUsername, String serverName, String secret, String proxyId) {
        return "JOINME_BROADCAST" + SEP + (senderUsername != null ? senderUsername : "") + SEP + (serverName != null ? serverName : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound REPORT_NOTIFY (notification legacy string so each proxy can display to local staff). */
    public static String reportNotify(String notificationLegacy, String secret, String proxyId) {
        return "REPORT_NOTIFY" + SEP + (notificationLegacy != null ? notificationLegacy : "") + SEP + secret + SEP + proxyId;
    }

    /** Build outbound BADWORD_ALERT (playerName, message, badWord) so each proxy can build the notification with click actions. */
    public static String badWordAlert(String playerName, String messageContent, String badWord, String secret, String proxyId) {
        return "BADWORD_ALERT" + SEP + (playerName != null ? playerName : "") + SEP + (messageContent != null ? messageContent : "") + SEP + (badWord != null ? badWord : "") + SEP + secret + SEP + proxyId;
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
                return new CrossProxyMessage(Type.KICK, parts[parts.length - 2], parts.length > 4 ? parts[parts.length - 1] : null, parts[1], reason, null, null, null);
            }
            if ("KICK_BY_NAME".equals(typeStr) && parts.length >= 5) {
                String reason = parts.length == 5 ? parts[2] : String.join(SEP, java.util.Arrays.copyOfRange(parts, 2, parts.length - 2));
                return new CrossProxyMessage(Type.KICK_BY_NAME, parts[parts.length - 2], parts.length > 4 ? parts[parts.length - 1] : null, null, reason, null, parts[1], null);
            }
            if ("SENDALL".equals(typeStr) && parts.length >= 4) {
                return new CrossProxyMessage(Type.SENDALL, parts[2], parts.length > 3 ? parts[3] : null, null, null, parts[1], null, null);
            }
            if ("PLAYER_CONNECT".equals(typeStr) && parts.length >= 4) {
                return new CrossProxyMessage(Type.PLAYER_CONNECT, parts[3], parts[1], parts[2], null, null, null, null);
            }
            if ("SEND_PLAYER".equals(typeStr) && parts.length >= 5) {
                return new CrossProxyMessage(Type.SEND_PLAYER, parts[3], parts.length > 4 ? parts[4] : null, parts[1], null, parts[2], null, null);
            }
            if ("MUTE_APPLIED".equals(typeStr) && parts.length >= 6) {
                String reason = parts.length == 6 ? parts[2] : String.join(SEP, java.util.Arrays.copyOfRange(parts, 2, parts.length - 3));
                String durationFormatted = parts[parts.length - 3];
                return new CrossProxyMessage(Type.MUTE_APPLIED, parts[parts.length - 2], parts[parts.length - 1], parts[1], reason, null, null, durationFormatted);
            }
            if ("PRIVATE_MSG".equals(typeStr) && parts.length >= 7) {
                String recipientMessageLegacy = parts.length == 7 ? parts[4] : String.join(SEP, java.util.Arrays.copyOfRange(parts, 4, parts.length - 2));
                return new CrossProxyMessage(Type.PRIVATE_MSG, parts[parts.length - 2], parts[parts.length - 1], parts[2], recipientMessageLegacy, parts[3], parts[1], null); // uuid=senderUuid, serverName=senderUsername, username=targetUsername
            }
            if ("BROADCAST".equals(typeStr) && parts.length >= 4) {
                String messageLegacy = parts.length == 4 ? parts[1] : String.join(SEP, java.util.Arrays.copyOfRange(parts, 1, parts.length - 2));
                return new CrossProxyMessage(Type.BROADCAST, parts[parts.length - 2], parts[parts.length - 1], null, messageLegacy, null, null, null);
            }
            if ("TEAM_CHAT".equals(typeStr) && parts.length >= 4) {
                String messageLegacy = parts.length == 4 ? parts[1] : String.join(SEP, java.util.Arrays.copyOfRange(parts, 1, parts.length - 2));
                return new CrossProxyMessage(Type.TEAM_CHAT, parts[parts.length - 2], parts[parts.length - 1], null, messageLegacy, null, null, null);
            }
            if ("CHATREPORT_RESULT".equals(typeStr) && parts.length >= 6) {
                return new CrossProxyMessage(Type.CHATREPORT_RESULT, parts[4], parts[5], null, parts[3], parts[2], parts[1], null); // reason=link, serverName=target, username=reporter
            }
            if ("CHATREPORT_REQUEST".equals(typeStr) && parts.length >= 6) {
                return new CrossProxyMessage(Type.CHATREPORT_REQUEST, parts[4], parts[5], parts[1], null, parts[2], parts[3], null); // uuid=targetUuid, serverName=targetUsername, username=reporterUsername
            }
            if ("MAINTENANCE_SET".equals(typeStr) && parts.length >= 5) {
                return new CrossProxyMessage(Type.MAINTENANCE_SET, parts[3], parts[4], null, parts.length > 2 ? parts[2] : "", parts[1], null, null); // reason=broadcastMessage, serverName=enabled "true"/"false"
            }
            if ("WHITELIST_SET".equals(typeStr) && parts.length >= 4) {
                return new CrossProxyMessage(Type.WHITELIST_SET, parts[2], parts[3], null, null, parts[1], null, null); // serverName=enabled "true"/"false"
            }
            if ("JOINME_TO_PLAYER".equals(typeStr) && parts.length >= 6) {
                return new CrossProxyMessage(Type.JOINME_TO_PLAYER, parts[4], parts[5], null, parts[3], parts[2], parts[1], null); // username=target, reason=serverName(parts[3]), serverName=senderUsername(parts[2])
            }
            if ("JOINME_BROADCAST".equals(typeStr) && parts.length >= 5) {
                return new CrossProxyMessage(Type.JOINME_BROADCAST, parts[3], parts[4], null, parts[2], parts[1], null, null); // reason=serverName(parts[2]), serverName=senderUsername(parts[1])
            }
            if ("REPORT_NOTIFY".equals(typeStr) && parts.length >= 4) {
                String notificationLegacy = parts.length == 4 ? parts[1] : String.join(SEP, java.util.Arrays.copyOfRange(parts, 1, parts.length - 2));
                return new CrossProxyMessage(Type.REPORT_NOTIFY, parts[parts.length - 2], parts[parts.length - 1], null, notificationLegacy, null, null, null);
            }
            if ("BADWORD_ALERT".equals(typeStr) && parts.length >= 6) {
                String messageContent = parts.length == 6 ? parts[2] : String.join(SEP, java.util.Arrays.copyOfRange(parts, 2, parts.length - 3));
                String badWord = parts[parts.length - 3];
                return new CrossProxyMessage(Type.BADWORD_ALERT, parts[parts.length - 2], parts[parts.length - 1], null, messageContent, badWord, parts[1], null); // username=playerName, reason=message, serverName=badWord
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
