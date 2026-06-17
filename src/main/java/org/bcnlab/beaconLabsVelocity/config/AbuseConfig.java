package org.bcnlab.beaconLabsVelocity.config;

import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class AbuseConfig {

    private boolean moduleEnabled = true;
    private String defenseMode = "normal"; // normal, elevated, attack
    private String screeningServer = "limbo";
    private int screeningTimeout = 15;

    // AntiBot settings
    private String apiKey = "YOUR_API_KEY_HERE";
    private int dailyLimit = 1000;
    private int minConfidenceScore = 90;
    private String kickMessage = "&c[BeaconLabs] Your connection was blocked due to suspicious activity (VPN/Proxy/Bot).\n&eIf you believe this is an error, please contact support.";
    private String webhookUrl = "";
    private String roleIdToPing = "";
    private int webhookCooldownMinutes = 60;

    private int forceBanScore = 90;
    private int screeningScore = 50;
    private boolean screenDataCenters = true;
    private List<String> screenCountries = Arrays.asList("CN", "RU");

    private final File configFile;
    private final Logger logger;
    private ConfigurationNode rootNode;

    public AbuseConfig(File dataDirectory, Logger logger) {
        this.logger = logger;
        this.configFile = new File(dataDirectory, "abuse.yml");
        loadConfig();
    }

    public void loadConfig() {
        if (!configFile.exists()) {
            try {
                configFile.getParentFile().mkdirs();
                // Copy default abuse.yml from resources
                try (java.io.InputStream in = getClass().getResourceAsStream("/abuse.yml")) {
                    if (in != null) {
                        java.nio.file.Files.copy(in, configFile.toPath());
                    } else {
                        configFile.createNewFile();
                    }
                }
            } catch (IOException e) {
                logger.error("Could not create default abuse.yml", e);
            }
        }

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(configFile.toPath()).build();
        try {
            rootNode = loader.load();
            ConfigurationNode abuseNode = rootNode.node("abuse");
            if (!abuseNode.virtual()) {
                moduleEnabled = abuseNode.node("module-enabled").getBoolean(true);
                defenseMode = abuseNode.node("defense-mode").getString("normal").toLowerCase();
                screeningServer = abuseNode.node("screening-server").getString("limbo");
                screeningTimeout = abuseNode.node("screening-timeout").getInt(15);

                ConfigurationNode botNode = abuseNode.node("antibot");
                if (!botNode.virtual()) {
                    apiKey = botNode.node("api-key").getString("YOUR_API_KEY_HERE");
                    dailyLimit = botNode.node("daily-limit").getInt(1000);
                    minConfidenceScore = botNode.node("min-confidence-score").getInt(90);
                    forceBanScore = botNode.node("force-ban-score").getInt(90);
                    screeningScore = botNode.node("screening-score").getInt(50);
                    screenDataCenters = botNode.node("screen-data-centers").getBoolean(true);
                    try {
                        screenCountries = botNode.node("screen-countries").getList(String.class, Arrays.asList("CN", "RU"))
                            .stream().map(String::toUpperCase).collect(Collectors.toList());
                    } catch (Exception e) {
                        screenCountries = Arrays.asList("CN", "RU");
                    }
                    kickMessage = botNode.node("kick-message").getString(kickMessage);
                    webhookUrl = botNode.node("webhook-url").getString("");
                    roleIdToPing = botNode.node("role-id-to-ping").getString("");
                    webhookCooldownMinutes = botNode.node("webhook-cooldown-minutes").getInt(60);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load abuse.yml", e);
        }
    }

    public void saveConfig() {
        if (rootNode == null) return;
        try {
            ConfigurationNode abuseNode = rootNode.node("abuse");
            abuseNode.node("defense-mode").set(defenseMode);
            abuseNode.node("module-enabled").set(moduleEnabled);
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(configFile.toPath()).build();
            loader.save(rootNode);
        } catch (IOException e) {
            logger.error("Failed to save abuse.yml", e);
        }
    }

    public boolean isModuleEnabled() { return moduleEnabled; }
    public void setModuleEnabled(boolean moduleEnabled) { this.moduleEnabled = moduleEnabled; saveConfig(); }
    
    public String getDefenseMode() { return defenseMode; }
    public void setDefenseMode(String defenseMode) { this.defenseMode = defenseMode; saveConfig(); }

    public String getScreeningServer() { return screeningServer; }
    public int getScreeningTimeout() { return screeningTimeout; }

    public String getApiKey() { return apiKey; }
    public int getDailyLimit() { return dailyLimit; }
    public int getMinConfidenceScore() { return minConfidenceScore; }
    public int getForceBanScore() { return forceBanScore; }
    public int getScreeningScore() { return screeningScore; }
    public boolean isScreenDataCenters() { return screenDataCenters; }
    public List<String> getScreenCountries() { return screenCountries; }
    public String getKickMessage() { return kickMessage; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getRoleIdToPing() { return roleIdToPing; }
    public int getWebhookCooldownMinutes() { return webhookCooldownMinutes; }
}
