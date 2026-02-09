package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tracks a specific item by UUID and monitors its stats_book value
 * Alerts if item is missing or stats_book doesn't increase each minute
 * Now tracks regardless of island status - stats shouldn't increase off island anyway
 */
public class ItemTracker {

    private static final String CONFIG_FILE = "config/islandtime_tracked_item.txt";
    private static final long CHECK_INTERVAL_MS = 60 * 1000; // 1 minute
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a");

    // Alert speed settings
    public enum AlertSpeed {
        OFF(0, 0),           // No alerts
        FAST(60_000, 5 * 60_000),      // 1 minute, then every 5 minutes
        NORMAL(10 * 60_000, 30 * 60_000),  // 10 minutes, then every 30 minutes
        SLOW(60 * 60_000, 3 * 60 * 60_000);  // 1 hour, then every 3 hours

        public final long firstAlertMs;
        public final long repeatAlertMs;

        AlertSpeed(long firstAlertMs, long repeatAlertMs) {
            this.firstAlertMs = firstAlertMs;
            this.repeatAlertMs = repeatAlertMs;
        }
    }

    private static AlertSpeed trackedAlertSpeed = AlertSpeed.NORMAL;

    // Tracked item configuration
    private static String trackedUuid = null;
    private static String trackedItemName = null; // Store for better alerts

    // Tracking state
    private static long lastCheckTime = 0;
    private static long lastStatsValue = 0;
    private static long lastIncrementTime = 0;
    private static long lastAlertTime = 0;
    private static boolean hasAlerted = false;

    // Expose this for FuelCycleTracker to use
    private static boolean statsIncreasingLastCheck = false;

    /**
     * Returns whether stats were increasing during the last check
     * Thread-safe read of volatile boolean
     */
    public static boolean areStatsIncreasing() {
        return statsIncreasingLastCheck;
    }

