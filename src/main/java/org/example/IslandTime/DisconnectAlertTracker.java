package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tracks connection status and sends Discord alert after a 10-second grace period,
 * then repeats at configured intervals while disconnected.
 * The 10-second delay prevents spurious alerts from brief connection blips.
 */
public class DisconnectAlertTracker {

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/disconnect_alerts.txt");
    private static final Path CONFIG_TMP  = Paths.get("config/IslandTime/disconnect_alerts.tmp");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a");

    /** Grace period before the first alert is sent. */
    private static final long INITIAL_ALERT_DELAY_MS = 10_000;

    /** How often to check connection status (1 second). */
    private static final long CHECK_INTERVAL_MS = 1_000;

    public enum AlertSpeed {
        OFF(0),
        FAST(5 * 60_000),
        NORMAL(30 * 60_000),
        SLOW(3 * 60 * 60_000);

        public final long repeatAlertMs;
        AlertSpeed(long repeatAlertMs) { this.repeatAlertMs = repeatAlertMs; }
    }

    private static AlertSpeed disconnectAlertSpeed = AlertSpeed.NORMAL;

    private static boolean wasConnected      = false;
    private static boolean hasEverConnected  = false;
    private static long    timeDisconnected  = 0;
    private static long    lastAlertSentTime = 0;
    private static boolean hasAlerted        = false;
    private static long    lastCheckTime     = 0;

    public static AlertSpeed getAlertSpeed() { return disconnectAlertSpeed; }

    public static void setAlertSpeed(AlertSpeed speed) {
        disconnectAlertSpeed = speed;
        lastAlertSentTime    = 0;
        hasAlerted           = false;
        save();
    }

    public static void tick(Minecraft client) {
        // Only check once per second for performance
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_INTERVAL_MS) return;
        lastCheckTime = now;

        boolean isConnected = isConnectedToServer(client);

        if (isConnected && !hasEverConnected) { hasEverConnected = true; wasConnected = true; }
        if (!hasEverConnected) return;

        if (isConnected && !wasConnected) {
            wasConnected      = true;
            timeDisconnected  = 0;
            lastAlertSentTime = 0;
            hasAlerted        = false;
            return;
        }
        if (isConnected) { wasConnected = true; return; }

        if (!isConnected && wasConnected) {
            wasConnected      = false;
            timeDisconnected  = System.currentTimeMillis();
            lastAlertSentTime = 0;
            hasAlerted        = false;
            return;
        }

        if (disconnectAlertSpeed == AlertSpeed.OFF) return;

        if (!isConnected && !wasConnected) {
            long disconnectedDuration = now - timeDisconnected;

            if (!hasAlerted && disconnectedDuration >= INITIAL_ALERT_DELAY_MS) {
                sendDisconnectAlert(disconnectedDuration);
                hasAlerted        = true;
                lastAlertSentTime = now;
            } else if (hasAlerted && (now - lastAlertSentTime) >= disconnectAlertSpeed.repeatAlertMs) {
                sendDisconnectAlert(disconnectedDuration);
                lastAlertSentTime = now;
            }
        }
    }

    private static boolean isConnectedToServer(Minecraft client) {
        ClientPacketListener networkHandler = client.getConnection();
        return networkHandler != null && client.player != null && client.level != null;
    }

    private static void sendDisconnectAlert(long disconnectedDuration) {
        String time        = LocalDateTime.now().format(TIME_FORMATTER);
        String durationStr = formatDuration(disconnectedDuration);
        String message = String.format(
                "⚠️ **Disconnected from Server**\n" +
                "You have been disconnected from the server\n" +
                "Disconnected for: %s\nTime: %s",
                durationStr, time);
        String userId = DiscordWebhookManager.getDiscordUserId();
        if (userId != null && !userId.isEmpty()) message = "<@" + userId + ">\n" + message;
        DiscordWebhookManager.sendCustomMessage(message);
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours   = minutes / 60;
        long days    = hours   / 24;
        if (days > 0)    return days  + "d " + (hours   % 24) + "h";
        if (hours > 0)   return hours + "h " + (minutes % 60) + "m";
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
                writer.write("DISCONNECT_ALERT_SPEED\n");
                writer.write(disconnectAlertSpeed.name() + "\n");
            }
            Files.move(CONFIG_TMP, CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[DisconnectAlertTracker] Save failed: " + e.getMessage());
            e.printStackTrace();
            try { Files.deleteIfExists(CONFIG_TMP); } catch (IOException ignored) {}
        }
    }

    public static void load() {
        if (!Files.exists(CONFIG_FILE) && Files.exists(CONFIG_TMP)) {
            try {
                Files.move(CONFIG_TMP, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[DisconnectAlertTracker] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[DisconnectAlertTracker] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(CONFIG_FILE)) return;

        try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE)) {
            String line;
            String section = "";
            while ((line = reader.readLine()) != null) {
                if (line.equals("DISCONNECT_ALERT_SPEED")) { section = "DISCONNECT_ALERT_SPEED"; continue; }
                if (section.equals("DISCONNECT_ALERT_SPEED") && !line.isEmpty()) {
                    try { disconnectAlertSpeed = AlertSpeed.valueOf(line); } catch (IllegalArgumentException ignored) {}
                    section = "";
                }
            }
        } catch (IOException e) {
            System.err.println("[DisconnectAlertTracker] Load failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
