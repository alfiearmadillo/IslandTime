package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tracks connection status and sends Discord alert after a 10-second grace period,
 * then repeats at configured intervals while disconnected.
 * The 10-second delay prevents spurious alerts from brief connection blips.
 */
public class DisconnectAlertTracker {

    private static final String CONFIG_FILE = "config/islandtime_disconnect_alerts.txt";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a");

    /** Grace period before the first alert is sent. If reconnected within this window, no alert fires. */
    private static final long INITIAL_ALERT_DELAY_MS = 10_000; // 10 seconds

    // Alert speed settings - controls repeat interval (first alert fires after 10s grace period)
    public enum AlertSpeed {
        OFF(0),              // No alerts at all
        FAST(5 * 60_000),    // Repeat every 5 minutes
        NORMAL(30 * 60_000), // Repeat every 30 minutes
        SLOW(3 * 60 * 60_000); // Repeat every 3 hours

        public final long repeatAlertMs;

        AlertSpeed(long repeatAlertMs) {
            this.repeatAlertMs = repeatAlertMs;
        }
    }

    private static AlertSpeed disconnectAlertSpeed = AlertSpeed.NORMAL;

    private static boolean wasConnected = false;
    private static boolean hasEverConnected = false; // Track if we've ever connected to a server
    private static long timeDisconnected = 0;
    private static long lastAlertSentTime = 0;
    private static boolean hasAlerted = false;

    /**
     * Get the current alert speed
     */
    public static AlertSpeed getAlertSpeed() {
        return disconnectAlertSpeed;
    }

    /**
     * Set the alert speed
     */
    public static void setAlertSpeed(AlertSpeed speed) {
        disconnectAlertSpeed = speed;

        // Reset alert state when changing speed
        lastAlertSentTime = 0;
        hasAlerted = false;

        save();
    }

    /**
     * Called every tick to check connection status
     */
    public static void tick(MinecraftClient client) {
        boolean isConnected = isConnectedToServer(client);

        // Mark that we've connected at least once
        if (isConnected && !hasEverConnected) {
            hasEverConnected = true;
            wasConnected = true;
        }

        // Don't track disconnections until we've connected at least once
        if (!hasEverConnected) {
            return;
        }

        // Player reconnected - reset everything
        if (isConnected && !wasConnected) {
            wasConnected = true;
            timeDisconnected = 0;
            lastAlertSentTime = 0;
            hasAlerted = false;
            return;
        }

        // Player is connected - nothing to track
        if (isConnected) {
            wasConnected = true;
            return;
        }

        // Player just disconnected - start timer
        if (!isConnected && wasConnected) {
            wasConnected = false;
            timeDisconnected = System.currentTimeMillis();
            lastAlertSentTime = 0;
            hasAlerted = false;
            return;
        }

        // Don't alert if alerts are disabled
        if (disconnectAlertSpeed == AlertSpeed.OFF) {
            return;
        }

        // Player is still disconnected - check if we should alert
        if (!isConnected && !wasConnected) {
            long now = System.currentTimeMillis();
            long disconnectedDuration = now - timeDisconnected;

            // First alert - only fires after the 10-second grace period.
            // If the player reconnects within 10 seconds, this branch is never reached.
            if (!hasAlerted && disconnectedDuration >= INITIAL_ALERT_DELAY_MS) {
                sendDisconnectAlert(disconnectedDuration);
                hasAlerted = true;
                lastAlertSentTime = now;
            }
            // Repeat alert at configured intervals
            else if (hasAlerted && (now - lastAlertSentTime) >= disconnectAlertSpeed.repeatAlertMs) {
                sendDisconnectAlert(disconnectedDuration);
                lastAlertSentTime = now;
            }
        }
    }

    /**
     * Check if the player is connected to a server
     */
    private static boolean isConnectedToServer(MinecraftClient client) {
        // Check if we have an active network handler (connected to server)
        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        return networkHandler != null && client.player != null && client.world != null;
    }

    /**
     * Send disconnect alert via Discord webhook
     */
    private static void sendDisconnectAlert(long disconnectedDuration) {
        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String durationStr = formatDuration(disconnectedDuration);

        String message = String.format(
                "⚠️ **Disconnected from Server**\n" +
                        "You have been disconnected from the server\n" +
                        "Disconnected for: %s\n" +
                        "Time: %s",
                durationStr,
                time
        );

        // Add user ping if configured
        String userId = DiscordWebhookManager.getDiscordUserId();
        if (userId != null && !userId.isEmpty()) {
            message = "<@" + userId + ">\n" + message;
        }

        DiscordWebhookManager.sendCustomMessage(message);
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
     * Save configuration to disk
     */
    public static void save() {
        try {
            Path configFile = Paths.get(CONFIG_FILE);
            Files.createDirectories(configFile.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(configFile)) {
                writer.write("DISCONNECT_ALERT_SPEED\n");
                writer.write(disconnectAlertSpeed.name() + "\n");
            }
        } catch (IOException e) {
            System.err.println("Failed to save disconnect alert config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load configuration from disk
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
                    if (line.equals("DISCONNECT_ALERT_SPEED")) {
                        section = "DISCONNECT_ALERT_SPEED";
                        continue;
                    }

                    if (section.equals("DISCONNECT_ALERT_SPEED") && !line.isEmpty()) {
                        try {
                            disconnectAlertSpeed = AlertSpeed.valueOf(line);
                        } catch (IllegalArgumentException ignored) {
                        }
                        section = "";
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load disconnect alert config: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
