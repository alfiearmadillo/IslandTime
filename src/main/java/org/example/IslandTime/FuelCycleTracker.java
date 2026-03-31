package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tracks time in 24-hour fuel cycles.
 * Each cycle starts when you fuel a minion and lasts 24 hours or until you fuel another minion.
 * Counts time when the tracked item's stats_book value is increasing.
 */
public class FuelCycleTracker {

    private static final String SAVE_FILE = "config/islandtime_fuel_cycles.txt";
    private static final long CYCLE_DURATION_MS = 24 * 60 * 60 * 1000L; // 24 hours in milliseconds
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MMM d, h:mm a");

    // FIX: Periodic save interval while a cycle is active (30 seconds)
    private static final long SAVE_INTERVAL_MS = 30_000L;
    private static long lastSaveTime = 0;

    // Current cycle data
    private static long currentCycleStartTime = 0; // Epoch milliseconds - when first minion was fueled
    private static long currentCycleLastFuelTime = 0; // Epoch milliseconds - when last minion was fueled (for 24h expiration)
    private static int currentCycleMinionsRefueled = 0;
    private static long currentCycleIslandTime = 0; // Milliseconds spent on island

    // Completed cycles (stored oldest to newest, never deleted)
    private static final List<CompletedCycle> completedCycles = new ArrayList<>();

    // For tracking island time
    private static long lastTickTime = 0;
    private static boolean wasOnIslandLastTick = false;

