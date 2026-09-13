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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
        minionFuelUnitsQueued.clear();
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

    static final long DAY_MS = 24 * 60 * 60 * 1000L;
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

    // Active per-minion fuel state: minion key (a permanent minion-upgrade uuid where the
    // minion has one — see MinionIdentityRegistry — otherwise the older armor stand
    // position+equipment key, or the GUI title as a last-resort fallback; the same key
    // MinionFuelTracker uses to distinguish minions)
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

    // Number of fuel units already queued for each minion in the current cohort. This
    // keeps a minion's cycle number independent of the exact moment its GUI was opened,
    // so refueling a full set cannot split one cycle across several day records merely
    // because the minions were clicked in sequence.
    private static final ConcurrentHashMap<String, Integer> minionFuelUnitsQueued = new ConcurrentHashMap<>();

    private static long    lastTickTime        = 0;
    private static boolean wasOnIslandLastTick  = false;

    // The buy-order unit price snapshotted the FIRST time it was needed for the currently
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
        /** True when the user manually corrected this day's displayed timestamps. */
        public boolean manualCycleTimes = false;
        /** Internal marker distinguishing a manually-entered end from a completed day. */
        public boolean cycleClosed = false;

        /** Minions refueled today, auto-detected from fuel placements. */
        public int minionsRefueledAuto = 0;

        /** Manual override for minions refueled — null means "use the auto-detected value".
         *  Useful when lag desyncs the automatic fuel-placement detection. */
        public Integer manualMinionCount = null;

        /** Milliseconds during which fuel was active and the tracked item's stats were increasing. */
        public long activeMs = 0;

        /** Legacy persisted total buy-order cost of the fuel placed this day. */
        public double fuelCost = 0.0;

        /** Buy-order recipe cost per minion, snapshotted when this cohort was first priced.
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
        /** True when this record's price was explicitly overridden instead of inherited from MoulConfig. */
        public boolean manualGabagoolPrice = false;

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
         * While a fuel-day is still actively ticking (including a manually corrected
         * record whose edited end is still in the future), the percentage is of time
         * elapsed since its start. Once it has ended, active time is measured against
         * the complete cycle duration.
         */
        public double getAfkPercent(long now) {
            if (activeMs <= 0) return 0.0;
            boolean manuallyTimed = manualCycleTimes && cycleEndMs > cycleStartMs;
            long duration = manuallyTimed ? cycleEndMs - cycleStartMs : DAY_MS;
            if (manuallyTimed && now < cycleEndMs) {
                long sinceStart = now - cycleStartMs;
                if (sinceStart <= 0) return 0.0;
                // Do not cap this branch: shifting a start time forward can legitimately
                // make already-recorded active time exceed the newly elapsed wall-clock time.
                return (activeMs * 100.0) / sinceStart;
            }

            long elapsed = duration;
            if (!manuallyTimed && cycleEndMs <= 0 && cycleStartMs > 0) {
                long sinceStart = now - cycleStartMs;
                if (sinceStart <= 0) return 0.0; // future/queued day — hasn't started yet
                elapsed = Math.min(sinceStart, duration);
            }
            double percent = (activeMs * 100.0) / elapsed;
            return Math.min(100.0, percent);
        }

        /**
         * AFK percentage of the full cycle duration, regardless of whether the day is still
         * in progress. Manually corrected cycle durations are used here too, so mob-kill
         * coins remain consistent with an edited timeframe instead of silently reverting to
         * a 24h denominator.
         */
        private double getFullDayAfkPercent() {
            if (activeMs <= 0) return 0.0;
            boolean manuallyTimed = manualCycleTimes && cycleEndMs > cycleStartMs;
            long duration = manuallyTimed ? cycleEndMs - cycleStartMs : DAY_MS;
            double percent = (activeMs * 100.0) / duration;
            return manuallyTimed ? percent : Math.min(100.0, percent);
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
        list.sort(Comparator.comparingLong(FuelCycleTracker::recordStartForSorting).reversed());
        return list;
    }

    private static long recordStartForSorting(DayRecord record) {
        if (record.cycleStartMs > 0) return record.cycleStartMs;
        try {
            return LocalDate.parse(record.date.substring(0, 10)).atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            return Long.MIN_VALUE;
        }
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
            start = LocalDate.parse(record.date.substring(0, 10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
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

    private static DayRecord getOrCreateCycleDayRecord(long cohortStart, int dayIndex, boolean freshCohort) {
        long slotStart = cohortStart + (long) dayIndex * DAY_MS;
        String date = daySlotKey(cohortStart, dayIndex);
        DayRecord existing = dayRecords.get(date);
        if (freshCohort && existing != null && existing.cycleStartMs > 0 && existing.cycleStartMs != slotStart) {
            String archivedKey = date + "~" + existing.cycleStartMs;
            while (dayRecords.containsKey(archivedKey)) {
                archivedKey += "~";
            }
            dayRecords.remove(date);
            existing.date = archivedKey;
            dayRecords.put(archivedKey, existing);
            Set<String> counted = minionsCountedByDaySlot.remove(date);
            if (counted != null) minionsCountedByDaySlot.put(archivedKey, counted);
        }
        return getOrCreateDayRecord(date);
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

    /** True when {@code key} is a position+equipment armor-stand key (see the pattern above). */
    static boolean isArmorStandFormatKey(String key) {
        return key != null && ARMOR_STAND_KEY_PATTERN.matcher(key).matches();
    }

    /**
     * True for any key that identifies one specific physical minion: either a permanent
     * upgrade-uuid key (the preferred modern form, see {@link MinionIdentityRegistry}) or
     * the older position+equipment armor-stand key. A bare GUI title is neither.
     */
    private static boolean isPerMinionKey(String key) {
        return MinionIdentityRegistry.isUuidKey(key) || isArmorStandFormatKey(key);
    }

    /**
     * Returns every stored armor-stand key recorded at the given block position, other than
     * {@code exceptKey}.
     *
     * <p>An armor-stand key is "{@code x,y,z}" followed by the stand's equipment, and keys
     * saved before the hand slots were excluded also contain whatever the minion was
     * holding — an item Hypixel re-randomizes every time the minion loads in. Those keys
     * therefore changed on almost every rejoin, stranding the minion's saved fuel-days
     * under keys that will never be matched again. The block position, by contrast, is
     * stable and holds exactly one minion, so it is the reliable half of the key and the
     * right thing to re-attach stranded state by.
     *
     * @param position the "{@code x,y,z}" prefix of an armor-stand key.
     */
    public static List<String> findMinionKeysAtPosition(String position, String exceptKey) {
        if (position == null || position.isEmpty()) return List.of();
        String prefix = position + "|";

        Set<String> candidates = new HashSet<>(minionExpiries.keySet());
        candidates.addAll(minionFuelUnitsQueued.keySet());
        for (Set<String> counted : minionsCountedByDaySlot.values()) candidates.addAll(counted);

        return candidates.stream()
                .filter(key -> key.startsWith(prefix))
                .filter(key -> !key.equals(exceptKey))
                .sorted()
                .toList();
    }

    /**
     * Moves all saved per-minion fuel state from {@code oldKey} onto {@code newKey}, used
     * when a minion previously tracked by its armor stand's position+equipment is
     * re-identified by a permanent minion-upgrade uuid (see {@link MinionIdentityRegistry}).
     * Purely a rename: expiries, queued fuel-unit counts and per-day-slot attribution all
     * carry over untouched, so an in-progress or queued fuel-day keeps running exactly as
     * it was.
     *
     * <p>If {@code newKey} somehow already has state of its own (e.g. two upgrades that
     * were previously registered separately ending up in the same minion), the two are
     * merged conservatively — the longer expiry and larger queued count win, and any
     * day-slot that had counted both keys as separate minions is decremented back to one
     * so the day's minion count stays accurate.
     *
     * @return true when anything was actually migrated.
     */
    public static boolean renameMinionKey(String oldKey, String newKey) {
        if (oldKey == null || newKey == null) return false;
        if (oldKey.isEmpty() || newKey.isEmpty() || oldKey.equals(newKey)) return false;

        boolean changed = false;

        Long expiry = minionExpiries.remove(oldKey);
        if (expiry != null) {
            Long existing = minionExpiries.get(newKey);
            if (existing != null) {
                System.out.println("[FuelCycleTracker] Minion key '" + newKey + "' already had fuel state "
                        + "while migrating '" + oldKey + "' onto it; merging conservatively.");
            }
            minionExpiries.put(newKey, existing == null ? expiry : Math.max(existing, expiry));
            changed = true;
        }

        Integer queued = minionFuelUnitsQueued.remove(oldKey);
        if (queued != null) {
            Integer existing = minionFuelUnitsQueued.get(newKey);
            minionFuelUnitsQueued.put(newKey, existing == null ? queued : Math.max(existing, queued));
            changed = true;
        }

        for (Map.Entry<String, Set<String>> entry : minionsCountedByDaySlot.entrySet()) {
            if (!entry.getValue().remove(oldKey)) continue;
            changed = true;
            if (!entry.getValue().add(newKey)) {
                // Both keys were counted toward this day slot, but they are the same
                // physical minion — drop the now-duplicated count.
                DayRecord record = dayRecords.get(entry.getKey());
                if (record != null && record.minionsRefueledAuto > 0) record.minionsRefueledAuto--;
            }
        }

        if (changed) {
            System.out.println("[FuelCycleTracker] Migrated minion key '" + oldKey + "' -> '" + newKey + "'.");
            save();
        }
        return changed;
    }

    /**
     * Removes any saved per-minion entry whose key identifies no specific physical
     * minion — i.e. a bare GUI-title fallback key like {@code "Inferno Minion XI"}, rather
     * than a permanent upgrade-uuid key or a position+armor armor-stand key (see
     * {@link #isPerMinionKey(String)}) — but only when at least one per-minion key also
     * exists at the same time.
     *
     * <p>A title-only key can't reliably represent one specific physical minion (every minion
     * of the same type/tier shares the same GUI title), so it's only ever meant to be a
     * last-resort fallback for the rare case a minion GUI opens without a captured armor-stand
     * right-click (e.g. via the favourite-GUI shortcut). If a title-only key is found alongside
     * real per-minion keys, it's almost certainly stale data left over from before
     * per-minion tracking existed, not a currently legitimate parallel mechanism — left
     * alone, such an entry can sit with an expiry far in the future indefinitely, which both
     * permanently blocks the cohort from ever being treated as "fully run dry" (since
     * {@code minionExpiries} never becomes empty) and keeps re-queuing a phantom single-minion
     * count onto every future day-slot up to its expiry. Purging it also decrements
     * {@code minionsRefueledAuto} on every not-yet-closed day-slot it had been counted toward,
     * so the bogus count doesn't linger in already-queued day records either.
     *
     * <p>{@code minionFuelUnitsQueued} is swept as well, and separately, because a stale
     * title-only key does not always appear in both maps — a fallback key that picked up
     * the name of the fuel item rather than a minion (e.g. {@code "Inferno Minion Fuel"})
     * can be left behind in the queued map alone, where it would otherwise never be
     * reachable by any real minion and never be cleaned up.
     */
    private static void purgeStaleLegacyMinionKeys() {
        if (minionExpiries.isEmpty()) return;
        boolean hasModernKey = minionExpiries.keySet().stream()
                .anyMatch(FuelCycleTracker::isPerMinionKey);
        if (!hasModernKey) return;

        boolean changed = false;

        List<String> legacyKeys = minionExpiries.keySet().stream()
                .filter(k -> !isPerMinionKey(k))
                .collect(java.util.stream.Collectors.toList());

        for (String legacyKey : legacyKeys) {
            minionExpiries.remove(legacyKey);
            minionFuelUnitsQueued.remove(legacyKey);
            changed = true;
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

        // Sweep queued-only leftovers: a stale fallback key with no expiry of its own is
        // invisible to the loop above, but still inflates this minion's queued fuel count
        // if it is ever matched again.
        List<String> queuedOnlyKeys = minionFuelUnitsQueued.keySet().stream()
                .filter(k -> !isPerMinionKey(k))
                .collect(java.util.stream.Collectors.toList());

        for (String queuedKey : queuedOnlyKeys) {
            minionFuelUnitsQueued.remove(queuedKey);
            changed = true;
            for (Map.Entry<String, Set<String>> entry : minionsCountedByDaySlot.entrySet()) {
                if (entry.getValue().remove(queuedKey)) {
                    DayRecord record = dayRecords.get(entry.getKey());
                    if (record != null && record.minionsRefueledAuto > 0) {
                        record.minionsRefueledAuto -= 1;
                    }
                }
            }
            System.out.println("[FuelCycleTracker] Purged stale queued-fuel entry for non-minion key '"
                    + queuedKey + "' from saved data.");
        }

        if (changed) save();
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
        record.manualGabagoolPrice = true;
        save();
    }

    /** Applies the configured default without turning it into a per-day manual override. */
    public static void applyDefaultGabagoolPrice(String date, double value) {
        DayRecord record = dayRecords.get(date);
        if (record == null || record.manualGabagoolPrice) return;
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

    public static String formatEditableTimestamp(long epochMs) {
        return CYCLE_LABEL_FORMAT.format(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()));
    }

    public static long parseEditableTimestamp(String value) {
        LocalDateTime parsed = LocalDateTime.parse(value.trim(), CYCLE_LABEL_FORMAT);
        return parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * Updates a record's cycle timestamps. For an active or queued record, later records
     * are moved with it so the queue remains contiguous; completed historical records are
     * left untouched unless they are the record explicitly edited.
     */
    public static void setCycleTimes(String date, long startMs, long endMs) {
        DayRecord selected = dayRecords.get(date);
        if (selected == null || startMs <= 0 || endMs <= startMs) return;

        long oldStart = selected.cycleStartMs;
        if (oldStart <= 0) oldStart = startMs;
        long oldEnd = selected.cycleEndMs;
        long oldEffectiveEnd = oldEnd > oldStart ? oldEnd : oldStart + DAY_MS;
        boolean wasActiveRecord = cohortStartMs > 0
                && date.equals(daySlotKey(cohortStartMs, activeDaySlotIndex));
        selected.cycleStartMs = startMs;
        selected.cycleEndMs = endMs;
        selected.manualCycleTimes = true;
        selected.cycleClosed = oldEnd > 0;

        if (wasActiveRecord) {
            long shift = endMs - oldEffectiveEnd;
            long newCohortStart = startMs - (long) activeDaySlotIndex * DAY_MS;
            String newKey = daySlotKey(newCohortStart, activeDaySlotIndex);
            if (!date.equals(newKey)) {
                DayRecord collision = dayRecords.get(newKey);
                if (collision != null && collision != selected) {
                    String archivedKey = newKey + "~" + collision.cycleStartMs;
                    while (dayRecords.containsKey(archivedKey)) archivedKey += "~";
                    dayRecords.remove(newKey);
                    collision.date = archivedKey;
                    dayRecords.put(archivedKey, collision);
                    Set<String> collisionCounted = minionsCountedByDaySlot.remove(newKey);
                    if (collisionCounted != null) {
                        minionsCountedByDaySlot.put(archivedKey, collisionCounted);
                    }
                }
                dayRecords.remove(date);
                selected.date = newKey;
                dayRecords.put(newKey, selected);
                Set<String> counted = minionsCountedByDaySlot.remove(date);
                if (counted != null) minionsCountedByDaySlot.put(newKey, counted);
            }
            cohortStartMs = newCohortStart;
            if (shift != 0) {
                for (Map.Entry<String, Long> entry : minionExpiries.entrySet()) {
                    entry.setValue(entry.getValue() + shift);
                }
            }
        }

        long now = System.currentTimeMillis();
        boolean activeOrQueued = oldEnd <= 0 || oldStart >= now || oldEnd > now;
        if (activeOrQueued) {
            final long oldStartForSort = oldStart;
            List<DayRecord> ordered = new ArrayList<>(dayRecords.values());
            ordered.sort(Comparator.comparingLong(record -> {
                if (record == selected) return oldStartForSort;
                if (record.cycleStartMs > 0) return record.cycleStartMs;
                try {
                    return LocalDate.parse(record.date.substring(0, 10)).atStartOfDay(ZoneId.systemDefault())
                            .toInstant().toEpochMilli();
                } catch (RuntimeException ignored) {
                    return Long.MAX_VALUE;
                }
            }));
            int selectedIndex = ordered.indexOf(selected);
            if (selectedIndex >= 0) {
                long previousEnd = endMs;
                for (int i = selectedIndex + 1; i < ordered.size(); i++) {
                    DayRecord next = ordered.get(i);
                    if (next.date != null && next.date.contains("~")) continue;
                    long duration = next.cycleEndMs > next.cycleStartMs
                            ? next.cycleEndMs - next.cycleStartMs : DAY_MS;
                    next.cycleStartMs = previousEnd;
                    next.cycleEndMs = previousEnd + duration;
                    next.manualCycleTimes = true;
                    previousEnd = next.cycleEndMs;
                }
            }
        }
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
                    : recordStartForSorting(last);
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
        if (!removeMinionFuelState(minionKey)) return;

        removeEmptyFutureDayRecords();

        resetCohortIfEmpty();
        save();
    }

    /** Removes one minion's expiry and its contribution to any still-open day slots. */
    private static boolean removeMinionFuelState(String minionKey) {
        if (minionExpiries.remove(minionKey) == null) return false;
        minionFuelUnitsQueued.remove(minionKey);

        for (Map.Entry<String, Set<String>> entry : minionsCountedByDaySlot.entrySet()) {
            if (!entry.getValue().remove(minionKey)) continue;
            DayRecord record = dayRecords.get(entry.getKey());
            if (record != null && record.cycleEndMs <= 0 && record.minionsRefueledAuto > 0) {
                record.minionsRefueledAuto--;
            }
        }
        return true;
    }

    /** Removes expired state left behind when a minion was not opened as its fuel ran out. */
    private static void removeExpiredMinionFuel(long now) {
        List<String> expiredKeys = minionExpiries.entrySet().stream()
                .filter(entry -> entry.getValue() <= now)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
        for (String expiredKey : expiredKeys) {
            removeExpiredMinionState(expiredKey, now);
        }
        if (!expiredKeys.isEmpty()) {
            removeEmptyFutureDayRecords();
            resetCohortIfEmpty();
        }
    }

    /** Reconstructs a legacy minion's queued cycle count from the saved day-slot sets. */
    private static int inferQueuedFuelUnits(String minionKey, long cohortStart) {
        int highestDayIndex = -1;
        for (Map.Entry<String, Set<String>> entry : minionsCountedByDaySlot.entrySet()) {
            if (!entry.getValue().contains(minionKey)) continue;
            DayRecord record = dayRecords.get(entry.getKey());
            if (record == null || record.cycleStartMs <= 0) continue;
            int dayIndex = (int) Math.max(0, Math.round(
                    (record.cycleStartMs - cohortStart) / (double) DAY_MS));
            highestDayIndex = Math.max(highestDayIndex, dayIndex);
        }
        if (highestDayIndex >= 0) return highestDayIndex + 1;

        Long expiry = minionExpiries.get(minionKey);
        if (expiry == null || expiry <= cohortStart) return 0;
        return Math.max(1, (int) Math.round((expiry - cohortStart) / (double) DAY_MS));
    }

    /** Removes expired state without changing counts for day-slots that already started. */
    private static boolean removeExpiredMinionState(String minionKey, long now) {
        if (minionExpiries.remove(minionKey) == null) return false;

        for (Map.Entry<String, Set<String>> entry : minionsCountedByDaySlot.entrySet()) {
            if (!entry.getValue().remove(minionKey)) continue;
            DayRecord record = dayRecords.get(entry.getKey());
            if (record != null && record.cycleStartMs > now && record.cycleEndMs <= 0
                    && record.minionsRefueledAuto > 0) {
                record.minionsRefueledAuto--;
            }
        }
        return true;
    }

    /** Starts the next fuel cohort from the next actual fueling event once none remain active. */
    private static void resetCohortIfEmpty() {
        if (!minionExpiries.isEmpty()) return;
        cohortStartMs = 0;
        activeDaySlotIndex = 0;
        minionsCountedByDaySlot.clear();
        minionFuelUnitsQueued.clear();
        wasOnIslandLastTick = false;
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
     * Called when the minion identified by {@code minionKey} (its permanent
     * minion-upgrade uuid where it has one, else the block position + equipment of the
     * armor stand entity right-clicked to open its GUI, or its GUI title as a final
     * fallback — the same string {@link MinionFuelTracker} uses to tell
     * minions apart) is fueled with
     * {@code fuelCount} units of fuel (e.g. placing a stack of 10 at once). While fuel is
     * still active, each unit extends that specific minion's own fuel expiry by 24h,
     * queued back-to-back — other minions' timers are untouched, so fueling a batch of
     * minions never serializes their fuel-days onto one shared timeline. If its previous
     * fuel has already run out, the new units start at this fueling event instead of being
     * appended to the old expiry. Because the key is per-physical-minion rather than
     * per-title, fueling several minions of the same type/tier is correctly tracked as
     * separate minions instead of being merged into one.
     *
     * Per-minion data is only ever used internally for this expiry and cycle-number
     * bookkeeping — the day-level aggregate (minionsRefueledAuto, etc.) stays a single
     * number per day-record, not a breakdown per minion. Each of the {@code fuelCount}
     * units gets attributed to its own cycle record; a full set refueled one unit each
     * therefore contributes all minions to the same next cycle even when the GUIs are
     * opened at slightly different times.
     *
     * If tracking is disabled, fueling is ignored for tracking purposes when this
     * minion has no fuel currently active. A minion whose fuel is already active keeps
     * running/extending regardless of the toggle — we never abandon data mid-cohort.
     */
    public static void onMinionFueled(String minionKey, int fuelCount) {
        if (fuelCount <= 0) return;
        if (minionKey == null || minionKey.isEmpty()) minionKey = "unknown";
        long now = System.currentTimeMillis();

        // Discard expired entries that were not observed through a minion GUI while
        // they ran dry. This is important after downtime: the next refuel must start
        // at the actual fueling time rather than being appended to the old expiry.
        removeExpiredMinionFuel(now);

        // True only when NO minion currently has active fuel, i.e. this fueling event
        // is starting a brand new cohort from scratch.
        boolean freshCycleStart = minionExpiries.isEmpty();

        Long existingExpiry = minionExpiries.get(minionKey);
        boolean hasActiveFuel = existingExpiry != null && now < existingExpiry;

        if (!hasActiveFuel && !trackingEnabled) {
            // Fresh start for this minion (no fuel currently tracked for it, or its
            // previous fuel already ran out) with tracking disabled — ignore it.
            minionExpiries.remove(minionKey);
            minionFuelUnitsQueued.remove(minionKey);
            return;
        }

        if (freshCycleStart) {
            cohortStartMs = now;
            activeDaySlotIndex = 0;
            minionsCountedByDaySlot.clear();
            minionFuelUnitsQueued.clear();
        }

        // The wall-clock moment the FIRST of these newly-added units starts burning:
        // right where this minion's existing fuel leaves off if it has any active,
        // otherwise right now.
        long unitsStart = hasActiveFuel ? existingExpiry : now;
        minionExpiries.put(minionKey, unitsStart + (long) fuelCount * DAY_MS);

        int queuedBefore = hasActiveFuel
                ? minionFuelUnitsQueued.getOrDefault(minionKey, inferQueuedFuelUnits(minionKey, cohortStartMs))
                : 0;
        int firstDayIndex = hasActiveFuel
                ? queuedBefore
                : Math.max(0, (int) ((now - cohortStartMs) / DAY_MS));
        minionFuelUnitsQueued.put(minionKey, queuedBefore + fuelCount);

        if (lastTickTime == 0) lastTickTime = now;

        // Attribute each newly added unit to the minion's next cycle number. This is
        // deliberately not derived from the exact timestamp: opening 32 minion GUIs
        // sequentially must still put their second fuel into one shared second cycle.
        for (int u = 0; u < fuelCount; u++) {
            int dayIndex = firstDayIndex + u;
            String daySlot = daySlotKey(cohortStartMs, dayIndex);
            DayRecord record = getOrCreateCycleDayRecord(cohortStartMs, dayIndex, freshCycleStart);
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
        final long cohortStartFinal = cohortStartMs;
        final int firstDayIndexFinal = firstDayIndex;
        saveExecutor.submit(() -> {
            try {
                double unitCost = getCohortFuelUnitCost(cohortStartFinal);
                if (unitCost > 0) {
                    for (int u = 0; u < fuelCount; u++) {
                        int dayIndex = firstDayIndexFinal + u;
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
     * Returns the buy-order unit price to use for every fuel-cost calculation within the
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
        double unitCost = PriceCache.getInfernoFuelUnitCost(PriceCache.getBazaar());
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
        // Fire the "fuel day up" alert purely off wall-clock time, independent of the
        // minion-fuel cohort tracking below — this is what keeps the alert in lockstep
        // with whatever /it fueltime displays as a day's end (see formatCycleRange()),
        // instead of only firing while a minion cohort happens to still be tracked.
        checkDueDayRecords(System.currentTimeMillis());

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
            minionFuelUnitsQueued.clear();
            wasOnIslandLastTick = false;
            save();
            return;
        }

        // Individual minions whose own fuel already ran out (but weren't the last to)
        // silently drop out here — no separate alert for them, only the per-day one above.
        removeExpiredMinionFuel(now);

        // Accumulate active time against the currently active day-slot's record — still
        // runs while any minion has active fuel, even if tracking was disabled after
        // this cohort started
        if (wasOnIslandLastTick && isStatsIncreasing && lastTickTime > 0) {
            long delta = now - lastTickTime;
            if (delta < 5000) {
                String daySlot = daySlotKey(cohortStartMs, currentDayIndex);
                DayRecord record = dayRecords.get(daySlot);
                if (record != null) {
                    if (!record.manualCycleTimes) {
                        record.cycleStartMs = cohortStartMs + (long) currentDayIndex * DAY_MS;
                    }
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
        if (record == null || (!record.manualCycleTimes && record.cycleEndMs > 0)
                || (record.manualCycleTimes && record.cycleClosed)) return;
        if (!record.manualCycleTimes) {
            record.cycleEndMs = cohortStartMs + (long) (dayIndex + 1) * DAY_MS;
        }
        record.cycleClosed = true;
        minionsCountedByDaySlot.remove(daySlot);
        FuelExpiredAlertTracker.onFuelDayUp(record.minionsRefueledAuto);
    }

    /**
     * Fires the "fuel day up" alert for — and closes out — every day-record whose own
     * 24h fuel-day span has elapsed, purely from wall-clock time. This is exactly the
     * same end point {@link #formatCycleRange(DayRecord)} shows for that row in
     * {@code /it fueltime}: a manually-corrected record's explicit end time, or
     * {@code cycleStartMs + 24h} otherwise.
     *
     * Unlike {@link #closeDaySlot(int)} (which is only reached via the live
     * minion-fuel cohort loop in {@link #tick(boolean)}), this runs every tick
     * regardless of whether any minion currently has active fuel — so it also covers
     * manually queued/edited days (see {@link #appendQueuedDay()},
     * {@link #setCycleTimes(String, long, long)}) that the cohort loop never revisits,
     * and any day left dangling once its cohort finishes and {@link #minionExpiries}
     * is cleared. Records already closed (by this or by {@link #closeDaySlot(int)})
     * are skipped, so nothing is ever double-alerted.
     */
    private static void checkDueDayRecords(long now) {
        for (DayRecord record : dayRecords.values()) {
            if (record.cycleStartMs <= 0) continue; // hasn't started / unknown legacy record

            boolean alreadyClosed = record.manualCycleTimes ? record.cycleClosed : record.cycleEndMs > 0;
            if (alreadyClosed) continue;

            boolean manuallyTimed = record.manualCycleTimes && record.cycleEndMs > record.cycleStartMs;
            long effectiveEnd = manuallyTimed ? record.cycleEndMs : record.cycleStartMs + DAY_MS;
            if (now < effectiveEnd) continue;

            if (!record.manualCycleTimes) {
                record.cycleEndMs = effectiveEnd;
            }
            record.cycleClosed = true;
            minionsCountedByDaySlot.remove(record.date);
            FuelExpiredAlertTracker.onFuelDayUp(record.minionsRefueledAuto);
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

    /**
     * The saved shape of {@code fuel_days.json}.
     *
     * <p>Field order here is the order Gson writes them, so the small scalars come first
     * and the two bulk sections ({@link #minions}, {@link #days}) last.
     *
     * <p>Per-minion state used to be three separate maps keyed by the full minion key
     * ({@code minionExpiries}, {@code minionFuelUnitsQueued} and {@code minionsCountedByDaySlot}),
     * which meant every minion's key — up to ~210 characters for a legacy armor-stand key —
     * was repeated once per map and again for every day slot it appeared in. On a real save
     * that was ~390 repetitions of those keys opening the file. They are now gathered into
     * one {@link MinionState} record per minion, each given a short {@code ref} that the
     * day slots cite instead of restating the key.
     *
     * <p>The three legacy fields are still declared so files written by older versions (and
     * older backups, which are restored by hand) load unchanged. Gson omits null fields, so
     * leaving them unset means they simply stop being written once a save is rewritten.
     */
    private static class SaveData {
        long cohortStartMs;
        int activeDaySlotIndex;
        double mobKillCoinsAt100Baseline;
        long lastBackupTime;

        /** One record per minion: its key, its fuel state, and a short ref for day slots. */
        List<MinionState> minions;
        /** Day slot -> the refs of the minions counted toward it. */
        Map<String, List<String>> daySlotMinions;

        Map<String, DayRecord> days;

        // --- pre-record format, read on load and never written again ---
        Map<String, Long> minionExpiries;
        Map<String, Integer> minionFuelUnitsQueued;
        Map<String, Set<String>> minionsCountedByDaySlot;
    }

    /** One physical minion's saved fuel state. */
    private static class MinionState {
        /** Short handle ({@code m1}, {@code m2}, ...) used by {@link SaveData#daySlotMinions}. */
        String ref;
        /** Display name, purely so the file can be read at a glance. Never loaded. */
        String name;
        /** Block position, purely for readability. Never loaded. */
        String at;
        /** The real minion key this state is stored under — the only identifying field. */
        String key;
        /** When this minion's fuel runs out, or null if it has none queued. */
        Long fuelExpiresAt;
        /** Fuel units still queued for this minion, or null if none. */
        Integer queuedUnits;
    }

    private static SaveData buildSaveData() {
        SaveData data = new SaveData();
        data.cohortStartMs              = cohortStartMs;
        data.activeDaySlotIndex         = activeDaySlotIndex;
        data.mobKillCoinsAt100Baseline  = mobKillCoinsAt100Baseline;
        data.lastBackupTime             = lastBackupTime;

        // Every minion mentioned anywhere, including ones that only survive in a historical
        // day slot after their fuel state was cleared.
        Set<String> allKeys = new TreeSet<>(minionExpiries.keySet());
        allKeys.addAll(minionFuelUnitsQueued.keySet());
        for (Set<String> counted : minionsCountedByDaySlot.values()) allKeys.addAll(counted);

        Map<String, String> refsByKey = new HashMap<>();
        data.minions = new ArrayList<>();
        for (String key : allKeys) {
            MinionState state = new MinionState();
            state.ref = "m" + (data.minions.size() + 1);
            state.name = MinionIdentityRegistry.displayNameFor(key);
            state.at = MinionIdentityRegistry.positionFor(key);
            state.key = key;
            state.fuelExpiresAt = minionExpiries.get(key);
            state.queuedUnits = minionFuelUnitsQueued.get(key);
            refsByKey.put(key, state.ref);
            data.minions.add(state);
        }

        data.daySlotMinions = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : minionsCountedByDaySlot.entrySet()) {
            List<String> refs = new ArrayList<>();
            for (String key : new TreeSet<>(entry.getValue())) {
                String ref = refsByKey.get(key);
                if (ref != null) refs.add(ref);
            }
            data.daySlotMinions.put(entry.getKey(), refs);
        }

        data.days                       = new TreeMap<>(dayRecords);
        return data;
    }

    /** The counted-minion set for a day slot, creating it if absent. */
    private static Set<String> countedSetFor(String daySlot) {
        return minionsCountedByDaySlot.computeIfAbsent(daySlot, k -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Restores the per-minion fuel state from the record format written by
     * {@link #buildSaveData()}, resolving each day slot's short refs back to minion keys.
     * Does nothing for a pre-record file, which has no {@code minions} list.
     */
    private static void readMinionRecords(SaveData data) {
        if (data.minions == null) return;

        Map<String, String> keysByRef = new HashMap<>();
        for (MinionState state : data.minions) {
            if (state == null || state.key == null || state.key.isEmpty()) continue;
            if (state.ref != null && !state.ref.isEmpty()) keysByRef.put(state.ref, state.key);
            if (state.fuelExpiresAt != null) minionExpiries.put(state.key, state.fuelExpiresAt);
            if (state.queuedUnits != null) minionFuelUnitsQueued.put(state.key, state.queuedUnits);
        }

        if (data.daySlotMinions == null) return;
        for (Map.Entry<String, List<String>> entry : data.daySlotMinions.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            Set<String> counted = countedSetFor(entry.getKey());
            for (String ref : entry.getValue()) {
                // An unknown ref means the record it pointed at is gone (hand-edited file).
                // Skipping it just loses that one minion's place in the slot, which is the
                // same outcome as it never having been counted.
                String key = keysByRef.get(ref);
                if (key != null) counted.add(key);
            }
        }
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
                    minionFuelUnitsQueued.clear();
                    minionsCountedByDaySlot.clear();

                    // Pre-record format (or a backup taken before this change): three
                    // separate maps keyed by the full minion key. Read first so a file
                    // containing both shapes still ends up with everything.
                    if (data.minionExpiries != null) minionExpiries.putAll(data.minionExpiries);
                    if (data.minionFuelUnitsQueued != null) {
                        minionFuelUnitsQueued.putAll(data.minionFuelUnitsQueued);
                    }
                    if (data.minionsCountedByDaySlot != null) {
                        for (Map.Entry<String, Set<String>> e : data.minionsCountedByDaySlot.entrySet()) {
                            countedSetFor(e.getKey()).addAll(e.getValue());
                        }
                    }

                    readMinionRecords(data);

                    cohortStartMs = data.cohortStartMs;
                    activeDaySlotIndex = data.activeDaySlotIndex;
                    if (data.days != null) {
                        dayRecords.clear();
                        dayRecords.putAll(data.days);
                    }
                    lastBackupTime = data.lastBackupTime;
                    lastTickTime = System.currentTimeMillis();

                    // Drop any per-minion fuel that already ran out while the game was closed —
                    // each survivor is re-checked individually rather than as one shared cycle.
                    long now = System.currentTimeMillis();
                    removeExpiredMinionFuel(now);

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
