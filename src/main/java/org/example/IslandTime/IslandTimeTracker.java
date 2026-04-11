package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THREAD SAFETY: Uses ConcurrentHashMap and atomic variables to prevent race conditions
 * when accessed by multiple game systems (FuelCycleTracker, IslandAlertTracker, etc.)
 */
public class IslandTimeTracker {

    private static final Path SAVE_FILE = Paths.get("config/IslandTime/islandtime.txt");
    private static final Path SAVE_TMP  = Paths.get("config/IslandTime/islandtime.tmp");

    private static final Map<String, Long> timePerDay = new ConcurrentHashMap<>();

    private static final AtomicLong lastTick   = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong lastSave   = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong lastCheck  = new AtomicLong(System.currentTimeMillis());

    private static final long SAVE_INTERVAL  = 5_000;
    private static final long CHECK_INTERVAL = 1_000;

    private static final AtomicBoolean wasOnIsland       = new AtomicBoolean(false);
    private static final AtomicBoolean currentlyOnIsland = new AtomicBoolean(false);

    private static final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("IslandTime-Saver");
        return t;
    });

    // -------------------------------------------------------------------------
    // Tracking toggle
    // -------------------------------------------------------------------------

    /**
     * When false, no new time is accumulated. Existing recorded data is preserved.
     * Disabling mid-day simply stops future ticks from adding to today's total —
     * whatever has already been recorded is kept. Re-enabling resumes immediately.
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
        if (!enabled) {
            // Reset tick state so we don't accumulate a stale delta if
            // tracking is re-enabled later while still on island.
            wasOnIsland.set(false);
            lastTick.set(System.currentTimeMillis());
            save(); // flush whatever we have so far
        }
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // We always run the scoreboard check so that isCurrentlyOnIsland()
        // stays accurate for FuelCycleTracker / IslandAlertTracker, even
        // when island-time tracking is disabled.

        long now            = System.currentTimeMillis();
        long lastCheckValue = lastCheck.get();

        if (now - lastCheckValue < CHECK_INTERVAL) {
            if (wasOnIsland.get() && trackingEnabled) {
                long lastTickValue = lastTick.get();
                long delta         = now - lastTickValue;
                if (lastTick.compareAndSet(lastTickValue, now)) {
                    String today = LocalDate.now().toString();
                    timePerDay.compute(today, (k, old) -> (old != null ? old : 0L) + delta);
                    long lastSaveValue = lastSave.get();
                    if (now - lastSaveValue >= SAVE_INTERVAL && lastSave.compareAndSet(lastSaveValue, now)) {
                        save();
                    }
                }
            } else {
                lastTick.set(now); // keep current so no huge delta on re-enable
            }
            return;
        }

        if (!lastCheck.compareAndSet(lastCheckValue, now)) return;

        ScoreboardObjective obj =
                client.world.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) { handleOffIsland(); return; }

        var scoreboard = client.world.getScoreboard();
        var scores     = scoreboard.getScoreboardEntries(obj);
        boolean onIsland = false;

        for (var scoreEntry : scores) {
            String ownerName = scoreEntry.owner();
            var playerTeam   = scoreboard.getScoreHolderTeam(ownerName);
            if (playerTeam != null) {
                String fullLine = (playerTeam.getPrefix().getString() + playerTeam.getSuffix().getString())
                        .replaceAll("§.", "").toLowerCase().trim();
                if (fullLine.contains("your island")) { onIsland = true; break; }
            }
        }

        if (!onIsland) { handleOffIsland(); return; }

        // Always update location flag
        currentlyOnIsland.set(true);

        if (!trackingEnabled) {
            // Not accumulating time; reset so no stale delta if later re-enabled
            wasOnIsland.set(false);
            lastTick.set(now);
            return;
        }

        long lastTickValue = lastTick.getAndSet(now);
        long delta         = now - lastTickValue;
        String today       = LocalDate.now().toString();
        timePerDay.compute(today, (k, old) -> (old != null ? old : 0L) + delta);

        wasOnIsland.set(true);

        long lastSaveValue = lastSave.get();
        if (now - lastSaveValue >= SAVE_INTERVAL && lastSave.compareAndSet(lastSaveValue, now)) {
            save();
        }
    }

    private static void handleOffIsland() {
        if (wasOnIsland.compareAndSet(true, false)) save();
        currentlyOnIsland.set(false);
        lastTick.set(System.currentTimeMillis());
    }

    public static boolean isCurrentlyOnIsland() { return currentlyOnIsland.get(); }
    public static Map<String, Long> getTimes()   { return new ConcurrentHashMap<>(timePerDay); }

    // -------------------------------------------------------------------------
    // Persistence — atomic write via tmp → rename
    // -------------------------------------------------------------------------

    public static void save() {
        Map<String, Long> snapshot = new ConcurrentHashMap<>(timePerDay);
        saveExecutor.submit(() -> {
            try {
                Files.createDirectories(SAVE_FILE.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(SAVE_TMP)) {
                    for (var entry : snapshot.entrySet()) {
                        writer.write(entry.getKey() + "=" + entry.getValue());
                        writer.newLine();
                    }
                }
                Files.move(SAVE_TMP, SAVE_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                System.err.println("[IslandTimeTracker] Save failed: " + e.getMessage());
                e.printStackTrace();
                try { Files.deleteIfExists(SAVE_TMP); } catch (IOException ignored) {}
            }
        });
    }

    public static void load() {
        if (!Files.exists(SAVE_FILE) && Files.exists(SAVE_TMP)) {
            try {
                Files.move(SAVE_TMP, SAVE_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[IslandTimeTracker] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[IslandTimeTracker] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(SAVE_FILE)) return;

        try {
            List<String> lines = Files.readAllLines(SAVE_FILE);
            for (String line : lines) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    try {
                        timePerDay.put(parts[0], Long.parseLong(parts[1]));
                    } catch (NumberFormatException e) {
                        System.err.println("[IslandTimeTracker] Skipping corrupt line: " + line);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[IslandTimeTracker] Load failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void shutdown() {
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("[IslandTimeTracker] Save executor timed out, forcing shutdown");
                saveExecutor.shutdownNow();
                if (!saveExecutor.awaitTermination(2, TimeUnit.SECONDS))
                    System.err.println("[IslandTimeTracker] Save executor did not terminate");
            }
        } catch (InterruptedException e) {
            saveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
