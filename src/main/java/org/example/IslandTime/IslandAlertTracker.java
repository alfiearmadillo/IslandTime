package org.example.islandtime;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Tracks time spent off island and sends Discord alert after configured threshold, then repeats at configured intervals
 */
public class IslandAlertTracker {

    private static final String CONFIG_FILE = "config/islandtime_island_alerts.txt";

    // Alert speed settings (reusing ItemTracker's enum structure)
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

    private static AlertSpeed islandAlertSpeed = AlertSpeed.NORMAL;

    private static boolean wasOnIsland = true;
    private static long timeLeftIsland = 0;
    private static long lastAlertSentTime = 0;

    /**
     * Get the current alert speed
     */
    public static AlertSpeed getAlertSpeed() {
        return islandAlertSpeed;
    }

    /**
     * Set the alert speed
     */
    public static void setAlertSpeed(AlertSpeed speed) {
        islandAlertSpeed = speed;

        // Reset alert state when changing speed
        lastAlertSentTime = 0;

        save();
    }

    /**
     * Called every tick to check island status
     */
    public static void tick(boolean isOnIsland) {
        // Player returned to island - reset everything
        if (isOnIsland && !wasOnIsland) {
            wasOnIsland = true;
            timeLeftIsland = 0;
            lastAlertSentTime = 0;
            return;
        }

        // Player is on island - nothing to track
        if (isOnIsland) {
            wasOnIsland = true;
            return;
        }

        // Player just left island - start timer
        if (!isOnIsland && wasOnIsland) {
            wasOnIsland = false;
            timeLeftIsland = System.currentTimeMillis();
            lastAlertSentTime = 0;
            return;
        }

        // Don't alert if alerts are disabled
        if (islandAlertSpeed == AlertSpeed.OFF) {
            return;
        }

        // Player is still off island - check if we should alert
        if (!isOnIsland && !wasOnIsland) {
            long now = System.currentTimeMillis();
            long timeOffIsland = now - timeLeftIsland;

            // First alert after configured threshold
            if (lastAlertSentTime == 0 && timeOffIsland >= islandAlertSpeed.firstAlertMs) {
                DiscordWebhookManager.sendIslandAlert();
                lastAlertSentTime = now;
            }
            // Repeat alert at configured intervals
            else if (lastAlertSentTime > 0 && (now - lastAlertSentTime) >= islandAlertSpeed.repeatAlertMs) {
                DiscordWebhookManager.sendIslandAlert();
                lastAlertSentTime = now;
            }
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
                writer.write("ISLAND_ALERT_SPEED\n");
                writer.write(islandAlertSpeed.name() + "\n");
            }
        } catch (IOException e) {
            System.err.println("Failed to save island alert config: " + e.getMessage());
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
                    if (line.equals("ISLAND_ALERT_SPEED")) {
                        section = "ISLAND_ALERT_SPEED";
                        continue;
                    }

                    if (section.equals("ISLAND_ALERT_SPEED") && !line.isEmpty()) {
                        try {
                            islandAlertSpeed = AlertSpeed.valueOf(line);
                        } catch (IllegalArgumentException ignored) {
                        }
                        section = "";
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load island alert config: " + e.getMessage());
            e.printStackTrace();
        }
    }
}