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
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tracks minion fuel usage per calendar day for profit-per-day tracking.
 *
 * Each minion (keyed by its GUI title) tracks its own fuel expiry independently —
 * fueling one minion never affects another's timer. A unit of fuel extends that
 * specific minion's own expiry by 24h, so placing a full stack of, say, 10 fuel
 * queues up 10 consecutive 24h burn periods for that minion. This per-minion state
 * is purely internal bookkeeping though: nothing about it is ever surfaced per
 * minion — day-level data (minionsRefueledAuto, AFK%, etc.) stays one aggregate
 * number per day, same as before. The shared "fuel day up" alert (see
 * FuelExpiredAlertTracker) fires once all currently-tracked minions' fuel has run
 * out — i.e. when the last (latest-expiring) minion in the batch finishes — not
 * when the first one does. Time is bucketed into per-calendar-day records (rather
 * than per-cycle) so day-by-day profit tracking (mob-kill coins, gabagool sack
 * income, fuel cost) is possible.
 */
public class FuelCycleTracker {

    private static final Path LIVE_SAVE_FILE     = Paths.get("config/IslandTime/fuel_days.json");
    private static final Path LIVE_SAVE_FILE_TMP = Paths.get("config/IslandTime/fuel_days.tmp");

    // Separate save location used while "test mode" is enabled, so experimenting with
    // the tracker (e.g. testing this session's changes) never touches real minion data.
    private static final Path TEST_SAVE_FILE     = Paths.get("config/IslandTime/fuel_days_test.json");
    private static final Path TEST_SAVE_FILE_TMP = Paths.get("config/IslandTime/fuel_days_test.tmp");

    // Legacy per-cycle save file, kept only so existing data can be migrated once.
    // Test mode never touches or migrates this — it's real historical data.
    private static final Path LEGACY_SAVE_FILE = Paths.get("config/IslandTime/fuel_cycles.txt");

    // The test-mode toggle itself is always persisted at a fixed path (never one of the
    // two data files above), so switching modes doesn't depend on whichever data file
    // happens to be active.
    private static final Path TEST_MODE_FLAG_FILE = Paths.get("config/IslandTime/fuel_test_mode.txt");

    /** When true, save()/load() read/write TEST_SAVE_FILE instead of LIVE_SAVE_FILE, so
     *  testing the tracker never risks corrupting real tracked fuel/profit data. */
    private static volatile boolean testModeEnabled = false;

    private static Path saveFile()    { return testModeEnabled ? TEST_SAVE_FILE    : LIVE_SAVE_FILE; }
    private static Path saveFileTmp() { return testModeEnabled ? TEST_SAVE_FILE_TMP : LIVE_SAVE_FILE_TMP; }

    public static boolean isTestModeEnabled() { return testModeEnabled; }

    /**
     * Switches between the live and test data files, persisting the toggle itself and
     * reloading all in-memory state (day records, per-minion expiries, etc.) from
     * whichever file is now active. Any active fuel-cycle state before the switch is
     * discarded in memory (it's still safely saved in the previous mode's file).
     */
    public static void setTestModeEnabled(boolean enabled) {
        if (testModeEnabled == enabled) return;
        testModeEnabled = enabled;
        saveTestModeFlag();

        // Reset in-memory state before reloading, so switching mid-session never bleeds
        // live data into the test file (or vice versa).
        dayRecords.clear();
        minionExpiries.clear();
        minionsCountedToday.clear();
        minionsCountedTodayDate = "";
        mobKillCoinsAt100Baseline = 0.0;

        load();
    }

    private static void saveTestModeFlag() {
        try {
            Files.createDirectories(TEST_MODE_FLAG_FILE.getParent());
            Files.writeString(TEST_MODE_FLAG_FILE, String.valueOf(testModeEnabled));
        } catch (IOException e) {
            System.err.println("[FuelCycleTracker] Failed to save test-mode flag: " + e.getMessage());
        }
    }

