package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THREAD SAFETY: Uses ConcurrentHashMap and atomic variables to prevent race conditions
 * when accessed by multiple game systems (FuelCycleTracker, AFKAlertTracker, etc.)
 */
public class IslandTimeTracker {

    // Thread-safe map for time tracking
    private static final Map<String, Long> timePerDay = new ConcurrentHashMap<>();

    // Thread-safe atomic variables
    private static final AtomicLong lastTick = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong lastSave = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong lastCheck = new AtomicLong(System.currentTimeMillis());

    private static final long SAVE_INTERVAL = 5000; // Save every 5 seconds
    private static final long CHECK_INTERVAL = 1000; // Check scoreboard every 1 second

    // Thread-safe boolean flags
    private static final AtomicBoolean wasOnIsland = new AtomicBoolean(false);
    private static final AtomicBoolean currentlyOnIsland = new AtomicBoolean(false);

    private static final Path SAVE_FILE = Paths.get("config/islandtime.txt");

    public static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        long now = System.currentTimeMillis();
        long lastCheckValue = lastCheck.get();

        // Only check scoreboard once per second
        if (now - lastCheckValue < CHECK_INTERVAL) {
            // Still update time if we were on island last check
            if (wasOnIsland.get()) {
                long lastTickValue = lastTick.get();
                long delta = now - lastTickValue;

                // Only update if we successfully set the new lastTick value
                if (lastTick.compareAndSet(lastTickValue, now)) {
                    String today = LocalDate.now().toString();

                    // Thread-safe update using compute
                    timePerDay.compute(today, (key, oldValue) -> {
                        long currentTime = (oldValue != null) ? oldValue : 0L;
                        return currentTime + delta;
                    });

                    // Save periodically
                    long lastSaveValue = lastSave.get();
                    if (now - lastSaveValue >= SAVE_INTERVAL) {
                        if (lastSave.compareAndSet(lastSaveValue, now)) {
                            save();
                        }
                    }
                }
            }
            return;
        }

        // Atomically update lastCheck
        if (!lastCheck.compareAndSet(lastCheckValue, now)) {
            return; // Another thread already updated, skip
        }

        ScoreboardObjective obj =
                client.world.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);

        if (obj == null) {
            handleOffIsland();
            return;
        }

        // Get the actual scoreboard lines
        var scoreboard = client.world.getScoreboard();
        var scores = scoreboard.getScoreboardEntries(obj);

        boolean onIsland = false;

        // Check each line for "Your Island"
        for (var scoreEntry : scores) {
            String ownerName = scoreEntry.owner();

            // Hypixel uses teams with prefixes/suffixes for colored text
            var playerTeam = scoreboard.getScoreHolderTeam(ownerName);
            if (playerTeam != null) {
                // Get the prefix and suffix (this is where the actual text is)
                String prefix = playerTeam.getPrefix().getString();
                String suffix = playerTeam.getSuffix().getString();

                // Remove Minecraft color codes and normalize
                String fullLine = (prefix + suffix).replaceAll("§.", "").toLowerCase().trim();

                if (fullLine.contains("your island")) {
                    onIsland = true;
                    break;
                }
            }
        }

        if (!onIsland) {
            handleOffIsland();
            return;
        }

        // We're on island - track time
        long lastTickValue = lastTick.getAndSet(now);
        long delta = now - lastTickValue;

        String today = LocalDate.now().toString();

        // Thread-safe update
        timePerDay.compute(today, (key, oldValue) -> {
            long currentTime = (oldValue != null) ? oldValue : 0L;
            return currentTime + delta;
        });

        wasOnIsland.set(true);
        currentlyOnIsland.set(true);

        // Save every 5 seconds while on island
        long lastSaveValue = lastSave.get();
        if (now - lastSaveValue >= SAVE_INTERVAL) {
            if (lastSave.compareAndSet(lastSaveValue, now)) {
                save();
            }
        }
    }

    private static void handleOffIsland() {
        // If we just left the island, save one final time
        if (wasOnIsland.compareAndSet(true, false)) {
            save();
        }

        currentlyOnIsland.set(false);

        // Reset tick timer so we don't get huge delta when returning
        lastTick.set(System.currentTimeMillis());
    }

    /**
     * Returns whether the player is currently on their island
     * Used by FuelCycleTracker
     * Thread-safe read
     */
    public static boolean isCurrentlyOnIsland() {
        return currentlyOnIsland.get();
    }

    /**
     * Get a thread-safe copy of the time tracking data
     * Returns a new ConcurrentHashMap to prevent external modification
     */
    public static Map<String, Long> getTimes() {
        return new ConcurrentHashMap<>(timePerDay);
    }

    /**
     * Save time data to disk
     * Thread-safe via synchronized block
     */
    public static synchronized void save() {
        try {
            Files.createDirectories(SAVE_FILE.getParent());
            BufferedWriter writer = Files.newBufferedWriter(SAVE_FILE);

            // Create a snapshot of the map to avoid holding lock during I/O
            Map<String, Long> snapshot = new ConcurrentHashMap<>(timePerDay);

            for (var entry : snapshot.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue());
                writer.newLine();
            }
            writer.close();
        } catch (IOException e) {
            System.err.println("Failed to save island time data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load time data from disk
     * Thread-safe via ConcurrentHashMap operations
     */
    public static void load() {
        if (!Files.exists(SAVE_FILE)) return;

        try {
            List<String> lines = Files.readAllLines(SAVE_FILE);
            for (String line : lines) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    try {
                        timePerDay.put(parts[0], Long.parseLong(parts[1]));
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid time value in save file: " + line);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load island time data: " + e.getMessage());
            e.printStackTrace();
        }
    }
}