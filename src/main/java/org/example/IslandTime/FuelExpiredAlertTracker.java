package org.example.islandtime;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Sends a repeating Discord alert once all of a minion's queued fuel has run out, so you know to refuel
 * and/or sell out your sacks. When a fuel-day ends but fuel is still left, a one-off reminder with the
 * fuel remaining is sent instead. This matters mainly because sacks
 * have a storage cap — AFKing past that point without emptying them stops the setup from
 * gaining anything more.
 *
 * The alert repeats at the configured interval until {@link #onSackEmptied()} is called, which
 * happens whenever a negative Crude Gabagool sack-change is detected (see SackIncomeTracker) —
 * i.e. as soon as you take gabagool out of your sacks to sell, the pings stop, since that
 * indicates you've emptied them. Removing gabagool never subtracts from the day's tracked
 * total (see FuelCycleTracker.addAutoGabagoolAmount) — this is purely about stopping the alert.
 *
 * Two settings (Alert Speeds category) change that for setups that don't AFK, both leaving the
 * behaviour above untouched while they are left at their defaults:
 *  - "Fuel Alert Only When All Fuel Expires": a fuel-day that ends while more fuel is still queued
 *    sends nothing, rather than the reminder it otherwise sends. The alert goes out when the last queued
 *    fuel-day ends and no minion's fuel is still burning, so fuel added before then postpones it. See
 *    {@link #onFuelDayUp(int, long)}.
 *  - "Repeat Fuel Alert Until" (a {@link RepeatMode}): what stops the repeating. Without AFKing the
 *    gabagool goes into minions, not sacks, so there is nothing to empty; the alert can instead stop
 *    when a minion's menu is opened ({@link #onMinionGuiOpened()}), or not repeat at all.
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

    /** What ends the repeating alert. The order matches the "Repeat Fuel Alert Until" dropdown. */
    public enum RepeatMode {
        /** Until Crude Gabagool is taken out of the sacks (the original behaviour). */
        SACKS_EMPTIED,
        /** Until a minion's menu is opened. */
        MINION_GUI_OPENED,
        /** The alert is sent once and doesn't repeat. */
        NONE
    }

    private static AlertSpeed fuelExpiredAlertSpeed = AlertSpeed.NORMAL;

    /** True from the moment fuel runs out until the repeat mode's stop event (or alerts are turned off). */
    private static boolean alertActive     = false;
    private static long    lastAlertSentTime = 0;
    private static int     lastMinionCount   = 0;

    /** The most minions any fuel-day of the current stack had, for the "all fuel expired" alert. */
    private static int     stackMinionCount  = 0;

    /** An alert was held back because fuel was still queued; {@link #onAllFuelExpired()} sends it. */
    private static boolean alertHeldBack     = false;

    public static AlertSpeed getAlertSpeed() { return fuelExpiredAlertSpeed; }

    public static void setAlertSpeed(AlertSpeed speed) {
        fuelExpiredAlertSpeed = speed;
        if (speed == AlertSpeed.OFF) {
            alertActive = false;
            alertHeldBack = false;
            stackMinionCount = 0;
        }
        save();
    }

    private static boolean onlyWhenAllExpired() {
        IslandTimeConfig config = ConfigManager.getConfig();
        return config != null && config.alertSpeeds.fuelAlertOnlyWhenAllExpired;
    }

    /** The configured repeat mode; a missing config or an unknown value is the original behaviour. */
    static RepeatMode repeatMode() {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null) return RepeatMode.SACKS_EMPTIED;
        RepeatMode[] modes = RepeatMode.values();
        int index = config.alertSpeeds.fuelAlertRepeatMode;
        return index >= 0 && index < modes.length ? modes[index] : RepeatMode.SACKS_EMPTIED;
    }

    /**
     * Called by FuelCycleTracker each time a fuel-day runs out. When no fuel is left, sends the "fuel has run
     * out" alert immediately, then repeats on tick() unless repeating is turned off. When fuel is still left,
     * sends a one-off reminder of how much remains instead (see
     * {@link DiscordWebhookManager#sendFuelReminder(int, long)}), or nothing if "Only When All Fuel Expires" is on.
     *
     * @param fuelRemainingMs how long the fuel still queued after this fuel-day lasts, or -1 when there is none.
     *                        With "Only When All Fuel Expires" on, the alert is skipped while fuel remains; the
     *                        next fuel-day to run out asks again, so it is sent once the last one has;
     *                        if that leaves fuel still burning, {@link #onAllFuelExpired()} sends it.
     */
    public static void onFuelDayUp(int minionCount, long fuelRemainingMs) {
        if (fuelExpiredAlertSpeed == AlertSpeed.OFF) return;

        boolean moreFuelQueued = fuelRemainingMs >= 0;
        boolean wasHeldBack = alertHeldBack;
        if (onlyWhenAllExpired()) {
            int previousCount = stackMinionCount;
            stackMinionCount = Math.max(stackMinionCount, minionCount);
            if (moreFuelQueued) {
                alertHeldBack = true;
                // Saved, so a restart before the last fuel runs out doesn't lose the alert
                if (!wasHeldBack || stackMinionCount != previousCount) save();
                return;
            }
            minionCount = stackMinionCount;
        } else if (moreFuelQueued) {
            DiscordWebhookManager.sendFuelReminder(minionCount, fuelRemainingMs);
            return;
        }
        stackMinionCount = 0;
        alertHeldBack    = false;
        if (wasHeldBack) save();

        alertActive     = repeatMode() != RepeatMode.NONE;
        lastMinionCount = minionCount;
        DiscordWebhookManager.sendFuelExpiredAlert(minionCount);
        lastAlertSentTime = System.currentTimeMillis();
    }

    /** Whether an alert is waiting for the last of the fuel to run out. */
    public static boolean hasHeldBackAlert() { return alertHeldBack; }

    /**
     * Called by FuelCycleTracker once no fuel is left burning or queued. Sends the alert that
     * {@link #onFuelDayUp(int, long)} held back because a minion fueled after the others was still
     * burning when the last fuel-day ended.
     */
    public static void onAllFuelExpired() {
        if (!alertHeldBack) return;
        onFuelDayUp(stackMinionCount, -1);
    }

    /**
     * Called by SackIncomeTracker whenever a negative Crude Gabagool sack-change is detected
     * (i.e. you've taken gabagool out to sell). Stops the repeating alert, if that is what the
     * repeat mode waits for.
     */
    public static void onSackEmptied() {
        if (repeatMode() == RepeatMode.SACKS_EMPTIED) alertActive = false;
    }

    /**
     * Called by MinionFuelTracker whenever a minion's menu is opened. Stops the repeating alert, if
     * that is what the repeat mode waits for.
     */
    public static void onMinionGuiOpened() {
        if (repeatMode() == RepeatMode.MINION_GUI_OPENED) alertActive = false;
    }

    public static void tick() {
        if (!alertActive || fuelExpiredAlertSpeed == AlertSpeed.OFF) return;
        if (repeatMode() == RepeatMode.NONE) {
            alertActive = false;
            return;
        }

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
                if (alertHeldBack) {
                    writer.write("FUEL_ALERT_HELD_BACK\n");
                    writer.write(stackMinionCount + "\n");
                }
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
                if (line.equals("FUEL_ALERT_HELD_BACK")) { section = "FUEL_ALERT_HELD_BACK"; continue; }
                if (section.equals("FUEL_ALERT_HELD_BACK") && !line.isEmpty()) {
                    try {
                        stackMinionCount = Integer.parseInt(line.trim());
                        alertHeldBack = true;
                    } catch (NumberFormatException ignored) {}
                    section = "";
                }
            }
            // An alert held back before a restart is sent by FuelCycleTracker once the fuel has run out
            if (fuelExpiredAlertSpeed == AlertSpeed.OFF) {
                alertHeldBack = false;
                stackMinionCount = 0;
            }
        } catch (IOException e) {
            System.err.println("[FuelExpiredAlertTracker] Load failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
