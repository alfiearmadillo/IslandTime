package org.example.islandtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which Hunting shards a Huntrap can catch at each placement location.
 *
 * The data follows the Locations table on the Hypixel SkyBlock Wiki's Huntraps page (marked as incomplete there,
 * and Fire Eel and Yog are still marked to be confirmed). It is the only place that needs editing when that
 * table changes: add or fix a {@code location(...)} line below.
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

    record Shard(String name, String bazaarId, Rarity rarity, Category category) {
    }

    /**
     * A shard that can be caught at a location, with its weight against the other shards there.
     * {@code weightKnown} is false when the wiki lists no weight and the value was filled in.
     */
    record Entry(Shard shard, double weight, boolean weightKnown) {
    }

    record Location(String name, String placement, List<Entry> entries) {

        /** e.g. "Crystal Hollows, Underwater", or just "Smoldering Tomb" when there is no placement. */
        String label() {
            return placement == null ? name : name + ", " + placement;
        }

        double totalWeight() {
            double total = 0;
            for (Entry entry : entries) total += entry.weight();
            return total;
        }

        /** True when at least one shard here has a weight listed on the wiki. */
        boolean anyWeightKnown() {
            for (Entry entry : entries) {
                if (entry.weightKnown()) return true;
            }
            return false;
        }
    }

    private static final Map<String, Shard> SHARDS = new LinkedHashMap<>();
    private static final List<Location> LOCATIONS = new ArrayList<>();

    static List<Location> all() {
        return Collections.unmodifiableList(LOCATIONS);
    }

    static Shard shard(String name) {
        return SHARDS.get(name);
    }

    private static void shard(String name, String bazaarId, Rarity rarity, Category category) {
        SHARDS.put(name, new Shard(name, bazaarId, rarity, category));
    }

    private static void location(String name, String placement, String... shards) {
        String[] names = new String[shards.length];
        double[] weights = new double[shards.length];
        double knownTotal = 0;
        int knownCount = 0;
        for (int i = 0; i < shards.length; i++) {
            names[i] = shards[i];
            weights[i] = Double.NaN;
            int eq = shards[i].indexOf('=');
            if (eq >= 0) {
                names[i] = shards[i].substring(0, eq);
                weights[i] = Double.parseDouble(shards[i].substring(eq + 1));
                if (!(weights[i] > 0)) throw new IllegalStateException("Weight must be above 0: " + shards[i]);
                knownTotal += weights[i];
                knownCount++;
            }
        }
        double fillWeight = knownCount > 0 ? knownTotal / knownCount : 1;

        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Shard shard = SHARDS.get(names[i]);
            if (shard == null) throw new IllegalStateException("Unknown shard '" + names[i] + "' at " + name);
            boolean known = !Double.isNaN(weights[i]);
            entries.add(new Entry(shard, known ? weights[i] : fillWeight, known));
        }
        LOCATIONS.add(new Location(name, placement, Collections.unmodifiableList(entries)));
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

    private HuntrapLocations() {
    }
}
