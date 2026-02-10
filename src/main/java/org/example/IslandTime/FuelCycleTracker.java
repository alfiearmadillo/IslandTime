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

    // Current cycle data
    private static long currentCycleStartTime = 0; // Epoch milliseconds - when first minion was fueled
    private static long currentCycleLastFuelTime = 0; // Epoch milliseconds - when last minion was fueled (for 24h expiration)
    private static int currentCycleMinionsRefueled = 0;
    private static long currentCycleIslandTime = 0; // Milliseconds spent on island

    // Completed cycles
    private static final List<CompletedCycle> completedCycles = new ArrayList<>();

    // For tracking island time
    private static long lastTickTime = 0;
    private static boolean wasOnIslandLastTick = false;

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
    }

    /**
     * Completes the current cycle and adds it to completed cycles
     */
    private static void completeCurrentCycle(long endTime) {
        if (currentCycleStartTime == 0) return;

        CompletedCycle cycle = new CompletedCycle(
                currentCycleStartTime,
                endTime,
                currentCycleMinionsRefueled,
                currentCycleIslandTime
        );

        // Add to end of list (cycles are stored oldest to newest)
        completedCycles.add(cycle);

        // Reset current cycle
        currentCycleStartTime = 0;
        currentCycleLastFuelTime = 0;
        currentCycleMinionsRefueled = 0;
        currentCycleIslandTime = 0;

        save();
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
            long elapsed = now - currentCycleLastFuelTime; // Time since last fuel
            long remaining = CYCLE_DURATION_MS - elapsed;

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
            String remainingStr = formatDuration(remaining);

            client.player.sendMessage(
                    Text.literal("Current Cycle: ")
                            .formatted(Formatting.GOLD)
                            .append(Text.literal(startStr + " - " + endStr + " (in " + remainingStr + ")")
                                    .formatted(Formatting.WHITE)),
                    false
            );

            client.player.sendMessage(
                    Text.literal("  Minions: ")
                            .formatted(Formatting.AQUA)
                            .append(Text.literal(String.valueOf(currentCycleMinionsRefueled))
                                    .formatted(Formatting.WHITE))
                            .append(Text.literal(" | Active Time: ")
                                    .formatted(Formatting.AQUA))
                            .append(Text.literal(formatDuration(currentCycleIslandTime))
                                    .formatted(Formatting.WHITE)),
                    false
            );

            client.player.sendMessage(Text.literal(""), false);
        }

        // Display recent completed cycles (last 4)
        if (!completedCycles.isEmpty()) {
            int displayCount = Math.min(4, totalCycles);
            int startIndex = Math.max(0, totalCycles - 4); // Start from 4th-to-last

            client.player.sendMessage(
                    Text.literal("Recent Completed Cycles:")
                            .formatted(Formatting.GOLD)
                            .append(Text.literal(" (showing " + displayCount + " of " + totalCycles + ")")
                                    .formatted(Formatting.GRAY)),
                    false
            );

            for (int i = startIndex; i < totalCycles; i++) {
                CompletedCycle cycle = completedCycles.get(i);
                int cycleNumber = i + 1; // 1-indexed

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

                client.player.sendMessage(
                        Text.literal("  Cycle " + cycleNumber + ": ")
                                .formatted(Formatting.YELLOW)
                                .append(Text.literal(startStr + " - " + endStr)
                                        .formatted(Formatting.WHITE)),
                        false
                );

                client.player.sendMessage(
                        Text.literal("    Duration: ")
                                .formatted(Formatting.GRAY)
                                .append(Text.literal(formatDuration(duration))
                                        .formatted(Formatting.WHITE))
                                .append(Text.literal(" | Minions: ")
                                        .formatted(Formatting.GRAY))
                                .append(Text.literal(String.valueOf(cycle.minionsRefueled))
                                        .formatted(Formatting.WHITE))
                                .append(Text.literal(" | Active Time: ")
                                        .formatted(Formatting.GRAY))
                                .append(Text.literal(formatDuration(cycle.islandTime))
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
                        .append(Text.literal(formatDuration(cycle.islandTime))
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
     * Save fuel cycle data to disk
     */
    public static void save() {
        try {
            Path saveFile = Paths.get(SAVE_FILE);
            Files.createDirectories(saveFile.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(saveFile)) {
                // Write current cycle
                writer.write("CURRENT\n");
                writer.write(currentCycleStartTime + "\n");
                writer.write(currentCycleLastFuelTime + "\n");
                writer.write(currentCycleMinionsRefueled + "\n");
                writer.write(currentCycleIslandTime + "\n");

                // Write completed cycles
                writer.write("COMPLETED\n");
                for (CompletedCycle cycle : completedCycles) {
                    writer.write(cycle.serialize() + "\n");
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Load fuel cycle data from disk
     */
    public static void load() {
        Path saveFile = Paths.get(SAVE_FILE);

        if (!Files.exists(saveFile)) return;

        try {
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
                        // Read current cycle (5 lines now)
                        currentCycleStartTime = Long.parseLong(line);
                        currentCycleLastFuelTime = Long.parseLong(reader.readLine());
                        currentCycleMinionsRefueled = Integer.parseInt(reader.readLine());
                        currentCycleIslandTime = Long.parseLong(reader.readLine());
                        lastTickTime = System.currentTimeMillis();

                        // Check if current cycle has expired while game was closed
                        long now = System.currentTimeMillis();
                        if (currentCycleStartTime > 0 && now - currentCycleLastFuelTime >= CYCLE_DURATION_MS) {
                            completeCurrentCycle(currentCycleLastFuelTime + CYCLE_DURATION_MS);
                        }

                        section = ""; // Done reading current
                    } else if (section.equals("COMPLETED")) {
                        // Read completed cycle
                        CompletedCycle cycle = CompletedCycle.deserialize(line);
                        if (cycle != null) {
                            completedCycles.add(cycle);
                        }
                    }
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
    }
}