package org.bcnlab.beaconLabsVelocity.config;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads punishments.yml and provides access to messages and predefined reasons.
 */
public class PunishmentConfig {
    private final CommentedConfigurationNode root;
    private final Map<String, PredefinedReason> reasons = new HashMap<>();

    public PunishmentConfig(Path dataFolder, Logger logger) throws IOException {
        Path configFile = dataFolder.resolve("punishments.yml");
        if (!Files.exists(configFile)) {
            Files.copy(getClass().getClassLoader().getResourceAsStream("punishments.yml"), configFile);
        }
        ConfigurationLoader<CommentedConfigurationNode> loader = YamlConfigurationLoader.builder()
                .path(configFile)
                .build();
        this.root = loader.load();
        loadPredefinedReasons();
    }

    private void loadPredefinedReasons() {
        ConfigurationNode node = root.node("predefined-reasons");
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
            String key = entry.getKey().toString();
            ConfigurationNode val = entry.getValue();
            String type = val.node("type").getString();
            String duration = val.node("duration").getString();
            String reason = val.node("reason").getString();
            reasons.put(key, new PredefinedReason(type, duration, reason));
        }
    }

    public String getMessage(String key) {
        return root.node("messages", key).getString();
    }

    public PredefinedReason getPredefinedReason(String key) {
        return reasons.get(key);
    }

    public Map<String, PredefinedReason> getAllPredefinedReasons() {
        return reasons;
    }

    public static class PredefinedReason {
        private final String type;
        private final String duration;
        private final String reason;
        public PredefinedReason(String type, String duration, String reason) {
            this.type = type;
            this.duration = duration;
            this.reason = reason;
        }
        public String getType() { return type; }
        public String getDuration() { return duration; }
        public String getReason() { return reason; }
    }
}
