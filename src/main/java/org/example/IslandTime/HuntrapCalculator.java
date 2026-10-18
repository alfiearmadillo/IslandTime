package org.example.islandtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Estimates Huntrap profit per hour. No Minecraft dependencies, so it can be tested on its own.
 *
 * Model, per shard i at a location where it is caught with odds p_i:
 * <pre>
 *   catch time T_i   = averageBaseHours * (1 - trapTimeReduction) / speed_i
 *   speed_i          = 1 + desertTemple(0.25) + forestLevel% (Forest shards) + combatLevel% (Combat shards)
 *   shards per catch = 1 + hunterFortune / 100
 *   mean catch time  = sum(p_i * T_i)
 *   profit per hour  = traps * shardsPerCatch * sum(p_i * price_i) / mean catch time
 * </pre>
 * Each shard's own share of the profit is traps * shardsPerCatch * p_i * price_i / mean catch time,
 * so the shares add up to the location's profit per hour.
 */
final class HuntrapCalculator {

    static final int TRAP_COUNT = 5;

    /** The wiki gives a 16-24h range per catch; profit is estimated from its average. */
    static final double MIN_BASE_HOURS = 16;
    static final double MAX_BASE_HOURS = 24;
    static final double AVERAGE_BASE_HOURS = (MIN_BASE_HOURS + MAX_BASE_HOURS) / 2;

    static final double DESERT_TEMPLE_SPEED_BONUS = 0.25;
    static final int MAX_ATTRIBUTE_LEVEL = 10;

    enum TrapType {
        SMALL("Small Huntrap", 0),
        MEDIUM("Medium Huntrap", 10),
        LARGE("Large Huntrap", 20),
        GREATER("Greater Huntrap", 35),
        ASTRAL("Astral Huntrap", 50);

        final String displayName;
        /** Percentage taken off the catch time. */
        final int timeReductionPercent;

        TrapType(String displayName, int timeReductionPercent) {
            this.displayName = displayName;
            this.timeReductionPercent = timeReductionPercent;
        }

        double timeMultiplier() {
            return 1 - timeReductionPercent / 100.0;
        }

        static TrapType fromIndex(int index) {
            TrapType[] values = values();
            return values[Math.max(0, Math.min(values.length - 1, index))];
        }
    }

    record Settings(TrapType trap, double hunterFortune, boolean desertTemple, int forestLevel, int combatLevel) {

        double shardsPerCatch() {
            return 1 + hunterFortune / 100.0;
        }

        /** Level of the Forest/Combat Trap attribute that applies to the shard's category (0 for others). */
        int attributeLevel(HuntrapLocations.Category category) {
            return switch (category) {
                case FOREST -> forestLevel;
                case COMBAT -> combatLevel;
                case WATER -> 0;
            };
        }

        double speed(HuntrapLocations.Category category) {
            return 1 + (desertTemple ? DESERT_TEMPLE_SPEED_BONUS : 0) + attributeLevel(category) / 100.0;
        }

        double catchHours(HuntrapLocations.Category category) {
            return AVERAGE_BASE_HOURS * trap.timeMultiplier() / speed(category);
        }
    }

    record ShardResult(HuntrapLocations.Entry entry, double odds, double price, double speed, double catchHours,
                       double profitPerHour) {

        boolean priced() {
            return price > 0;
        }
    }

    record LocationResult(HuntrapLocations.Location location, List<ShardResult> shards, double meanCatchHours,
                          double profitPerHour) {

        /** False when none of the location's shards has a Bazaar price. */
        boolean priced() {
            for (ShardResult shard : shards) {
                if (shard.priced()) return true;
            }
            return false;
        }
    }

    /** Evaluates one location; {@code shards} come back ordered by their share of the profit, highest first. */
    static LocationResult evaluate(HuntrapLocations.Location location, Settings settings,
                                   ToDoubleFunction<HuntrapLocations.Shard> priceOf) {
        double totalWeight = location.totalWeight();
        double meanCatchHours = 0;
        for (HuntrapLocations.Entry entry : location.entries()) {
            meanCatchHours += entry.weight() / totalWeight * settings.catchHours(entry.shard().category());
        }

        double catchesPerHour = TRAP_COUNT / meanCatchHours;
        List<ShardResult> results = new ArrayList<>();
        double total = 0;
        for (HuntrapLocations.Entry entry : location.entries()) {
            HuntrapLocations.Shard shard = entry.shard();
            double odds = entry.weight() / totalWeight;
            double price = Math.max(0, priceOf.applyAsDouble(shard));
            double perHour = catchesPerHour * settings.shardsPerCatch() * odds * price;
            total += perHour;
            results.add(new ShardResult(entry, odds, price, settings.speed(shard.category()),
                    settings.catchHours(shard.category()), perHour));
        }
        results.sort((a, b) -> Double.compare(b.profitPerHour(), a.profitPerHour()));
        return new LocationResult(location, List.copyOf(results), meanCatchHours, total);
    }

    /** Evaluates every location, best first. Locations without any priced shard are returned last. */
    static List<LocationResult> evaluateAll(Settings settings, ToDoubleFunction<HuntrapLocations.Shard> priceOf) {
        List<LocationResult> results = new ArrayList<>();
        for (HuntrapLocations.Location location : HuntrapLocations.all()) {
            results.add(evaluate(location, settings, priceOf));
        }
        results.sort((a, b) -> {
            if (a.priced() != b.priced()) return a.priced() ? -1 : 1;
            return Double.compare(b.profitPerHour(), a.profitPerHour());
        });
        return results;
    }

    /**
     * Parses the Hunter Fortune text box. Accepts plain or comma-grouped numbers such as "120" or "1,250.5".
     * Returns NaN when the text is not a finite number of at least 0.
     */
    static double parseHunterFortune(String text) {
        if (text == null) return Double.NaN;
        String cleaned = text.trim().replace(",", "").replace("_", "");
        if (cleaned.isEmpty()) return 0;
        try {
            double value = Double.parseDouble(cleaned);
            return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private HuntrapCalculator() {
    }
}
