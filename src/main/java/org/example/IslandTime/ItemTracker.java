package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tracks a specific item by UUID and monitors its stats_book value.
 * Alerts if item is missing or stats_book doesn't increase each minute.
 * Tracks regardless of island status — stats shouldn't increase off island anyway.
 */
public class ItemTracker {

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/tracked_item.txt");
    private static final Path CONFIG_TMP  = Paths.get("config/IslandTime/tracked_item.tmp");
    private static final long CHECK_INTERVAL_MS = 60 * 1000;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a");

    public enum AlertSpeed {
        OFF(0, 0),
        FAST(60_000, 5 * 60_000),
        NORMAL(10 * 60_000, 30 * 60_000),
        SLOW(60 * 60_000, 3 * 60 * 60_000);

        public final long firstAlertMs;
        public final long repeatAlertMs;

        AlertSpeed(long firstAlertMs, long repeatAlertMs) {
            this.firstAlertMs  = firstAlertMs;
            this.repeatAlertMs = repeatAlertMs;
        }
    }

    private static AlertSpeed trackedAlertSpeed = AlertSpeed.NORMAL;

    private static String trackedUuid     = null;
    private static String trackedItemName = null;

    private static long    lastCheckTime           = 0;
    private static long    lastStatsValue          = 0;
    private static long    lastIncrementTime       = 0;
    private static long    lastAlertTime           = 0;
    private static boolean hasAlerted              = false;
    private static boolean statsIncreasingLastCheck = false;

    public static boolean areStatsIncreasing() { return statsIncreasingLastCheck; }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    public static boolean setTrackedItemFromMainHand(Minecraft client) {
        if (client.player == null) return false;
        ItemStack mainHandItem = client.player.getMainHandItem();
        if (mainHandItem.isEmpty()) return false;

        CompoundTag nbt = null;
        for (var entry : mainHandItem.getComponents()) {
            if (entry.type() == DataComponents.CUSTOM_DATA && entry.value() instanceof CustomData nbtComponent) {
                if (!nbtComponent.isEmpty()) { nbt = nbtComponent.copyTag(); break; }
            }
        }
        if (nbt == null) return false;

        System.out.println("[ItemTracker] Custom data keys: " + nbt.keySet());
        if (!nbt.contains("uuid"))       { System.out.println("[ItemTracker] Item missing 'uuid'");       return false; }
        if (!nbt.contains("stats_book")) { System.out.println("[ItemTracker] Item missing 'stats_book'"); return false; }

        try {
            String uuid = nbt.get("uuid").toString();
            if (uuid.startsWith("\"") && uuid.endsWith("\"")) uuid = uuid.substring(1, uuid.length() - 1);

            String statsBookStr = nbt.get("stats_book").toString();
            if (statsBookStr.endsWith("L")) statsBookStr = statsBookStr.substring(0, statsBookStr.length() - 1);
            long statsBook = Long.parseLong(statsBookStr);

            trackedUuid     = uuid;
            trackedItemName = mainHandItem.getHoverName().getString();
            lastStatsValue          = statsBook;
            lastIncrementTime       = System.currentTimeMillis();
            lastCheckTime           = System.currentTimeMillis();
            lastAlertTime           = 0;
            hasAlerted              = false;
            statsIncreasingLastCheck = false;

            save();
            return true;
        } catch (Exception e) {
            System.err.println("[ItemTracker] Error reading item data: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static void clearTrackedItem() {
        trackedUuid     = null;
        trackedItemName = null;
        lastStatsValue          = 0;
        lastIncrementTime       = 0;
        lastCheckTime           = 0;
        lastAlertTime           = 0;
        hasAlerted              = false;
        statsIncreasingLastCheck = false;
        save();
    }

    public static String    getTrackedUuid()     { return trackedUuid; }
    public static String    getTrackedItemName() { return trackedItemName; }
    public static boolean   isTracking()         { return trackedUuid != null; }
    public static AlertSpeed getAlertSpeed()     { return trackedAlertSpeed; }

    public static void setTrackedUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) {
            clearTrackedItem();
        } else {
            trackedUuid = uuid.trim();
            trackedItemName = "Manually Set Item";
            lastStatsValue = 0;
            lastIncrementTime = System.currentTimeMillis();
            lastCheckTime = System.currentTimeMillis();
            lastAlertTime = 0;
            hasAlerted = false;
            statsIncreasingLastCheck = false;
            save();
        }
    }

