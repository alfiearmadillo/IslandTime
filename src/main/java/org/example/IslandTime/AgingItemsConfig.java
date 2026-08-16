package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Persists aging item pairs (aging skyblock ID -> aged skyblock ID),
 * number of time-bag slots, and inventory slots used for levelling.
 */
public class AgingItemsConfig {

    private static final File CONFIG_FILE = new File("config/IslandTime/aging_items.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Ordered list of aging-item pairs (aging ID → aged ID). */
    private static List<String[]> itemPairs = new ArrayList<>();

    /** Number of Time Pocket (time-bag) slots available. */
    private static int timeBagSlots = 3;

    /** Number of inventory slots the player dedicates to levelling items. */
    private static int inventorySlots = 0;

    // ── defaults ────────────────────────────────────────────────────────────

    private static final String[][] DEFAULT_PAIRS = {
            {"NEW_BOTTLE_OF_JYRRE",  "REFINED_BOTTLE_OF_JYRRE"},
            {"DARK_CACAO_TRUFFLE",   "REFINED_DARK_CACAO_TRUFFLE"},
            {"DISCRITE",             "SPOTLITE"},
            {"MOBY_DUCK",            "MOBY_DUCK_COLLECTOR_EDITION"},
            {"NOT_VERY_MOLDY_BREAD", "VERY_MOLDY_BREAD"},
            {"ROSEWATER_FLASK",      "FILLED_ROSEWATER_FLASK"},
    };

    // ── persistence ─────────────────────────────────────────────────────────

    public static void load() {
        if (!CONFIG_FILE.exists()) {
            // seed with defaults
            itemPairs = new ArrayList<>();
            for (String[] p : DEFAULT_PAIRS) itemPairs.add(new String[]{p[0], p[1]});
            save();
            return;
        }
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data != null) {
                itemPairs = data.pairs != null ? data.pairs : new ArrayList<>();
                timeBagSlots = data.timeBagSlots;
                inventorySlots = data.inventorySlots;
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to load aging items config: " + e.getMessage());
            itemPairs = new ArrayList<>();
            for (String[] p : DEFAULT_PAIRS) itemPairs.add(new String[]{p[0], p[1]});
        }
    }

    public static void save() {
        try {
            CONFIG_FILE.getParentFile().mkdirs();
            Data data = new Data();
            data.pairs = itemPairs;
            data.timeBagSlots = timeBagSlots;
            data.inventorySlots = inventorySlots;
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to save aging items config: " + e.getMessage());
        }
    }

    // ── getters / setters ───────────────────────────────────────────────────

    public static List<String[]> getItemPairs() { return itemPairs; }

    public static boolean addPair(String agingId, String agedId) {
        for (String[] p : itemPairs) {
            if (p[0].equalsIgnoreCase(agingId) && p[1].equalsIgnoreCase(agedId)) return false;
        }
        itemPairs.add(new String[]{agingId.toUpperCase(), agedId.toUpperCase()});
        save();
        return true;
    }

    public static boolean removePair(String agingId) {
        boolean removed = itemPairs.removeIf(p -> p[0].equalsIgnoreCase(agingId));
        if (removed) save();
        return removed;
    }

    public static int getTimeBagSlots()  { return timeBagSlots; }
    public static int getInventorySlots() { return inventorySlots; }

    public static void setTimeBagSlots(int slots) {
        timeBagSlots = Math.max(0, slots);
        save();
    }

    public static void setInventorySlots(int slots) {
        inventorySlots = Math.max(0, slots);
        save();
    }

    /** Total number of items that can level simultaneously. */
    public static int getTotalSlots() {
        return timeBagSlots + inventorySlots;
    }

    // ── inner data class for JSON ───────────────────────────────────────────

    private static class Data {
        List<String[]> pairs;
        int timeBagSlots = 3;
        int inventorySlots = 0;
    }
}
