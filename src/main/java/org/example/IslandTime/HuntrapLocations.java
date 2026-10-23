package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which Hunting shards a Huntrap can catch at each placement location.
 *
 * The built-in data follows the Locations table on the Hypixel SkyBlock Wiki's Huntraps page (marked as incomplete
 * there, and Fire Eel and Yog are still marked to be confirmed). It is what is used until something is changed in
 * the in-game editor ({@link HuntrapEditorScreen}), which saves everything to {@code huntrap_locations.json}. From
 * then on that file is used instead, and the built-in data can be restored from the editor. To change what a fresh
 * install starts with, add or fix a {@code location(...)} line in the static block below.
 *
 * Odds between the shards of a location:
 * <ul>
 *   <li>{@code "Name=100"} is a shard with a weight listed on the wiki.</li>
 *   <li>{@code "Name"} is a shard without one. If no shard at the location has a weight, all of them get equal
 *       odds. If only some do, the ones without get the average of the listed weights.</li>
 * </ul>
 *
 * A shard the wiki lists for a whole island or zone ("Anywhere", or no placement) is written on every location
 * line it applies to, e.g. Thyst anywhere in the Crystal Hollows is also a Thyst entry underwater and in the
 * Magma Fields.
 */
final class HuntrapLocations {

    enum Rarity {
        COMMON("Common"), UNCOMMON("Uncommon"), RARE("Rare"), EPIC("Epic"), LEGENDARY("Legendary");

        final String displayName;

        Rarity(String displayName) {
            this.displayName = displayName;
        }
    }

    /** Shard category. The Forest and Combat Trap attributes only speed up shards of their own category. */
    enum Category {
        FOREST("Forest"), WATER("Water"), COMBAT("Combat");

