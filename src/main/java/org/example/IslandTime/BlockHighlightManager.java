package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a persistent list of block highlight entries stored as JSON.
 * Each entry has its own block target, color, range, through-walls toggle, and enabled state.
 */
public class BlockHighlightManager {

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/block_highlights.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<HighlightEntry>>() {}.getType();

    private static List<HighlightEntry> entries = new ArrayList<>();

    /**
     * A single highlight entry. The target can be:
     * - A block ID like "diamond_ore" or "minecraft:diamond_ore"
     * - A skull texture hash prefixed with "skull:" like "skull:abc123..."
     * - A SkyBlock item ID prefixed with "sb:" like "sb:DITTO_SKULL"
     */
    public static class HighlightEntry {
        public String target;       // block ID, "skull:TEXTURE_HASH", or "sb:SKYBLOCK_ID"
        public String color;        // hex "#rrggbb" or named color
        public int range = 48;      // scan range in blocks
        public boolean throughWalls = true;
        public boolean enabled = true;
        public int intensity = 50;  // 0-100
        public String area = "";    // SkyBlock area name (empty = show everywhere)

        public HighlightEntry() {}

        public HighlightEntry(String target, String color) {
            this.target = target;
            this.color = color;
        }

        /**
         * Returns true if this entry targets a skull by texture hash.
         */
        public boolean isSkullTarget() {
            return target != null && target.startsWith("skull:");
        }

        /**
         * Returns true if this entry targets a SkyBlock custom item ID.
         */
        public boolean isSkyBlockTarget() {
            return target != null && target.startsWith("sb:");
        }

        /**
         * Returns the skull texture hash (without the "skull:" prefix).
         */
        public String getSkullHash() {
            if (!isSkullTarget()) return null;
            return target.substring(6);
        }

        /**
         * Returns the SkyBlock item ID (without the "sb:" prefix).
         */
        public String getSkyBlockId() {
            if (!isSkyBlockTarget()) return null;
            return target.substring(3);
        }

        /**
         * Returns the block ID (with minecraft: prefix if needed).
         * Returns null for skull and skyblock targets.
         */
        public String getBlockId() {
            if (isSkullTarget() || isSkyBlockTarget()) return null;
            if (target == null) return null;
            String normalized = target.trim().toLowerCase();
            if (!normalized.contains(":")) {
                normalized = "minecraft:" + normalized;
            }
            return normalized;
        }

        /**
         * Returns a short display name for this entry.
         */
        public String getDisplayName() {
            if (isSkullTarget()) {
                String hash = getSkullHash();
                if (hash.length() > 24) {
                    return "skull:" + hash.substring(0, 24) + "...";
                }
                return target;
            }
            if (isSkyBlockTarget()) {
                return target;
            }
            return target != null ? target : "(empty)";
        }
    }

    public static void load() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String json = Files.readString(CONFIG_FILE);
                List<HighlightEntry> loaded = GSON.fromJson(json, LIST_TYPE);
                if (loaded != null) {
                    entries = loaded;
                }
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to load block highlights: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, GSON.toJson(entries));
        } catch (IOException e) {
            System.err.println("[IslandTime] Failed to save block highlights: " + e.getMessage());
        }
    }

    public static List<HighlightEntry> getEntries() {
        return entries;
    }

    public static void addEntry(String target, String color) {
        entries.add(new HighlightEntry(target, color));
        save();
    }

    public static void removeEntry(int index) {
        if (index >= 0 && index < entries.size()) {
            entries.remove(index);
            save();
        }
    }

    public static void updateEntry(int index, HighlightEntry updated) {
        if (index >= 0 && index < entries.size()) {
            entries.set(index, updated);
            save();
        }
    }

    public static void setEnabled(int index, boolean enabled) {
        if (index >= 0 && index < entries.size()) {
            entries.get(index).enabled = enabled;
            save();
        }
    }

    public static void setColor(int index, String color) {
        if (index >= 0 && index < entries.size()) {
            entries.get(index).color = color;
            save();
        }
    }

    public static void setRange(int index, int range) {
        if (index >= 0 && index < entries.size()) {
            entries.get(index).range = Math.max(1, Math.min(320, range));
            save();
        }
    }

    public static void setThroughWalls(int index, boolean throughWalls) {
        if (index >= 0 && index < entries.size()) {
            entries.get(index).throughWalls = throughWalls;
            save();
        }
    }

    public static void setIntensity(int index, int intensity) {
        if (index >= 0 && index < entries.size()) {
            entries.get(index).intensity = Math.max(0, Math.min(100, intensity));
            save();
        }
    }

    /**
     * Find an entry by target string.
     */
    public static int findByTarget(String target) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).target != null && entries.get(i).target.equalsIgnoreCase(target)) {
                return i;
            }
        }
        return -1;
    }
}
