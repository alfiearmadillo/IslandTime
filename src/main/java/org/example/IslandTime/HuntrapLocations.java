package org.example.islandtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which Hunting shards a Huntrap can catch at each placement location.
 *
 * This is the only place that needs editing when the Huntraps locations list changes:
 * add or fix a {@code location(...)} line below. A shard written as {@code "Name"} has
 * equal odds with the other shards at that location; {@code "Name=3"} gives it a weight
 * of 3 against the default of 1 for the others.
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

    /** A shard that can be caught at a location, with its weight against the other shards there. */
    record Entry(Shard shard, double weight) {
    }

    record Location(String name, String placement, List<Entry> entries) {

        /** e.g. "Crystal Hollows, Underwater", or just "Smoldering Tomb" when the page gives no placement. */
        String label() {
            return placement == null ? name : name + ", " + placement;
        }

        double totalWeight() {
            double total = 0;
            for (Entry entry : entries) total += entry.weight();
            return total;
        }

        /** True when no shard at this location has a weight different from the others. */
        boolean equalOdds() {
            for (Entry entry : entries) {
                if (entry.weight() != entries.get(0).weight()) return false;
            }
            return true;
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
        List<Entry> entries = new ArrayList<>();
        for (String spec : shards) {
            String shardName = spec;
            double weight = 1;
            int eq = spec.indexOf('=');
            if (eq >= 0) {
                shardName = spec.substring(0, eq);
                weight = Double.parseDouble(spec.substring(eq + 1));
            }
            Shard shard = SHARDS.get(shardName);
            if (shard == null) throw new IllegalStateException("Unknown shard '" + shardName + "' at " + name);
            entries.add(new Entry(shard, weight));
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
        shard("Birries", "SHARD_BIRRIES", Rarity.COMMON, Category.WATER);
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
        shard("Fenlord", "SHARD_FENLORD", Rarity.EPIC, Category.FOREST);
        shard("Giant Water Bug", "SHARD_GIANT_WATER_BUG", Rarity.RARE, Category.WATER);
        shard("Goldolot", "SHARD_GOLDOLOT", Rarity.RARE, Category.WATER);
        shard("Hellwisp", "SHARD_HELLWISP", Rarity.LEGENDARY, Category.COMBAT);
        shard("Inferno Koi", "SHARD_INFERNO_KOI", Rarity.RARE, Category.WATER);
        shard("Invisibug", "SHARD_INVISIBUG", Rarity.RARE, Category.FOREST);
        shard("Joydive", "SHARD_JOYDIVE", Rarity.RARE, Category.WATER);
        shard("Lava Flame", "SHARD_LAVA_FLAME", Rarity.RARE, Category.WATER);
        shard("Lotum", "SHARD_LOTUM", Rarity.UNCOMMON, Category.WATER);
        shard("Lotusfish", "SHARD_LOTUS_FISH", Rarity.COMMON, Category.WATER);
        shard("Lumisquid", "SHARD_LUMISQUID", Rarity.RARE, Category.WATER);
        shard("Magma Slug", "SHARD_MAGMA_SLUG", Rarity.UNCOMMON, Category.WATER);
        shard("Mochibear", "SHARD_MOCHIBEAR", Rarity.UNCOMMON, Category.FOREST);
        shard("Mossybit", "SHARD_MOSSYBIT", Rarity.UNCOMMON, Category.WATER);
        shard("Mountain Goat", "SHARD_MOUNTAIN_GOAT", Rarity.EPIC, Category.FOREST);
        shard("Mudworm", "SHARD_MUDWORM", Rarity.COMMON, Category.FOREST);
        shard("Phanflare", "SHARD_PHANFLARE", Rarity.COMMON, Category.FOREST);
        shard("Phanpyre", "SHARD_PHANPYRE", Rarity.COMMON, Category.FOREST);
        shard("Piranha", "SHARD_PIRANHA", Rarity.RARE, Category.WATER);
        shard("Queen Ant", "SHARD_QUEEN_ANT", Rarity.UNCOMMON, Category.FOREST);
        shard("Salmon", "SHARD_SALMON", Rarity.UNCOMMON, Category.WATER);
        shard("Sepialot", "SHARD_SEPIALOT", Rarity.UNCOMMON, Category.WATER);
        shard("Shellwise", "SHARD_SHELLWISE", Rarity.EPIC, Category.WATER);
        shard("Silentdepth", "SHARD_SILENTDEPTH", Rarity.RARE, Category.WATER);
        shard("Snowfin", "SHARD_SNOWFIN", Rarity.RARE, Category.WATER);
        shard("Solar", "SHARD_SOLAR", Rarity.UNCOMMON, Category.WATER);
        shard("Soul of the Alpha", "SHARD_SOUL_OF_THE_ALPHA", Rarity.UNCOMMON, Category.COMBAT);
        shard("Spike", "SHARD_SPIKE", Rarity.LEGENDARY, Category.WATER);
        shard("Stingray", "SHARD_STINGRAY", Rarity.RARE, Category.WATER);
        shard("Stridersurfer", "SHARD_STRIDER_SURFER", Rarity.UNCOMMON, Category.WATER);
        shard("Tadgang", "SHARD_TADGANG", Rarity.COMMON, Category.WATER);
        shard("Tewtil", "SHARD_TEWTIL", Rarity.UNCOMMON, Category.WATER);
        shard("Thyst", "SHARD_THYST", Rarity.RARE, Category.FOREST);
        shard("Timil", "SHARD_TIMIL", Rarity.COMMON, Category.WATER);
        shard("Titanoboa", "SHARD_TITANOBOA", Rarity.LEGENDARY, Category.WATER);
        shard("Treasure Hoarder", "SHARD_TREASURE_HOARDER", Rarity.UNCOMMON, Category.WATER);
        shard("Verdant", "SHARD_VERDANT", Rarity.COMMON, Category.WATER);
        shard("Water Snake", "SHARD_WATER_SNAKE", Rarity.UNCOMMON, Category.WATER);
        shard("Wetwing", "SHARD_WETWING", Rarity.UNCOMMON, Category.WATER);
        shard("XYZ", "SHARD_XYZ", Rarity.EPIC, Category.FOREST);

        // name, placement (null = none given), shards
        location("Ant's Cave", null, "Ant", "Queen Ant");
        location("Backwater Bayou", "Underwater", "Alligator", "Bayou Sludge", "Dumpster Diver", "Titanoboa");
        location("Crimson Isle", "Under lava", "Inferno Koi", "Lava Flame", "Magma Slug");
        location("Crystal Hollows", "Underwater", "Silentdepth", "Thyst");
        location("Drowned Reliquary", null, "Drowned");
        location("Dwarven Mines", "Underwater", "Abyssal Lanternfish", "Treasure Hoarder");
        location("Galatea", "On Land", "Birries", "Dreadwing", "Fenlord", "Invisibug", "Mossybit", "Mudworm",
                "Phanflare", "Phanpyre");
        location("Hotspot Haven", "Underwater", "Ember", "Giant Water Bug", "Stingray");
        location("Howling Cave", null, "Soul of the Alpha");
        location("Jerry's Workshop", "Underwater", "Snowfin");
        location("Kelpwoven Tunnels", "Underwater", "Spike");
        location("Lotus Atoll", "On Land", "Lotum", "Tewtil");
        location("Lotus Atoll", "Underwater", "Lotusfish", "Tewtil");
        location("Matriarch's Lair", null, "Hellwisp");
        location("Murkwater Depths", "Underwater", "Azure", "Cod", "Coralot", "Ent", "Joydive", "Piranha", "Salmon",
                "Shellwise", "Spike", "Tadgang", "Verdant", "Wetwing");
        location("Murkwater Shallows", "Underwater", "Azure", "Cod", "Ent", "Piranha", "Salmon", "Tadgang", "Verdant",
                "Wetwing");
        location("Mystic Marsh", null, "XYZ");
        location("Smoldering Tomb", null, "Cinderbat");
        location("Spring Depths", "Underwater", "Ember", "Goldolot", "Sepialot", "Stingray", "Water Snake");
        location("Spring Path", null, "Dung Beetle", "Dustybit", "Mountain Goat");
        location("Spring Path", "Underwater", "Timil");
        location("Spring Shallows", "Underwater", "Goldolot", "Sepialot", "Solar", "Water Snake");
        location("Squid Cave", "Underwater", "Lumisquid");
        location("Stride-Ember Fissure", null, "Stridersurfer");
        location("Torrhus Canyon", "On Land", "Bunbun", "Dung Beetle", "Sepialot");
        location("Torrhus Springs", "On Land", "Dustybit", "Goldolot", "Sepialot");
        location("Tranquility Sanctum", "Spring or Summer", "Bambuleaf");
        location("Tranquility Sanctum", "Autumn or Winter", "Mochibear");
        location("Wyrmgrove Tomb", null, "Chill");
    }

    private HuntrapLocations() {
    }
}
