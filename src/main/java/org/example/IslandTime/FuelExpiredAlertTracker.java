package org.example.islandtime;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Sends a repeating Discord alert once a minion's queued fuel-days run out ("a day of fuel is
 * up"), so you know to refuel and/or sell out your sacks. This matters mainly because sacks
 * have a storage cap — AFKing past that point without emptying them stops the setup from
 * gaining anything more.
 *
 * The alert repeats at the configured interval until {@link #onSackEmptied()} is called, which
 * happens whenever a negative Crude Gabagool sack-change is detected (see SackIncomeTracker) —
 * i.e. as soon as you take gabagool out of your sacks to sell, the pings stop, since that
 * indicates you've emptied them. Removing gabagool never subtracts from the day's tracked
 * total (see FuelCycleTracker.addAutoGabagoolAmount) — this is purely about stopping the alert.
 */
public class FuelExpiredAlertTracker {

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/fuel_expired_alerts.txt");
    private static final Path CONFIG_TMP  = Paths.get("config/IslandTime/fuel_expired_alerts.tmp");

    public enum AlertSpeed {
        OFF(0),
        FAST(5 * 60_000),
        NORMAL(30 * 60_000),
        SLOW(3 * 60 * 60_000);

        public final long repeatAlertMs;
        AlertSpeed(long repeatAlertMs) { this.repeatAlertMs = repeatAlertMs; }
    }

    private static AlertSpeed fuelExpiredAlertSpeed = AlertSpeed.NORMAL;

    /** True from the moment fuel runs out until the sacks are emptied (or alerts are turned off). */
    private static boolean alertActive     = false;
    private static long    lastAlertSentTime = 0;
    private static int     lastMinionCount   = 0;

    public static AlertSpeed getAlertSpeed() { return fuelExpiredAlertSpeed; }

    public static void setAlertSpeed(AlertSpeed speed) {
        fuelExpiredAlertSpeed = speed;
        if (speed == AlertSpeed.OFF) {
            alertActive = false;
        }
        save();
    }

    /**
     * Called by FuelCycleTracker when the active fuel cycle expires (all queued fuel-days have
     * been burned through). Sends the first alert immediately, then repeats on tick().
     */
    public static void onFuelDayUp(int minionCount) {
        if (fuelExpiredAlertSpeed == AlertSpeed.OFF) return;
        alertActive     = true;
        lastMinionCount = minionCount;
        DiscordWebhookManager.sendFuelExpiredAlert(minionCount);
        lastAlertSentTime = System.currentTimeMillis();
    }

    /**
     * Called by SackIncomeTracker whenever a negative Crude Gabagool sack-change is detected
     * (i.e. you've taken gabagool out to sell). Stops the repeating alert.
     */
    public static void onSackEmptied() {
        alertActive = false;
    }

    public static void tick() {
        if (!alertActive || fuelExpiredAlertSpeed == AlertSpeed.OFF) return;

        long now = System.currentTimeMillis();
        if (now - lastAlertSentTime >= fuelExpiredAlertSpeed.repeatAlertMs) {
            DiscordWebhookManager.sendFuelExpiredAlert(lastMinionCount);
            lastAlertSentTime = now;
        }
    }

    // -------------------------------------------------------------------------
    // Persistence — atomic write via tmp → rename
    // -------------------------------------------------------------------------

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_TMP)) {
                writer.write("FUEL_EXPIRED_ALERT_SPEED\n");
                writer.write(fuelExpiredAlertSpeed.name() + "\n");
            }
            Files.move(CONFIG_TMP, CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[FuelExpiredAlertTracker] Save failed: " + e.getMessage());
            e.printStackTrace();
            try { Files.deleteIfExists(CONFIG_TMP); } catch (IOException ignored) {}
        }
    }

    public static void load() {
        if (!Files.exists(CONFIG_FILE) && Files.exists(CONFIG_TMP)) {
            try {
                Files.move(CONFIG_TMP, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[FuelExpiredAlertTracker] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[FuelExpiredAlertTracker] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(CONFIG_FILE)) return;

        try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE)) {
            String line;
            String section = "";
            while ((line = reader.readLine()) != null) {
                if (line.equals("FUEL_EXPIRED_ALERT_SPEED")) { section = "FUEL_EXPIRED_ALERT_SPEED"; continue; }
                if (section.equals("FUEL_EXPIRED_ALERT_SPEED") && !line.isEmpty()) {
                    try { fuelExpiredAlertSpeed = AlertSpeed.valueOf(line); } catch (IllegalArgumentException ignored) {}
                    section = "";
                }
            }
        } catch (IOException e) {
            System.err.println("[FuelExpiredAlertTracker] Load failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
