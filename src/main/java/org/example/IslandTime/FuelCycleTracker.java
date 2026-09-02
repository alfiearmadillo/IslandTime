package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tracks minion fuel usage per calendar day for profit-per-day tracking.
 *
 * A "fuel cycle" starts when you fuel a minion and normally lasts 24 hours per
 * unit of fuel placed — placing a full stack of, say, 10 fuel queues up 10
 * consecutive 24h burn periods instead of just one. Time is bucketed into
 * per-calendar-day records (rather than per-cycle) so day-by-day profit
 * tracking (mob-kill coins, gabagool sack income, fuel cost) is possible.
 */
public class FuelCycleTracker {

    private static final Path SAVE_FILE     = Paths.get("config/IslandTime/fuel_days.json");
    private static final Path SAVE_FILE_TMP = Paths.get("config/IslandTime/fuel_days.tmp");

    // Legacy per-cycle save file, kept only so existing data can be migrated once.
    private static final Path LEGACY_SAVE_FILE = Paths.get("config/IslandTime/fuel_cycles.txt");

    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final long SAVE_INTERVAL_MS = 30_000L;
    private static long lastSaveTime = 0;

    // Active (in-progress) cycle state. currentCycleStartTime == 0 means no active cycle.
    private static long currentCycleStartTime  = 0;
    private static long currentCycleExpiryTime = 0;
    private static int  currentCycleMinions    = 0;

    private static long    lastTickTime        = 0;
    private static boolean wasOnIslandLastTick  = false;

    /** Default mob-kill coins/day at 100% AFK, used as the snapshot baseline for newly created days. */
    private static double mobKillCoinsAt100Baseline = 0.0;

    private static final ConcurrentSkipListMap<String, DayRecord> dayRecords = new ConcurrentSkipListMap<>();

