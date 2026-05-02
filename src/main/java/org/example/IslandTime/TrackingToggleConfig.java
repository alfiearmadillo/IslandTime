package org.example.islandtime;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Persists the enabled/disabled toggles for island-time tracking and fuel-cycle tracking.
 * Both default to true. The values are loaded at startup and applied to their respective
 * trackers. All saves are atomic (tmp → rename) consistent with the rest of the mod.
 */
public class TrackingToggleConfig {

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/tracking_toggles.txt");
    private static final Path CONFIG_TMP  = Paths.get("config/IslandTime/tracking_toggles.tmp");

    // These mirror the live values in the trackers and are only used for persistence.
    private static boolean islandTimeEnabled = true;
    private static boolean fuelCycleEnabled  = true;

    /** Called by IslandTimeTracker.setTrackingEnabled and the toggle command. */
    public static void setIslandTimeEnabled(boolean enabled) {
        islandTimeEnabled = enabled;
        IslandTimeTracker.setTrackingEnabled(enabled);
        save();
    }

    /** Called by FuelCycleTracker.setTrackingEnabled and the toggle command. */
    public static void setFuelCycleEnabled(boolean enabled) {
        fuelCycleEnabled = enabled;
        FuelCycleTracker.setTrackingEnabled(enabled);
        save();
    }

    public static boolean isIslandTimeEnabled() { return islandTimeEnabled; }
    public static boolean isFuelCycleEnabled()  { return fuelCycleEnabled; }

    // -------------------------------------------------------------------------
    // Persistence — atomic write via tmp → rename
    // -------------------------------------------------------------------------

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_TMP)) {
                writer.write("ISLAND_TIME_TRACKING\n");
                writer.write(islandTimeEnabled + "\n");
                writer.write("FUEL_CYCLE_TRACKING\n");
                writer.write(fuelCycleEnabled + "\n");
            }
            Files.move(CONFIG_TMP, CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[TrackingToggleConfig] Save failed: " + e.getMessage());
            e.printStackTrace();
            try { Files.deleteIfExists(CONFIG_TMP); } catch (IOException ignored) {}
        }
    }

    public static void load() {
        if (!Files.exists(CONFIG_FILE) && Files.exists(CONFIG_TMP)) {
            try {
                Files.move(CONFIG_TMP, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[TrackingToggleConfig] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[TrackingToggleConfig] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(CONFIG_FILE)) return;

        try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE)) {
            String line;
            String section = "";
            while ((line = reader.readLine()) != null) {
                if      (line.equals("ISLAND_TIME_TRACKING")) { section = "ISLAND_TIME_TRACKING"; continue; }
                else if (line.equals("FUEL_CYCLE_TRACKING"))  { section = "FUEL_CYCLE_TRACKING";  continue; }

                if (section.equals("ISLAND_TIME_TRACKING") && !line.isEmpty()) {
                    islandTimeEnabled = Boolean.parseBoolean(line);
                    section = "";
                } else if (section.equals("FUEL_CYCLE_TRACKING") && !line.isEmpty()) {
                    fuelCycleEnabled = Boolean.parseBoolean(line);
                    section = "";
                }
            }
        } catch (IOException e) {
            System.err.println("[TrackingToggleConfig] Load failed: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Apply loaded values to the trackers.
        // Called after IslandTimeTracker.load() and FuelCycleTracker.load() in IslandTimeMod,
        // so the trackers already have their data before we flip their flags.
        IslandTimeTracker.setTrackingEnabled(islandTimeEnabled);
        FuelCycleTracker.setTrackingEnabled(fuelCycleEnabled);
    }
}