    public static void setAlertSpeed(AlertSpeed speed) {
        trackedAlertSpeed = speed;
        lastAlertTime = 0;
        hasAlerted    = false;
        save();
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public static void tick(Minecraft client) {
        if (!isTracking() || client.player == null) {
            statsIncreasingLastCheck = false;
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_INTERVAL_MS) return;
        lastCheckTime = now;

        // Search inventory for tracked item
        ItemStack tracked = findTrackedItem(client);

        if (tracked == null) {
            statsIncreasingLastCheck = false;
            handleMissingItem(now);
            return;
        }

        long currentStats = getStatsBookValue(tracked);

        if (currentStats > lastStatsValue) {
            statsIncreasingLastCheck = true;
            lastStatsValue    = currentStats;
            lastIncrementTime = now;
            hasAlerted        = false;
            lastAlertTime     = 0;
        } else {
            statsIncreasingLastCheck = false;
            handleStatsNotIncreasing(now);
        }
    }

    private static ItemStack findTrackedItem(Minecraft client) {
        if (client.player == null) return null;
        for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && isTrackedItem(stack)) return stack;
        }
        return null;
    }

    private static boolean isTrackedItem(ItemStack stack) {
        for (var entry : stack.getComponents()) {
            if (entry.type() == DataComponents.CUSTOM_DATA && entry.value() instanceof CustomData nbtComponent) {
                if (!nbtComponent.isEmpty()) {
                    CompoundTag nbt = nbtComponent.copyTag();
                    if (nbt.contains("uuid")) {
                        String uuid = nbt.get("uuid").toString();
                        if (uuid.startsWith("\"") && uuid.endsWith("\""))
                            uuid = uuid.substring(1, uuid.length() - 1);
                        if (uuid.equals(trackedUuid)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static long getStatsBookValue(ItemStack stack) {
        for (var entry : stack.getComponents()) {
            if (entry.type() == DataComponents.CUSTOM_DATA && entry.value() instanceof CustomData nbtComponent) {
                if (!nbtComponent.isEmpty()) {
                    CompoundTag nbt = nbtComponent.copyTag();
                    if (nbt.contains("stats_book")) {
                        String s = nbt.get("stats_book").toString();
                        if (s.endsWith("L")) s = s.substring(0, s.length() - 1);
                        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Alerts
    // -------------------------------------------------------------------------

    private static void handleMissingItem(long now) {
        if (trackedAlertSpeed == AlertSpeed.OFF) return;
        long timeSince = now - lastIncrementTime;
        if (!hasAlerted && timeSince >= trackedAlertSpeed.firstAlertMs) {
            sendAlert("⚠️ **Item Missing**\nYour tracked item (" + trackedItemName + ") is not in your inventory!");
            hasAlerted    = true;
            lastAlertTime = now;
        } else if (hasAlerted && (now - lastAlertTime) >= trackedAlertSpeed.repeatAlertMs) {
            sendAlert("⚠️ **Item Still Missing**\nYour tracked item (" + trackedItemName + ") is still not in your inventory!\nTime missing: " + formatDuration(timeSince));
            lastAlertTime = now;
        }
    }

    private static void handleStatsNotIncreasing(long now) {
        if (trackedAlertSpeed == AlertSpeed.OFF) return;
        long timeSince = now - lastIncrementTime;
        if (!hasAlerted && timeSince >= trackedAlertSpeed.firstAlertMs) {
            sendAlert("⚠️ **Stats Not Increasing**\nYour tracked item (" + trackedItemName + ") stats haven't increased in " + formatDuration(timeSince) + "!");
            hasAlerted    = true;
            lastAlertTime = now;
        } else if (hasAlerted && (now - lastAlertTime) >= trackedAlertSpeed.repeatAlertMs) {
            sendAlert("⚠️ **Stats Still Not Increasing**\nYour tracked item (" + trackedItemName + ") stats still haven't increased!\nTime without increase: " + formatDuration(timeSince));
            lastAlertTime = now;
        }
    }

    private static void sendAlert(String message) {
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String fullMessage = message + "\nTime: " + time;
        String userId = DiscordWebhookManager.getDiscordUserId();
        if (userId != null && !userId.isEmpty()) fullMessage = "<@" + userId + ">\n" + fullMessage;
        DiscordWebhookManager.sendCustomMessage(fullMessage);
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours   = minutes / 60;
        long days    = hours   / 24;
        if (days > 0)    return days    + "d " + (hours   % 24) + "h";
        if (hours > 0)   return hours   + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m";
        return seconds + "s";
    }

    // -------------------------------------------------------------------------
    // Persistence — atomic write via tmp → rename
    // -------------------------------------------------------------------------

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_TMP)) {
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
            Files.move(CONFIG_TMP, CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[ItemTracker] Save failed: " + e.getMessage());
            e.printStackTrace();
            try { Files.deleteIfExists(CONFIG_TMP); } catch (IOException ignored) {}
        }
    }

    public static void load() {
        if (!Files.exists(CONFIG_FILE) && Files.exists(CONFIG_TMP)) {
            try {
                Files.move(CONFIG_TMP, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[ItemTracker] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[ItemTracker] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(CONFIG_FILE)) return;

        try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE)) {
            String line;
            String section = "";
            while ((line = reader.readLine()) != null) {
                if      (line.equals("UUID"))                                        { section = "UUID";                continue; }
                else if (line.equals("NAME"))                                        { section = "NAME";                continue; }
                else if (line.equals("LAST_STATS"))                                  { section = "LAST_STATS";          continue; }
                else if (line.equals("LAST_INCREMENT_TIME"))                         { section = "LAST_INCREMENT_TIME"; continue; }
                else if (line.equals("ALERT_SPEED") || line.equals("TRACKED_ALERT_SPEED")) { section = "TRACKED_ALERT_SPEED"; continue; }

                if (section.equals("UUID") && !line.isEmpty()) {
                    trackedUuid = line; section = "";
                } else if (section.equals("NAME") && !line.isEmpty()) {
                    trackedItemName = line; section = "";
                } else if (section.equals("LAST_STATS") && !line.isEmpty()) {
                    try { lastStatsValue = Long.parseLong(line); } catch (NumberFormatException ignored) {}
                    section = "";
                } else if (section.equals("LAST_INCREMENT_TIME") && !line.isEmpty()) {
                    try { lastIncrementTime = Long.parseLong(line); } catch (NumberFormatException ignored) {}
                    section = "";
                } else if (section.equals("TRACKED_ALERT_SPEED") && !line.isEmpty()) {
                    try { trackedAlertSpeed = AlertSpeed.valueOf(line); } catch (IllegalArgumentException ignored) {}
                    section = "";
                }
            }
            lastCheckTime = System.currentTimeMillis();
            lastAlertTime = 0;
            hasAlerted    = false;
        } catch (IOException e) {
            System.err.println("[ItemTracker] Load failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