    /**
     * Set the tracked item from the player's main hand
     */
    public static boolean setTrackedItemFromMainHand(MinecraftClient client) {
        if (client.player == null) {
            return false;
        }

        ItemStack mainHandItem = client.player.getMainHandStack();
        if (mainHandItem.isEmpty()) {
            return false;
        }

        // Get custom data by iterating through components (1.21.10 compatible)
        NbtCompound nbt = null;
        for (var entry : mainHandItem.getComponents()) {
            if (entry.type() == DataComponentTypes.CUSTOM_DATA && entry.value() instanceof NbtComponent nbtComponent) {
                if (!nbtComponent.isEmpty()) {
                    nbt = nbtComponent.copyNbt();
                    break;
                }
            }
        }

        if (nbt == null) {
            return false;
        }

        // Debug: print all keys
        System.out.println("[ItemTracker] Custom data keys: " + nbt.getKeys());

        // Check for required fields
        if (!nbt.contains("uuid")) {
            System.out.println("[ItemTracker] Item missing 'uuid' field");
            return false;
        }

        if (!nbt.contains("stats_book")) {
            System.out.println("[ItemTracker] Item missing 'stats_book' field");
            return false;
        }

        System.out.println("[ItemTracker] Item has both uuid and stats_book!");

        // Try to read the values using get() and toString()
        try {
            // Get UUID using get() then toString()
            String uuid = nbt.get("uuid").toString();
            // Remove quotes if present (NbtString includes quotes)
            if (uuid.startsWith("\"") && uuid.endsWith("\"")) {
                uuid = uuid.substring(1, uuid.length() - 1);
            }

            // Get stats_book - NbtLong.toString() gives the number
            String statsBookStr = nbt.get("stats_book").toString();
            // Remove 'L' suffix if present (e.g., "12345L" -> "12345")
            if (statsBookStr.endsWith("L")) {
                statsBookStr = statsBookStr.substring(0, statsBookStr.length() - 1);
            }
            long statsBook = Long.parseLong(statsBookStr);

            System.out.println("[ItemTracker] UUID: " + uuid + ", stats: " + statsBook);

            // Store the tracking info
            trackedUuid = uuid;
            trackedItemName = mainHandItem.getName().getString();

            // Initialize tracking state
            lastStatsValue = statsBook;
            lastIncrementTime = System.currentTimeMillis();
            lastCheckTime = System.currentTimeMillis();
            lastAlertTime = 0;
            hasAlerted = false;
            statsIncreasingLastCheck = false;

            save();
            return true;

        } catch (Exception e) {
            System.err.println("[ItemTracker] Error reading item data: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Clear the tracked item
     */
    public static void clearTrackedItem() {
        trackedUuid = null;
        trackedItemName = null;
        lastStatsValue = 0;
        lastIncrementTime = 0;
        lastCheckTime = 0;
        lastAlertTime = 0;
        hasAlerted = false;
        statsIncreasingLastCheck = false;
        save();
    }

    /**
     * Get the UUID of the currently tracked item
     */
    public static String getTrackedUuid() {
        return trackedUuid;
    }

    /**
     * Get the name of the currently tracked item
     */
    public static String getTrackedItemName() {
        return trackedItemName;
    }

    /**
     * Check if an item is currently being tracked
     */
    public static boolean isTracking() {
        return trackedUuid != null;
    }

    /**
     * Get the current alert speed
     */
    public static AlertSpeed getAlertSpeed() {
        return trackedAlertSpeed;
    }

    /**
     * Set the alert speed
     */
    public static void setAlertSpeed(AlertSpeed speed) {
        trackedAlertSpeed = speed;

        // Reset alert state when changing speed
        lastAlertTime = 0;
        hasAlerted = false;

        save();
    }

    /**
     * Main tick function - checks item status regardless of island status
     */
    public static void tick(MinecraftClient client) {
        if (!isTracking()) {
            statsIncreasingLastCheck = false;
            return;
        }

        if (client.player == null) {
            statsIncreasingLastCheck = false;
            return;
        }

        long now = System.currentTimeMillis();

        // Only check once per minute
        if (now - lastCheckTime < CHECK_INTERVAL_MS) {
            return;
        }

        lastCheckTime = now;

        // Find the tracked item in inventory
        ItemStack trackedItem = findTrackedItemInInventory(client);

        if (trackedItem == null) {
            // Item is missing
            statsIncreasingLastCheck = false;
            handleMissingItem(now);
            return;
        }

        // Item exists - check if stats increased
        long currentStats = getStatsBookValue(trackedItem);

        if (currentStats > lastStatsValue) {
            // Stats increased! Reset everything
            lastStatsValue = currentStats;
            lastIncrementTime = now;
            lastAlertTime = 0;
            hasAlerted = false;
            statsIncreasingLastCheck = true;
        } else {
            // Stats didn't increase
            statsIncreasingLastCheck = false;
            handleStatsNotIncreasing(now);
        }
    }

    /**
     * Find the tracked item in the player's inventory
     */
    private static ItemStack findTrackedItemInInventory(MinecraftClient client) {
        if (client.player == null) return null;

        // Check all inventory slots using the public size() and getStack() methods
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (hasMatchingUuid(stack)) return stack;
        }

        return null;
    }

    /**
     * Check if an item has the tracked UUID
     */
    private static boolean hasMatchingUuid(ItemStack stack) {
        if (stack.isEmpty()) return false;

        // Get custom data
        NbtCompound nbt = null;
        for (var entry : stack.getComponents()) {
            if (entry.type() == DataComponentTypes.CUSTOM_DATA && entry.value() instanceof NbtComponent nbtComponent) {
                if (!nbtComponent.isEmpty()) {
                    nbt = nbtComponent.copyNbt();
                    break;
                }
            }
        }

        if (nbt == null || !nbt.contains("uuid")) {
            return false;
        }

        String uuid = nbt.get("uuid").toString();
        // Remove quotes if present
        if (uuid.startsWith("\"") && uuid.endsWith("\"")) {
            uuid = uuid.substring(1, uuid.length() - 1);
        }
        if (uuid.equals(trackedUuid)) {
            return true;
        }

        return false;
    }

    /**
     * Get the stats_book value from an item
     */
    private static long getStatsBookValue(ItemStack stack) {
        // Get custom data by iterating through components
        for (var entry : stack.getComponents()) {
            if (entry.type() == DataComponentTypes.CUSTOM_DATA && entry.value() instanceof NbtComponent nbtComponent) {
                if (!nbtComponent.isEmpty()) {
                    NbtCompound nbt = nbtComponent.copyNbt();
                    if (nbt.contains("stats_book")) {
                        String statsBookStr = nbt.get("stats_book").toString();
                        // Remove 'L' suffix if present
                        if (statsBookStr.endsWith("L")) {
                            statsBookStr = statsBookStr.substring(0, statsBookStr.length() - 1);
                        }
                        return Long.parseLong(statsBookStr);
                    }
                }
            }
        }
        return 0;
    }

    /**
     * Handle case where tracked item is missing
     */
    private static void handleMissingItem(long now) {
        // Don't alert if alerts are disabled
        if (trackedAlertSpeed == AlertSpeed.OFF) {
            return;
        }

        long timeSinceIncrement = now - lastIncrementTime;

        // Check if we should send first alert
        if (!hasAlerted && timeSinceIncrement >= trackedAlertSpeed.firstAlertMs) {
            sendAlert("⚠️ **Item Missing**\n" +
                    "Your tracked item (" + trackedItemName + ") is not in your inventory!");
            hasAlerted = true;
            lastAlertTime = now;
        }
        // Check if we should send repeat alert
        else if (hasAlerted && (now - lastAlertTime) >= trackedAlertSpeed.repeatAlertMs) {
            sendAlert("⚠️ **Item Still Missing**\n" +
                    "Your tracked item (" + trackedItemName + ") is still not in your inventory!\n" +
                    "Time missing: " + formatDuration(timeSinceIncrement));
            lastAlertTime = now;
        }
    }

    /**
     * Handle case where stats didn't increase
     */
    private static void handleStatsNotIncreasing(long now) {
        // Don't alert if alerts are disabled
        if (trackedAlertSpeed == AlertSpeed.OFF) {
            return;
        }

        long timeSinceIncrement = now - lastIncrementTime;

        // Check if we should send first alert
        if (!hasAlerted && timeSinceIncrement >= trackedAlertSpeed.firstAlertMs) {
            sendAlert("⚠️ **Stats Not Increasing**\n" +
                    "Your tracked item (" + trackedItemName + ") stats haven't increased in " +
                    formatDuration(timeSinceIncrement) + "!");
            hasAlerted = true;
            lastAlertTime = now;
        }
        // Check if we should send repeat alert
        else if (hasAlerted && (now - lastAlertTime) >= trackedAlertSpeed.repeatAlertMs) {
            sendAlert("⚠️ **Stats Still Not Increasing**\n" +
                    "Your tracked item (" + trackedItemName + ") stats still haven't increased!\n" +
                    "Time without increase: " + formatDuration(timeSinceIncrement));
            lastAlertTime = now;
        }
    }

    /**
     * Send an alert via Discord webhook
     */
    private static void sendAlert(String message) {
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String fullMessage = message + "\nTime: " + time;

        // Add user ping if configured
        String userId = DiscordWebhookManager.getDiscordUserId();
        if (userId != null && !userId.isEmpty()) {
            fullMessage = "<@" + userId + ">\n" + fullMessage;
        }

        DiscordWebhookManager.sendCustomMessage(fullMessage);
    }

    /**
     * Format duration in milliseconds to human readable string
     */
    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return days + "d " + (hours % 24) + "h";
        } else if (hours > 0) {
            return hours + "h " + (minutes % 60) + "m";
        } else if (minutes > 0) {
            return minutes + "m";
        } else {
            return seconds + "s";
        }
    }