    // FIX: Async executor for saves — prevents blocking the render thread on disk I/O,
    // mirrors the approach used by IslandTimeTracker.
    private static final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("FuelCycle-Saver");
        return t;
    });

    /**
     * Data class for a completed fuel cycle
     */
    private static class CompletedCycle {
        long startTime; // Epoch milliseconds
        long endTime; // Epoch milliseconds
        int minionsRefueled;
        long islandTime; // Milliseconds

        CompletedCycle(long startTime, long endTime, int minionsRefueled, long islandTime) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.minionsRefueled = minionsRefueled;
            this.islandTime = islandTime;
        }

        // Serialize to string
        String serialize() {
            return startTime + "," + endTime + "," + minionsRefueled + "," + islandTime;
        }

        // Deserialize from string
        static CompletedCycle deserialize(String line) {
            String[] parts = line.split(",");
            if (parts.length != 4) return null;

            try {
                return new CompletedCycle(
                        Long.parseLong(parts[0]),
                        Long.parseLong(parts[1]),
                        Integer.parseInt(parts[2]),
                        Long.parseLong(parts[3])
                );
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * Called when a minion is fueled
     */
    public static void onMinionFueled() {
        long now = System.currentTimeMillis();

        // If there's no active cycle, start a new one
        if (currentCycleStartTime == 0) {
            currentCycleStartTime = now;
            currentCycleLastFuelTime = now;
            currentCycleMinionsRefueled = 1;
            currentCycleIslandTime = 0;
            lastTickTime = now;
        } else {
            // There's an active cycle - increment counter and update last fuel time
            currentCycleMinionsRefueled++;
            currentCycleLastFuelTime = now; // Update last fuel time (for 24h expiration)
            lastTickTime = now;
            // Note: currentCycleStartTime stays the same (first fuel time)
        }

        // FIX: Persist the new/updated cycle state immediately so a game restart
        // during an active cycle can always recover it from disk.
        save();
    }

    /**
     * Called every tick to update active time
     * @param isStatsIncreasing whether the tracked item's stats are currently increasing
     */
    public static void tick(boolean isStatsIncreasing) {
        if (currentCycleStartTime == 0) {
            wasOnIslandLastTick = false;
            return; // No active cycle
        }

        long now = System.currentTimeMillis();

        // Check if the cycle has expired (24 hours since LAST fuel)
        if (now - currentCycleLastFuelTime >= CYCLE_DURATION_MS) {
            completeCurrentCycle(currentCycleLastFuelTime + CYCLE_DURATION_MS);
            wasOnIslandLastTick = false;
            return;
        }

        // Only count time if stats are increasing
        if (wasOnIslandLastTick && isStatsIncreasing && lastTickTime > 0) {
            long delta = now - lastTickTime;

            // Sanity check: don't add time if delta is too large (e.g., game was paused)
            if (delta < 5000) { // Less than 5 seconds
                currentCycleIslandTime += delta;
            }
        }

        lastTickTime = now;
        wasOnIslandLastTick = isStatsIncreasing;

        // FIX: Periodically save current cycle state while it is active.
        // This ensures that even if the game is closed mid-cycle without a proper
        // shutdown event firing, load() on the next startup will find fresh data
        // and correctly detect + complete an expired cycle.
        if (now - lastSaveTime >= SAVE_INTERVAL_MS) {
            lastSaveTime = now;
            save();
        }
    }

    /**
     * Completes the current cycle and adds it to completed cycles
     * NEVER deletes old cycles - all cycles are preserved
     */
    private static void completeCurrentCycle(long endTime) {
        if (currentCycleStartTime == 0) return;

        CompletedCycle cycle = new CompletedCycle(
                currentCycleStartTime,
                endTime,
                currentCycleMinionsRefueled,
                currentCycleIslandTime
        );

        // Add to end of list (cycles are stored oldest to newest, never deleted)
        completedCycles.add(cycle);

        // Reset current cycle
        currentCycleStartTime = 0;
        currentCycleLastFuelTime = 0;
        currentCycleMinionsRefueled = 0;
        currentCycleIslandTime = 0;

        save();
    }

    /**
     * Injects a fake completed cycle for debug/testing purposes.
     * Does not affect any active cycle.
     * The cycle is backdated 25 hours with ~20h of active island time and 32 minions refueled.
     * @return true if the cycle was saved successfully
     */
    public static boolean debugSimulateCycle() {
        long now = System.currentTimeMillis();
        long fakeStart = now - (25 * 60 * 60 * 1000L); // 25 hours ago
        long fakeEnd   = now - (1  * 60 * 60 * 1000L); // 1 hour ago
        long fakeIslandTime = 20 * 60 * 60 * 1000L;    // 20 hours active
        int  fakeMinions = 32;

        System.out.println("[FuelCycleTracker] DEBUG: Injecting fake cycle — " +
                "start=" + fakeStart + " end=" + fakeEnd +
                " minions=" + fakeMinions + " islandTime=" + fakeIslandTime);

        CompletedCycle fake = new CompletedCycle(fakeStart, fakeEnd, fakeMinions, fakeIslandTime);
        completedCycles.add(fake);
        save();

        // Verify it landed in the file
        Path saveFile = Paths.get(SAVE_FILE);
        return Files.exists(saveFile);
    }

    /**
     * Display fuel cycle stats to the player
     * @param client Minecraft client
     * @param specificCycle Optional cycle number to display (1 = oldest), or null to show last 4
     */
    public static void displayStats(MinecraftClient client, Integer specificCycle) {
        if (client.player == null) return;

        // Check if there's any data
        if (currentCycleStartTime == 0 && completedCycles.isEmpty()) {
            client.player.sendMessage(
                    Text.literal("No fuel cycle data yet. Fuel a minion to start tracking!")
                            .formatted(Formatting.YELLOW),
                    false
            );
            return;
        }

        int totalCycles = completedCycles.size();

        // If requesting a specific cycle
        if (specificCycle != null) {
            if (specificCycle < 1 || specificCycle > totalCycles) {
                client.player.sendMessage(
                        Text.literal("Invalid cycle number. Valid range: 1-" + totalCycles)
                                .formatted(Formatting.RED),
                        false
                );
                return;
            }

            // Display the requested cycle (cycles are 1-indexed from oldest)
            CompletedCycle cycle = completedCycles.get(specificCycle - 1);
            displaySingleCycle(client, cycle, specificCycle, totalCycles);
            return;
        }

        // Display current cycle if active
        if (currentCycleStartTime > 0) {
            long now = System.currentTimeMillis();
            long elapsed = now - currentCycleStartTime; // Total time since cycle started
            long timeSinceLastFuel = now - currentCycleLastFuelTime; // Time since last fuel
            long remaining = CYCLE_DURATION_MS - timeSinceLastFuel;

            LocalDateTime startTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(currentCycleStartTime),
                    ZoneId.systemDefault()
            );
            LocalDateTime endTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(currentCycleLastFuelTime + CYCLE_DURATION_MS),
                    ZoneId.systemDefault()
            );

            String startStr = startTime.format(TIME_FORMATTER);
            String endStr = endTime.format(TIME_FORMATTER);

            // Calculate percentage based on elapsed time (not full 24h)
            double activePercentage = elapsed > 0 ? (currentCycleIslandTime * 100.0 / elapsed) : 0.0;

            // Header for current cycle
            client.player.sendMessage(
                    Text.literal("» Current Cycle")
                            .formatted(Formatting.GOLD),
                    false
            );

            client.player.sendMessage(
                    Text.literal("  Current: ")
                            .formatted(Formatting.GREEN)
                            .append(Text.literal(startStr + " - " + endStr)
                                    .formatted(Formatting.WHITE)),
                    false
            );

            client.player.sendMessage(
                    Text.literal("    Minions: ")
                            .formatted(Formatting.GRAY)
                            .append(Text.literal(String.valueOf(currentCycleMinionsRefueled))
                                    .formatted(Formatting.WHITE))
                            .append(Text.literal(" | Duration: ")
                                    .formatted(Formatting.GRAY))
                            .append(Text.literal(formatDuration(elapsed))
                                    .formatted(Formatting.WHITE))
                            .append(Text.literal(" | Active: ")
                                    .formatted(Formatting.GRAY))
                            .append(Text.literal(formatDuration(currentCycleIslandTime) + String.format(" (%.1f%%)", activePercentage))
                                    .formatted(Formatting.WHITE)),
                    false
            );

            client.player.sendMessage(Text.literal(""), false); // Blank line
        }

        // Display recent completed cycles (last 4)
        if (!completedCycles.isEmpty()) {
            // Calculate which cycles to show (last 4)
            // IMPORTANT: We show the MOST RECENT 4 cycles in REVERSE chronological order
            // Cycle 1 is the OLDEST cycle ever recorded, Cycle N is the most recent
            int startIndex = Math.max(0, totalCycles - 4);

            client.player.sendMessage(
                    Text.literal("» Recent Completed Cycles (Showing " +
                                    Math.min(4, totalCycles) + " of " + totalCycles + ")")
                            .formatted(Formatting.GOLD),
                    false
            );

            // Iterate BACKWARDS from most recent to oldest (reverse chronological order)
            for (int i = totalCycles - 1; i >= startIndex; i--) {
                CompletedCycle cycle = completedCycles.get(i);
                int cycleNumber = i + 1;

                LocalDateTime startTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(cycle.startTime),
                        ZoneId.systemDefault()
                );
                LocalDateTime endTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(cycle.endTime),
                        ZoneId.systemDefault()
                );

                String startStr = startTime.format(TIME_FORMATTER);
                String endStr = endTime.format(TIME_FORMATTER);
                long duration = cycle.endTime - cycle.startTime;

                // Calculate percentage based on full duration
                double activePercentage = duration > 0 ? (cycle.islandTime * 100.0 / duration) : 0.0;

                client.player.sendMessage(
                        Text.literal("  Cycle " + cycleNumber + ": ")
                                .formatted(Formatting.YELLOW)
                                .append(Text.literal(startStr + " - " + endStr)
                                        .formatted(Formatting.WHITE)),
                        false
                );

                client.player.sendMessage(
                        Text.literal("    Minions: ")
                                .formatted(Formatting.GRAY)
                                .append(Text.literal(String.valueOf(cycle.minionsRefueled))
                                        .formatted(Formatting.WHITE))
                                .append(Text.literal(" | Duration: ")
                                        .formatted(Formatting.GRAY))
                                .append(Text.literal(formatDuration(duration))
                                        .formatted(Formatting.WHITE))
                                .append(Text.literal(" | Active: ")
                                        .formatted(Formatting.GRAY))
                                .append(Text.literal(formatDuration(cycle.islandTime) + String.format(" (%.1f%%)", activePercentage))
                                        .formatted(Formatting.WHITE)),
                        false
                );
            }

            if (totalCycles > 4) {
                client.player.sendMessage(
                        Text.literal("Use /fueltime <cycle> to view older cycles (e.g., /fueltime 1)")
                                .formatted(Formatting.GRAY)
                                .formatted(Formatting.ITALIC),
                        false
                );
            }
        }
    }

    /**
     * Display a single cycle's information
     */
    private static void displaySingleCycle(MinecraftClient client, CompletedCycle cycle, int cycleNumber, int totalCycles) {
        LocalDateTime startTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(cycle.startTime),
                ZoneId.systemDefault()
        );
        LocalDateTime endTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(cycle.endTime),
                ZoneId.systemDefault()
        );

        String startStr = startTime.format(TIME_FORMATTER);
        String endStr = endTime.format(TIME_FORMATTER);
        long duration = cycle.endTime - cycle.startTime;

        // Calculate percentage based on full duration
        double activePercentage = duration > 0 ? (cycle.islandTime * 100.0 / duration) : 0.0;

        client.player.sendMessage(
                Text.literal("Cycle " + cycleNumber + " of " + totalCycles)
                        .formatted(Formatting.GOLD),
                false
        );

        client.player.sendMessage(
                Text.literal("Period: ")
                        .formatted(Formatting.AQUA)
                        .append(Text.literal(startStr + " - " + endStr)
                                .formatted(Formatting.WHITE)),
                false
        );

        client.player.sendMessage(
                Text.literal("Duration: ")
                        .formatted(Formatting.AQUA)
                        .append(Text.literal(formatDuration(duration))
                                .formatted(Formatting.WHITE)),
                false
        );

        client.player.sendMessage(
                Text.literal("Minions Refueled: ")
                        .formatted(Formatting.AQUA)
                        .append(Text.literal(String.valueOf(cycle.minionsRefueled))
                                .formatted(Formatting.WHITE)),
                false
        );

        client.player.sendMessage(
                Text.literal("Active Time: ")
                        .formatted(Formatting.AQUA)
                        .append(Text.literal(formatDuration(cycle.islandTime) + String.format(" (%.1f%%)", activePercentage))
                                .formatted(Formatting.WHITE)),
                false
        );
    }

    /**
     * Format milliseconds into a human-readable duration
     */
    private static String formatDuration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;

        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    /**
     * Save fuel cycle data to disk asynchronously.
     * FIX: Now runs on a background thread (same pattern as IslandTimeTracker) so disk
     * I/O never blocks the render thread. A snapshot of all mutable state is captured
     * before the executor task runs to avoid reading stale values off-thread.
     */
    public static void save() {
        // Snapshot all mutable state on the calling (render) thread before handing off.
        final long snapStart     = currentCycleStartTime;
        final long snapLastFuel  = currentCycleLastFuelTime;
        final int  snapMinions   = currentCycleMinionsRefueled;
        final long snapIsland    = currentCycleIslandTime;
        final List<CompletedCycle> snapCompleted = new ArrayList<>(completedCycles);

        saveExecutor.submit(() -> {
            try {
                Path saveFile = Paths.get(SAVE_FILE);
                Files.createDirectories(saveFile.getParent());

                try (BufferedWriter writer = Files.newBufferedWriter(saveFile)) {
                    // Write current cycle
                    writer.write("CURRENT\n");
                    writer.write(snapStart + "\n");
                    writer.write(snapLastFuel + "\n");
                    writer.write(snapMinions + "\n");
                    writer.write(snapIsland + "\n");

                    // Write ALL completed cycles (never delete old ones)
                    writer.write("COMPLETED\n");
                    for (CompletedCycle cycle : snapCompleted) {
                        writer.write(cycle.serialize() + "\n");
                    }
                }
            } catch (IOException e) {
                System.err.println("[FuelCycleTracker] Save failed: " + e.getMessage());
                System.err.println("[FuelCycleTracker] Attempted path: " + Paths.get(SAVE_FILE).toAbsolutePath());
                e.printStackTrace();
            }
        });
    }

    /**
     * Load fuel cycle data from disk
     * Loads ALL saved cycles - never deletes past cycles
     */
    public static void load() {
        Path saveFile = Paths.get(SAVE_FILE);

        if (!Files.exists(saveFile)) return;

        // Read all sections before acting on them, so completedCycles is fully
        // populated before completeCurrentCycle (and its save()) is called.
        long[] currentSnapshot = new long[4]; // startTime, lastFuelTime, minionsRefueled, islandTime
        boolean hasCurrentSection = false;

        try (BufferedReader reader = Files.newBufferedReader(saveFile)) {
            String line;
            String section = "";

            while ((line = reader.readLine()) != null) {
                if (line.equals("CURRENT")) {
                    section = "CURRENT";
                    continue;
                } else if (line.equals("COMPLETED")) {
                    section = "COMPLETED";
                    continue;
                }

                if (section.equals("CURRENT")) {
                    currentSnapshot[0] = Long.parseLong(line);                  // startTime
                    currentSnapshot[1] = Long.parseLong(reader.readLine());     // lastFuelTime
                    currentSnapshot[2] = Integer.parseInt(reader.readLine());   // minionsRefueled
                    currentSnapshot[3] = Long.parseLong(reader.readLine());     // islandTime
                    hasCurrentSection = true;
                    section = "";
                } else if (section.equals("COMPLETED")) {
                    CompletedCycle cycle = CompletedCycle.deserialize(line);
                    if (cycle != null) {
                        completedCycles.add(cycle);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[FuelCycleTracker] Load failed: " + e.getMessage());
            e.printStackTrace();
            return;
        } catch (NumberFormatException e) {
            System.err.println("[FuelCycleTracker] Corrupt save file: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Now that completedCycles is fully populated, restore current cycle state
        // and handle expiry — save() will now write the full history correctly.
        if (hasCurrentSection) {
            currentCycleStartTime      = currentSnapshot[0];
            currentCycleLastFuelTime   = currentSnapshot[1];
            currentCycleMinionsRefueled = (int) currentSnapshot[2];
            currentCycleIslandTime     = currentSnapshot[3];
            lastTickTime = System.currentTimeMillis();

            long now = System.currentTimeMillis();
            if (currentCycleStartTime > 0 && now - currentCycleLastFuelTime >= CYCLE_DURATION_MS) {
                completeCurrentCycle(currentCycleLastFuelTime + CYCLE_DURATION_MS);
            }
        }
    }

    /**
     * FIX: Shutdown the save executor gracefully — mirrors IslandTimeTracker.shutdown().
     * Call this from your mod's ClientLifecycleEvents.CLIENT_STOPPING handler to ensure
     * any queued async saves complete before the JVM exits.
     */
    public static void shutdown() {
        // Do one final synchronous-style save by submitting it and then waiting.
        save();
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("[FuelCycleTracker] Save executor did not terminate in time, forcing shutdown");
                saveExecutor.shutdownNow();
                if (!saveExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    System.err.println("[FuelCycleTracker] Save executor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            System.err.println("[FuelCycleTracker] Save shutdown interrupted, forcing shutdown");
            saveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
