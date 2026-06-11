package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

import java.io.*;

/**
 * Persists the "show hidden tooltips" override flag.
 * Stored alongside the other IslandTime config files.
 */
public class TooltipOverrideConfig {

    private static final File FILE = new File("config/IslandTime/tooltip_override.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** When true, items with hide_tooltip will still show their tooltip. */
    private static boolean showHiddenTooltips = false;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static boolean isShowHiddenTooltips() {
        return showHiddenTooltips;
    }

    public static void setShowHiddenTooltips(boolean value) {
        showHiddenTooltips = value;
        save();
    }

    public static void toggle() {
        setShowHiddenTooltips(!showHiddenTooltips);
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    public static void load() {
        if (!FILE.exists()) return;
        try (FileReader reader = new FileReader(FILE)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data != null) {
                showHiddenTooltips = data.showHiddenTooltips;
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to load tooltip_override.json: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(FILE)) {
                Data data = new Data();
                data.showHiddenTooltips = showHiddenTooltips;
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to save tooltip_override.json: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // JSON model
    // -------------------------------------------------------------------------

    private static class Data {
        @Expose
        boolean showHiddenTooltips = false;
    }
}