    private static void loadTestModeFlag() {
        if (Files.exists(TEST_MODE_FLAG_FILE)) {
            try {
                testModeEnabled = Boolean.parseBoolean(Files.readString(TEST_MODE_FLAG_FILE).trim());
            } catch (IOException e) {
                System.err.println("[FuelCycleTracker] Failed to load test-mode flag: " + e.getMessage());
            }
        }
    }

    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final long SAVE_INTERVAL_MS = 30_000L;
    private static long lastSaveTime = 0;

    // Rolling backups of the day-record data, taken whenever new fuel data is added
    // (not on every save) provided at least BACKUP_INTERVAL_MS has passed since the
    // last one. Only the most recent MAX_BACKUPS are kept.
    private static final Path BACKUP_DIR = Paths.get("config/IslandTime/backups");
    private static final long BACKUP_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_BACKUPS = 7;
    private static long lastBackupTime = 0;

    // Active per-minion fuel state: minion GUI title (the same key MinionFuelTracker
    // uses to distinguish minions) -> that minion's own fuel expiry time. Each minion
    // tracks its own remaining fuel independently, so fueling 32 minions doesn't
    // serialize their fuel-days onto one shared timeline. An empty map means no
    // minion currently has active fuel.
    private static final ConcurrentHashMap<String, Long> minionExpiries = new ConcurrentHashMap<>();

    // Distinct minion keys already counted toward today's minionsRefueledAuto total,
    // so topping up the same minion again later the same day doesn't double-count it.
    // Reset whenever the calendar day rolls over.
    private static final Set<String> minionsCountedToday = ConcurrentHashMap.newKeySet();
    private static String minionsCountedTodayDate = "";

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

    /** Kept for backwards compatibility — treats it as a single unit of fuel for an unspecified minion. */
    public static void onMinionFueled() {
        onMinionFueled("unknown", 1);
    }

    /** Kept for backwards compatibility — attributes the fuel to an unspecified minion key. */
    public static void onMinionFueled(int fuelCount) {
        onMinionFueled("unknown", fuelCount);
    }

    /**
     * Called when the minion identified by {@code minionKey} (its GUI title, e.g.
     * "Cobblestone Minion V" — the same string {@link MinionFuelTracker} uses to tell
     * minions apart) is fueled with {@code fuelCount} units of fuel (e.g. placing a
     * stack of 10 at once). Each unit extends that specific minion's own fuel expiry
     * by 24h, queued back-to-back — other minions' timers are untouched, so fueling a
     * batch of minions never serializes their fuel-days onto one shared timeline.
     *
     * Per-minion data is only ever used internally for this expiry bookkeeping — the
     * day-level aggregate (minionsRefueledAuto, AFK%, etc.) stays a single number, not
     * a breakdown per minion.
     *
     * If tracking is disabled, fueling is ignored for cycle purposes when this minion
     * has no fuel currently active. A minion whose fuel is already active keeps
     * running/extending regardless of the toggle — we never abandon data mid-cycle.
     */
    public static void onMinionFueled(String minionKey, int fuelCount) {
        if (fuelCount <= 0) return;
        if (minionKey == null || minionKey.isEmpty()) minionKey = "unknown";
        long now = System.currentTimeMillis();

        Long existingExpiry = minionExpiries.get(minionKey);
        boolean hasActiveFuel = existingExpiry != null && now < existingExpiry;

        if (!hasActiveFuel) {
            // Fresh start for this minion (no fuel currently tracked for it, or its
            // previous fuel already ran out) — only begin tracking it if enabled.
            if (!trackingEnabled) {
                minionExpiries.remove(minionKey);
                return;
            }
            minionExpiries.put(minionKey, now + fuelCount * DAY_MS);
        } else {
            // This minion already has active fuel — top up just its own timer from
            // its own current expiry, rather than stacking onto any other minion's
            // timeline. Always applies even if tracking was disabled after this
            // minion's fuel started, since an in-progress cycle keeps running.
            minionExpiries.put(minionKey, existingExpiry + fuelCount * DAY_MS);
        }

        if (lastTickTime == 0) lastTickTime = now;

        // Record the fuel placement against today's day record. Count this minion
        // once per calendar day (topping it up again later the same day doesn't
        // double-count it), independent of how many fuel units were placed.
        String today = LocalDate.now().toString();
        if (!today.equals(minionsCountedTodayDate)) {
            minionsCountedToday.clear();
            minionsCountedTodayDate = today;
        }
        DayRecord record = getOrCreateDayRecord(today);
        if (minionsCountedToday.add(minionKey)) {
            record.minionsRefueledAuto += 1;
        }

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

        maybeBackupData();
        save();
    }

