package org.example.islandtime;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every crop that can sit in a Garden greenhouse plot, with the names the game uses for it.
 *
 * <p>A greenhouse plot is a 10x10 area of soil. Most crops cover a single cell; the Garden mutations
 * Snoozling and Godseed are 3x3, and Noctilume, Glasscorn and PlantBoy Advance are 2x2.
 */
final class GreenhouseCrops {

    /**
     * One plantable crop.
     *
     * @param key      stable upper case id (for example "VEILSHROOM")
     * @param name     display name
     * @param size     width and height in cells
     * @param color    packed 0xRRGGBB colour of the ghost box
     * @param aliases  normalized (see {@link #normalize(String)}) names it is also known by
     * @param mutation true for crops that are grown as a mutation, false for plain crops
     * @param block    id of the block a plain crop is planted as ("minecraft:wheat"), "" for crops that
     *                 are armor stands
     */
    record Crop(String key, String name, int size, int color, List<String> aliases, boolean mutation, String block) {}

    /** The block each plain crop is planted as. The special crops are armor stands, so have no block. */
    private static final Map<String, String> BLOCK_IDS = Map.ofEntries(
            Map.entry("WHEAT", "minecraft:wheat"),
            Map.entry("POTATO", "minecraft:potatoes"),
            Map.entry("CARROT", "minecraft:carrots"),
            Map.entry("PUMPKIN", "minecraft:pumpkin"),
            Map.entry("MELON", "minecraft:melon"),
            Map.entry("COCOA_BEANS", "minecraft:cocoa"),
            Map.entry("SUGAR_CANE", "minecraft:sugar_cane"),
            Map.entry("CACTUS", "minecraft:cactus"),
            Map.entry("NETHER_WART", "minecraft:nether_wart"),
            Map.entry("RED_MUSHROOM", "minecraft:red_mushroom"),
            Map.entry("BROWN_MUSHROOM", "minecraft:brown_mushroom"),
            Map.entry("FIRE", "minecraft:fire"),
            Map.entry("DEAD_PLANT", "minecraft:dead_bush"));

    private static final List<Crop> ALL;
    private static final Map<String, Crop> BY_KEY = new HashMap<>();
    private static final Map<String, Crop> BY_ALIAS = new HashMap<>();
    private static final Map<String, Crop> BY_BLOCK = new HashMap<>();

    /** Aliases of at most this many letters only match a whole name, so "fire" doesn't match "fireworks". */
    private static final int MIN_PARTIAL_ALIAS_LENGTH = 5;

    static {
        List<Crop> crops = new ArrayList<>();
        // Plain crops, with the other names the game uses for some of them
        add(crops, "WHEAT", "Wheat", 1, false, "wheatseeds");
        add(crops, "POTATO", "Potato", 1, false);
        add(crops, "CARROT", "Carrot", 1, false);
        add(crops, "PUMPKIN", "Pumpkin", 1, false, "pumpkinseeds");
        add(crops, "MELON", "Melon", 1, false, "melonseeds");
        add(crops, "COCOA_BEANS", "Cocoa Beans", 1, false, "cocoa", "coco");
        add(crops, "SUGAR_CANE", "Sugar Cane", 1, false);
        add(crops, "CACTUS", "Cactus", 1, false);
        add(crops, "NETHER_WART", "Nether Wart", 1, false);
        add(crops, "RED_MUSHROOM", "Red Mushroom", 1, false);
        add(crops, "BROWN_MUSHROOM", "Brown Mushroom", 1, false);
        add(crops, "MOONFLOWER", "Moonflower", 1, false);
        add(crops, "SUNFLOWER", "Sunflower", 1, false);
        add(crops, "WILD_ROSE", "Wild Rose", 1, false);
        add(crops, "FIRE", "Fire", 1, false);
        add(crops, "DEAD_PLANT", "Dead Plant", 1, false, "deadplants", "deadbush");
        add(crops, "FERMENTO", "Fermento", 1, false);
        add(crops, "HELIANTHUS", "Helianthus", 1, false);
        add(crops, "SQUASH", "Squash", 1, false);
        add(crops, "CROPIE", "Cropie", 1, false);

        // Mutations
        add(crops, "ALL_IN_ALOE", "All-in Aloe", 1, true, "allinaloe");
        add(crops, "ASHWREATH", "Ashwreath", 1, true);
        add(crops, "BLASTBERRY", "Blastberry", 1, true);
        add(crops, "CHEESEBITE", "Cheesebite", 1, true);
        add(crops, "CHLORONITE", "Chloronite", 1, true);
        add(crops, "CHOCOBERRY", "Chocoberry", 1, true);
        add(crops, "CHOCONUT", "Choconut", 1, true);
        add(crops, "CHORUS_FRUIT", "Chorus Fruit", 1, true);
        add(crops, "CINDERSHADE", "Cindershade", 1, true);
        add(crops, "COALROOT", "Coalroot", 1, true);
        add(crops, "CREAMBLOOM", "Creambloom", 1, true);
        add(crops, "DEVOURER", "Devourer", 1, true);
        add(crops, "DO_NOT_EAT_SHROOM", "Do-not-eat-shroom", 1, true);
        add(crops, "DUSKBLOOM", "Duskbloom", 1, true);
        add(crops, "DUSTGRAIN", "Dustgrain", 1, true);
        add(crops, "FLESHTRAP", "Fleshtrap", 1, true);
        add(crops, "GLASSCORN", "Glasscorn", 2, true);
        add(crops, "GLOOMGOURD", "Gloomgourd", 1, true);
        add(crops, "GODSEED", "Godseed", 3, true);
        add(crops, "JERRYFLOWER", "Jerryflower", 1, true, "jerryseed");
        add(crops, "LONELILY", "Lonelily", 1, true, "lonelilly");
        add(crops, "MAGIC_JELLYBEAN", "Magic Jellybean", 1, true);
        add(crops, "NOCTILUME", "Noctilume", 2, true);
        add(crops, "PHANTOMLEAF", "Phantomleaf", 1, true);
        add(crops, "PLANTBOY_ADVANCE", "PlantBoy Advance", 2, true, "plantboy");
        add(crops, "PUFFERCLOUD", "Puffercloud", 1, true);
        add(crops, "SCOURROOT", "Scourroot", 1, true);
        add(crops, "SHADEVINE", "Shadevine", 1, true);
        add(crops, "SHELLFRUIT", "Shellfruit", 1, true);
        add(crops, "SNOOZLING", "Snoozling", 3, true);
        add(crops, "SOGGYBUD", "Soggybud", 1, true);
        add(crops, "STARTLEVINE", "Startlevine", 1, true);
        add(crops, "STOPLIGHT_PETAL", "Stoplight Petal", 1, true);
        add(crops, "THORNSHADE", "Thornshade", 1, true);
        add(crops, "THUNDERLING", "Thunderling", 1, true);
        add(crops, "TIMESTALK", "Timestalk", 1, true);
        add(crops, "TURTLELLINI", "Turtlellini", 1, true);
        add(crops, "VEILSHROOM", "Veilshroom", 1, true);
        add(crops, "WITHERBLOOM", "Witherbloom", 1, true);
        add(crops, "ZOMBUD", "Zombud", 1, true);

        ALL = Collections.unmodifiableList(crops);
        for (Crop crop : crops) {
            BY_KEY.put(crop.key(), crop);
            if (!crop.block().isEmpty()) BY_BLOCK.put(crop.block(), crop);
            for (String alias : crop.aliases()) {
                BY_ALIAS.putIfAbsent(alias, crop);
            }
        }
    }

