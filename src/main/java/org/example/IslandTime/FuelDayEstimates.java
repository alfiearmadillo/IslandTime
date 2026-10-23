package org.example.islandtime;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The estimated return and profit of one fuel day, shared by the Fuel Day Tracker and the Data Sets screen so a data
 * set in estimated mode shows the same numbers in both. The estimate is for the whole day (24 hours of production,
 * even if a time warp made the day shorter), with the grade of fuel that was placed for that day, and is item drops
 * only: minions that aren't being AFKed next to make no mob-kill coins.
 */
final class FuelDayEstimates {

    /** The estimated return of one day, with what it was worked out from. */
    record DayEstimate(InfernoReturnCalculator.Estimate estimate, InfernoReturnCalculator.Settings settings,
                       InfernoReturnCalculator.Prices prices, String priceSource, double fuelCost,
                       boolean gradeFromFueling, boolean mixedGrades, long productionMs, long timeWarpedMs) {

        boolean priced() {
            return estimate.fullyPriced();
        }

        /** Estimated return minus fuel and Capsaicin Eyedrops. NaN with no price. */
        double profit() {
            return priced() ? estimate.value() - fuelCost - estimate.eyedropTotal() : Double.NaN;
        }
    }

    private FuelDayEstimates() {
    }

    /**
     * What the Inferno minions should have returned on a day.
     *
     * @param configured the set-up from the config; its fuel grade is only used when the day's fueling didn't show one
     * @param liveBazaar the Bazaar to price whatever the day's saved end-of-day prices lack, or null for none
     */
    static DayEstimate estimate(FuelCycleTracker.DayRecord record, InfernoReturnCalculator.Settings configured,
                                JsonObject liveBazaar) {
        int usedGrade = record.getFuelGrade();
        boolean fromFueling = usedGrade >= 0;
        InfernoReturnCalculator.Settings settings = fromFueling ? configured.withFuelGrade(usedGrade) : configured;
        long productionMs = record.getProductionMs();

        Map<String, Double> byId = new HashMap<>();
        boolean fromSnapshot = record.endPricesMs > 0
                && (record.endCrudeSellOffer != null || record.endVeryCrudeSellOffer != null);
        if (fromSnapshot) {
            if (record.endCrudeSellOffer != null) byId.put(InfernoReturnCalculator.CRUDE_GABAGOOL_ID, record.endCrudeSellOffer);
            if (record.endVeryCrudeSellOffer != null) {
                byId.put(InfernoReturnCalculator.VERY_CRUDE_GABAGOOL_ID, record.endVeryCrudeSellOffer);
            }
            if (record.endRarePrices != null) {
                record.endRarePrices.forEach((id, price) -> {
                    if (id != null && price != null) byId.put(id, price);
                });
            }
        }
        // Whatever the saved prices don't have (days saved before the eyedrop ingredients were saved too, or an
        // item with no price then) is priced from the live Bazaar.
        boolean liveFill = false;
        if (liveBazaar != null) {
            for (String id : InfernoReturnCalculator.priceIds()) {
                if (byId.containsKey(id)) continue;
                double price = PriceCache.getSellOfferPrice(liveBazaar, id);
                if (price > 0) {
                    byId.put(id, price);
                    liveFill = true;
                }
            }
        }
        String source;
        if (fromSnapshot) {
            source = "Bazaar sell offer from the end of the day" + (liveFill ? " (live for the ones it didn't have)" : "");
        } else if (!record.isClosed()) {
            source = "Live Bazaar sell offer (the day hasn't ended)";
        } else if (record.endPricesPending) {
            source = "Live Bazaar sell offer (the end-of-day price is still to be saved)";
        } else {
            source = "Live Bazaar sell offer (no end-of-day price was saved for this day)";
        }
        InfernoReturnCalculator.Prices prices = new InfernoReturnCalculator.Prices(byId);

        double seconds = productionMs / 1000.0;
        InfernoReturnCalculator.Estimate estimate = InfernoReturnCalculator.estimate(settings,
                record.getEffectiveMinionCount(), seconds, seconds, prices);
        return new DayEstimate(estimate, settings, prices, source, record.getEffectiveFuelCost(), fromFueling,
                record.hasMixedFuelGrades(), productionMs, record.timeWarpedMs);
    }
}