    /**
     * Save tracking configuration to disk
     */
    public static void save() {
        try {
            Path configFile = Paths.get(CONFIG_FILE);
            Files.createDirectories(configFile.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(configFile)) {
                writer.write("UUID\n");
                writer.write((trackedUuid != null ? trackedUuid : "") + "\n");
                writer.write("NAME\n");
                writer.write((trackedItemName != null ? trackedItemName : "") + "\n");
                writer.write("LAST_STATS\n");
                writer.write(lastStatsValue + "\n");
                writer.write("LAST_INCREMENT_TIME\n");
                writer.write(lastIncrementTime + "\n");
                writer.write("TRACKED_ALERT_SPEED\n");
                writer.write(trackedAlertSpeed.name() + "\n");
            }
        } catch (IOException e) {
            System.err.println("Failed to save item tracker config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load tracking configuration from disk
     */
    public static void load() {
        Path configFile = Paths.get(CONFIG_FILE);
        if (!Files.exists(configFile)) {
            return;
        }

        try {
            try (BufferedReader reader = Files.newBufferedReader(configFile)) {
                String line;
                String section = "";

                while ((line = reader.readLine()) != null) {
                    if (line.equals("UUID")) {
                        section = "UUID";
                        continue;
                    } else if (line.equals("NAME")) {
                        section = "NAME";
                        continue;
                    } else if (line.equals("LAST_STATS")) {
                        section = "LAST_STATS";
                        continue;
                    } else if (line.equals("LAST_INCREMENT_TIME")) {
                        section = "LAST_INCREMENT_TIME";
                        continue;
                    } else if (line.equals("ALERT_SPEED") || line.equals("TRACKED_ALERT_SPEED")) {
                        // Support both old and new names for backwards compatibility
                        section = "TRACKED_ALERT_SPEED";
                        continue;
                    }

                    if (section.equals("UUID") && !line.isEmpty()) {
                        trackedUuid = line;
                        section = "";
                    } else if (section.equals("NAME") && !line.isEmpty()) {
                        trackedItemName = line;
                        section = "";
                    } else if (section.equals("LAST_STATS") && !line.isEmpty()) {
                        try {
                            lastStatsValue = Long.parseLong(line);
                        } catch (NumberFormatException ignored) {
                        }
                        section = "";
                    } else if (section.equals("LAST_INCREMENT_TIME") && !line.isEmpty()) {
                        try {
                            lastIncrementTime = Long.parseLong(line);
                        } catch (NumberFormatException ignored) {
                        }
                        section = "";
                    } else if (section.equals("TRACKED_ALERT_SPEED") && !line.isEmpty()) {
                        try {
                            trackedAlertSpeed = AlertSpeed.valueOf(line);
                        } catch (IllegalArgumentException ignored) {
                        }
                        section = "";
                    }
                }

                // Initialize tracking state
                lastCheckTime = System.currentTimeMillis();
                lastAlertTime = 0;
                hasAlerted = false;
            }
        } catch (IOException e) {
            System.err.println("Failed to load item tracker config: " + e.getMessage());
            e.printStackTrace();
        }
    }
}