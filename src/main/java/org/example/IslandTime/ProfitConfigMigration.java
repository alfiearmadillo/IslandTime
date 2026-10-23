package org.example.islandtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The profit calculators' settings used to live in Misc (aging items, cookie profit, Huntrap profit) and Fuel Cycles
 * (the Inferno estimate). They are now in the Profit Calculators category under the same field names, so an existing
 * config.json is carried over by copying those keys before it is loaded.
 */
final class ProfitConfigMigration {
    static final String CATEGORY = "profitCalculators";

    private static final String[] FROM_MISC = {
            "agingTimeBagSlots", "agingInventorySlots", "agingUseSellOffer", "agingTopCount",
            "bitsPerCookie", "includeBitsTalisman", "infernoFuelBlazeSlayerPerk", "cookieTopCount",
            "kismetSellMode", "magmaBucketSellMode", "plasmaBucketSellMode", "ultimateCarrotCandySellMode",
            "xlStorageSellMode", "xxlStorageSellMode", "bitbugShardSellMode", "infernoFuelSellMode",
            "enchantmentSellMode",
            "huntrapType", "huntrapHunterFortune", "huntrapDesertTemple", "huntrapForestTrapLevel",
            "huntrapCombatTrapLevel", "huntrapUseSellOffer", "huntrapTopCount"
    };

    private static final String[] FROM_FUEL_CYCLES = {
            "estimateMinionTier", "estimateFuelGrade", "estimateEyedrops", "estimateFishPriceString",
            "estimateHydraHeadPriceString", "estimateFlycatcher", "estimateSecondFlycatcher", "estimateBeacon",
            "estimatePostcard", "estimateInfusion", "estimateFreeWill", "estimateSuperCompactor"
    };

    private ProfitConfigMigration() {
    }

    /**
     * Copies the old settings into a new "profitCalculators" object of {@code root}. A config that already has one
     * is left alone, so this only ever runs once per file.
     *
     * @return whether anything was copied
     */
    static boolean apply(JsonObject root) {
        if (root == null || root.has(CATEGORY)) return false;

        JsonObject moved = new JsonObject();
        copy(root.get("misc"), FROM_MISC, moved);
        copy(root.get("fuelCycles"), FROM_FUEL_CYCLES, moved);
        if (moved.size() == 0) return false;

        root.add(CATEGORY, moved);
        return true;
    }

    private static void copy(JsonElement from, String[] names, JsonObject to) {
        if (from == null || !from.isJsonObject()) return;
        JsonObject source = from.getAsJsonObject();
        for (String name : names) {
            JsonElement value = source.get(name);
            if (value != null && !value.isJsonNull()) to.add(name, value);
        }
    }
}
