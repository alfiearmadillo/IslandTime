package org.example.islandtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimates what Inferno minions running on Inferno Minion Fuel return over one fuel day. No Minecraft
 * dependencies, so it can be tested on its own.
 *
 * Model, per Inferno minion:
 * <pre>
 *   speed bonus %    = Rising Celsius + Flycatcher(s) + Beacon + Postcard + Mithril Infusion + Free Will   (all additive)
 *   seconds / action = baseSeconds(tier) / (1 + speed bonus / 100) / (1 + fuel grade bonus)
 *                      (rounded down to a whole tick, as in the in-game cooldown)
 *   harvests / day   = active seconds / (2 * seconds / action)                (one harvest = spawn + kill)
 *   Crude Gabagool   = harvests / day                                        (Gabagool Distillate fuel: every
 *                                                                              harvest drops 1 Crude Gabagool)
 * </pre>
 * The speed bonuses add up to one percentage; only the fuel grade multiplies on top of that sum, so a +20%
 * and a +10% bonus make +30%, not +32%.
 * Rising Celsius gives every Inferno minion +18% speed per Inferno minion on the island, up to 10 minions.
 * A Super Compactor 3000 turns Crude Gabagool into Very Crude Gabagool at 192 : 1, so with one the loot is valued
 * as {@code crude / 192} Very Crude Gabagool, keeping the decimals for a partial one.
 *
 * Only Hypergolic fuel also has rare drops: each harvest has a small chance of a Chili Pepper, Inferno Vertex,
 * Reaper Pepper or Inferno Apex (twice the chance for tier X and XI minions), and each fuel day gives a Hypergolic
 * Ionized Ceramics. Gabagool the Fish (1 in 3,927,273) isn't sold on the Bazaar, so it is worth the price the user
 * sets ({@link #DEFAULT_FISH_PRICE} by default). Capsaicin Eyedrops multiply the chance of every per-harvest rare
 * drop by 1.3 (not the ceramics) and are assumed used on every minion every day. They cost one craft (4 Chili Pepper,
 * 128 Exportable Carrots, 128 Enchanted Carrot and a Water Hydra Head, at the user's price as it isn't on the Bazaar)
 * for each 3 minions, rounded up: 31 minions craft 11 times, which makes 33. Minions only make item drops, so no
 * coins are counted for the mobs they kill.
 */
final class InfernoReturnCalculator {

    /** Seconds between actions of an Inferno minion, tier I to XI, before any bonus. */
    static final double[] BASE_SECONDS = {1013, 982, 950, 919, 886, 855, 823, 792, 760, 728, 697};
    static final int MAX_TIER = BASE_SECONDS.length;

    static final double RISING_CELSIUS_PER_MINION = 18;
    static final int RISING_CELSIUS_MAX_MINIONS = 10;

    static final double FLYCATCHER_BONUS = 20;
    static final double SECOND_FLYCATCHER_BONUS = 20;
    /** Beacon V. A Power Crystal adds nothing on top, a Scorched Power Crystal adds 1 more. */
    static final double BEACON_V_BONUS = 10;
    static final double SCORCHED_POWER_CRYSTAL_BONUS = 1;
    static final double POSTCARD_BONUS = 5;
    static final double INFUSION_BONUS = 10;
    static final double FREE_WILL_BONUS = 10;

    static final int BEACON_NONE = 0;
    static final int BEACON_POWER_CRYSTAL = 1;
    static final int BEACON_SCORCHED_POWER_CRYSTAL = 2;

    static final int GRADE_FUEL = 0;
    static final int GRADE_HEAVY = 1;
    static final int GRADE_HYPERGOLIC = 2;
    static final String[] GRADE_NAMES = {"Fuel Gabagool", "Heavy Gabagool", "Hypergolic Gabagool"};
    /** The fuel grade multiplies the minion's speed by {@code 1 + bonus}: +1000%, +1500% and +2000%. */
    static final double[] GRADE_BONUS = {10, 15, 20};

    static final double ACTIONS_PER_HARVEST = 2;
    static final double CRUDE_PER_VERY_CRUDE = 192;

    /** Bazaar product ids of the loot. */
    static final String CRUDE_GABAGOOL_ID = "CRUDE_GABAGOOL";
    static final String VERY_CRUDE_GABAGOOL_ID = "VERY_CRUDE_GABAGOOL";
    static final String HYPERGOLIC_IONIZED_CERAMICS_ID = "HYPERGOLIC_IONIZED_CERAMICS";
    private static final double TICK_SECONDS = 0.05;
    static final double FUEL_DAY_SECONDS = 86_400;
    /** Minion tier from which the Inferno Apex chance doubles (the Apex minion perk). */
    static final int APEX_PERK_TIER = 10;

    /** Capsaicin Eyedrops: the chance of each per-harvest rare drop is multiplied by this. */
    static final double EYEDROPS_MULTIPLIER = 1.3;
    /** Gabagool the Fish and Water Hydra Heads aren't on the Bazaar, so they are worth what the user sets. */
    static final double DEFAULT_FISH_PRICE = 5_000_000;
    static final double DEFAULT_HYDRA_HEAD_PRICE = 1_500_000;

    /**
     * A drop of Hypergolic fuel that happens with {@code chancePerHarvest} on every harvest. Only the ones with
     * {@code onBazaar} get their price from the Bazaar; the other is worth the fish price set in the {@link Settings}.
     */
    record RareDrop(String id, String name, double chancePerHarvest, boolean onBazaar) {
        RareDrop(String id, String name, double chancePerHarvest) {
            this(id, name, chancePerHarvest, true);
        }
    }

    static final String INFERNO_APEX_ID = "INFERNO_APEX";
    static final String GABAGOOL_THE_FISH_ID = "GABAGOOL_THE_FISH";
    static final List<RareDrop> RARE_DROPS = List.of(
            new RareDrop("CHILI_PEPPER", "Chili Pepper", 1.0 / 136),
            new RareDrop("INFERNO_VERTEX", "Inferno Vertex", 1.0 / 5950),
            new RareDrop("REAPER_PEPPER", "Reaper Pepper", 1.0 / 458_182),
            new RareDrop(INFERNO_APEX_ID, "Inferno Apex", 1.0 / 1_309_091),
            new RareDrop(GABAGOOL_THE_FISH_ID, "Gabagool the Fish", 1.0 / 3_927_273, false));

    /** One Capsaicin Eyedrops craft makes 3, and an eyedrop lasts a day on one Inferno minion. */
    static final int EYEDROPS_PER_CRAFT = 3;
    static final String WATER_HYDRA_HEAD_ID = "WATER_HYDRA_HEAD";

    /** What one craft of Capsaicin Eyedrops uses; the hydra head isn't on the Bazaar. */
    record Ingredient(String id, String name, int amount, boolean onBazaar) {
    }

    static final List<Ingredient> EYEDROPS_RECIPE = List.of(
            new Ingredient("CHILI_PEPPER", "Chili Pepper", 4, true),
            new Ingredient("EXPORTABLE_CARROTS", "Exportable Carrots", 128, true),
            new Ingredient("ENCHANTED_CARROT", "Enchanted Carrot", 128, true),
            new Ingredient(WATER_HYDRA_HEAD_ID, "Water Hydra Head", 1, false));

    /** Every Bazaar product the estimate can use, for saving their prices at the end of a day. */
    static List<String> priceIds() {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(CRUDE_GABAGOOL_ID);
        ids.add(VERY_CRUDE_GABAGOOL_ID);
        for (RareDrop drop : RARE_DROPS) {
            if (drop.onBazaar()) ids.add(drop.id());
        }
        ids.add(HYPERGOLIC_IONIZED_CERAMICS_ID);
        for (Ingredient ingredient : EYEDROPS_RECIPE) {
            if (ingredient.onBazaar()) ids.add(ingredient.id());
        }
        return new ArrayList<>(ids);
    }

    /**
     * Reads a price typed as "1500000", "1,500,000", "1.5m", "750k" or "2b". Blank, invalid or negative text gives
     * {@code fallback}; an explicit 0 is a valid price.
     */
    static double parsePrice(String text, double fallback) {
        if (text == null) return fallback;
        Matcher matcher = PRICE_TEXT.matcher(text.trim());
        if (!matcher.matches()) return fallback;
        try {
            double value = Double.parseDouble(matcher.group(1).replace(",", ""));
            String suffix = matcher.group(2);
            if (suffix != null) {
                switch (Character.toLowerCase(suffix.charAt(0))) {
                    case 'k' -> value *= 1_000.0;
                    case 'm' -> value *= 1_000_000.0;
                    default -> value *= 1_000_000_000.0;
                }
            }
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final Pattern PRICE_TEXT = Pattern.compile("^([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?$");

    /** Sell offers by Bazaar product id. A missing id, or one that is 0 or less, has no price. */
    record Prices(Map<String, Double> byId) {

        Prices {
            byId = byId == null ? Map.of() : Map.copyOf(byId);
        }

        double get(String id) {
            Double price = byId.get(id);
            return price != null && price > 0 && Double.isFinite(price) ? price : 0;
        }
    }

    /**
     * What is set up on the minions. {@code eyedrops} are Capsaicin Eyedrops in the Hypergolic fuel, used on every
     * minion every day, and {@code afk} makes the estimate only count the time spent AFKing. {@code fishPrice} and
     * {@code hydraHeadPrice} are what Gabagool the Fish and a Water Hydra Head (for crafting the eyedrops) are
     * worth, as neither is on the Bazaar; one that isn't a number of 0 or more is replaced by its default.
     */
    record Settings(int minionTier, int fuelGrade, boolean eyedrops, boolean flycatcher, boolean secondFlycatcher, int beacon,
                    boolean postcard, boolean infusion, boolean freeWill, boolean superCompactor, boolean afk,
                    double fishPrice, double hydraHeadPrice) {

        Settings {
            minionTier = Math.max(1, Math.min(MAX_TIER, minionTier));
            fuelGrade = Math.max(0, Math.min(GRADE_BONUS.length - 1, fuelGrade));
            beacon = Math.max(BEACON_NONE, Math.min(BEACON_SCORCHED_POWER_CRYSTAL, beacon));
            if (!(fishPrice >= 0) || !Double.isFinite(fishPrice)) fishPrice = DEFAULT_FISH_PRICE;
            if (!(hydraHeadPrice >= 0) || !Double.isFinite(hydraHeadPrice)) hydraHeadPrice = DEFAULT_HYDRA_HEAD_PRICE;
        }

        /** The same set-up with another grade of fuel. */
        Settings withFuelGrade(int grade) {
            return new Settings(minionTier, grade, eyedrops, flycatcher, secondFlycatcher, beacon, postcard, infusion,
                    freeWill, superCompactor, afk, fishPrice, hydraHeadPrice);
        }

        /** The same set-up with AFK mode switched on or off. */
        Settings withAfk(boolean value) {
            return new Settings(minionTier, fuelGrade, eyedrops, flycatcher, secondFlycatcher, beacon, postcard, infusion,
                    freeWill, superCompactor, value, fishPrice, hydraHeadPrice);
        }
    }

    /** One line of the speed bonus: what gives it and how much (a percentage, or a multiplier for the fuel). */
    record Contribution(String name, double percent) {
    }

    /**
     * Some amount of one item and what it is worth; {@code price} is 0 when there is no price.
     * {@code assumed} is for a price the user set rather than one from the Bazaar.
     */
    record DropLine(String id, String name, double amount, double price, double value, boolean assumed) {
    }

    /**
     * The Capsaicin Eyedrops for one day: one per minion, but they are made 3 at a time, so the day crafts
     * {@code crafts} times and may make up to 2 more than it uses. {@code total} is NaN when an ingredient has no price.
     */
    record EyedropCost(int crafts, int eyedrops, double perCraft, double total, List<DropLine> ingredients) {
    }

    /**
     * {@code value} is the whole return: Crude (or Very Crude) Gabagool plus the rare drops. It is NaN when
     * Crude Gabagool has no price, and a rare drop with no price just adds nothing. {@code eyedropCost} is null
     * without eyedrops.
     */
    record Estimate(int minions, double speedBonusPercent, double secondsPerAction, double activeSeconds,
                    double harvestsPerMinion, double crudeTotal, double veryCrudeTotal, boolean compacted,
                    double value, boolean usedCrudePriceForCompacted, List<Contribution> bonuses,
                    List<DropLine> rareDrops, EyedropCost eyedropCost) {

        /** False when no price was available to value the loot with. */
        boolean priced() {
            return !Double.isNaN(value);
        }

        /** What the eyedrops cost: 0 without them, NaN when an ingredient has no price. */
        double eyedropTotal() {
            return eyedropCost == null ? 0 : eyedropCost.total();
        }

        /** True when the loot and the eyedrops all have a price, so a profit can be worked out. */
        boolean fullyPriced() {
            return priced() && !Double.isNaN(eyedropTotal());
        }
    }

    /** The additive speed bonuses for {@code minions} Inferno minions on the island, in the order they are listed. */
    static List<Contribution> bonuses(Settings settings, int minions) {
        List<Contribution> list = new ArrayList<>();
        double rising = RISING_CELSIUS_PER_MINION * Math.min(RISING_CELSIUS_MAX_MINIONS, Math.max(0, minions));
        list.add(new Contribution("Rising Celsius (" + Math.min(RISING_CELSIUS_MAX_MINIONS, Math.max(0, minions))
                + " minions)", rising));
        if (settings.flycatcher()) list.add(new Contribution("Flycatcher", FLYCATCHER_BONUS));
        if (settings.secondFlycatcher()) list.add(new Contribution("Second Flycatcher", SECOND_FLYCATCHER_BONUS));
        if (settings.beacon() == BEACON_POWER_CRYSTAL) {
            list.add(new Contribution("Beacon V + Power Crystal", BEACON_V_BONUS));
        } else if (settings.beacon() == BEACON_SCORCHED_POWER_CRYSTAL) {
            list.add(new Contribution("Beacon V + Scorched Power Crystal",
                    BEACON_V_BONUS + SCORCHED_POWER_CRYSTAL_BONUS));
        }
        if (settings.postcard()) list.add(new Contribution("Postcard", POSTCARD_BONUS));
        if (settings.infusion()) list.add(new Contribution("Mithril Infusion", INFUSION_BONUS));
        if (settings.freeWill()) list.add(new Contribution("Free Will", FREE_WILL_BONUS));
        return list;
    }

    static double totalBonusPercent(List<Contribution> bonuses) {
        double total = 0;
        for (Contribution bonus : bonuses) total += bonus.percent();
        return total;
    }

    /** Seconds between two actions of one minion, never below a single tick. */
    static double secondsPerAction(Settings settings, double speedBonusPercent) {
        double seconds = BASE_SECONDS[settings.minionTier() - 1] / (1 + speedBonusPercent / 100)
                / (1 + GRADE_BONUS[settings.fuelGrade()]);
        // Cooldowns are whole ticks. The small epsilon stops an exact tick count such as 0.35 s from rounding down.
        seconds = Math.floor(seconds / TICK_SECONDS + 1e-9) * TICK_SECONDS;
        return Math.max(TICK_SECONDS, seconds);
    }

    /**
     * Estimates the loot of {@code minions} Inferno minions over {@code activeSeconds} of running.
     *
     * @param activeSeconds   how long the minions were producing: the length of the fuel day, or only the time
     *                        spent AFKing
     * @param fuelSeconds     how long the fuel burned, which is what the Hypergolic Ionized Ceramics depends on
     * @param prices          sell offers: Crude and Very Crude Gabagool (the latter only used with a Super
     *                        Compactor) and, for Hypergolic fuel, the rare drops and the eyedrop ingredients
     */
    static Estimate estimate(Settings settings, int minions, double activeSeconds, double fuelSeconds, Prices prices) {
        int count = Math.max(0, minions);
        double seconds = Double.isFinite(activeSeconds) ? Math.max(0, activeSeconds) : 0;
        double fuelTime = Double.isFinite(fuelSeconds) ? Math.max(0, fuelSeconds) : 0;
        double crudePrice = prices.get(CRUDE_GABAGOOL_ID);
        double veryCrudePrice = prices.get(VERY_CRUDE_GABAGOOL_ID);
        List<Contribution> bonuses = bonuses(settings, count);
        double bonusPercent = totalBonusPercent(bonuses);
        double secondsPerAction = secondsPerAction(settings, bonusPercent);
        double harvestsPerMinion = seconds / (ACTIONS_PER_HARVEST * secondsPerAction);
        double crudeTotal = harvestsPerMinion * count;

        boolean compacted = settings.superCompactor();
        double veryCrude = crudeTotal / CRUDE_PER_VERY_CRUDE;
        double value;
        boolean fallback = false;
        if (crudeTotal <= 0) {
            value = 0;
        } else if (compacted && veryCrudePrice > 0) {
            value = veryCrude * veryCrudePrice;
        } else if (crudePrice > 0) {
            value = crudeTotal * crudePrice;
            fallback = compacted;
        } else {
            value = Double.NaN;
        }

        List<DropLine> rare = new ArrayList<>();
        EyedropCost eyedropCost = null;
        if (settings.fuelGrade() == GRADE_HYPERGOLIC) {
            double harvests = harvestsPerMinion * count;
            for (RareDrop drop : RARE_DROPS) {
                double chance = drop.chancePerHarvest();
                if (INFERNO_APEX_ID.equals(drop.id()) && settings.minionTier() >= APEX_PERK_TIER) chance *= 2;
                if (settings.eyedrops()) chance *= EYEDROPS_MULTIPLIER;
                double price = drop.onBazaar() ? prices.get(drop.id()) : settings.fishPrice();
                rare.add(dropLine(drop.id(), drop.name(), harvests * chance, price, !drop.onBazaar()));
            }
            rare.add(dropLine(HYPERGOLIC_IONIZED_CERAMICS_ID, "Hypergolic Ionized Ceramics",
                    count * fuelTime / FUEL_DAY_SECONDS, prices.get(HYPERGOLIC_IONIZED_CERAMICS_ID), false));
            if (settings.eyedrops() && count > 0) eyedropCost = eyedropCost(settings, count, prices);
        }
        if (!Double.isNaN(value)) {
            for (DropLine line : rare) value += line.value();
        }
        return new Estimate(count, bonusPercent, secondsPerAction, seconds, harvestsPerMinion, crudeTotal, veryCrude,
                compacted, value, fallback, List.copyOf(bonuses), List.copyOf(rare), eyedropCost);
    }

    /**
     * One eyedrop per minion per day, crafted 3 at a time and rounded up to whole crafts. The ingredients are
     * valued at their Bazaar sell offer (the price to buy them at once); the hydra head at the user's price.
     */
    static EyedropCost eyedropCost(Settings settings, int minions, Prices prices) {
        int crafts = (Math.max(0, minions) + EYEDROPS_PER_CRAFT - 1) / EYEDROPS_PER_CRAFT;
        double perCraft = 0;
        boolean allPriced = true;
        List<DropLine> ingredients = new ArrayList<>();
        for (Ingredient ingredient : EYEDROPS_RECIPE) {
            boolean assumed = !ingredient.onBazaar();
            double price = assumed ? settings.hydraHeadPrice() : prices.get(ingredient.id());
            if (!assumed && price <= 0) allPriced = false;
            perCraft += ingredient.amount() * price;
            ingredients.add(dropLine(ingredient.id(), ingredient.name(), (double) ingredient.amount() * crafts, price, assumed));
        }
        return new EyedropCost(crafts, crafts * EYEDROPS_PER_CRAFT, allPriced ? perCraft : Double.NaN,
                allPriced ? perCraft * crafts : Double.NaN, List.copyOf(ingredients));
    }

    private static DropLine dropLine(String id, String name, double amount, double price, boolean assumed) {
        return new DropLine(id, name, amount, price, amount * price, assumed);
    }

    private InfernoReturnCalculator() {
    }
}
