package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.notenoughupdates.moulconfig.processor.BuiltinMoulConfigGuis;
import io.github.notenoughupdates.moulconfig.processor.ConfigProcessorDriver;
import io.github.notenoughupdates.moulconfig.processor.MoulConfigProcessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Manages synchronization between MoulConfig and existing config files.
 * Also handles JSON persistence for MoulConfig.
 */
public class ConfigManager {

    private static IslandTimeConfig config;
    private static MoulConfigProcessor<?> processor;
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final File configFile = new File("config/IslandTime/moulconfig.json");

    private static Screen lastScreen = null;
    private static boolean configScreenWasOpen = false;

    public static void initialize() {
        // Load config from JSON or create default
        config = loadFromFile();

        // Set up MoulConfig processor
        processor = new MoulConfigProcessor<>(config);
        BuiltinMoulConfigGuis.addProcessors(processor);

        ConfigProcessorDriver driver = new ConfigProcessorDriver(processor);
        driver.processConfig(config);

        // Sync initial values from existing config files to MoulConfig
        syncFromExistingConfigs();
    }

    public static IslandTimeConfig getConfig() {
        return config;
    }

    public static MoulConfigProcessor<?> getProcessor() {
        return processor;
    }

    /**
     * Load config from JSON file
     */
    private static IslandTimeConfig loadFromFile() {
        if (!configFile.exists()) {
            return new IslandTimeConfig();
        }

        try (FileReader reader = new FileReader(configFile)) {
            IslandTimeConfig loaded = gson.fromJson(reader, IslandTimeConfig.class);
            return loaded != null ? loaded : new IslandTimeConfig();
        } catch (Exception e) {
            System.err.println("Failed to load MoulConfig, using defaults: " + e.getMessage());
            return new IslandTimeConfig();
        }
    }