    private GreenhouseCrops() {}

    private static void add(List<Crop> crops, String key, String name, int size, boolean mutation,
                            String... extraAliases) {
        List<String> aliases = new ArrayList<>();
        aliases.add(normalize(key));
        aliases.add(normalize(name));
        for (String alias : extraAliases) aliases.add(normalize(alias));
        // Spread the hues with the golden ratio so neighbouring crops in the list look different
        float hue = (float) ((crops.size() * 0.618033988749895) % 1.0);
        int color = Color.HSBtoRGB(hue, 0.7f, 1.0f) & 0xFFFFFF;
        crops.add(new Crop(key, name, size, color, List.copyOf(aliases.stream().distinct().toList()), mutation,
                BLOCK_IDS.getOrDefault(key, "")));
    }

    static List<Crop> all() {
        return ALL;
    }

    /** Looks a crop up by its upper case key. */
    static Crop byKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** The plain crop that is planted as this block (an id like "minecraft:wheat"), or null. */
    static Crop byBlockId(String blockId) {
        return blockId == null ? null : BY_BLOCK.get(blockId);
    }

    /**
     * Looks a crop up by any of its names ("Wheat Seeds", "WILD_ROSE", "do not eat shroom"), ignoring
     * case, spaces and punctuation. Returns null for names that are not a crop.
     */
    static Crop find(String name) {
        if (name == null) return null;
        String normalized = normalize(name);
        return normalized.isEmpty() ? null : BY_ALIAS.get(normalized);
    }

    /**
     * Works out which crop a planted armor stand head is from the head item's name, for example
     * "Veilshroom". Armor stands that merely decorate a crop (PlantBoy's roots, the Godseed pillar)
     * don't count. Returns null when the name is not a crop.
     */
    static Crop fromPlantedName(String itemName) {
        if (itemName == null) return null;
        String normalized = normalize(itemName);
        if (normalized.isEmpty()) return null;
        if (normalized.contains("roots") && normalized.contains("plantboy")) return null;
        if (normalized.contains("pillar")) return null;

        Crop exact = BY_ALIAS.get(normalized);
        if (exact != null) return exact;

        Crop best = null;
        int bestLength = 0;
        for (Crop crop : ALL) {
            for (String alias : crop.aliases()) {
                if (alias.length() < MIN_PARTIAL_ALIAS_LENGTH || alias.length() <= bestLength) continue;
                if (normalized.contains(alias)) {
                    best = crop;
                    bestLength = alias.length();
                }
            }
        }
        return best;
    }

    /** Lower cases a name and drops everything but letters and digits. */
    static String normalize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
