package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent; // Import CommandExecuteEvent
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.command.CommandSource; // Import CommandSource

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public class FileChatLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("dd.MM.yyyy - HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final String logDirectory;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BeaconLabsVelocity-ChatLogger");
        thread.setDaemon(true);
        return thread;
    });

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
        writer.execute(() -> appendLog(playerId, playerName, message, logStartTime));
    }

    private void appendLog(UUID playerId, String playerName, String message, long logStartTime) {
        String filePath = logDirectory + "/" + playerId + ".log";
        File logFile = new File(filePath);
        boolean isNewFile = !logFile.exists();

        try (BufferedWriter fileWriter = new BufferedWriter(new FileWriter(filePath, true))) {
            if (isNewFile) {
                fileWriter.write("Username: " + playerName);
                fileWriter.newLine();
                fileWriter.write("UUID: " + playerId);
                fileWriter.newLine();
                fileWriter.write("File created: " + TIMESTAMP_FORMAT.format(Instant.now()));
                fileWriter.newLine();
                fileWriter.newLine();
            }

            fileWriter.write(formatLogMessage(message, logStartTime, System.currentTimeMillis()));
            fileWriter.newLine();
        } catch (IOException e) {
            System.err.println("Failed to write chat log: " + e.getMessage());
        }
    }

    public String readChatLog(UUID playerId) throws IOException {
        try {
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while flushing chat logs", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("Timed out while flushing chat logs", e);
        }
        String filePath = logDirectory + "/" + playerId + ".log";
        java.nio.file.Path path = java.nio.file.Paths.get(filePath);
        if (!java.nio.file.Files.exists(path)) {
            return null;
        }
        return new String(java.nio.file.Files.readAllBytes(path));
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
        String logMessage = String.format("[CHAT] %s", message); 

        logChat(playerId, playerName, logMessage, logStartTime);
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        CommandSource source = event.getCommandSource();
        
        // Only log commands executed by players
        if (source instanceof Player) {
            Player player = (Player) source;
            UUID playerId = player.getUniqueId();
            String playerName = player.getUsername();
            String command = event.getCommand();

            long logStartTime = System.currentTimeMillis();
            String logMessage = String.format("[CMD] /%s", command); 

            logChat(playerId, playerName, logMessage, logStartTime);
        }
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
        String timestamp = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(currentTime));

        return String.format("%s %s | %s", formattedTime, timestamp, message);
    }

    public void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}