    /**
     * Save config to JSON file
     */
    public static void saveToFile() {
        try {
            configFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(config, writer);
            }
        } catch (Exception e) {
            System.err.println("Failed to save MoulConfig: " + e.getMessage());
        }
    }

    /**
     * Call this from the client tick to detect when config screen closes
     */
    public static void tick(MinecraftClient client) {
        if (client == null) return;

        Screen currentScreen = client.currentScreen;

        // Detect if config screen just closed
        if (configScreenWasOpen && currentScreen == null) {
            // Config screen was just closed, sync changes
            saveToFile();
            syncToExistingConfigs();
            configScreenWasOpen = false;
        }

        // Track if config screen is currently open
        if (currentScreen != null && currentScreen.getClass().getName().contains("MoulConfig")) {
            configScreenWasOpen = true;
        }

        lastScreen = currentScreen;
    }

    /**
     * Load values from existing config files into MoulConfig
     */
    public static void syncFromExistingConfigs() {
        if (config == null) return;

        // Tracking toggles
        config.tracking.islandTimeEnabled = TrackingToggleConfig.isIslandTimeEnabled();
        config.tracking.fuelCycleEnabled = TrackingToggleConfig.isFuelCycleEnabled();
        config.tracking.favouriteGuiEnabled = FavouriteGuiManager.isTrackingEnabled();

        // Webhooks
        String webhook = DiscordWebhookManager.getWebhook();
        config.webhooksAndAlerts.webhookUrl = webhook != null ? webhook : "";

        String userId = DiscordWebhookManager.getDiscordUserId();
        config.webhooksAndAlerts.discordUserId = userId != null ? userId : "";

        // Alert speeds
        config.alertSpeeds.trackedItemSpeed = convertAlertSpeedToInt(ItemTracker.getAlertSpeed().name());
        config.alertSpeeds.islandSpeed = convertAlertSpeedToInt(IslandAlertTracker.getAlertSpeed().name());
        config.alertSpeeds.disconnectSpeed = convertAlertSpeedToInt(DisconnectAlertTracker.getAlertSpeed().name());

        // Item tracking (read-only display)
        String uuid = ItemTracker.getTrackedUuid();
        String name = ItemTracker.getTrackedItemName();
        config.itemTracking.trackedUuid = uuid != null ? uuid : "";
        config.itemTracking.trackedItemName = name != null ? name : "None";

        // Chat relay
        config.chatRelay.chatWebhookUrl = ChatRelayConfig.getChatWebhookUrl();
        config.chatRelay.pollPort = ChatRelayConfig.getPollPort();
        config.chatRelay.pollSecret = ChatRelayConfig.getPollSecret();

        saveToFile();
    }

    /**
     * Apply MoulConfig values to existing config systems
     */
    public static void syncToExistingConfigs() {
        if (config == null) return;

        // Tracking toggles
        if (TrackingToggleConfig.isIslandTimeEnabled() != config.tracking.islandTimeEnabled) {
            TrackingToggleConfig.setIslandTimeEnabled(config.tracking.islandTimeEnabled);
        }
        if (TrackingToggleConfig.isFuelCycleEnabled() != config.tracking.fuelCycleEnabled) {
            TrackingToggleConfig.setFuelCycleEnabled(config.tracking.fuelCycleEnabled);
        }
        if (FavouriteGuiManager.isTrackingEnabled() != config.tracking.favouriteGuiEnabled) {
            FavouriteGuiManager.setTracking(config.tracking.favouriteGuiEnabled);
        }

        // Webhooks
        if (!DiscordWebhookManager.getWebhook().equals(config.webhooksAndAlerts.webhookUrl)) {
            DiscordWebhookManager.setWebhook(config.webhooksAndAlerts.webhookUrl);
        }
        if (!DiscordWebhookManager.getDiscordUserId().equals(config.webhooksAndAlerts.discordUserId)) {
            DiscordWebhookManager.setDiscordUserId(config.webhooksAndAlerts.discordUserId);
        }

        // Alert speeds
        String trackedItemSpeed = convertIntToAlertSpeed(config.alertSpeeds.trackedItemSpeed);
        if (!ItemTracker.getAlertSpeed().name().equals(trackedItemSpeed)) {
            ItemTracker.setAlertSpeed(AlertSpeedConfig.AlertSpeed.valueOf(trackedItemSpeed));
        }

        String islandSpeed = convertIntToAlertSpeed(config.alertSpeeds.islandSpeed);
        if (!IslandAlertTracker.getAlertSpeed().name().equals(islandSpeed)) {
            IslandAlertTracker.setAlertSpeed(AlertSpeedConfig.AlertSpeed.valueOf(islandSpeed));
        }

        String disconnectSpeed = convertIntToAlertSpeed(config.alertSpeeds.disconnectSpeed);
        if (!DisconnectAlertTracker.getAlertSpeed().name().equals(disconnectSpeed)) {
            DisconnectAlertTracker.setAlertSpeed(AlertSpeedConfig.AlertSpeed.valueOf(disconnectSpeed));
        }

        // Chat relay
        if (!ChatRelayConfig.getChatWebhookUrl().equals(config.chatRelay.chatWebhookUrl)) {
            ChatRelayConfig.setChatWebhookUrl(config.chatRelay.chatWebhookUrl);
        }
        if (ChatRelayConfig.getPollPort() != config.chatRelay.pollPort) {
            ChatRelayConfig.setPollPort(config.chatRelay.pollPort);
        }
        if (!ChatRelayConfig.getPollSecret().equals(config.chatRelay.pollSecret)) {
            ChatRelayConfig.setPollSecret(config.chatRelay.pollSecret);
        }

        saveToFile();
    }

    /**
     * Convert alert speed string to dropdown index
     */
    private static int convertAlertSpeedToInt(String speed) {
        switch (speed) {
            case "OFF": return 0;
            case "FAST": return 1;
            case "NORMAL": return 2;
            case "SLOW": return 3;
            default: return 2; // Default to NORMAL
        }
    }

    /**
     * Convert dropdown index to alert speed string
     */
    private static String convertIntToAlertSpeed(int index) {
        switch (index) {
            case 0: return "OFF";
            case 1: return "FAST";
            case 2: return "NORMAL";
            case 3: return "SLOW";
            default: return "NORMAL";
        }
    }
}