    /**
     * Called every tick.
     * @param isStatsIncreasing whether the tracked item's stats are currently increasing
     */
    public static void tick(boolean isStatsIncreasing) {
        if (minionExpiries.isEmpty()) {
            wasOnIslandLastTick = false;
            return;
        }

        long now = System.currentTimeMillis();

        // The shared "fuel day up" alert fires when the LAST (latest-expiring) minion's
        // own fuel runs out, not when the first one does — mirroring how the alert
        // conceptually worked before, just computed correctly per-minion now.
        long maxExpiry = 0;
        for (long expiry : minionExpiries.values()) {
            if (expiry > maxExpiry) maxExpiry = expiry;
        }

        if (now >= maxExpiry) {
            int minionsAtExpiry = minionExpiries.size();
            minionExpiries.clear();
            wasOnIslandLastTick = false;
            FuelExpiredAlertTracker.onFuelDayUp(minionsAtExpiry);
            save();
            return;
        }

        // Individual minions whose own fuel already ran out (but weren't the last to)
        // silently drop out here — no separate alert for them, only the shared one above.
        minionExpiries.values().removeIf(expiry -> now >= expiry);

        // Accumulate active time against today's bucket — still runs while any minion
        // has active fuel, even if tracking was disabled after cycles started
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

        return Files.exists(saveFile());
    }

    // -------------------------------------------------------------------------
    // Display (chat fallback — the GUI is the primary interface now)
    // -------------------------------------------------------------------------