    private static final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("FuelCycle-Saver");
        return t;
    });

    // -------------------------------------------------------------------------
    // Tracking toggle
    // -------------------------------------------------------------------------

    /**
     * When false:
     *   - onMinionFueled() will not start a new cycle (fueling is a no-op for tracking).
     *   - Any in-progress cycle continues to run and will complete naturally at its
     *     expiry — we never abandon data mid-cycle.
     * The value is persisted via TrackingToggleConfig.
     */
    private static volatile boolean trackingEnabled = true;

    public static boolean isTrackingEnabled() { return trackingEnabled; }

    /**
     * Called by TrackingToggleConfig after loading, and by the toggle command.
     * Does NOT call TrackingToggleConfig.save() itself — the caller owns that.
     */
    public static void setTrackingEnabled(boolean enabled) {
        trackingEnabled = enabled;
        // No state reset needed: an active cycle keeps running regardless.
        // onMinionFueled() is the only entry point for new cycles, and it
        // checks the flag before doing anything.
    }

    // -------------------------------------------------------------------------
    // Data class
    // -------------------------------------------------------------------------

    /**
     * Per-calendar-day profit tracking record. All monetary/coin fields can be
     * tweaked directly (e.g. from the fuel tracker GUI) without affecting other days.
     */
    public static class DayRecord {
        public String date; // yyyy-MM-dd

        /** Minions refueled today, auto-detected from fuel placements. */
        public int minionsRefueledAuto = 0;

        /** Manual override for minions refueled — null means "use the auto-detected value".
         *  Useful when lag desyncs the automatic fuel-placement detection. */
        public Integer manualMinionCount = null;

        /** Milliseconds during which fuel was active and the tracked item's stats were increasing. */
        public long activeMs = 0;

        /** Estimated total bazaar cost of the fuel placed this day (cheapest of the 5 distillates). */
        public double fuelCost = 0.0;

        /** Snapshot of the 100%-AFK mob-kill coins/day baseline at the time this day was recorded.
         *  Stored per-day so changing the live config baseline later doesn't alter past days. */
        public double mobKillCoinsAt100 = 0.0;

        /** Manually entered sell price for a single Crude Gabagool (or whatever sack item is tracked). */
        public double gabagoolPrice = 0.0;

        /** Amount of Crude Gabagool auto-tracked today from Hypixel's periodic "[Sacks]" chat
         *  messages (see SackIncomeTracker) — sum of all positive sack-gain deltas seen today. */
        public double gabagoolAmountAuto = 0.0;

        /** Manual override for gabagool amount collected today — null means "use the
         *  auto-tracked {@link #gabagoolAmountAuto} value". Useful as a fallback when sack
         *  messages are disabled, or to correct the auto-tracked value. */
        public Double manualGabagoolAmount = null;

        public String notes = "";

        public DayRecord() {}

        public DayRecord(String date) {
            this.date = date;
        }

        public int getEffectiveMinionCount() {
            return manualMinionCount != null ? manualMinionCount : minionsRefueledAuto;
        }

        public double getAfkPercent() {
            if (activeMs <= 0) return 0.0;
            return Math.min(100.0, (activeMs * 100.0) / DAY_MS);
        }

        public double getMobKillCoins() {
            return mobKillCoinsAt100 * (getAfkPercent() / 100.0);
        }

        /** Effective gabagool amount used for profit calc: manual override if set, else auto-tracked. */
        public double getEffectiveGabagoolAmount() {
            return manualGabagoolAmount != null ? manualGabagoolAmount : gabagoolAmountAuto;
        }

        public double getGabagoolRevenue() {
            return gabagoolPrice * getEffectiveGabagoolAmount();
        }

        public double getProfit() {
            return getMobKillCoins() + getGabagoolRevenue() - fuelCost;
        }
    }

    // -------------------------------------------------------------------------
    // Public API — mob-kill baseline
    // -------------------------------------------------------------------------

    public static double getMobKillCoinsAt100Baseline() { return mobKillCoinsAt100Baseline; }

    public static void setMobKillCoinsAt100Baseline(double value) {
        mobKillCoinsAt100Baseline = Math.max(0.0, value);
        save();
    }

    // -------------------------------------------------------------------------
    // Public API — day records
    // -------------------------------------------------------------------------

    /** Returns all tracked day records, most recent first. */
    public static List<DayRecord> getDayRecordsDescending() {
        List<DayRecord> list = new ArrayList<>(dayRecords.values());
        list.sort(Comparator.comparing((DayRecord d) -> d.date).reversed());
        return list;
    }

    public static DayRecord getDayRecord(String date) {
        return dayRecords.get(date);
    }

    private static DayRecord getOrCreateDayRecord(String date) {
        return dayRecords.computeIfAbsent(date, d -> {
            DayRecord record = new DayRecord(d);
            record.mobKillCoinsAt100 = mobKillCoinsAt100Baseline;
            return record;
        });
    }

    public static DayRecord getOrCreateToday() {
        DayRecord record = getOrCreateDayRecord(LocalDate.now().toString());
        save();
        return record;
    }

    public static void setManualMinionCount(String date, Integer value) {
        DayRecord record = getOrCreateDayRecord(date);
        record.manualMinionCount = value;
        save();
    }

    public static void setGabagoolPrice(String date, double value) {
        DayRecord record = getOrCreateDayRecord(date);
        record.gabagoolPrice = Math.max(0.0, value);
        save();
    }

    /** Sets a manual override for gabagool amount, taking precedence over the auto-tracked value. */
    public static void setGabagoolAmount(String date, double value) {
        DayRecord record = getOrCreateDayRecord(date);
        record.manualGabagoolAmount = Math.max(0.0, value);
        save();
    }

    /** Clears the manual override, reverting the day back to using the auto-tracked gabagool amount. */
    public static void clearManualGabagoolAmount(String date) {
        DayRecord record = getOrCreateDayRecord(date);
        record.manualGabagoolAmount = null;
        save();
    }

    /**
     * Adds to today's auto-tracked gabagool amount. Called by {@link SackIncomeTracker} whenever a
     * positive Crude Gabagool sack-gain is detected in Hypixel's periodic "[Sacks]" chat message.
     * No-ops for non-positive deltas (sack losses/removals aren't "collected" amounts).
     */
    public static void addAutoGabagoolAmount(String date, double delta) {
        if (delta <= 0) return;
        DayRecord record = getOrCreateDayRecord(date);
        record.gabagoolAmountAuto += delta;
        save();
    }

    public static void setMobKillCoinsAt100(String date, double value) {
        DayRecord record = getOrCreateDayRecord(date);
        record.mobKillCoinsAt100 = Math.max(0.0, value);
        save();
    }

    public static void setNotes(String date, String value) {
        DayRecord record = getOrCreateDayRecord(date);
        record.notes = value != null ? value : "";
        save();
    }

    public static void removeDayRecord(String date) {
        if (dayRecords.remove(date) != null) {
            save();
        }
    }

    public static DayRecord addManualDayRecord(String date) {
        DayRecord record = getOrCreateDayRecord(date);
        save();
        return record;
    }

    // -------------------------------------------------------------------------
    // Public API — fueling
    // -------------------------------------------------------------------------

    /** Kept for backwards compatibility — treats it as a single unit of fuel. */
    public static void onMinionFueled() {
        onMinionFueled(1);
    }

    /**
     * Called when a minion is fueled with {@code fuelCount} units of fuel (e.g. placing
     * a stack of 10 fuel at once). Each unit extends the active cycle by 24h, queued
     * back-to-back, rather than just resetting a single 24h timer.
     *
     * If tracking is disabled, fueling is ignored for cycle purposes when no cycle is
     * currently active. An already-active cycle is unaffected by the toggle.
     */
    public static void onMinionFueled(int fuelCount) {
        if (fuelCount <= 0) return;
        long now = System.currentTimeMillis();

        if (currentCycleStartTime == 0) {
            // No active cycle — only start one if tracking is enabled
            if (!trackingEnabled) return;
            currentCycleStartTime  = now;
            currentCycleExpiryTime = now + fuelCount * DAY_MS;
            currentCycleMinions    = fuelCount;
            lastTickTime            = now;
        } else {
            // Active cycle — always increment the minion counter and queue up the
            // extra fuel-days, even if tracking was disabled after the cycle started.
            currentCycleMinions += fuelCount;
            long base = Math.max(now, currentCycleExpiryTime);
            currentCycleExpiryTime = base + fuelCount * DAY_MS;
        }

        // Record the fuel placement against today's day record.
        String today = LocalDate.now().toString();
        DayRecord record = getOrCreateDayRecord(today);
        record.minionsRefueledAuto += fuelCount;

        // Estimate fuel cost from the bazaar in the background so we never
        // block the client thread while a container screen is open.
        saveExecutor.submit(() -> {
            try {
                JsonObject bazaar = PriceCache.getBazaar();
                double unitCost = PriceCache.getCheapestInfernoFuelDistillatePrice(bazaar);
                if (unitCost > 0) {
                    DayRecord r = getOrCreateDayRecord(today);
                    r.fuelCost += unitCost * fuelCount;
                    save();
                }
            } catch (Exception e) {
                System.err.println("[FuelCycleTracker] Fuel cost estimate failed: " + e.getMessage());
            }
        });

        save();
    }

    /**
     * Called every tick.
     * @param isStatsIncreasing whether the tracked item's stats are currently increasing
     */
    public static void tick(boolean isStatsIncreasing) {
        if (currentCycleStartTime == 0) {
            wasOnIslandLastTick = false;
            return;
        }

        long now = System.currentTimeMillis();

        // Check cycle expiry — always runs, regardless of toggle
        if (now >= currentCycleExpiryTime) {
            int minionsAtExpiry = currentCycleMinions;
            currentCycleStartTime  = 0;
            currentCycleExpiryTime = 0;
            currentCycleMinions    = 0;
            wasOnIslandLastTick     = false;
            FuelExpiredAlertTracker.onFuelDayUp(minionsAtExpiry);
            save();
            return;
        }

        // Accumulate active time against today's bucket — still runs for an
        // in-progress cycle even if tracking was disabled after it started
        if (wasOnIslandLastTick && isStatsIncreasing && lastTickTime > 0) {
            long delta = now - lastTickTime;
            if (delta < 5000) {
                DayRecord record = getOrCreateDayRecord(LocalDate.now().toString());
                record.activeMs += delta;
            }
        }

        lastTickTime        = now;
        wasOnIslandLastTick = isStatsIncreasing;

        if (now - lastSaveTime >= SAVE_INTERVAL_MS) {
            lastSaveTime = now;
            save();
        }
    }

    // -------------------------------------------------------------------------
    // Debug
    // -------------------------------------------------------------------------

    public static boolean debugSimulateDay() {
        String fakeDate = LocalDate.now().minusDays(1).toString();
        DayRecord record = getOrCreateDayRecord(fakeDate);
        record.minionsRefueledAuto = 32;
        record.activeMs = 20 * 60 * 60 * 1000L;
        record.mobKillCoinsAt100 = mobKillCoinsAt100Baseline;
        record.gabagoolPrice = 15.0;
        record.gabagoolAmountAuto = 500;

        System.out.println("[FuelCycleTracker] DEBUG: Injecting fake day record — " + fakeDate);

        save();

        return Files.exists(SAVE_FILE);
    }

    // -------------------------------------------------------------------------
    // Display (chat fallback — the GUI is the primary interface now)
    // -------------------------------------------------------------------------

    public static void displayStats(Minecraft client, String date) {
        if (client.player == null) return;

        if (dayRecords.isEmpty() && currentCycleStartTime == 0) {
            client.player.sendSystemMessage(
                    Component.literal("No fuel tracking data yet. Fuel a minion to start tracking!")
                            .withStyle(ChatFormatting.YELLOW));
            return;
        }

        if (date != null) {
            DayRecord record = dayRecords.get(date);
            if (record == null) {
                client.player.sendSystemMessage(
                        Component.literal("No data recorded for " + date + ".").withStyle(ChatFormatting.RED));
                return;
            }
            displaySingleDay(client, record);
            return;
        }

        client.player.sendSystemMessage(
                Component.literal("Use /it fueldays (or click 'Open Fuel Tracker' in the config) to view and edit day-by-day fuel/profit stats.")
                        .withStyle(ChatFormatting.GOLD));

        List<DayRecord> recent = getDayRecordsDescending();
        int shown = Math.min(4, recent.size());
        for (int i = 0; i < shown; i++) {
            displaySingleDay(client, recent.get(i));
        }
    }

    private static void displaySingleDay(Minecraft client, DayRecord record) {
        client.player.sendSystemMessage(
                Component.literal("» " + record.date).withStyle(ChatFormatting.GOLD));
        client.player.sendSystemMessage(
                Component.literal("  Minions: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(record.getEffectiveMinionCount())).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" | AFK: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.format("%.1f%%", record.getAfkPercent())).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" | Fuel Cost: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(PriceCache.formatCoins(record.fuelCost)).withStyle(ChatFormatting.WHITE)));
        client.player.sendSystemMessage(
                Component.literal("  Mob-Kill Coins: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(PriceCache.formatCoins(record.getMobKillCoins())).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" | Gabagool Revenue: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(PriceCache.formatCoins(record.getGabagoolRevenue())).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" | Profit: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(PriceCache.formatCoins(record.getProfit())).withStyle(ChatFormatting.WHITE)));
    }

    // -------------------------------------------------------------------------
    // Persistence — atomic write via tmp → rename
    // -------------------------------------------------------------------------

    private static class SaveData {
        long currentCycleStartTime;
        long currentCycleExpiryTime;
        int  currentCycleMinions;
        double mobKillCoinsAt100Baseline;
        Map<String, DayRecord> days;
    }

    public static void save() {
        SaveData data = new SaveData();
        data.currentCycleStartTime      = currentCycleStartTime;
        data.currentCycleExpiryTime     = currentCycleExpiryTime;
        data.currentCycleMinions        = currentCycleMinions;
        data.mobKillCoinsAt100Baseline  = mobKillCoinsAt100Baseline;
        data.days                       = new TreeMap<>(dayRecords);

        saveExecutor.submit(() -> {
            try {
                Files.createDirectories(SAVE_FILE.getParent());
                Files.writeString(SAVE_FILE_TMP, GSON.toJson(data));
                Files.move(SAVE_FILE_TMP, SAVE_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                System.err.println("[FuelCycleTracker] Save failed: " + e.getMessage());
                System.err.println("[FuelCycleTracker] Attempted path: " + SAVE_FILE.toAbsolutePath());
                e.printStackTrace();
                try { Files.deleteIfExists(SAVE_FILE_TMP); } catch (IOException ignored) {}
            }
        });
    }

    public static void load() {
        if (!Files.exists(SAVE_FILE) && Files.exists(SAVE_FILE_TMP)) {
            try {
                Files.move(SAVE_FILE_TMP, SAVE_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[FuelCycleTracker] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[FuelCycleTracker] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (Files.exists(SAVE_FILE)) {
            try {
                String json = Files.readString(SAVE_FILE);
                SaveData data = GSON.fromJson(json, SaveData.class);
                if (data != null) {
                    currentCycleStartTime     = data.currentCycleStartTime;
                    currentCycleExpiryTime    = data.currentCycleExpiryTime;
                    currentCycleMinions       = data.currentCycleMinions;
                    mobKillCoinsAt100Baseline = data.mobKillCoinsAt100Baseline;
                    if (data.days != null) {
                        dayRecords.clear();
                        dayRecords.putAll(data.days);
                    }
                    lastTickTime = System.currentTimeMillis();

                    // Expire any cycle that finished while the game was closed.
                    long now = System.currentTimeMillis();
                    if (currentCycleStartTime > 0 && now >= currentCycleExpiryTime) {
                        currentCycleStartTime  = 0;
                        currentCycleExpiryTime = 0;
                        currentCycleMinions    = 0;
                    }
                }
            } catch (Exception e) {
                System.err.println("[FuelCycleTracker] Load failed: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        // No new-format file yet — attempt a one-time migration from the legacy
        // per-cycle text format, bucketing each completed cycle's data into the
        // calendar day it started on.
        migrateLegacyFile();
    }

    private static void migrateLegacyFile() {
        if (!Files.exists(LEGACY_SAVE_FILE)) return;

        try (BufferedReader reader = Files.newBufferedReader(LEGACY_SAVE_FILE)) {
            String line;
            String section = "";
            long[] currentSnapshot = new long[4];
            boolean hasCurrentSection = false;

            while ((line = reader.readLine()) != null) {
                if      (line.equals("CURRENT"))   { section = "CURRENT";   continue; }
                else if (line.equals("COMPLETED")) { section = "COMPLETED"; continue; }

                if (section.equals("CURRENT")) {
                    try {
                        currentSnapshot[0] = Long.parseLong(line);
                        currentSnapshot[1] = Long.parseLong(readLineOrThrow(reader));
                        currentSnapshot[2] = Integer.parseInt(readLineOrThrow(reader));
                        currentSnapshot[3] = Long.parseLong(readLineOrThrow(reader));
                        hasCurrentSection  = true;
                    } catch (NumberFormatException | IOException e) {
                        System.err.println("[FuelCycleTracker] Corrupt legacy CURRENT section, skipping: " + e.getMessage());
                    }
                    section = "";
                } else if (section.equals("COMPLETED") && !line.isBlank()) {
                    String[] parts = line.split(",");
                    if (parts.length == 4) {
                        try {
                            long startTime       = Long.parseLong(parts[0]);
                            int  minionsRefueled = Integer.parseInt(parts[2]);
                            long islandTime      = Long.parseLong(parts[3]);
                            String date = java.time.Instant.ofEpochMilli(startTime)
                                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
                            DayRecord record = getOrCreateDayRecord(date);
                            record.minionsRefueledAuto += minionsRefueled;
                            record.activeMs += islandTime;
                        } catch (NumberFormatException e) {
                            System.err.println("[FuelCycleTracker] Skipping corrupt legacy COMPLETED line: " + line);
                        }
                    }
                }
            }

            if (hasCurrentSection && currentSnapshot[0] > 0) {
                currentCycleStartTime  = currentSnapshot[0];
                currentCycleMinions    = (int) currentSnapshot[2];
                // Legacy format only stored a single 24h window from the last fuel time.
                currentCycleExpiryTime = currentSnapshot[1] + DAY_MS;

                long now = System.currentTimeMillis();
                if (now >= currentCycleExpiryTime) {
                    currentCycleStartTime  = 0;
                    currentCycleExpiryTime = 0;
                    currentCycleMinions    = 0;
                }
            }

            System.out.println("[FuelCycleTracker] Migrated legacy fuel_cycles.txt into per-day records.");
            save();
        } catch (IOException e) {
            System.err.println("[FuelCycleTracker] Legacy migration failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String readLineOrThrow(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) throw new IOException("Unexpected end of file");
        return line;
    }

    public static void shutdown() {
        save();
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("[FuelCycleTracker] Save executor timed out, forcing shutdown");
                saveExecutor.shutdownNow();
                if (!saveExecutor.awaitTermination(2, TimeUnit.SECONDS))
                    System.err.println("[FuelCycleTracker] Save executor did not terminate after forced shutdown");
            }
        } catch (InterruptedException e) {
            System.err.println("[FuelCycleTracker] Save shutdown interrupted");
            saveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
