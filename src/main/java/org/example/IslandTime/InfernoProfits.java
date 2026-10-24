package org.example.islandtime;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a day of Inferno minions earns on each of the three fuels: the expected loot of a whole fuel day
 * ({@link InfernoReturnCalculator}, valued at the Bazaar sell offer) less the fuel and, on Hypergolic fuel,
 * the eyedrops. Fuel is priced as {@code /it fuelcost} does, at the Bazaar buy order and with only Gabagool
 * Distillate. Kept free of Minecraft code, like the calculator, so it can be tested alone.
 */
final class InfernoProfits {

    static final String[] GRADE_RARITIES = {"Rare", "Epic", "Legendary"};

    /**
     * One fuel's day. {@code fuel} is null when a fuel ingredient has no Bazaar price. {@code fuelCost} and
     * {@code profit} are NaN when the fuel, the loot or the eyedrops can't be priced.
     */
    record GradeProfit(int grade, InfernoReturnCalculator.Estimate estimate, PriceCache.FuelCostBreakdown fuel,
                       double fuelCost, double profit) {

        String fuelName() {
            return InfernoReturnCalculator.GRADE_NAMES[grade];
        }

        String rarity() {
            return GRADE_RARITIES[grade];
        }

        boolean priced() {
            return !Double.isNaN(profit);
        }
    }

    /** Sell offers of everything the loot and eyedrops use, from the Bazaar; what it has no price for is left out. */
    static InfernoReturnCalculator.Prices sellOffers(JsonObject products) {
        Map<String, Double> byId = new HashMap<>();
        if (products != null) {
            for (String id : InfernoReturnCalculator.priceIds()) {
                double price = PriceCache.getSellOfferPrice(products, id);
                if (price > 0) byId.put(id, price);
            }
        }
        return new InfernoReturnCalculator.Prices(byId);
    }

    /**
     * The profit of {@code minions} minions over one whole fuel day on each grade of fuel, in the order Rare,
     * Epic, Legendary. The minions use the speed bonuses and the eyedrops of {@code settings}, whatever fuel it
     * names, and whole days only: nothing is earned from mob kills while the minions are left alone.
     *
     * @param sellOffers prices the loot and the eyedrop ingredients are valued at
     * @param products   the Bazaar, for the buy orders of the fuel ingredients
     */
    static List<GradeProfit> compute(InfernoReturnCalculator.Settings settings, int minions,
                                     InfernoReturnCalculator.Prices sellOffers, JsonObject products) {
        List<GradeProfit> result = new ArrayList<>();
        if (minions <= 0) return result;
        for (int grade = 0; grade < InfernoReturnCalculator.GRADE_NAMES.length; grade++) {
            InfernoReturnCalculator.Settings gradeSettings = settings.withFuelGrade(grade);
            InfernoReturnCalculator.Estimate estimate = InfernoReturnCalculator.estimate(gradeSettings, minions,
                    InfernoReturnCalculator.FUEL_DAY_SECONDS, InfernoReturnCalculator.FUEL_DAY_SECONDS, sellOffers);
            PriceCache.FuelCostBreakdown fuel = PriceCache.getInfernoFuelCostBreakdown(products, minions, true, grade);
            double fuelCost = fuel == null ? Double.NaN : fuel.totalCost;
            double profit = estimate.fullyPriced() ? estimate.value() - fuelCost - estimate.eyedropTotal() : Double.NaN;
            result.add(new GradeProfit(grade, estimate, fuel, fuelCost, profit));
        }
        return result;
    }

    /** Chat/tooltip text for an item count: whole numbers when large, a few decimals for fractions of an item. */
    static String formatAmount(double amount) {
        double abs = Math.abs(amount);
        if (abs == 0) return "0";
        if (abs >= 100) return String.format(Locale.ROOT, "%,.0f", amount);
        if (abs >= 1) return String.format(Locale.ROOT, "%,.2f", amount);
        if (abs >= 0.0001) return String.format(Locale.ROOT, "%.4f", amount);
        return String.format(Locale.ROOT, "%.2e", amount);
    }

    private InfernoProfits() {
    }
}