    public static void displayStats(Minecraft client, String date) {
        if (client.player == null) return;

        if (dayRecords.isEmpty() && minionExpiries.isEmpty()) {
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
        Map<String, Long> minionExpiries;
        Set<String> minionsCountedToday;
        String minionsCountedTodayDate;
        double mobKillCoinsAt100Baseline;
        Map<String, DayRecord> days;
        long lastBackupTime;
    }

    private static SaveData buildSaveData() {
        SaveData data = new SaveData();
        data.minionExpiries             = new java.util.HashMap<>(minionExpiries);
        data.minionsCountedToday        = new java.util.HashSet<>(minionsCountedToday);
        data.minionsCountedTodayDate    = minionsCountedTodayDate;
        data.mobKillCoinsAt100Baseline  = mobKillCoinsAt100Baseline;
        data.days                       = new TreeMap<>(dayRecords);
        data.lastBackupTime             = lastBackupTime;
        return data;
    }

    public static void save() {
        SaveData data = buildSaveData();

        // Snapshot the target file at submit-time (not inside the async lambda) so a
        // save queued just before a test-mode toggle still writes to the file it was
        // meant for, instead of racing the mode switch and writing live data into the
        // test file (or vice versa).
        Path file = saveFile();
        Path tmp  = saveFileTmp();

        saveExecutor.submit(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(tmp, GSON.toJson(data));
                Files.move(tmp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                System.err.println("[FuelCycleTracker] Save failed: " + e.getMessage());
                System.err.println("[FuelCycleTracker] Attempted path: " + file.toAbsolutePath());
                e.printStackTrace();
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        });
    }

    /**
     * Takes a rolling backup of the current day-record data, but only if at least
     * BACKUP_INTERVAL_MS has passed since the last backup. Called whenever new fuel
     * data is added (rather than on every save), so a busy session doesn't spam
     * backups. Keeps only the most recent MAX_BACKUPS files.
     */
    private static void maybeBackupData() {
        long now = System.currentTimeMillis();
        if (lastBackupTime != 0 && now - lastBackupTime < BACKUP_INTERVAL_MS) return;
        lastBackupTime = now;

        SaveData data = buildSaveData();
        String prefix = testModeEnabled ? "fuel_days_test_backup-" : "fuel_days_backup-";
        Path backupFile = BACKUP_DIR.resolve(prefix + now + ".json");

        saveExecutor.submit(() -> {
            try {
                Files.createDirectories(BACKUP_DIR);
                Files.writeString(backupFile, GSON.toJson(data));
                pruneOldBackups(prefix);
            } catch (IOException e) {
                System.err.println("[FuelCycleTracker] Backup failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /** Deletes the oldest backups beyond MAX_BACKUPS for the given file-name prefix. */
    private static void pruneOldBackups(String prefix) {
        try {
            if (!Files.isDirectory(BACKUP_DIR)) return;
            List<Path> backups;
            try (var stream = Files.list(BACKUP_DIR)) {
                backups = stream
                        .filter(p -> p.getFileName().toString().startsWith(prefix)
                                  && p.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .collect(java.util.stream.Collectors.toList());
            }
            while (backups.size() > MAX_BACKUPS) {
                Path oldest = backups.remove(0);
                Files.deleteIfExists(oldest);
            }
        } catch (IOException e) {
            System.err.println("[FuelCycleTracker] Failed to prune old backups: " + e.getMessage());
        }
    }

    public static void load() {
        loadTestModeFlag();

        Path file = saveFile();
        Path tmp  = saveFileTmp();

        if (!Files.exists(file) && Files.exists(tmp)) {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[FuelCycleTracker] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[FuelCycleTracker] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (Files.exists(file)) {
            try {
                String json = Files.readString(file);
                SaveData data = GSON.fromJson(json, SaveData.class);
                if (data != null) {
                    mobKillCoinsAt100Baseline = data.mobKillCoinsAt100Baseline;
                    minionExpiries.clear();
                    if (data.minionExpiries != null) minionExpiries.putAll(data.minionExpiries);
                    minionsCountedToday.clear();
                    if (data.minionsCountedToday != null) minionsCountedToday.addAll(data.minionsCountedToday);
                    minionsCountedTodayDate = data.minionsCountedTodayDate != null ? data.minionsCountedTodayDate : "";
                    if (data.days != null) {
                        dayRecords.clear();
                        dayRecords.putAll(data.days);
                    }
                    lastBackupTime = data.lastBackupTime;
                    lastTickTime = System.currentTimeMillis();

                    // Drop any per-minion fuel that already ran out while the game was closed —
                    // each survivor is re-checked individually rather than as one shared cycle.
                    long now = System.currentTimeMillis();
                    minionExpiries.values().removeIf(expiry -> now >= expiry);
                }
            } catch (Exception e) {
                System.err.println("[FuelCycleTracker] Load failed: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        // No new-format file yet for the active mode. Legacy migration only ever applies
        // to real (live) data — test mode simply starts empty instead of pulling in the
        // legacy live history.
        if (!testModeEnabled) {
            migrateLegacyFile();
        }
    }

    private static void migrateLegacyFile() {
        if (!Files.exists(LEGACY_SAVE_FILE)) return;

        // Back up the legacy file before touching it at all, so if anything goes wrong
        // during migration (corrupt data, a bug, etc.) the original is never lost. Uses
        // a timestamped name so repeated runs (or a prior failed migration) don't clobber
        // an earlier backup.
        Path backupFile = Paths.get("config/IslandTime/fuel_cycles.backup-"
                + java.time.Instant.now().toEpochMilli() + ".txt");
        try {
            Files.copy(LEGACY_SAVE_FILE, backupFile, StandardCopyOption.COPY_ATTRIBUTES);
            System.out.println("[FuelCycleTracker] Backed up legacy fuel_cycles.txt to " + backupFile.getFileName());
        } catch (IOException e) {
            System.err.println("[FuelCycleTracker] Failed to back up legacy fuel_cycles.txt before migration: " + e.getMessage());
            System.err.println("[FuelCycleTracker] Aborting migration to avoid risking data loss without a backup.");
            return;
        }

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

            // Note: the legacy CURRENT section (an in-progress single shared cycle,
            // with no per-minion breakdown) is intentionally not restored — it isn't
            // meaningfully portable to the new per-minion expiry model, so any
            // in-progress fuel simply starts fresh from the next fueling event.
            if (hasCurrentSection) {
                System.out.println("[FuelCycleTracker] Skipping legacy in-progress cycle (not portable to per-minion tracking); historical day records were still migrated.");
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
