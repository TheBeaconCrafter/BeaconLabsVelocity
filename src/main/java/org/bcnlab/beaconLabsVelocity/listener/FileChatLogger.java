package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

public class FileChatLogger {

    private final String logDirectory;

    public FileChatLogger(String dataDirectory) {
        this.logDirectory = Paths.get(dataDirectory).resolve("logs").toString();

        Path logPath = Paths.get(this.logDirectory);
        if (!Files.exists(logPath)) {
            try {
                Files.createDirectories(logPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        clearLogs();
    }

    public void logChat(UUID playerId, String playerName, String message, long logStartTime) {
        String filePath = logDirectory + "/" + playerId.toString() + ".log";
        File logFile = new File(filePath);
        boolean isNewFile = !logFile.exists();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, true))) {
            if (isNewFile) {
                String fileCreationTime = new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss").format(new Date());
                writer.write("Username: " + playerName);
                writer.newLine();
                writer.write("UUID: " + playerId);
                writer.newLine();
                writer.write("File created: " + fileCreationTime);
                writer.newLine();
                writer.newLine();
            }

            long currentTime = System.currentTimeMillis();
            String formattedMessage = formatLogMessage(message, logStartTime, currentTime);
            writer.write(formattedMessage);
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String readChatLog(UUID playerId) throws IOException {
        String filePath = logDirectory + "/" + playerId.toString() + ".log";
        return new String(Files.readAllBytes(Paths.get(filePath)));
    }

    private void clearLogs() {
        File dir = new File(logDirectory);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.isDirectory()) file.delete();
                }
            }
        }
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String playerName = player.getUsername();
        String message = event.getMessage();

        long logStartTime = System.currentTimeMillis();
        String logMessage = String.format("%s", message);

        // Log the chat message
        logChat(playerId, playerName, logMessage, logStartTime);
    }

    private String formatLogMessage(String message, long logStartTime, long currentTime) {
        long elapsedTime = currentTime - logStartTime;
        Duration duration = Duration.ofMillis(elapsedTime);

        long days = duration.toDays();
        long totalHours = duration.toHours();
        long hours = totalHours % 24;
        long totalMinutes = duration.toMinutes();
        long minutes = totalMinutes % 60;

        String formattedTime = String.format("[%dd %dh %dm ago]", days, hours, minutes);
        String timestamp = new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss").format(new Date(currentTime));

        return String.format("%s %s | %s", formattedTime, timestamp, message);
    }
}