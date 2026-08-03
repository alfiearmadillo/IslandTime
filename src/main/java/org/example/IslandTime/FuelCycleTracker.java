package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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

    private static final String SAVE_FILE     = "config/IslandTime/fuel_cycles.txt";
    private static final String SAVE_FILE_TMP = "config/IslandTime/fuel_cycles.tmp";
    private static final long CYCLE_DURATION_MS = 24 * 60 * 60 * 1000L;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MMM d, h:mm a");

    private static final long SAVE_INTERVAL_MS = 30_000L;
    private static long lastSaveTime = 0;

    private static long currentCycleStartTime       = 0;
    private static long currentCycleLastFuelTime    = 0;
    private static int  currentCycleMinionsRefueled = 0;
    private static long currentCycleIslandTime      = 0;

    private static final List<CompletedCycle> completedCycles = new ArrayList<>();

    private static long    lastTickTime        = 0;
    private static boolean wasOnIslandLastTick = false;

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
     *     24-hour expiry — we never abandon data mid-cycle.
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

    private static class CompletedCycle {
        long startTime;
        long endTime;
        int  minionsRefueled;
        long islandTime;

        CompletedCycle(long startTime, long endTime, int minionsRefueled, long islandTime) {
            this.startTime       = startTime;
            this.endTime         = endTime;
            this.minionsRefueled = minionsRefueled;
            this.islandTime      = islandTime;
        }

        String serialize() {
            return startTime + "," + endTime + "," + minionsRefueled + "," + islandTime;
        }

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

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Called when a minion is fueled.
     * If tracking is disabled, fueling is ignored for cycle purposes — no new
     * cycle starts. An already-active cycle is unaffected.
     */
    public static void onMinionFueled() {
        long now = System.currentTimeMillis();

        if (currentCycleStartTime == 0) {
            // No active cycle — only start one if tracking is enabled
            if (!trackingEnabled) return;
            currentCycleStartTime       = now;
            currentCycleLastFuelTime    = now;
            currentCycleMinionsRefueled = 1;
            currentCycleIslandTime      = 0;
            lastTickTime                = now;
        } else {
            // Active cycle — always increment the minion counter and extend the
            // 24-hour window, even if tracking was disabled after the cycle started.
            // (The cycle was already started with the player's consent.)
            currentCycleMinionsRefueled++;
            currentCycleLastFuelTime = now;
            lastTickTime             = now;
        }

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
        if (now - currentCycleLastFuelTime >= CYCLE_DURATION_MS) {
            completeCurrentCycle(currentCycleLastFuelTime + CYCLE_DURATION_MS);
            wasOnIslandLastTick = false;
            return;
        }

        // Accumulate active time — still runs for an in-progress cycle even if
        // tracking was disabled after it started
        if (wasOnIslandLastTick && isStatsIncreasing && lastTickTime > 0) {
            long delta = now - lastTickTime;
            if (delta < 5000) {
                currentCycleIslandTime += delta;
            }
        }

        lastTickTime        = now;
        wasOnIslandLastTick = isStatsIncreasing;

        if (now - lastSaveTime >= SAVE_INTERVAL_MS) {
            lastSaveTime = now;
            save();
        }
    }

    private static void completeCurrentCycle(long endTime) {
        if (currentCycleStartTime == 0) return;

        completedCycles.add(new CompletedCycle(
                currentCycleStartTime,
                endTime,
                currentCycleMinionsRefueled,
                currentCycleIslandTime
        ));

        currentCycleStartTime       = 0;
        currentCycleLastFuelTime    = 0;
        currentCycleMinionsRefueled = 0;
        currentCycleIslandTime      = 0;

        save();
    }

    // -------------------------------------------------------------------------
    // Debug
    // -------------------------------------------------------------------------

    public static boolean debugSimulateCycle() {
        long now        = System.currentTimeMillis();
        long fakeStart  = now - (25 * 60 * 60 * 1000L);
        long fakeEnd    = now - (1  * 60 * 60 * 1000L);
        long fakeIsland = 20 * 60 * 60 * 1000L;
        int  fakeMinions = 32;

        System.out.println("[FuelCycleTracker] DEBUG: Injecting fake cycle — " +
                "start=" + fakeStart + " end=" + fakeEnd +
                " minions=" + fakeMinions + " islandTime=" + fakeIsland);

        completedCycles.add(new CompletedCycle(fakeStart, fakeEnd, fakeMinions, fakeIsland));
        save();

        Path saveFile = Paths.get(SAVE_FILE);
        return Files.exists(saveFile);
    }

    // -------------------------------------------------------------------------
    // Display
    // -------------------------------------------------------------------------

    public static void displayStats(Minecraft client, Integer specificCycle) {
        if (client.player == null) return;

        if (currentCycleStartTime == 0 && completedCycles.isEmpty()) {
            client.player.sendSystemMessage(
                    Component.literal("No fuel cycle data yet. Fuel a minion to start tracking!")
                            .withStyle(ChatFormatting.YELLOW));
            return;
        }

        int totalCycles = completedCycles.size();

        if (specificCycle != null) {
            if (specificCycle < 1 || specificCycle > totalCycles) {
                client.player.sendSystemMessage(
                        Component.literal("Invalid cycle number. Valid range: 1-" + totalCycles)
                                .withStyle(ChatFormatting.RED));
                return;
            }
            displaySingleCycle(client, completedCycles.get(specificCycle - 1), specificCycle, totalCycles);
            return;
        }

        if (currentCycleStartTime > 0) {
            long now               = System.currentTimeMillis();
            long elapsed           = now - currentCycleStartTime;
            long timeSinceLastFuel = now - currentCycleLastFuelTime;

            LocalDateTime startTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(currentCycleStartTime), ZoneId.systemDefault());
            LocalDateTime endTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(currentCycleLastFuelTime + CYCLE_DURATION_MS), ZoneId.systemDefault());

            double activePercentage = elapsed > 0 ? (currentCycleIslandTime * 100.0 / elapsed) : 0.0;

            client.player.sendSystemMessage(Component.literal("» Current Cycle").withStyle(ChatFormatting.GOLD));
            client.player.sendSystemMessage(
                    Component.literal("  Current: ").withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(startTime.format(TIME_FORMATTER) + " - " + endTime.format(TIME_FORMATTER))
                                    .withStyle(ChatFormatting.WHITE)));
            client.player.sendSystemMessage(
                    Component.literal("    Minions: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.valueOf(currentCycleMinionsRefueled)).withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(" | Duration: ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(formatDuration(elapsed)).withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(" | Active: ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(formatDuration(currentCycleIslandTime) +
                                    String.format(" (%.1f%%)", activePercentage)).withStyle(ChatFormatting.WHITE)));
            client.player.sendSystemMessage(Component.literal(""));
        }

        if (!completedCycles.isEmpty()) {
            int startIndex = Math.max(0, totalCycles - 4);
            client.player.sendSystemMessage(
                    Component.literal("» Recent Completed Cycles (Showing " +
                                    Math.min(4, totalCycles) + " of " + totalCycles + ")")
                            .withStyle(ChatFormatting.GOLD));

            for (int i = totalCycles - 1; i >= startIndex; i--) {
                CompletedCycle cycle      = completedCycles.get(i);
                int            cycleNumber = i + 1;
                long           duration    = cycle.endTime - cycle.startTime;
                double         activePerc  = duration > 0 ? (cycle.islandTime * 100.0 / duration) : 0.0;

                LocalDateTime st = LocalDateTime.ofInstant(Instant.ofEpochMilli(cycle.startTime), ZoneId.systemDefault());
                LocalDateTime et = LocalDateTime.ofInstant(Instant.ofEpochMilli(cycle.endTime),   ZoneId.systemDefault());

                client.player.sendSystemMessage(
                        Component.literal("  Cycle " + cycleNumber + ": ").withStyle(ChatFormatting.YELLOW)
                                .append(Component.literal(st.format(TIME_FORMATTER) + " - " + et.format(TIME_FORMATTER))
                                        .withStyle(ChatFormatting.WHITE)));
                client.player.sendSystemMessage(
                        Component.literal("    Minions: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.valueOf(cycle.minionsRefueled)).withStyle(ChatFormatting.WHITE))
                                .append(Component.literal(" | Duration: ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(formatDuration(duration)).withStyle(ChatFormatting.WHITE))
                                .append(Component.literal(" | Active: ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(formatDuration(cycle.islandTime) +
                                        String.format(" (%.1f%%)", activePerc)).withStyle(ChatFormatting.WHITE)));
            }

            if (totalCycles > 4) {
                client.player.sendSystemMessage(
                        Component.literal("Use /fueltime <cycle> to view older cycles (e.g., /fueltime 1)")
                                .withStyle(ChatFormatting.GRAY).withStyle(ChatFormatting.ITALIC));
            }
        }
    }

    private static void displaySingleCycle(Minecraft client, CompletedCycle cycle,
                                            int cycleNumber, int totalCycles) {
        LocalDateTime startTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(cycle.startTime), ZoneId.systemDefault());
        LocalDateTime endTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(cycle.endTime), ZoneId.systemDefault());

        long   duration         = cycle.endTime - cycle.startTime;
        double activePercentage = duration > 0 ? (cycle.islandTime * 100.0 / duration) : 0.0;

        client.player.sendSystemMessage(
                Component.literal("Cycle " + cycleNumber + " of " + totalCycles).withStyle(ChatFormatting.GOLD));
        client.player.sendSystemMessage(
                Component.literal("Period: ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(startTime.format(TIME_FORMATTER) + " - " + endTime.format(TIME_FORMATTER))
                                .withStyle(ChatFormatting.WHITE)));
        client.player.sendSystemMessage(
                Component.literal("Duration: ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(formatDuration(duration)).withStyle(ChatFormatting.WHITE)));
        client.player.sendSystemMessage(
                Component.literal("Minions Refueled: ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(String.valueOf(cycle.minionsRefueled)).withStyle(ChatFormatting.WHITE)));
        client.player.sendSystemMessage(
                Component.literal("Active Time: ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(formatDuration(cycle.islandTime) +
                                String.format(" (%.1f%%)", activePercentage)).withStyle(ChatFormatting.WHITE)));
    }

    private static String formatDuration(long millis) {
        long hours   = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        if (hours > 0)   return String.format("%dh %dm", hours, minutes);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return String.format("%ds", seconds);
    }

    // -------------------------------------------------------------------------
    // Persistence — atomic write via tmp → rename
    // -------------------------------------------------------------------------

    public static void save() {
        final long              snapStart    = currentCycleStartTime;
        final long              snapLastFuel = currentCycleLastFuelTime;
        final int               snapMinions  = currentCycleMinionsRefueled;
        final long              snapIsland   = currentCycleIslandTime;
        final List<CompletedCycle> snapDone  = new ArrayList<>(completedCycles);

        saveExecutor.submit(() -> {
            Path realFile = Paths.get(SAVE_FILE);
            Path tmpFile  = Paths.get(SAVE_FILE_TMP);
            try {
                Files.createDirectories(realFile.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(tmpFile)) {
                    writer.write("CURRENT\n");
                    writer.write(snapStart    + "\n");
                    writer.write(snapLastFuel + "\n");
                    writer.write(snapMinions  + "\n");
                    writer.write(snapIsland   + "\n");
                    writer.write("COMPLETED\n");
                    for (CompletedCycle cycle : snapDone) {
                        writer.write(cycle.serialize() + "\n");
                    }
                }
                Files.move(tmpFile, realFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                System.err.println("[FuelCycleTracker] Save failed: " + e.getMessage());
                System.err.println("[FuelCycleTracker] Attempted path: " + Paths.get(SAVE_FILE).toAbsolutePath());
                e.printStackTrace();
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        });
    }

    public static void load() {
        Path realFile = Paths.get(SAVE_FILE);
        Path tmpFile  = Paths.get(SAVE_FILE_TMP);

        if (!Files.exists(realFile) && Files.exists(tmpFile)) {
            try {
                Files.move(tmpFile, realFile, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[FuelCycleTracker] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[FuelCycleTracker] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(realFile)) return;

        long[]  currentSnapshot   = new long[4];
        boolean hasCurrentSection = false;

        try (BufferedReader reader = Files.newBufferedReader(realFile)) {
            String line;
            String section = "";

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
                        System.err.println("[FuelCycleTracker] Corrupt CURRENT section, skipping: " + e.getMessage());
                    }
                    section = "";
                } else if (section.equals("COMPLETED")) {
                    CompletedCycle cycle = CompletedCycle.deserialize(line);
                    if (cycle != null) {
                        completedCycles.add(cycle);
                    } else if (!line.isBlank()) {
                        System.err.println("[FuelCycleTracker] Skipping corrupt COMPLETED line: " + line);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[FuelCycleTracker] Load failed: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        if (hasCurrentSection) {
            currentCycleStartTime       = currentSnapshot[0];
            currentCycleLastFuelTime    = currentSnapshot[1];
            currentCycleMinionsRefueled = (int) currentSnapshot[2];
            currentCycleIslandTime      = currentSnapshot[3];
            lastTickTime                = System.currentTimeMillis();

            long now = System.currentTimeMillis();
            if (currentCycleStartTime > 0 && now - currentCycleLastFuelTime >= CYCLE_DURATION_MS) {
                completeCurrentCycle(currentCycleLastFuelTime + CYCLE_DURATION_MS);
            }
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
