package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/**
 * Manages synchronization between MoulConfig and existing config files.
 * This ensures that changes in the GUI are reflected in the actual mod behavior
 * and that existing commands continue to work.
 */
public class ConfigManager {

    private static IslandTimeConfig config;
    private static Screen lastScreen = null;
    private static boolean configScreenWasOpen = false;

    public static void initialize() {
        config = new IslandTimeConfig();
        config.load();

        // Sync initial values from existing config files to MoulConfig
        syncFromExistingConfigs();
    }

    public static IslandTimeConfig getConfig() {
        return config;
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
            syncToExistingConfigs();
            configScreenWasOpen = false;
        }

        // Track if config screen is currently open
        if (currentScreen != null && currentScreen.getClass().getName().contains("MoulConfigScreen")) {
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
        config.alertSpeeds.trackedItemSpeed = convertAlertSpeed(ItemTracker.getAlertSpeed().name());
        config.alertSpeeds.islandSpeed = convertAlertSpeed(IslandAlertTracker.getAlertSpeed().name());
        config.alertSpeeds.disconnectSpeed = convertAlertSpeed(DisconnectAlertTracker.getAlertSpeed().name());

        // Item tracking (read-only display)
        String uuid = ItemTracker.getTrackedUuid();
        String name = ItemTracker.getTrackedItemName();
        config.itemTracking.trackedUuid = uuid != null ? uuid : "";
        config.itemTracking.trackedItemName = name != null ? name : "None";

        // Chat relay
        config.chatRelay.chatWebhookUrl = ChatRelayConfig.getChatWebhookUrl();
        config.chatRelay.pollPort = ChatRelayConfig.getPollPort();
        config.chatRelay.pollSecret = ChatRelayConfig.getPollSecret();

        config.save();
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
            FavouriteGuiManager.setTrackingEnabled(config.tracking.favouriteGuiEnabled);
        }

        // Webhooks
        String currentWebhook = DiscordWebhookManager.getWebhook();
        String configWebhook = config.webhooksAndAlerts.webhookUrl;
        if (!equals(currentWebhook, configWebhook)) {
            if (configWebhook == null || configWebhook.isEmpty()) {
                DiscordWebhookManager.clearWebhook();
            } else if (configWebhook.startsWith("https://discord.com/api/webhooks/")) {
                DiscordWebhookManager.setWebhook(configWebhook);
            }
        }

        String currentUserId = DiscordWebhookManager.getDiscordUserId();
        String configUserId = config.webhooksAndAlerts.discordUserId;
        if (!equals(currentUserId, configUserId)) {
            if (configUserId == null || configUserId.isEmpty()) {
                DiscordWebhookManager.clearDiscordUserId();
            } else if (configUserId.matches("\\d+")) {
                DiscordWebhookManager.setDiscordUserId(configUserId);
            }
        }

        // Alert speeds
        ItemTracker.setAlertSpeed(convertToTrackerSpeed(config.alertSpeeds.trackedItemSpeed.name(), ItemTracker.AlertSpeed.class));
        IslandAlertTracker.setAlertSpeed(convertToTrackerSpeed(config.alertSpeeds.islandSpeed.name(), IslandAlertTracker.AlertSpeed.class));
        DisconnectAlertTracker.setAlertSpeed(convertToTrackerSpeed(config.alertSpeeds.disconnectSpeed.name(), DisconnectAlertTracker.AlertSpeed.class));

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
    }

    private static IslandTimeConfig.AlertSpeeds.AlertSpeed convertAlertSpeed(String name) {
        try {
            return IslandTimeConfig.AlertSpeeds.AlertSpeed.valueOf(name);
        } catch (IllegalArgumentException e) {
            return IslandTimeConfig.AlertSpeeds.AlertSpeed.NORMAL;
        }
    }

    private static <T extends Enum<T>> T convertToTrackerSpeed(String name, Class<T> enumClass) {
        try {
            return Enum.valueOf(enumClass, name);
        } catch (IllegalArgumentException e) {
            return enumClass.getEnumConstants()[0]; // Return first enum value as default
        }
    }

    private static boolean equals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /**
     * Save both MoulConfig and existing config files
     */
    public static void saveAll() {
        if (config != null) {
            config.save();
        }
        syncToExistingConfigs();
    }
}
