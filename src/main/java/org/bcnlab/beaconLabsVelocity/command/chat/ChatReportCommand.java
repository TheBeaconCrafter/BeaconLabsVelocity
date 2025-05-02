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
            pasteLink = uploadToPastebin(chatLog);
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
    }

    private String uploadToPastebin(String content) throws IOException {
        URL url = new URL(PASTEBIN_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/plain");

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(content.getBytes());
            outputStream.flush();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String key = reader.readLine();
            return "https://paste.md-5.net/" + key.split("\"")[3];
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
}
