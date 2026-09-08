package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * Tracks minion fuel usage per real 24h fuel-day for profit-per-day tracking.
 *
 * Each minion (keyed by the block position + full equipment of the armor stand entity
 * you right-clicked to open its GUI, falling back to the GUI title if that couldn't be
 * captured — see {@link MinionFuelTracker}) tracks its own fuel expiry independently —
 * fueling one minion never affects another's timer. A unit of fuel extends that
 * specific minion's own expiry by 24h, so placing a full stack of, say, 10 fuel
 * queues up 10 consecutive 24h burn periods for that minion. This per-minion state
 * is purely internal bookkeeping though: nothing about it is ever surfaced per
 * minion — day-level data (minionsRefueledAuto, AFK%, etc.) stays one aggregate
 * number per day-record, same as before.
 *
 * Crucially, a multi-fuel placement is NOT one aggregate record spanning the whole
 * batch — it's split into one {@link DayRecord} per real 24h fuel-day it queues up,
 * created immediately when the fuel is placed (even for days that won't become
 * "active" until hours/days later). Placing a stack of 4 fuel across a batch of
 * minions therefore creates 4 day-records right away: one currently ticking down,
 * three future/queued ones already carrying their share of the auto-detected minion
 * count and estimated bazaar fuel cost. Topping the same batch up with 2 more fuel
 * later creates 2 more day-records on top of whatever's already queued. Each day's
 * own "fuel day up" alert (see FuelExpiredAlertTracker) fires the moment THAT day's
 * fuel actually runs out — not just once, when the entire (possibly multi-day)
 * queued batch finally finishes — so the next queued day is already ticking by the
 * time you get pinged about the one that just ended. This is the entire point of
 * per-day tracking: it lets profit (mainly Crude Gabagool caught via sacks) be
 * attributed to the specific day it was earned on, not smeared across however many
 * days happened to be queued up front.
 *
 * A batch of minions is considered one "cohort" internally ({@code cohortStartMs}):
 * the span from when every tracked minion's fuel last fully ran dry until the next
 * fueling event starts a new cohort from scratch. Real 24h fuel-days are bucketed
 * within that cohort purely by elapsed wall-clock time since {@code cohortStartMs}
 * (not per-minion, and not by live calendar date), so a late-fueled or desynced
 * minion's units still land in whichever day-slot is actually current, and a day
 * that happens to straddle a calendar midnight is never split across two records.
 * Each {@link DayRecord}'s lookup key is still a calendar-date string (the date its
 * particular 24h slot started on) purely for backwards compatibility with the GUI
 * and existing saved data — it does not need to match the live calendar date when
 * the record was created or updated.
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
        cohortStartMs = 0;
        activeDaySlotIndex = 0;
        minionsCountedByDaySlot.clear();
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

    // Active per-minion fuel state: minion key (armor stand position+equipment, or GUI
    // title as a fallback — the same key MinionFuelTracker uses to distinguish minions)
    // -> that minion's own fuel expiry time. Each minion tracks its own remaining fuel
    // independently, so fueling 32 minions doesn't serialize their fuel-days onto one
    // shared timeline. An empty map means no minion currently has active fuel (no
    // cohort in progress).
    private static final ConcurrentHashMap<String, Long> minionExpiries = new ConcurrentHashMap<>();

    // Wall-clock time the current cohort started (0 = no cohort in progress). Real
    // 24h fuel-days are bucketed purely by elapsed time since this timestamp — see
    // the class javadoc and daySlotKey().
    private static volatile long cohortStartMs = 0;

    // The day-slot index (0 = the cohort's first 24h day, 1 = its second, etc.) that
    // is currently active/ticking — i.e. the next one that still needs to be closed
    // out once its 24h elapses. Reset to 0 whenever a fresh cohort starts.
    private static volatile int activeDaySlotIndex = 0;

    // Distinct minion keys already counted toward each day-slot's minionsRefueledAuto
    // total, keyed by that day-slot's DayRecord key, so topping up the same minion
    // again within the same 24h fuel-day doesn't double-count it. A minion topped up
    // far enough to reach a LATER day-slot still gets counted again for that new slot
    // (it genuinely has fuel queued for that day too). Cleared per-slot once that
    // slot closes, and entirely whenever a fresh cohort starts.
    private static final ConcurrentHashMap<String, Set<String>> minionsCountedByDaySlot = new ConcurrentHashMap<>();

    private static long    lastTickTime        = 0;
    private static boolean wasOnIslandLastTick  = false;

    // The lowest-BIN unit price snapshotted the FIRST time it was needed for the currently
    // active cohort (whichever cohortStartMs it was captured for), so every fuel unit
    // placed anywhere within the same cohort — even in separate onMinionFueled() calls
    // minutes or hours apart — is costed at the same price, instead of drifting with
    // the live bazaar between fuelings. Naturally invalidates itself once a new cohort
    // starts (cohortStartMs changes), since the guard below compares against it.
    private static volatile long   cachedFuelUnitCostCohortStart = -1;
    private static volatile double cachedFuelUnitCost           = -1;

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
     * Per-fuel-day profit tracking record — one real, exactly-24h "fuel day" within a
     * (possibly multi-day) queued batch, see the class javadoc. All monetary/coin
     * fields can be tweaked directly (e.g. from the fuel tracker GUI) without
     * affecting any other day's record.
     */
    public static class DayRecord {
        public String date; // yyyy-MM-dd of the calendar date this fuel-day's 24h span started on (used as the lookup key)

        /** Wall-clock time this fuel-day's 24h span started (i.e. when the previous
         *  day in its batch ended, or when the fueling event that queued it happened,
         *  whichever "started" the cohort). 0 for legacy/migrated records that predate
         *  this field (see {@link #formatCycleRange(DayRecord)} for the display fallback). */
        public long cycleStartMs = 0;

        /** Wall-clock time this specific fuel-day's own "fuel day up" alert fired (see
         *  {@link #tick(boolean)}), i.e. when this day's 24h span elapsed. 0 while this
         *  day is still in progress, still queued/in the future, or unknown
         *  (legacy/manual records) — see {@link #formatCycleRange(DayRecord)} for the
         *  display fallback. */
        public long cycleEndMs = 0;

        /** Minions refueled today, auto-detected from fuel placements. */
        public int minionsRefueledAuto = 0;

        /** Manual override for minions refueled — null means "use the auto-detected value".
         *  Useful when lag desyncs the automatic fuel-placement detection. */
        public Integer manualMinionCount = null;

        /** Milliseconds during which fuel was active and the tracked item's stats were increasing. */
        public long activeMs = 0;

        /** Legacy persisted total lowest-BIN cost of the fuel placed this day. */
        public double fuelCost = 0.0;

        /** Lowest-BIN recipe cost per minion, snapshotted when this cohort was first priced.
         *  Null for records saved before per-minion pricing was introduced. */
        public Double lockedFuelCostPerMinion = null;

        /** Manual override for this day's total fuel cost — null means "use the auto-estimated
         *  cost from {@link #lockedFuelCostPerMinion} and {@link #getEffectiveMinionCount()}". Useful when the live bazaar price at fueling time
         *  wasn't representative (e.g. you already had distillates stockpiled, or bought
         *  them at a different price than the bazaar snapshot used). */
        public Double manualFuelCost = null;

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

        /** Automatic total fuel cost, recalculated from the effective minion count. */
        public double getAutoFuelCost() {
            if (lockedFuelCostPerMinion != null && lockedFuelCostPerMinion >= 0.0) {
                return lockedFuelCostPerMinion * getEffectiveMinionCount();
            }

            // Older records only persisted a total. Scale that total when possible so
            // manual minion-count corrections still update their displayed cost.
            if (minionsRefueledAuto > 0) {
                return fuelCost * (getEffectiveMinionCount() / (double) minionsRefueledAuto);
            }
            return fuelCost;
        }

        /** Effective total fuel cost used for profit calc: manual override if set, else auto-estimated. */
        public double getEffectiveFuelCost() {
            return manualFuelCost != null ? manualFuelCost : getAutoFuelCost();
        }

        public double getAfkPercent() {
            return getAfkPercent(System.currentTimeMillis());
        }

        /**
         * AFK percentage using {@code now} as the current time, letting callers (e.g. a GUI
         * that wants to throttle live updates) freeze "now" for a batch of calculations
         * instead of it silently drifting between calls within the same render pass.
         *
         * While this fuel-day is still actively ticking (started, not yet closed), the
         * percentage is of time elapsed so far today — not the full 24h — so it reflects
         * real progress instead of instantly looking tiny right after the day starts and
         * only reaching a meaningful number near the end of the day. Once the day is
         * closed (or for legacy records with no start timestamp), it's simply activeMs
         * out of the full 24h day, same as before.
         */
        public double getAfkPercent(long now) {
            if (activeMs <= 0) return 0.0;
            long elapsed = DAY_MS;
            if (cycleEndMs <= 0 && cycleStartMs > 0) {
                long sinceStart = now - cycleStartMs;
                if (sinceStart <= 0) return 0.0; // future/queued day — hasn't started yet
                elapsed = Math.min(sinceStart, DAY_MS);
            }
            return Math.min(100.0, (activeMs * 100.0) / elapsed);
        }

        /**
         * AFK percentage of the full 24h day, regardless of whether the day is still in
         * progress. Unlike {@link #getAfkPercent(long)} (which uses elapsed-time-so-far as
         * the denominator for a live/in-progress day, purely for a more meaningful live
         * display), mob-kill coins must always be computed against the full day so they
         * accumulate smoothly as the day goes on instead of jumping around based on how much
         * of the day has elapsed.
         */
        private double getFullDayAfkPercent() {
            if (activeMs <= 0) return 0.0;
            return Math.min(100.0, (activeMs * 100.0) / DAY_MS);
        }

        public double getMobKillCoins() {
            return getMobKillCoins(System.currentTimeMillis());
        }

        public double getMobKillCoins(long now) {
            return mobKillCoinsAt100 * (getFullDayAfkPercent() / 100.0);
        }

        /** Effective gabagool amount used for profit calc: manual override if set, else auto-tracked. */
        public double getEffectiveGabagoolAmount() {
            return manualGabagoolAmount != null ? manualGabagoolAmount : gabagoolAmountAuto;
        }

        public double getGabagoolRevenue() {
            return gabagoolPrice * getEffectiveGabagoolAmount();
        }

        public double getProfit() {
            return getProfit(System.currentTimeMillis());
        }

        public double getProfit(long now) {
            return getMobKillCoins(now) + getGabagoolRevenue() - getEffectiveFuelCost();
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

    private static final DateTimeFormatter CYCLE_LABEL_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm");

    /**
     * Formats a day record's title as "{@code start - end}" (dd/MM/yy HH:mm each), spanning
     * from when this record's fuel cycle started to when it ended (or "now" if still in
     * progress). Legacy/manually-added records without real timestamps fall back to treating
     * {@link DayRecord#date} as a full calendar-day span, so old data still displays sensibly.
     */
    public static String formatCycleRange(DayRecord record) {
        long start = record.cycleStartMs;
        long end = record.cycleEndMs;
        if (start <= 0) {
            start = LocalDate.parse(record.date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (end <= 0) {
            end = start + DAY_MS;
        }
        return formatEpochMs(start) + " - " + formatEpochMs(end);
    }

    private static String formatEpochMs(long epochMs) {
        return CYCLE_LABEL_FORMAT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()));
    }

    private static DayRecord getOrCreateDayRecord(String date) {
        return dayRecords.computeIfAbsent(date, d -> {
            DayRecord record = new DayRecord(d);
            record.mobKillCoinsAt100 = mobKillCoinsAt100Baseline;
            record.cycleStartMs = System.currentTimeMillis();
            return record;
        });
    }

    /**
     * Computes the DayRecord key for the given day-slot index within a cohort that
     * started at {@code cohortStart} — the calendar date that particular 24h span
     * started on. Since each slot is exactly 24h after the last, this normally advances
     * by exactly one calendar day per index (aside from rare DST edge cases).
     */
    private static String daySlotKey(long cohortStart, int dayIndex) {
        long slotStartMs = cohortStart + (long) dayIndex * DAY_MS;
        return LocalDate.ofInstant(Instant.ofEpochMilli(slotStartMs), ZoneId.systemDefault()).toString();
    }

    /** Matches a modern armor-stand key from {@code MinionFuelTracker.buildArmorStandKey()}: a
     *  block position followed by '|'-separated equipment slots, e.g. "-39,213,10|...". */
    private static final java.util.regex.Pattern ARMOR_STAND_KEY_PATTERN =
            java.util.regex.Pattern.compile("^-?\\d+,-?\\d+,-?\\d+\\|.*");

    /**
     * Removes any {@code minionExpiries} entry whose key is <b>not</b> a modern
     * position+equipment armor-stand key (see {@code MinionFuelTracker.buildArmorStandKey()}) —
     * i.e. a bare GUI-title fallback key like {@code "Inferno Minion XI"} — but only when at
     * least one modern position-format key also exists at the same time.
     *
     * <p>A title-only key can't reliably represent one specific physical minion (every minion
     * of the same type/tier shares the same GUI title), so it's only ever meant to be a
     * last-resort fallback for the rare case a minion GUI opens without a captured armor-stand
     * right-click (e.g. via the favourite-GUI shortcut). If a title-only key is found alongside
     * real position-format keys, it's almost certainly stale data left over from before
     * position-based tracking existed, not a currently legitimate parallel mechanism — left
     * alone, such an entry can sit with an expiry far in the future indefinitely, which both
     * permanently blocks the cohort from ever being treated as "fully run dry" (since
     * {@code minionExpiries} never becomes empty) and keeps re-queuing a phantom single-minion
     * count onto every future day-slot up to its expiry. Purging it also decrements
     * {@code minionsRefueledAuto} on every not-yet-closed day-slot it had been counted toward,
     * so the bogus count doesn't linger in already-queued day records either.
     */
    private static void purgeStaleLegacyMinionKeys() {
        if (minionExpiries.isEmpty()) return;
        boolean hasModernKey = minionExpiries.keySet().stream()
                .anyMatch(k -> ARMOR_STAND_KEY_PATTERN.matcher(k).matches());
        if (!hasModernKey) return;

        List<String> legacyKeys = minionExpiries.keySet().stream()
                .filter(k -> !ARMOR_STAND_KEY_PATTERN.matcher(k).matches())
                .collect(java.util.stream.Collectors.toList());
        if (legacyKeys.isEmpty()) return;

        for (String legacyKey : legacyKeys) {
            minionExpiries.remove(legacyKey);
            for (Map.Entry<String, Set<String>> entry : minionsCountedByDaySlot.entrySet()) {
                if (entry.getValue().remove(legacyKey)) {
                    DayRecord record = dayRecords.get(entry.getKey());
                    if (record != null && record.minionsRefueledAuto > 0) {
                        record.minionsRefueledAuto -= 1;
                    }
                }
            }
            System.out.println("[FuelCycleTracker] Purged stale legacy (pre-armor-stand-tracking) "
                    + "minion key '" + legacyKey + "' from saved data.");
        }
        save();
    }

    /**
     * Returns the key of the record for the currently active fuel-day (see the class
     * javadoc), or today's calendar date if no cohort is currently in progress. This is
     * what "current"/"today's" fields (e.g. the moulconfig gabagool price/amount
     * overrides, and SackIncomeTracker's auto-tracked gabagool amount) should read/write
     * against, instead of the live calendar date directly — otherwise editing "today's"
     * values while an existing fuel-day is still running past midnight would silently
     * create a disconnected, mostly-empty record for the new calendar date rather than
     * updating the fuel-day that's actually in progress.
     */
    public static String getCurrentCycleDateKey() {
        return cohortStartMs > 0 ? daySlotKey(cohortStartMs, activeDaySlotIndex) : LocalDate.now().toString();
    }

    /** Returns the currently active fuel-day, without creating one when no fuel was placed. */
    public static DayRecord getToday() {
        return dayRecords.get(getCurrentCycleDateKey());
    }

    public static void setManualMinionCount(String date, Integer value) {
        DayRecord record = dayRecords.get(date);
        if (record == null) return;
        record.manualMinionCount = value;
        save();
    }

    public static void setGabagoolPrice(String date, double value) {
        DayRecord record = dayRecords.get(date);
        if (record == null) return;
        record.gabagoolPrice = Math.max(0.0, value);
        save();
    }

    /** Sets a manual override for gabagool amount, taking precedence over the auto-tracked value. */
    public static void setGabagoolAmount(String date, double value) {
        DayRecord record = dayRecords.get(date);
        if (record == null) return;
        record.manualGabagoolAmount = Math.max(0.0, value);
        save();
    }

    /** Clears the manual override, reverting the day back to using the auto-tracked gabagool amount. */
    public static void clearManualGabagoolAmount(String date) {
        DayRecord record = dayRecords.get(date);
        if (record == null) return;
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
        DayRecord record = dayRecords.get(date);
        if (record == null) return;
        record.gabagoolAmountAuto += delta;
        save();
    }

    /** Sets a manual override for this day's fuel cost, taking precedence over the auto-estimated value. */
    public static void setFuelCost(String date, double value) {
        DayRecord record = dayRecords.get(date);
        if (record == null) return;
        record.manualFuelCost = Math.max(0.0, value);
        save();
    }

    /** Clears the manual fuel cost override, reverting the day back to the auto-estimated bazaar cost. */
    public static void clearManualFuelCost(String date) {
        DayRecord record = dayRecords.get(date);
        if (record == null) return;
        record.manualFuelCost = null;
        save();
    }

    public static void setMobKillCoinsAt100(String date, double value) {
        DayRecord record = dayRecords.get(date);
        if (record == null) return;
        record.mobKillCoinsAt100 = Math.max(0.0, value);
        save();
    }

    public static void setNotes(String date, String value) {
        DayRecord record = dayRecords.get(date);
        if (record == null) return;
        record.notes = value != null ? value : "";
        save();
    }

    public static void removeDayRecord(String date) {
        if (dayRecords.remove(date) != null) {
            minionsCountedByDaySlot.remove(date);
            save();
        }
    }

    /**
     * Appends a brand new queued day directly after whichever existing day-record is
     * latest (by {@link DayRecord#cycleStartMs}), carrying over that day's effective
     * minion count and fuel cost as manual overrides — so the new day is immediately
     * useful without requiring any input, matching what queuing extra fuel-days from an
     * actual fuel placement would look like. Everything else (AFK/mob-kill coins,
     * gabagool, notes) starts fresh like any other new day. If no day-records exist yet,
     * starts a brand new queue from today with zero minions/cost.
     */
    public static DayRecord appendQueuedDay() {
        List<DayRecord> existing = getDayRecordsDescending();

        long nextCycleStart;
        int minionCount;
        double fuelCost;
        if (!existing.isEmpty()) {
            DayRecord last = existing.get(0);
            long lastStart = last.cycleStartMs > 0
                    ? last.cycleStartMs
                    : LocalDate.parse(last.date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            nextCycleStart = lastStart + DAY_MS;
            minionCount = last.getEffectiveMinionCount();
            fuelCost = last.getEffectiveFuelCost();
        } else {
            nextCycleStart = System.currentTimeMillis();
            minionCount = 0;
            fuelCost = 0.0;
        }

        // Guard against colliding with an already-existing record at that calendar date
        // (e.g. a rare DST edge case) by advancing a further day at a time until free.
        String date;
        do {
            date = LocalDate.ofInstant(Instant.ofEpochMilli(nextCycleStart), ZoneId.systemDefault()).toString();
            if (!dayRecords.containsKey(date)) break;
            nextCycleStart += DAY_MS;
        } while (true);

        DayRecord record = getOrCreateDayRecord(date);
        record.cycleStartMs = nextCycleStart;
        record.manualMinionCount = minionCount;
        record.manualFuelCost = fuelCost;
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
     * Removes a minion from the active fuel cohort when its fuel slot is observed empty.
     * Without this, manually emptied minions remain in {@code minionExpiries} until their
     * old tracked expiry, causing later fueling to be split across stale future day-slots.
     */
    public static void onMinionFuelRemoved(String minionKey) {
        if (minionKey == null || minionKey.isEmpty()) return;
        if (minionExpiries.remove(minionKey) == null) return;

        for (Map.Entry<String, Set<String>> entry : minionsCountedByDaySlot.entrySet()) {
            if (!entry.getValue().remove(minionKey)) continue;
            DayRecord record = dayRecords.get(entry.getKey());
            if (record != null && record.cycleEndMs <= 0 && record.minionsRefueledAuto > 0) {
                record.minionsRefueledAuto--;
            }
        }
        removeEmptyFutureDayRecords();

        if (minionExpiries.isEmpty()) {
            cohortStartMs = 0;
            activeDaySlotIndex = 0;
            minionsCountedByDaySlot.clear();
            wasOnIslandLastTick = false;
        }
        save();
    }

    /** Drops queued records that no longer have any tracked minions after stale fuel is removed. */
    private static void removeEmptyFutureDayRecords() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Set<String>> entry : minionsCountedByDaySlot.entrySet()) {
            if (!entry.getValue().isEmpty()) continue;
            DayRecord record = dayRecords.get(entry.getKey());
            if (record == null || record.cycleStartMs <= now
                    || record.minionsRefueledAuto > 0 || record.manualMinionCount != null) {
                continue;
            }
            dayRecords.remove(entry.getKey(), record);
            minionsCountedByDaySlot.remove(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Called when the minion identified by {@code minionKey} (the block position +
     * equipment of the armor stand entity right-clicked to open its GUI, or its GUI
     * title as a fallback — the same string {@link MinionFuelTracker} uses to tell
     * minions apart) is fueled with
     * {@code fuelCount} units of fuel (e.g. placing a stack of 10 at once). Each unit
     * extends that specific minion's own fuel expiry by 24h, queued back-to-back — other
     * minions' timers are untouched, so fueling a batch of minions never serializes their
     * fuel-days onto one shared timeline. Because the key is per-physical-minion rather
     * than per-title, fueling several minions of the same type/tier is correctly tracked
     * as separate minions instead of being merged into one.
     *
     * Per-minion data is only ever used internally for this expiry bookkeeping — the
     * day-level aggregate (minionsRefueledAuto, etc.) stays a single number per
     * day-record, not a breakdown per minion. Each of the {@code fuelCount} units gets
     * attributed to its own day-record (see the class javadoc) — placing 10 fuel
     * creates/updates 10 day-records immediately, not one record for the whole batch.
     *
     * If tracking is disabled, fueling is ignored for tracking purposes when this
     * minion has no fuel currently active. A minion whose fuel is already active keeps
     * running/extending regardless of the toggle — we never abandon data mid-cohort.
     */
    public static void onMinionFueled(String minionKey, int fuelCount) {
        if (fuelCount <= 0) return;
        if (minionKey == null || minionKey.isEmpty()) minionKey = "unknown";
        long now = System.currentTimeMillis();

        // Captured before mutating minionExpiries: true only when NO minion currently
        // has fuel tracked at all, i.e. this fueling event is starting a brand new
        // cohort from scratch (the previous one fully ran dry). Used below to decide
        // whether to reset the cohort's day-slot bookkeeping.
        boolean freshCycleStart = minionExpiries.isEmpty();

        Long existingExpiry = minionExpiries.get(minionKey);
        boolean hasActiveFuel = existingExpiry != null && now < existingExpiry;

        if (!hasActiveFuel && !trackingEnabled) {
            // Fresh start for this minion (no fuel currently tracked for it, or its
            // previous fuel already ran out) with tracking disabled — ignore it.
            minionExpiries.remove(minionKey);
            return;
        }

        if (freshCycleStart) {
            cohortStartMs = now;
            activeDaySlotIndex = 0;
            minionsCountedByDaySlot.clear();
        }

        // The wall-clock moment the FIRST of these newly-added units starts burning:
        // right where this minion's existing fuel leaves off if it has any active,
        // otherwise right now.
        long unitsStart = hasActiveFuel ? existingExpiry : now;
        minionExpiries.put(minionKey, unitsStart + (long) fuelCount * DAY_MS);

        if (lastTickTime == 0) lastTickTime = now;

        // Attribute each of the fuelCount units to the real 24h fuel-day it queues up —
        // bucketed purely by elapsed time since cohortStartMs (not per-minion, not by
        // live calendar date), so a stack of 10 fuel creates/updates 10 day-records
        // right away, each carrying its own share of the auto-detected minion count.
        for (int u = 0; u < fuelCount; u++) {
            long slotStart = unitsStart + (long) u * DAY_MS;
            int dayIndex = (int) ((slotStart - cohortStartMs) / DAY_MS);
            String daySlot = daySlotKey(cohortStartMs, dayIndex);
            DayRecord record = getOrCreateDayRecord(daySlot);
            record.cycleStartMs = cohortStartMs + (long) dayIndex * DAY_MS;

            Set<String> counted = minionsCountedByDaySlot.computeIfAbsent(daySlot, k -> ConcurrentHashMap.newKeySet());
            if (counted.add(minionKey)) {
                record.minionsRefueledAuto += 1;
            }
        }

        // Estimate the per-minion recipe price from the bazaar in the background so we
        // never block the client thread while a container screen is open. Each day gets
        // the same locked price for the cohort, while its total is derived from the
        // minion count attributed above. The unit price itself is snapshotted once per
        // cohort (see getCohortFuelUnitCost()) so re-fueling the same cohort later
        // doesn't cost different days at different live prices.
        final long unitsStartFinal  = unitsStart;
        final long cohortStartFinal = cohortStartMs;
        saveExecutor.submit(() -> {
            try {
                double unitCost = getCohortFuelUnitCost(cohortStartFinal);
                if (unitCost > 0) {
                    for (int u = 0; u < fuelCount; u++) {
                        long slotStart = unitsStartFinal + (long) u * DAY_MS;
                        int dayIndex = (int) ((slotStart - cohortStartFinal) / DAY_MS);
                        DayRecord r = dayRecords.get(daySlotKey(cohortStartFinal, dayIndex));
                        if (r != null) {
                            r.lockedFuelCostPerMinion = unitCost;
                            r.fuelCost = unitCost * r.minionsRefueledAuto;
                        }
                    }
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
     * Returns the lowest-BIN unit price to use for every fuel-cost calculation within the
     * cohort that started at {@code cohortStart}, fetching and caching it from the live
     * bazaar the first time it's needed for that cohort and simply returning the cached
     * value for every later call (from the same or a different fueling event) within
     * that same cohort — so a day's recorded fuel cost is never affected by however the
     * live bazaar price happens to move later in the cohort's lifetime. A new cohort
     * (different {@code cohortStart}) always re-fetches a fresh price. If the live fetch
     * fails (returns <= 0, e.g. no network), nothing is cached, so the next call retries.
     */
    private static synchronized double getCohortFuelUnitCost(long cohortStart) {
        if (cachedFuelUnitCostCohortStart == cohortStart && cachedFuelUnitCost > 0) {
            return cachedFuelUnitCost;
        }
        double unitCost = PriceCache.getInfernoFuelUnitCost(null);
        if (unitCost > 0) {
            cachedFuelUnitCost = unitCost;
            cachedFuelUnitCostCohortStart = cohortStart;
        }
        return unitCost;
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

        // The whole cohort (every currently-tracked minion) is only truly over once the
        // LAST (latest-expiring) minion's own fuel runs out.
        long maxExpiry = 0;
        for (long expiry : minionExpiries.values()) {
            if (expiry > maxExpiry) maxExpiry = expiry;
        }

        boolean cohortEnding = now >= maxExpiry;
        long clampedNow = cohortEnding ? maxExpiry : now;
        int currentDayIndex = (int) Math.max(0, (clampedNow - cohortStartMs) / DAY_MS);

        // Close out (set cycleEndMs + fire that day's own "fuel day up" alert) every
        // day-slot that has fully elapsed since we last ticked. Each real 24h fuel-day
        // gets its own alert the moment IT runs out — not just once, when the entire
        // (possibly multi-day) queued batch finally finishes — so by the time you're
        // pinged about one day, the next queued day is already ticking.
        while (activeDaySlotIndex < currentDayIndex) {
            closeDaySlot(activeDaySlotIndex);
            activeDaySlotIndex++;
        }

        if (cohortEnding) {
            closeDaySlot(currentDayIndex);
            minionExpiries.clear();
            // The cohort is over — reset so the next fueling event starts a fresh one.
            cohortStartMs = 0;
            activeDaySlotIndex = 0;
            minionsCountedByDaySlot.clear();
            wasOnIslandLastTick = false;
            save();
            return;
        }

        // Individual minions whose own fuel already ran out (but weren't the last to)
        // silently drop out here — no separate alert for them, only the per-day one above.
        minionExpiries.values().removeIf(expiry -> now >= expiry);

        // Accumulate active time against the currently active day-slot's record — still
        // runs while any minion has active fuel, even if tracking was disabled after
        // this cohort started
        if (wasOnIslandLastTick && isStatsIncreasing && lastTickTime > 0) {
            long delta = now - lastTickTime;
            if (delta < 5000) {
                String daySlot = daySlotKey(cohortStartMs, currentDayIndex);
                DayRecord record = dayRecords.get(daySlot);
                if (record != null) {
                    record.cycleStartMs = cohortStartMs + (long) currentDayIndex * DAY_MS;
                    record.activeMs += delta;
                }
            }
        }

        lastTickTime        = now;
        wasOnIslandLastTick = isStatsIncreasing;

        if (now - lastSaveTime >= SAVE_INTERVAL_MS) {
            lastSaveTime = now;
            save();
        }
    }

    /**
     * Closes out the record for the given day-slot index (if not already closed),
     * setting its {@code cycleEndMs} to that slot's real end time and firing the
     * shared "fuel day up" alert for it — using THAT day's own auto-detected minion
     * count, not the whole (possibly multi-day) batch's count.
     */
    private static void closeDaySlot(int dayIndex) {
        String daySlot = daySlotKey(cohortStartMs, dayIndex);
        DayRecord record = dayRecords.get(daySlot);
        if (record == null || record.cycleEndMs > 0) return; // missing or already closed
        record.cycleEndMs = cohortStartMs + (long) (dayIndex + 1) * DAY_MS;
        minionsCountedByDaySlot.remove(daySlot);
        FuelExpiredAlertTracker.onFuelDayUp(record.minionsRefueledAuto);
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
                Component.literal("» " + formatCycleRange(record)).withStyle(ChatFormatting.GOLD));
        client.player.sendSystemMessage(
                Component.literal("  Minions: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(record.getEffectiveMinionCount())).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" | AFK: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(String.format("%.1f%%", record.getAfkPercent())).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" | Fuel Cost: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(PriceCache.formatCoins(record.getEffectiveFuelCost())).withStyle(ChatFormatting.WHITE)));
        client.player.sendSystemMessage(
                Component.literal("  Coins: ").withStyle(ChatFormatting.GRAY)
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
        long cohortStartMs;
        int activeDaySlotIndex;
        Map<String, Set<String>> minionsCountedByDaySlot;
        double mobKillCoinsAt100Baseline;
        Map<String, DayRecord> days;
        long lastBackupTime;
    }

    private static SaveData buildSaveData() {
        SaveData data = new SaveData();
        data.minionExpiries             = new java.util.HashMap<>(minionExpiries);
        data.cohortStartMs              = cohortStartMs;
        data.activeDaySlotIndex         = activeDaySlotIndex;
        data.minionsCountedByDaySlot    = new java.util.HashMap<>();
        for (Map.Entry<String, Set<String>> e : minionsCountedByDaySlot.entrySet()) {
            data.minionsCountedByDaySlot.put(e.getKey(), new java.util.HashSet<>(e.getValue()));
        }
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
                    cohortStartMs = data.cohortStartMs;
                    activeDaySlotIndex = data.activeDaySlotIndex;
                    minionsCountedByDaySlot.clear();
                    if (data.minionsCountedByDaySlot != null) {
                        for (Map.Entry<String, Set<String>> e : data.minionsCountedByDaySlot.entrySet()) {
                            minionsCountedByDaySlot.put(e.getKey(), ConcurrentHashMap.newKeySet());
                            minionsCountedByDaySlot.get(e.getKey()).addAll(e.getValue());
                        }
                    }
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

                    purgeStaleLegacyMinionKeys();
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
