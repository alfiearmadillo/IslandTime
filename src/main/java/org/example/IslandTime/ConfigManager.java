package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.notenoughupdates.moulconfig.processor.BuiltinMoulConfigGuis;
import io.github.notenoughupdates.moulconfig.processor.ConfigProcessorDriver;
import io.github.notenoughupdates.moulconfig.processor.MoulConfigProcessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

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
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonObject()) ProfitConfigMigration.apply(root.getAsJsonObject());
            IslandTimeConfig loaded = gson.fromJson(root, IslandTimeConfig.class);
            if (loaded == null) return new IslandTimeConfig();
            migrateLegacyFuelAlertRepeat(root, loaded);
            return loaded;
        } catch (Exception e) {
            System.err.println("Failed to load MoulConfig, using defaults: " + e.getMessage());
            return new IslandTimeConfig();
        }
    }

    /**
     * The "repeat until" fuel alert setting used to be an on/off toggle (fuelAlertRepeatUntilSacksEmptied).
     * Carry an "off" over to the dropdown's "Don't Repeat", so it doesn't start repeating again.
     */
    private static void migrateLegacyFuelAlertRepeat(JsonElement root, IslandTimeConfig loaded) {
        if (!root.isJsonObject()) return;
        JsonElement speeds = root.getAsJsonObject().get("alertSpeeds");
        if (speeds == null || !speeds.isJsonObject()) return;
        JsonObject alertSpeeds = speeds.getAsJsonObject();
        if (alertSpeeds.has("fuelAlertRepeatMode")) return;

        JsonElement legacy = alertSpeeds.get("fuelAlertRepeatUntilSacksEmptied");
        if (legacy != null && legacy.isJsonPrimitive() && legacy.getAsJsonPrimitive().isBoolean()
                && !legacy.getAsBoolean()) {
            loaded.alertSpeeds.fuelAlertRepeatMode = 2; // Don't Repeat
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
    public static void tick(Minecraft client) {
        if (client == null) return;

        applyConfiguredFuelDefaults();

        Screen currentScreen = client.screen;

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

        // Island Time tracking
        config.islandTime.enabled = TrackingToggleConfig.isIslandTimeEnabled();

        // Fuel Cycles tracking
        config.fuelCycles.enabled = TrackingToggleConfig.isFuelCycleEnabled();
        config.fuelCycles.mobKillCoinsAt100PercentString = String.valueOf(FuelCycleTracker.getMobKillCoinsAt100Baseline());
        FuelCycleTracker.DayRecord today = FuelCycleTracker.getDayRecord(FuelCycleTracker.getCurrentCycleDateKey());
        // Keep this as the persistent default. It must not be replaced with the current
        // record's value when a new fuel-day starts or when MoulConfig is reopened.
        // Blank = "use the auto-tracked value from sack chat messages" (see SackIncomeTracker).
        config.fuelCycles.todayGabagoolAmountString = (today != null && today.manualGabagoolAmount != null)
                ? String.valueOf(today.manualGabagoolAmount)
                : "";

        // Favourite GUI tracking
        config.favouriteGui.enabled = FavouriteGuiManager.isTrackingEnabled();

        // Discord webhooks
        String webhook = DiscordWebhookManager.getWebhook();
        config.discord.webhookUrl = webhook != null ? webhook : "";

        String userId = DiscordWebhookManager.getDiscordUserId();
        config.discord.discordUserId = userId != null ? userId : "";

        // Alert speeds
        config.alertSpeeds.trackedItemSpeed = convertAlertSpeedToInt(ItemTracker.getAlertSpeed().name());
        config.alertSpeeds.islandSpeed = convertAlertSpeedToInt(IslandAlertTracker.getAlertSpeed().name());
        config.alertSpeeds.disconnectSpeed = convertAlertSpeedToInt(DisconnectAlertTracker.getAlertSpeed().name());
        config.alertSpeeds.fuelExpiredSpeed = convertAlertSpeedToInt(FuelExpiredAlertTracker.getAlertSpeed().name());

        // Item tracking UUID (editable)
        String uuid = ItemTracker.getTrackedUuid();
        config.itemTracking.trackedUuid = uuid != null ? uuid : "";

        // Chat relay
        config.chatRelay.chatWebhookUrl = ChatRelayConfig.getChatWebhookUrl();
        config.chatRelay.pollPortString = String.valueOf(ChatRelayConfig.getPollPort());
        config.chatRelay.pollSecret = ChatRelayConfig.getPollSecret();

        // Message toggles
        config.messages.minionFuelMessages  = MinionFuelTracker.isShowFuelMessages();
        config.messages.islandLeaveMessages = IslandPlayerTracker.isShowLeaveMessages();

        // Misc
        config.misc.showHiddenTooltips = TooltipOverrideConfig.isShowHiddenTooltips();
        config.misc.keepResourcePack = ResourcePackConfig.isEnabled();
        config.profitCalculators.agingTimeBagSlots = AgingItemsConfig.getTimeBagSlots();
        config.profitCalculators.agingInventorySlots = AgingItemsConfig.getInventorySlots();

        saveToFile();
    }

    /**
     * Apply MoulConfig values to existing config systems
     */
    public static void syncToExistingConfigs() {
        if (config == null) return;

        // Island Time tracking
        if (TrackingToggleConfig.isIslandTimeEnabled() != config.islandTime.enabled) {
            TrackingToggleConfig.setIslandTimeEnabled(config.islandTime.enabled);
        }

        // Fuel Cycles tracking
        if (TrackingToggleConfig.isFuelCycleEnabled() != config.fuelCycles.enabled) {
            TrackingToggleConfig.setFuelCycleEnabled(config.fuelCycles.enabled);
        }
        try {
            double baseline = Double.parseDouble(config.fuelCycles.mobKillCoinsAt100PercentString.trim());
            if (baseline != FuelCycleTracker.getMobKillCoinsAt100Baseline()) {
                FuelCycleTracker.setMobKillCoinsAt100Baseline(baseline);
            }
        } catch (NumberFormatException ignored) {
            // Invalid number, keep existing value
        }
        String todayDate = FuelCycleTracker.getCurrentCycleDateKey();
        applyConfiguredFuelDefaults();
        try {
            String amountText = config.fuelCycles.todayGabagoolAmountString.trim();
            FuelCycleTracker.DayRecord today = FuelCycleTracker.getDayRecord(todayDate);
            Double currentManual = today != null ? today.manualGabagoolAmount : null;
            if (amountText.isEmpty()) {
                if (currentManual != null) {
                    FuelCycleTracker.clearManualGabagoolAmount(todayDate);
                }
            } else {
                double parsedAmount = Double.parseDouble(amountText);
                // A leading "+"/"-" means "adjust the auto-tracked amount by this much"
                // (e.g. "+100000" to correct for a missed sack message) rather than "set the
                // absolute total to this value" — gabagool amounts are never legitimately
                // negative, so the sign is unambiguous.
                double autoAmount = today != null ? today.gabagoolAmountAuto : 0.0;
                double amount = (amountText.startsWith("+") || amountText.startsWith("-"))
                        ? Math.max(0.0, autoAmount + parsedAmount)
                        : Math.max(0.0, parsedAmount);
                if (currentManual == null || currentManual != amount) {
                    FuelCycleTracker.setGabagoolAmount(todayDate, amount);
                }
            }
        } catch (NumberFormatException ignored) {
            // Invalid number, keep existing value
        }

        // Favourite GUI tracking
        if (FavouriteGuiManager.isTrackingEnabled() != config.favouriteGui.enabled) {
            FavouriteGuiManager.setTrackingEnabled(config.favouriteGui.enabled);
        }

        // Discord webhooks
        if (!DiscordWebhookManager.getWebhook().equals(config.discord.webhookUrl)) {
            DiscordWebhookManager.setWebhook(config.discord.webhookUrl);
        }
        if (!DiscordWebhookManager.getDiscordUserId().equals(config.discord.discordUserId)) {
            DiscordWebhookManager.setDiscordUserId(config.discord.discordUserId);
        }

        // Alert speeds
        String trackedItemSpeed = convertIntToAlertSpeed(config.alertSpeeds.trackedItemSpeed);
        if (!ItemTracker.getAlertSpeed().name().equals(trackedItemSpeed)) {
            ItemTracker.setAlertSpeed(ItemTracker.AlertSpeed.valueOf(trackedItemSpeed));
        }

        String islandSpeed = convertIntToAlertSpeed(config.alertSpeeds.islandSpeed);
        if (!IslandAlertTracker.getAlertSpeed().name().equals(islandSpeed)) {
            IslandAlertTracker.setAlertSpeed(IslandAlertTracker.AlertSpeed.valueOf(islandSpeed));
        }

        String disconnectSpeed = convertIntToAlertSpeed(config.alertSpeeds.disconnectSpeed);
        if (!DisconnectAlertTracker.getAlertSpeed().name().equals(disconnectSpeed)) {
            DisconnectAlertTracker.setAlertSpeed(DisconnectAlertTracker.AlertSpeed.valueOf(disconnectSpeed));
        }

        String fuelExpiredSpeed = convertIntToAlertSpeed(config.alertSpeeds.fuelExpiredSpeed);
        if (!FuelExpiredAlertTracker.getAlertSpeed().name().equals(fuelExpiredSpeed)) {
            FuelExpiredAlertTracker.setAlertSpeed(FuelExpiredAlertTracker.AlertSpeed.valueOf(fuelExpiredSpeed));
        }

        // Item tracking UUID (manual editing)
        String currentUuid = ItemTracker.getTrackedUuid();
        String configUuid = config.itemTracking.trackedUuid != null ? config.itemTracking.trackedUuid.trim() : "";
        String currentUuidTrimmed = currentUuid != null ? currentUuid.trim() : "";
        if (!currentUuidTrimmed.equals(configUuid)) {
            ItemTracker.setTrackedUuid(configUuid);
        }

        // Chat relay
        if (!ChatRelayConfig.getChatWebhookUrl().equals(config.chatRelay.chatWebhookUrl)) {
            ChatRelayConfig.setChatWebhookUrl(config.chatRelay.chatWebhookUrl);
        }
        try {
            int portValue = Integer.parseInt(config.chatRelay.pollPortString);
            if (portValue >= 1 && portValue <= 65535 && ChatRelayConfig.getPollPort() != portValue) {
                ChatRelayConfig.setPollPort(portValue);
            }
        } catch (NumberFormatException e) {
            // Invalid port number, keep existing value
        }
        if (!ChatRelayConfig.getPollSecret().equals(config.chatRelay.pollSecret)) {
            ChatRelayConfig.setPollSecret(config.chatRelay.pollSecret);
        }

        // Message toggles
        if (MinionFuelTracker.isShowFuelMessages() != config.messages.minionFuelMessages) {
            MinionFuelTracker.setShowFuelMessages(config.messages.minionFuelMessages);
        }
        if (IslandPlayerTracker.isShowLeaveMessages() != config.messages.islandLeaveMessages) {
            IslandPlayerTracker.setShowLeaveMessages(config.messages.islandLeaveMessages);
        }

        // Misc — tooltip override
        if (TooltipOverrideConfig.isShowHiddenTooltips() != config.misc.showHiddenTooltips) {
            TooltipOverrideConfig.setShowHiddenTooltips(config.misc.showHiddenTooltips);
        }

        // Misc — keep resource pack
        if (ResourcePackConfig.isEnabled() != config.misc.keepResourcePack) {
            ResourcePackConfig.setEnabled(config.misc.keepResourcePack);
            if (!config.misc.keepResourcePack) {
                ResourcePackManager.reset();
            }
        }

        // Misc — aging items slots
        if (AgingItemsConfig.getTimeBagSlots() != config.profitCalculators.agingTimeBagSlots) {
            AgingItemsConfig.setTimeBagSlots(config.profitCalculators.agingTimeBagSlots);
        }
        if (AgingItemsConfig.getInventorySlots() != config.profitCalculators.agingInventorySlots) {
            AgingItemsConfig.setInventorySlots(config.profitCalculators.agingInventorySlots);
        }

        saveToFile();
    }

    /** Applies defaults to active/queued fuel days even when the config screen is not opened. */
    private static void applyConfiguredFuelDefaults() {
        if (config == null) return;
        String priceText = config.fuelCycles.todayGabagoolPriceString;
        double price = Double.NaN;
        if (priceText != null && !priceText.isBlank()) {
            try {
                price = Double.parseDouble(priceText.trim());
            } catch (NumberFormatException ignored) {
                // Invalid number, treat as no default
            }
        }
        FuelCycleTracker.setDefaultGabagoolPrice(price);
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