        final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }
    }

    /** {@code enabled} is false for a shard that is turned off everywhere (kept in the catalog, but never counted). */
    record Shard(String name, String bazaarId, Rarity rarity, Category category, boolean enabled) {
    }

    /**
     * A shard that can be caught at a location, with its weight against the other shards there.
     * {@code weightKnown} is false when the wiki lists no weight and the value was filled in. {@code enabled} is
     * false when this shard is turned off at this location only; see {@link #active()} for whether it counts.
     */
    record Entry(Shard shard, double weight, boolean weightKnown, boolean enabled) {

        /** Whether the calculation counts it: turned on here, and the shard itself isn't turned off. */
        boolean active() {
            return enabled && shard.enabled();
        }
    }

    /**
     * A placement location. {@code entries} has every shard of the location, turned off ones included, so they can
     * be edited. The weights, odds and totals only go by the {@linkplain Entry#active() active} ones. The calculator
     * is given locations from {@link #all()}, which hold nothing else.
     */
    record Location(String name, String placement, List<Entry> entries, boolean enabled) {

        /** e.g. "Crystal Hollows, Underwater", or just "Smoldering Tomb" when there is no placement. */
        String label() {
            return placement == null ? name : name + ", " + placement;
        }

        double totalWeight() {
            double total = 0;
            for (Entry entry : entries) {
                if (entry.active()) total += entry.weight();
            }
            return total;
        }

        /** True when at least one active shard here has a weight listed on the wiki. */
        boolean anyWeightKnown() {
            for (Entry entry : entries) {
                if (entry.active() && entry.weightKnown()) return true;
            }
            return false;
        }

        int activeCount() {
            int count = 0;
            for (Entry entry : entries) {
                if (entry.active()) count++;
            }
            return count;
        }
    }

    static final int MAX_LOCATION_NAME = 48;
    static final int MAX_PLACEMENT = 64;
    static final int MAX_SHARD_NAME = 40;
    static final int MAX_BAZAAR_ID = 64;
    private static final double MAX_WEIGHT = 1e9;

    // -------------------------------------------------------------------------
    // The editable data. Everything below that is public reads the immutable snapshot, which is rebuilt after
    // every change, so a Huntrap calculation running on another thread never sees a half-edited location.
    // -------------------------------------------------------------------------

    // The "disabled" flags are missing from a file saved before they existed, so everything in one stays on.
    private static final class ShardDef {
        String name;
        String bazaarId;
        Rarity rarity;
        Category category;
        boolean disabled;

        ShardDef(String name, String bazaarId, Rarity rarity, Category category) {
            this.name = name;
            this.bazaarId = bazaarId;
            this.rarity = rarity;
            this.category = category;
        }
    }

    /** A shard at a location. A null weight means "not known", and is filled in when the snapshot is built. */
    private static final class EntryDef {
        String shard;
        Double weight;
        boolean disabled;

        EntryDef(String shard, Double weight) {
            this.shard = shard;
            this.weight = weight;
        }
    }

    private static final class LocationDef {
        String name;
        String placement;
        boolean disabled;
        List<EntryDef> entries = new ArrayList<>();

        LocationDef(String name, String placement) {
            this.name = name;
            this.placement = placement;
        }
    }

    /** What is saved to {@code huntrap_locations.json}. */
    private static final class Model {
        List<ShardDef> shards = new ArrayList<>();
        List<LocationDef> locations = new ArrayList<>();

        Model copy() {
            Model copy = new Model();
            for (ShardDef s : shards) {
                ShardDef shardCopy = new ShardDef(s.name, s.bazaarId, s.rarity, s.category);
                shardCopy.disabled = s.disabled;
                copy.shards.add(shardCopy);
            }
            for (LocationDef l : locations) {
                LocationDef locationCopy = new LocationDef(l.name, l.placement);
                locationCopy.disabled = l.disabled;
                for (EntryDef e : l.entries) {
                    EntryDef entryCopy = new EntryDef(e.shard, e.weight);
                    entryCopy.disabled = e.disabled;
                    locationCopy.entries.add(entryCopy);
                }
                copy.locations.add(locationCopy);
            }
            return copy;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The built-in data from the wiki, filled in by the static block below. Never changed afterwards. */
    private static final Model DEFAULTS = new Model();

    private static Path baseDir = Paths.get("config/IslandTime");
    private static Model model = new Model();
    private static boolean customised;

    private static volatile Map<String, Shard> shardSnapshot = Map.of();
    private static volatile List<Location> locationSnapshot = List.of();
    private static volatile List<Location> activeSnapshot = List.of();

    /**
     * The locations the calculation should rank, in the order they are listed: only the ones turned on that have at
     * least one active shard, and holding just those shards (so their odds are shared out between them alone).
     */
    static List<Location> all() {
        return activeSnapshot;
    }

    /**
     * Every location including empty and turned off ones, with all of their shards. The index of a location here is
     * what the editing methods take.
     */
    static List<Location> allIncludingEmpty() {
        return locationSnapshot;
    }

    /** Every known shard, in the order they are listed. */
    static List<Shard> shards() {
        return List.copyOf(shardSnapshot.values());
    }

    static Shard shard(String name) {
        return shardSnapshot.get(name);
    }

    /** True when the data was changed from the built-in one, so a file is being used. */
    static synchronized boolean isCustomised() {
        return customised;
    }

    private static void shard(String name, String bazaarId, Rarity rarity, Category category) {
        DEFAULTS.shards.add(new ShardDef(name, bazaarId, rarity, category));
    }

    private static void location(String name, String placement, String... shards) {
        LocationDef location = new LocationDef(name, placement);
        for (String spec : shards) {
            String shardName = spec;
            Double weight = null;
            int eq = spec.indexOf('=');
            if (eq >= 0) {
                shardName = spec.substring(0, eq);
                weight = Double.parseDouble(spec.substring(eq + 1));
                if (!(weight > 0)) throw new IllegalStateException("Weight must be above 0: " + spec);
            }
            boolean exists = false;
            for (ShardDef def : DEFAULTS.shards) {
                if (def.name.equals(shardName)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) throw new IllegalStateException("Unknown shard '" + shardName + "' at " + name);
            location.entries.add(new EntryDef(shardName, weight));
        }
        DEFAULTS.locations.add(location);
    }

    static {
        // name, Bazaar id, rarity, category
        shard("Abyssal Lanternfish", "SHARD_ABYSSAL_LANTERN", Rarity.RARE, Category.WATER);
        shard("Alligator", "SHARD_ALLIGATOR", Rarity.EPIC, Category.COMBAT);
        shard("Ant", "SHARD_ANT", Rarity.COMMON, Category.FOREST);
        shard("Azure", "SHARD_AZURE", Rarity.COMMON, Category.WATER);
        shard("Bambuleaf", "SHARD_BAMBULEAF", Rarity.UNCOMMON, Category.FOREST);
        shard("Bayou Sludge", "SHARD_BAYOU_SLUDGE", Rarity.UNCOMMON, Category.WATER);
        shard("Bezal", "SHARD_BEZAL", Rarity.COMMON, Category.COMBAT);
        shard("Birries", "SHARD_BIRRIES", Rarity.COMMON, Category.WATER);
        shard("Brineling", "SHARD_BRINELING", Rarity.COMMON, Category.WATER);
        shard("Bunbun", "SHARD_BUNBUN", Rarity.COMMON, Category.FOREST);
        shard("Chill", "SHARD_CHILL", Rarity.COMMON, Category.COMBAT);
        shard("Cinderbat", "SHARD_CINDER_BAT", Rarity.LEGENDARY, Category.FOREST);
        shard("Cod", "SHARD_COD", Rarity.COMMON, Category.WATER);
        shard("Coralot", "SHARD_CORALOT", Rarity.COMMON, Category.WATER);
        shard("Dreadwing", "SHARD_DREADWING", Rarity.RARE, Category.FOREST);
        shard("Drowned", "SHARD_DROWNED", Rarity.RARE, Category.COMBAT);
        shard("Dumpster Diver", "SHARD_DUMPSTER_DIVER", Rarity.COMMON, Category.WATER);
        shard("Dung Beetle", "SHARD_DUNG_BEETLE", Rarity.UNCOMMON, Category.FOREST);
        shard("Dustybit", "SHARD_DUSTYBIT", Rarity.UNCOMMON, Category.WATER);
        shard("Ember", "SHARD_EMBER", Rarity.UNCOMMON, Category.WATER);
        shard("Ent", "SHARD_ENT", Rarity.UNCOMMON, Category.WATER);
        shard("Fire Eel", "SHARD_FIRE_EEL", Rarity.EPIC, Category.WATER);
        shard("Flaming Spider", "SHARD_FLAMING_SPIDER", Rarity.UNCOMMON, Category.COMBAT);
        shard("Ghost", "SHARD_GHOST", Rarity.EPIC, Category.COMBAT);
        shard("Giant Isopod", "SHARD_GIANT_ISOPOD", Rarity.EPIC, Category.WATER);
        shard("Giant Water Bug", "SHARD_GIANT_WATER_BUG", Rarity.RARE, Category.WATER);
        shard("Glacite Walker", "SHARD_GLACITE_WALKER", Rarity.RARE, Category.COMBAT);
        shard("Golden Ghoul", "SHARD_GOLDEN_GHOUL", Rarity.COMMON, Category.COMBAT);
        shard("Goldolot", "SHARD_GOLDOLOT", Rarity.RARE, Category.WATER);
        shard("Haggard", "SHARD_HAGGARD", Rarity.COMMON, Category.WATER);
        shard("Hellwisp", "SHARD_HELLWISP", Rarity.LEGENDARY, Category.COMBAT);
        shard("Inferno Koi", "SHARD_INFERNO_KOI", Rarity.RARE, Category.WATER);
        shard("Invisibug", "SHARD_INVISIBUG", Rarity.RARE, Category.FOREST);
        shard("Joydive", "SHARD_JOYDIVE", Rarity.RARE, Category.WATER);
        shard("Lapis Zombie", "SHARD_LAPIS_ZOMBIE", Rarity.COMMON, Category.COMBAT);
        shard("Lava Flame", "SHARD_LAVA_FLAME", Rarity.RARE, Category.WATER);
        shard("Lotum", "SHARD_LOTUM", Rarity.UNCOMMON, Category.WATER);
        shard("Lotusfish", "SHARD_LOTUS_FISH", Rarity.COMMON, Category.WATER);
        shard("Lumisquid", "SHARD_LUMISQUID", Rarity.RARE, Category.WATER);
        shard("Magma Slug", "SHARD_MAGMA_SLUG", Rarity.UNCOMMON, Category.WATER);
        shard("Mochibear", "SHARD_MOCHIBEAR", Rarity.UNCOMMON, Category.FOREST);
        shard("Mossybit", "SHARD_MOSSYBIT", Rarity.UNCOMMON, Category.WATER);
        shard("Mudworm", "SHARD_MUDWORM", Rarity.COMMON, Category.FOREST);
        shard("Obsidian Defender", "SHARD_OBSIDIAN_DEFENDER", Rarity.UNCOMMON, Category.COMBAT);
        shard("Phanflare", "SHARD_PHANFLARE", Rarity.COMMON, Category.FOREST);
        shard("Phanpyre", "SHARD_PHANPYRE", Rarity.COMMON, Category.FOREST);
        shard("Piranha", "SHARD_PIRANHA", Rarity.RARE, Category.WATER);
        shard("Queen Ant", "SHARD_QUEEN_ANT", Rarity.UNCOMMON, Category.FOREST);
        shard("Salmon", "SHARD_SALMON", Rarity.UNCOMMON, Category.WATER);
        shard("Seer", "SHARD_SEER", Rarity.UNCOMMON, Category.COMBAT);
        shard("Sepialot", "SHARD_SEPIALOT", Rarity.UNCOMMON, Category.WATER);
        shard("Shellwise", "SHARD_SHELLWISE", Rarity.EPIC, Category.WATER);
        shard("Silentdepth", "SHARD_SILENTDEPTH", Rarity.RARE, Category.WATER);
        shard("Silkbreeze", "SHARD_SILKBREEZE", Rarity.EPIC, Category.WATER);
        shard("Snowfin", "SHARD_SNOWFIN", Rarity.RARE, Category.WATER);
        shard("Solar", "SHARD_SOLAR", Rarity.UNCOMMON, Category.WATER);
        shard("Soul of the Alpha", "SHARD_SOUL_OF_THE_ALPHA", Rarity.UNCOMMON, Category.COMBAT);
        shard("Spike", "SHARD_SPIKE", Rarity.LEGENDARY, Category.WATER);
        shard("Sprawl", "SHARD_SPRAWL", Rarity.UNCOMMON, Category.WATER);
        shard("Stingray", "SHARD_STINGRAY", Rarity.RARE, Category.WATER);
        shard("Stridersurfer", "SHARD_STRIDER_SURFER", Rarity.UNCOMMON, Category.WATER);
        shard("Tadgang", "SHARD_TADGANG", Rarity.COMMON, Category.WATER);
        shard("Tewtil", "SHARD_TEWTIL", Rarity.UNCOMMON, Category.WATER);
        shard("Thyst", "SHARD_THYST", Rarity.RARE, Category.FOREST);
        shard("Timil", "SHARD_TIMIL", Rarity.COMMON, Category.WATER);
        shard("Titanoboa", "SHARD_TITANOBOA", Rarity.LEGENDARY, Category.WATER);
        shard("Torrid", "SHARD_TORRID", Rarity.RARE, Category.WATER);
        shard("Treasure Hoarder", "SHARD_TREASURE_HOARDER", Rarity.UNCOMMON, Category.WATER);
        shard("Verdant", "SHARD_VERDANT", Rarity.COMMON, Category.WATER);
        shard("Voracious Spider", "SHARD_VORACIOUS_SPIDER", Rarity.COMMON, Category.COMBAT);
        shard("Water Snake", "SHARD_WATER_SNAKE", Rarity.UNCOMMON, Category.WATER);
        shard("Wetwing", "SHARD_WETWING", Rarity.UNCOMMON, Category.WATER);
        shard("Wither Spectre", "SHARD_WITHER_SPECTER", Rarity.RARE, Category.COMBAT);
        shard("XYZ", "SHARD_XYZ", Rarity.EPIC, Category.FOREST);
        shard("Yog", "SHARD_YOG", Rarity.COMMON, Category.COMBAT);
        shard("Zealot", "SHARD_ZEALOT", Rarity.COMMON, Category.COMBAT);
        shard("Zealot Bruiser", "SHARD_BRUISER", Rarity.UNCOMMON, Category.COMBAT);

        // name, placement (null = none), shards. Zone rows on the wiki use the zone as the name; the other rows
        // use the island as the name and the placement column as the placement.
        location("Backwater Bayou", "Underwater", "Dumpster Diver=2000", "Bayou Sludge=1000", "Alligator=500",
                "Titanoboa=100");

        location("Crimson Isle", "Under Lava", "Magma Slug", "Lava Flame", "Inferno Koi", "Fire Eel");
        location("Mystic Marsh", null, "XYZ");
        location("Matriarch's Lair", null, "Hellwisp");
        location("Smoldering Tomb", null, "Cinderbat");
        location("Burning Desert", null, "Flaming Spider");
        location("Stronghold", "Blaze rooms", "Bezal");
        location("Stronghold", "Left side, where Wither Spectres spawn", "Wither Spectre");

        // Thyst is listed for "Anywhere" in the Crystal Hollows, so it is also caught underwater and in the Magma Fields
        location("Crystal Hollows", "Underwater", "Thyst", "Silentdepth");
        location("Crystal Hollows", "Anywhere else", "Thyst");
        location("Magma Fields", null, "Thyst", "Yog");

        location("Lapis Quarry", null, "Lapis Zombie");

        location("Dwarven Mines", "Underwater", "Abyssal Lanternfish");
        location("The Mist", null, "Ghost");
        location("Great Ice Wall", null, "Glacite Walker");
        location("Treasure Hoarder's Pond", null, "Treasure Hoarder");

        location("Moonglade Marsh", "On Land", "Phanpyre", "Phanflare", "Birries", "Mudworm", "Mossybit", "Invisibug",
                "Dreadwing");
        location("Murkwater Depths", "Underwater", "Wetwing", "Coralot", "Joydive", "Shellwise", "Spike", "Cod",
                "Verdant", "Tadgang", "Azure", "Salmon", "Ent", "Piranha");
        location("Kelpwoven Tunnels", "Underwater", "Spike", "Cod", "Verdant", "Tadgang", "Azure", "Salmon", "Ent",
                "Piranha");
        location("Stride-Ember Fissure", null, "Stridersurfer");
        location("Drowned Reliquary", null, "Drowned");
        location("Squid Cave", null, "Lumisquid");
        location("Wyrmgrove Tomb", null, "Chill");
        location("Tranquility Sanctum", "Autumn or Winter", "Mochibear");
        location("Tranquility Sanctum", "Spring or Summer", "Bambuleaf");

        location("Hub", "Golden Ghoul spawns in the Crypts", "Golden Ghoul");
        location("Jerry's Workshop", "Underwater", "Snowfin");

        // Tewtil is listed for "Anywhere" on Lotus Atoll, so it is caught both on land and underwater
        location("Lotus Atoll", "On Land", "Tewtil", "Lotum");
        location("Lotus Atoll", "Underwater", "Tewtil", "Lotusfish");

        location("Spider Mound", null, "Voracious Spider");

        // The wiki lists these three under "near where each one spawns", so each has its own spot
        location("Dragon's Nest", "Near Zealot spawns", "Zealot");
        location("Dragon's Nest", "Near Obsidian Defender spawns", "Obsidian Defender");
        location("Dragon's Nest", "Near Seer spawns", "Seer");
        location("Zealot Bruiser Hideout", null, "Zealot Bruiser");

        location("Howling Cave", null, "Soul of the Alpha");

        location("Torrhus Canyon", "On Land", "Bunbun", "Dung Beetle", "Sepialot=100");
        location("Spring Path", "On Land", "Dung Beetle", "Dustybit");
        location("Spring Path", "Underwater", "Timil");
        location("Torrhus Springs", "On Land", "Dustybit", "Goldolot=25", "Sepialot=100");
        location("Spring Shallows", "Underwater", "Solar=75", "Haggard=100", "Brineling=50", "Sprawl=25",
                "Water Snake=50", "Goldolot=25", "Sepialot=100");
        location("Spring Depths", "Underwater", "Ember=50", "Stingray=50", "Torrid=100", "Silkbreeze=25",
                "Giant Isopod=10", "Water Snake=50", "Goldolot=25", "Sepialot=100");
        location("Hotspot Haven", "Underwater", "Ember=50", "Stingray=50", "Giant Water Bug");
        location("Ant's Cave", null, "Ant", "Queen Ant");
    }

    static {
        model = DEFAULTS.copy();
        rebuild();
    }

    // -------------------------------------------------------------------------
    // Snapshot
    // -------------------------------------------------------------------------

    /**
     * Builds the immutable shards and locations from the editable model. A shard without a weight gets the average
     * of the weights that are known at its location (among the shards that count), or 1 if none are. A shard or
     * location that is turned off stays in the editor's list but is left out of what {@link #all()} gives.
     */
    private static void rebuild() {
        Map<String, Shard> shards = new LinkedHashMap<>();
        for (ShardDef def : model.shards) {
            shards.put(def.name, new Shard(def.name, def.bazaarId, def.rarity, def.category, !def.disabled));
        }

        List<Location> locations = new ArrayList<>();
        List<Location> active = new ArrayList<>();
        for (LocationDef def : model.locations) {
            double knownTotal = 0;
            int knownCount = 0;
            for (EntryDef entry : def.entries) {
                Shard shard = shards.get(entry.shard);
                if (shard == null || entry.disabled || !shard.enabled()) continue;
                if (entry.weight != null) {
                    knownTotal += entry.weight;
                    knownCount++;
                }
            }
            double fillWeight = knownCount > 0 ? knownTotal / knownCount : 1;

            List<Entry> entries = new ArrayList<>();
            List<Entry> activeEntries = new ArrayList<>();
            for (EntryDef entry : def.entries) {
                Shard shard = shards.get(entry.shard);
                if (shard == null) continue;
                boolean known = entry.weight != null;
                Entry built = new Entry(shard, known ? entry.weight : fillWeight, known, !entry.disabled);
                entries.add(built);
                if (built.active()) activeEntries.add(built);
            }
            locations.add(new Location(def.name, def.placement, List.copyOf(entries), !def.disabled));
            if (!def.disabled && !activeEntries.isEmpty()) {
                active.add(new Location(def.name, def.placement, List.copyOf(activeEntries), true));
            }
        }

        shardSnapshot = Collections.unmodifiableMap(shards);
        locationSnapshot = List.copyOf(locations);
        activeSnapshot = List.copyOf(active);
    }

    // -------------------------------------------------------------------------
    // Saving and loading
    // -------------------------------------------------------------------------

    /** Points the file at another directory and goes back to the built-in data. Only for tests. */
    static synchronized void useDirectory(Path dir) {
        baseDir = dir;
        model = DEFAULTS.copy();
        customised = false;
        rebuild();
    }

    private static Path file() {
        return baseDir.resolve("huntrap_locations.json");
    }

    /**
     * Loads the saved data, if there is any. Until the first edit there is no file and the built-in data is used.
     * A file that can't be read is kept next to the original as {@code .corrupt} rather than overwritten.
     */
    static synchronized void load() {
        model = DEFAULTS.copy();
        customised = false;
        Path file = file();
        if (Files.exists(file)) {
            try {
                Model loaded = sanitise(GSON.fromJson(Files.readString(file), Model.class));
                if (loaded == null) throw new IllegalStateException("the file has no shard or location data");
                model = loaded;
                customised = true;
            } catch (Exception e) {
                System.err.println("[HuntrapLocations] Could not read " + file + ": " + e.getMessage()
                        + ". Using the built-in data, and keeping the file as .corrupt.");
                try {
                    Files.move(file, baseDir.resolve("huntrap_locations.json.corrupt"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveFailure) {
                    System.err.println("[HuntrapLocations] Could not keep the unreadable file: "
                            + moveFailure.getMessage());
                }
            }
        }
        rebuild();
    }

    /** Drops anything in loaded data that would break the calculation, instead of refusing the whole file. */
    private static Model sanitise(Model raw) {
        if (raw == null || (raw.shards == null && raw.locations == null)) return null;
        Model clean = new Model();

        Map<String, String> canonicalByLower = new HashMap<>();
        if (raw.shards != null) {
            for (ShardDef def : raw.shards) {
                if (def == null || isBlank(def.name) || isBlank(def.bazaarId) || def.rarity == null
                        || def.category == null) {
                    continue;
                }
                String name = def.name.strip();
                if (canonicalByLower.putIfAbsent(name.toLowerCase(Locale.ROOT), name) != null) continue;
                ShardDef cleanShard = new ShardDef(name, def.bazaarId.strip(), def.rarity, def.category);
                cleanShard.disabled = def.disabled;
                clean.shards.add(cleanShard);
            }
        }

        if (raw.locations != null) {
            for (LocationDef def : raw.locations) {
                if (def == null || isBlank(def.name)) continue;
                LocationDef location = new LocationDef(def.name.strip(), isBlank(def.placement) ? null
                        : def.placement.strip());
                location.disabled = def.disabled;
                Set<String> seen = new HashSet<>();
                if (def.entries != null) {
                    for (EntryDef entry : def.entries) {
                        if (entry == null || entry.shard == null) continue;
                        String shard = canonicalByLower.get(entry.shard.strip().toLowerCase(Locale.ROOT));
                        if (shard == null || !seen.add(shard)) continue;
                        Double weight = entry.weight != null && Double.isFinite(entry.weight) && entry.weight > 0
                                && entry.weight <= MAX_WEIGHT ? entry.weight : null;
                        EntryDef cleanEntry = new EntryDef(shard, weight);
                        cleanEntry.disabled = entry.disabled;
                        location.entries.add(cleanEntry);
                    }
                }
                clean.locations.add(location);
            }
        }
        return clean;
    }

    /** @return null when saved, otherwise why it could not be */
    private static String save() {
        Path file = file();
        Path tmp = baseDir.resolve("huntrap_locations.tmp");
        try {
            Files.createDirectories(baseDir);
            Files.writeString(tmp, GSON.toJson(model));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            customised = true;
            return null;
        } catch (IOException e) {
            System.err.println("[HuntrapLocations] Failed to save: " + e.getMessage());
            return "Changed, but could not be saved to disk: " + e.getMessage();
        }
    }

    /** Applies a finished edit: rebuilds what the calculator reads, and saves. */
    private static String commit() {
        rebuild();
        return save();
    }

    /** Goes back to the built-in data and deletes the saved file. */
    static synchronized String resetToDefaults() {
        model = DEFAULTS.copy();
        customised = false;
        rebuild();
        try {
            Files.deleteIfExists(file());
            return null;
        } catch (IOException e) {
            return "Reset, but the saved file could not be deleted: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Editing. Each of these returns null when it worked, or a message saying why it didn't.
    // -------------------------------------------------------------------------

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String normalisePlacement(String placement) {
        return isBlank(placement) ? null : placement.strip();
    }

    private static String locationKey(String name, String placement) {
        return name.strip().toLowerCase(Locale.ROOT) + "|"
                + (isBlank(placement) ? "" : placement.strip().toLowerCase(Locale.ROOT));
    }

    private static String validateLocation(String name, String placement, int ignoreIndex) {
        if (isBlank(name)) return "Enter a location name.";
        if (name.strip().length() > MAX_LOCATION_NAME) return "Location names can be at most " + MAX_LOCATION_NAME + " characters.";
        if (!isBlank(placement) && placement.strip().length() > MAX_PLACEMENT) {
            return "Placements can be at most " + MAX_PLACEMENT + " characters.";
        }
        String key = locationKey(name, placement);
        for (int i = 0; i < model.locations.size(); i++) {
            LocationDef other = model.locations.get(i);
            if (i != ignoreIndex && locationKey(other.name, other.placement).equals(key)) {
                return "There is already a location with that name and placement.";
            }
        }
        return null;
    }

    private static boolean validLocation(int index) {
        return index >= 0 && index < model.locations.size();
    }

    /** Adds an empty location at the end of the list. Add shards to it afterwards. */
    static synchronized String addLocation(String name, String placement) {
        String problem = validateLocation(name, placement, -1);
        if (problem != null) return problem;
        model.locations.add(new LocationDef(name.strip(), normalisePlacement(placement)));
        return commit();
    }

    static synchronized String updateLocation(int index, String name, String placement) {
        if (!validLocation(index)) return "That location no longer exists.";
        String problem = validateLocation(name, placement, index);
        if (problem != null) return problem;
        LocationDef def = model.locations.get(index);
        def.name = name.strip();
        def.placement = normalisePlacement(placement);
        return commit();
    }

    static synchronized String removeLocation(int index) {
        if (!validLocation(index)) return "That location no longer exists.";
        model.locations.remove(index);
        return commit();
    }

    /** Turns a location on or off. A location that is off is kept, but /it huntrapProfit leaves it out. */
    static synchronized String setLocationEnabled(int index, boolean enabled) {
        if (!validLocation(index)) return "That location no longer exists.";
        model.locations.get(index).disabled = !enabled;
        return commit();
    }

    /**
     * Checks the text of a weight box. Blank is allowed and means "not known", and gets the average of the others
     * at the location.
     */
    static String validateWeightText(String text) {
        if (isBlank(text)) return null;
        return weightError(parseNumber(text));
    }

    private static String weightError(double value) {
        if (Double.isNaN(value)) return "The weight must be a number.";
        if (!(value > 0)) return "The weight must be above 0.";
        if (value > MAX_WEIGHT) return "The weight can be at most " + formatWeight(MAX_WEIGHT) + ".";
        return null;
    }

    /** The weight in a box that has passed {@link #validateWeightText}: null for blank. */
    static Double parseWeight(String text) {
        return isBlank(text) ? null : parseNumber(text);
    }

    private static double parseNumber(String text) {
        try {
            double value = Double.parseDouble(text.trim().replace(",", "").replace("_", ""));
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A weight as it is edited: no exponent and no trailing zeros, e.g. "2000" or "12.5". */
    static String formatWeight(double weight) {
        return BigDecimal.valueOf(weight).stripTrailingZeros().toPlainString();
    }

    /** @param weight null when the weight isn't known */
    static synchronized String addEntry(int locationIndex, String shardName, Double weight) {
        if (!validLocation(locationIndex)) return "That location no longer exists.";
        if (!shardSnapshot.containsKey(shardName)) return "That shard no longer exists.";
        if (weight != null) {
            String problem = weightError(weight);
            if (problem != null) return problem;
        }
        LocationDef location = model.locations.get(locationIndex);
        for (EntryDef entry : location.entries) {
            if (entry.shard.equals(shardName)) return shardName + " is already at this location.";
        }
        location.entries.add(new EntryDef(shardName, weight));
        return commit();
    }

    /** @param weight null when the weight isn't known */
    static synchronized String setWeight(int locationIndex, int entryIndex, Double weight) {
        if (!validLocation(locationIndex)) return "That location no longer exists.";
        List<EntryDef> entries = model.locations.get(locationIndex).entries;
        if (entryIndex < 0 || entryIndex >= entries.size()) return "That shard is no longer here.";
        if (weight != null) {
            String problem = weightError(weight);
            if (problem != null) return problem;
        }
        entries.get(entryIndex).weight = weight;
        return commit();
    }

    static synchronized String removeEntry(int locationIndex, int entryIndex) {
        if (!validLocation(locationIndex)) return "That location no longer exists.";
        List<EntryDef> entries = model.locations.get(locationIndex).entries;
        if (entryIndex < 0 || entryIndex >= entries.size()) return "That shard is no longer here.";
        entries.remove(entryIndex);
        return commit();
    }

    /**
     * Turns a shard on or off at one location. One that is off is kept with its weight, but the calculation acts as
     * if it wasn't there, so the odds of the others add up to 100% without it.
     */
    static synchronized String setEntryEnabled(int locationIndex, int entryIndex, boolean enabled) {
        if (!validLocation(locationIndex)) return "That location no longer exists.";
        List<EntryDef> entries = model.locations.get(locationIndex).entries;
        if (entryIndex < 0 || entryIndex >= entries.size()) return "That shard is no longer here.";
        entries.get(entryIndex).disabled = !enabled;
        return commit();
    }

    /** Turns a shard on or off at every location at once, without touching its per-location settings. */
    static synchronized String setShardEnabled(String name, boolean enabled) {
        ShardDef def = findShard(name);
        if (def == null) return "That shard no longer exists.";
        def.disabled = !enabled;
        return commit();
    }

    private static String validateShard(String name, String bazaarId, String ignoreName) {
        if (isBlank(name)) return "Enter a shard name.";
        String stripped = name.strip();
        if (stripped.length() > MAX_SHARD_NAME) return "Shard names can be at most " + MAX_SHARD_NAME + " characters.";
        for (ShardDef other : model.shards) {
            if (!other.name.equals(ignoreName) && other.name.equalsIgnoreCase(stripped)) {
                return "There is already a shard called \"" + other.name + "\".";
            }
        }
        if (isBlank(bazaarId)) return "Enter the Bazaar id, e.g. SHARD_ANT.";
        String id = bazaarId.strip();
        if (id.length() > MAX_BAZAAR_ID) return "The Bazaar id can be at most " + MAX_BAZAAR_ID + " characters.";
        if (!id.matches("[A-Za-z0-9_:.-]+")) return "The Bazaar id can only use letters, numbers and _ : . -";
        return null;
    }

    static synchronized String addShard(String name, String bazaarId, Rarity rarity, Category category) {
        String problem = validateShard(name, bazaarId, null);
        if (problem != null) return problem;
        model.shards.add(new ShardDef(name.strip(), bazaarId.strip().toUpperCase(Locale.ROOT), rarity, category));
        return commit();
    }

    /** Changes a shard. If it is renamed, every location that has it follows. */
    static synchronized String updateShard(String oldName, String name, String bazaarId, Rarity rarity,
                                           Category category) {
        ShardDef def = findShard(oldName);
        if (def == null) return "That shard no longer exists.";
        String problem = validateShard(name, bazaarId, oldName);
        if (problem != null) return problem;
        String newName = name.strip();
        for (LocationDef location : model.locations) {
            for (EntryDef entry : location.entries) {
                if (entry.shard.equals(oldName)) entry.shard = newName;
            }
        }
        def.name = newName;
        def.bazaarId = bazaarId.strip().toUpperCase(Locale.ROOT);
        def.rarity = rarity;
        def.category = category;
        return commit();
    }

    /** Deletes a shard, and takes it out of every location that has it. */
    static synchronized String removeShard(String name) {
        ShardDef def = findShard(name);
        if (def == null) return "That shard no longer exists.";
        model.shards.remove(def);
        for (LocationDef location : model.locations) location.entries.removeIf(entry -> entry.shard.equals(name));
        return commit();
    }

    /** How many locations a shard can be caught at, for telling the user what deleting it would change. */
    static synchronized int locationsWith(String shardName) {
        int count = 0;
        for (LocationDef location : model.locations) {
            for (EntryDef entry : location.entries) {
                if (entry.shard.equals(shardName)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static ShardDef findShard(String name) {
        for (ShardDef def : model.shards) {
            if (def.name.equals(name)) return def;
        }
        return null;
    }

    private HuntrapLocations() {
    }
}
