package org.bcnlab.beaconLabsVelocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class ChatFilterListener {
    private final BeaconLabsVelocity plugin;
    private List<String> badWords;
    private Path badWordsFile;

    @Inject
    private ProxyServer server;

    public ChatFilterListener(BeaconLabsVelocity plugin, ProxyServer server) {
        this.plugin = plugin;
        this.server = server;
        this.badWordsFile = plugin.getDataDirectory().resolve("badwords.yml");
        loadBadWords();
    }

    private void loadBadWords() {
        try {
            if (!Files.exists(badWordsFile)) {
                Files.createDirectories(badWordsFile.getParent());
                Files.copy(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("badwords.yml")), badWordsFile);
            }

            ConfigurationLoader<?> loader = YamlConfigurationLoader.builder()
                    .path(badWordsFile)
                    .build();

            ConfigurationNode rootNode = loader.load();
            badWords = rootNode.node("badwords").getList(String.class);

        } catch (IOException e) {
            plugin.getLogger().error("Failed to load bad words file", e);
            badWords = List.of();
        }
    }

    private boolean containsBadWords(String message) {
        String lowerCaseMessage = message.toLowerCase(); // Ensure no ambiguity
        return badWords.stream().anyMatch(badWord -> lowerCaseMessage.contains(badWord.toLowerCase()));
    }

    /**
     * Builds the bad-word alert component with click actions (Chatreport, Warn). Shared so cross-proxy
     * handlers can show the same interactive notification.
     */
    public static Component buildBadWordAlertComponent(String playerName, String message, String badWord) {
        if (playerName == null) playerName = "";
        if (message == null) message = "";
        if (badWord == null) badWord = "";
        String[] parts = message.split("(?i)" + Pattern.quote(badWord), 2);
        String beforeBadWord = parts[0];
        String afterBadWord = parts.length > 1 ? parts[1] : "";

        return Component.text("")
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("[ALERT] ", NamedTextColor.RED).style(Style.style(TextDecoration.BOLD)))
                .append(Component.text("Player ", NamedTextColor.YELLOW))
                .append(Component.text(playerName, NamedTextColor.GREEN))
                .append(Component.text(" used a bad word: ", NamedTextColor.YELLOW))
                .append(Component.text(beforeBadWord, NamedTextColor.WHITE))
                .append(Component.text(badWord, NamedTextColor.RED))
                .append(Component.text(afterBadWord, NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("[Chatreport]", NamedTextColor.AQUA)
                        .style(Style.style(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.runCommand("/chatreport " + playerName))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to gather a chat report!")))))
                .append(Component.text(" | ", NamedTextColor.GRAY))
                .append(Component.text("[Warn]", NamedTextColor.RED)
                        .style(Style.style(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.suggestCommand("/warn " + playerName + " "))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to warn the player!")))))
                .append(Component.newline())
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GRAY))
                .append(Component.newline());
    }

    private void notifyAdmins(String playerName, String message) {
        String badWord = findBadWord(message);
        if (badWord == null) return;

        Component notification = buildBadWordAlertComponent(playerName, message, badWord);

        server.getAllPlayers().stream()
                .filter(player -> player.hasPermission("beaconlabs.chatfilter.alert"))
                .forEach(player -> player.sendMessage(notification));

        if (plugin.getCrossProxyService() != null && plugin.getCrossProxyService().isEnabled()) {
            plugin.getCrossProxyService().publishBadWordAlert(playerName, message, badWord);
        }
    }

    private String findBadWord(String message) {
        for (String badWord : badWords) {
            if (message.toLowerCase().contains(badWord.toLowerCase())) {
                return badWord;
            }
        }
        return null;
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        String message = event.getMessage();
        String playerName = event.getPlayer().getUsername();

        if (containsBadWords(message)) {
            notifyAdmins(playerName, message);
        }
    }
}
