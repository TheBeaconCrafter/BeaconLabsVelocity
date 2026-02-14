package org.bcnlab.beaconLabsVelocity.command.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.listener.FileChatLogger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ChatReportCommand implements SimpleCommand {

    private static final String PASTEBIN_URL = "https://paste.md-5.net/documents";
    private static final String HSTSH_URL = "https://hst.sh/documents";
    private final FileChatLogger chatLogger;
    private final BeaconLabsVelocity plugin;
    private final ProxyServer proxy;

    public ChatReportCommand(FileChatLogger chatLogger, BeaconLabsVelocity plugin, ProxyServer proxy) {
        this.chatLogger = chatLogger;
        this.plugin = plugin;
        this.proxy = proxy;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        if (args.length != 1) {
            sender.sendMessage(plugin.getPrefix().append(Component.text("Usage: /chatreport <player>", NamedTextColor.RED)));
            return;
        }

        String targetName = args[0];
        sender.sendMessage(plugin.getPrefix().append(Component.text("Gathering chat logs...")));

        long startTime = System.nanoTime();

        UUID playerId;
        try {
            playerId = getUUIDFromPlayerName(targetName);
            if (playerId == null) {
                sender.sendMessage(plugin.getPrefix().append(Component.text("Player " + targetName + " does not exist or is not online.")));
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            sender.sendMessage(plugin.getPrefix().append(Component.text("Failed to retrieve player UUID.")));
            return;
        }

        boolean targetOnThisProxy = proxy.getPlayer(playerId).isPresent();
        if (!targetOnThisProxy && plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()
                && plugin.getCrossProxyService().getPlayerProxy(playerId) != null) {
            String reporterName = sender instanceof Player ? ((Player) sender).getUsername() : "Console";
            plugin.getCrossProxyService().publishChatReportRequest(playerId, targetName, reporterName);
            sender.sendMessage(plugin.getPrefix().append(Component.text("Requesting chat log from the proxy where " + targetName + " is connected. You will receive the link when it's ready.", NamedTextColor.YELLOW)));
            return;
        }
        if (!targetOnThisProxy) {
            sender.sendMessage(plugin.getPrefix().append(Component.text("Player " + targetName + " is not online on the network.")));
            return;
        }

        String chatLog;
        try {
            chatLog = chatLogger.readChatLog(playerId);
        } catch (IOException e) {
            e.printStackTrace();
            sender.sendMessage(plugin.getPrefix().append(Component.text("Failed to retrieve chat logs.")));
            return;
        }

        String pasteLink;
        try {
            pasteLink = uploadToPastebinWithFallback(chatLog);
        } catch (IOException e) {
            e.printStackTrace();
            sender.sendMessage(plugin.getPrefix().append(Component.text("Failed to upload chat logs.")));
            return;
        }

        long endTime = System.nanoTime();
        long durationMillis = (endTime - startTime) / 1000000;

        Component linkMessage = Component.text()
                .append(plugin.getPrefix())
                .append(Component.text("Chat log for ", NamedTextColor.WHITE))
                .append(Component.text(targetName, NamedTextColor.GOLD))
                .append(Component.text(" has been uploaded. ", NamedTextColor.WHITE))
                .append(Component.text("[Click here to view]", NamedTextColor.BLUE)
                        .clickEvent(ClickEvent.openUrl(pasteLink)))
                .append(Component.text(" (Took " + durationMillis + "ms)", NamedTextColor.GRAY))
                .build();

        sender.sendMessage(linkMessage);

        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            String reporterName = sender instanceof Player ? ((Player) sender).getUsername() : "Console";
            plugin.getCrossProxyService().publishChatReportResult(reporterName, targetName, pasteLink);
        }
    }

    /** Tries paste.md-5 first, then hst.sh on failure. Public for cross-proxy report from plugin. */
    public static String uploadToPastebinWithFallback(String content) throws IOException {
        try {
            return uploadToPasteMd5(content);
        } catch (IOException e) {
            try {
                return uploadToHstSh(content);
            } catch (IOException e2) {
                e.addSuppressed(e2);
                throw e;
            }
        }
    }

    private static String uploadToPasteMd5(String content) throws IOException {
        URL url = new URL(PASTEBIN_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/plain");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("paste.md-5 returned " + code);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) throw new IOException("Empty response from paste.md-5");
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            String key = json.has("key") ? json.get("key").getAsString() : line;
            return "https://paste.md-5.net/" + key;
        }
    }

    private static String uploadToHstSh(String content) throws IOException {
        URL url = new URL(HSTSH_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/plain");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("hst.sh returned " + code);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) throw new IOException("Empty response from hst.sh");
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            String key = json.get("key").getAsString();
            return "https://hst.sh/" + key;
        }
    }

    private UUID getUUIDFromPlayerName(String playerName) throws IOException {
        String urlString = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json");

        if (connection.getResponseCode() != 200) return null;

        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            String uuidString = json.get("id").getAsString();
            return UUID.fromString(uuidString.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5"
            ));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length == 0 || invocation.arguments()[0].isEmpty()) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("beaconlabs.chat.chatreport");
    }
}
