package org.example.islandtime;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Tracks time spent off island and sends Discord alert after configured threshold,
 * then repeats at configured intervals.
 */
public class IslandAlertTracker {

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/island_alerts.txt");
    private static final Path CONFIG_TMP  = Paths.get("config/IslandTime/island_alerts.tmp");

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

    private static AlertSpeed islandAlertSpeed = AlertSpeed.NORMAL;

    private static boolean wasOnIsland      = true;
    private static long    timeLeftIsland   = 0;
    private static long    lastAlertSentTime = 0;

    public static AlertSpeed getAlertSpeed() { return islandAlertSpeed; }

    public static void setAlertSpeed(AlertSpeed speed) {
        islandAlertSpeed  = speed;
        lastAlertSentTime = 0;
        save();
    }

    public static void tick(boolean isOnIsland) {
        if (isOnIsland && !wasOnIsland) {
            wasOnIsland       = true;
            timeLeftIsland    = 0;
            lastAlertSentTime = 0;
            return;
        }
        if (isOnIsland) { wasOnIsland = true; return; }

        if (!isOnIsland && wasOnIsland) {
            wasOnIsland       = false;
            timeLeftIsland    = System.currentTimeMillis();
            lastAlertSentTime = 0;
            return;
        }

        if (islandAlertSpeed == AlertSpeed.OFF) return;

        if (!isOnIsland && !wasOnIsland) {
            long now          = System.currentTimeMillis();
            long timeOffIsland = now - timeLeftIsland;

            if (lastAlertSentTime == 0 && timeOffIsland >= islandAlertSpeed.firstAlertMs) {
                DiscordWebhookManager.sendIslandAlert(timeOffIsland);
                lastAlertSentTime = now;
            } else if (lastAlertSentTime > 0 && (now - lastAlertSentTime) >= islandAlertSpeed.repeatAlertMs) {
                DiscordWebhookManager.sendIslandAlert(timeOffIsland);
                lastAlertSentTime = now;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Persistence — atomic write via tmp → rename
    // -------------------------------------------------------------------------

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_TMP)) {
                writer.write("ISLAND_ALERT_SPEED\n");
                writer.write(islandAlertSpeed.name() + "\n");
            }
            Files.move(CONFIG_TMP, CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[IslandAlertTracker] Save failed: " + e.getMessage());
            e.printStackTrace();
            try { Files.deleteIfExists(CONFIG_TMP); } catch (IOException ignored) {}
        }
    }

    public static void load() {
        if (!Files.exists(CONFIG_FILE) && Files.exists(CONFIG_TMP)) {
            try {
                Files.move(CONFIG_TMP, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[IslandAlertTracker] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[IslandAlertTracker] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(CONFIG_FILE)) return;

        try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE)) {
            String line;
            String section = "";
            while ((line = reader.readLine()) != null) {
                if (line.equals("ISLAND_ALERT_SPEED")) { section = "ISLAND_ALERT_SPEED"; continue; }
                if (section.equals("ISLAND_ALERT_SPEED") && !line.isEmpty()) {
                    try { islandAlertSpeed = AlertSpeed.valueOf(line); } catch (IllegalArgumentException ignored) {}
                    section = "";
                }
            }
        } catch (IOException e) {
            System.err.println("[IslandAlertTracker] Load failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
