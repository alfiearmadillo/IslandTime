package org.example.islandtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches Bazaar and AH lowest-BIN data for 5 minutes to avoid hammering APIs.
 */
public class PriceCache {

    private static final String BAZAAR_API = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String COFLNET_ITEM_API = "https://sky.coflnet.com/api/item/price/%s/bin";
    private static final String COFLNET_ACTIVE_BIN_API = "https://sky.coflnet.com/api/auctions/tag/%s/active/bin";
    private static final String COFLNET_SOLD_API = "https://sky.coflnet.com/api/auctions/tag/%s/sold";
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000; // 5 minutes

    private static JsonObject cachedBazaar = null;
    private static long bazaarCacheTime = 0;

    // Per-item cache for AH lowest BIN prices
    private static final Map<String, CachedPrice> lowestBinCache = new ConcurrentHashMap<>();

    /**
     * The 5 Epic-tier Inferno Minion Fuel distillates (used to craft the medium
     * Epic fuel variants). Only these are relevant for the "cheapest distillate"
     * fuel-cost strategy — the other Inferno Minion Fuel tiers are ignored.
     */
    public static final String[] INFERNO_FUEL_DISTILLATE_IDS = {
            "BLAZE_ROD_DISTILLATE",
            "GLOWSTONE_DUST_DISTILLATE",
            "MAGMA_CREAM_DISTILLATE",
            "NETHER_STALK_DISTILLATE",
            "CRUDE_GABAGOOL_DISTILLATE"
    };

    /**
     * Returns the insta-buy price of the cheapest of the 5 Epic Inferno Minion Fuel
     * distillates, or -1 if the bazaar data is unavailable or none of the products exist.
     */
    public static double getCheapestInfernoFuelDistillatePrice(JsonObject products) {
        if (products == null) return -1;
        double cheapest = -1;
        for (String id : INFERNO_FUEL_DISTILLATE_IDS) {
            double price = getInstaBuyPrice(products, id);
            if (price > 0 && (cheapest < 0 || price < cheapest)) {
                cheapest = price;
            }
        }
        return cheapest;
    }

    public static JsonObject getBazaar() {
        long now = System.currentTimeMillis();
        if (cachedBazaar != null && (now - bazaarCacheTime) < CACHE_DURATION_MS) {
            return cachedBazaar;
        }
        cachedBazaar = fetchBazaar();
        bazaarCacheTime = now;
        return cachedBazaar;
    }

    // ── Price extraction helpers ────────────────────────────────────────────

    /** Insta-buy price (lowest sell order — what you'd pay to buy instantly). */
    public static double getInstaBuyPrice(JsonObject products, String productId) {
        JsonObject product = products.getAsJsonObject(productId);
        if (product == null) return -1;
        JsonObject qs = product.getAsJsonObject("quick_status");
        if (qs == null) return -1;
        JsonElement el = qs.get("buyPrice");
        return el != null && !el.isJsonNull() ? el.getAsDouble() : -1;
    }

    /** Insta-sell price (highest buy order — what you'd get selling instantly). */
    public static double getInstaSellPrice(JsonObject products, String productId) {
        JsonObject product = products.getAsJsonObject(productId);
        if (product == null) return -1;
        JsonObject qs = product.getAsJsonObject("quick_status");
        if (qs == null) return -1;
        JsonElement el = qs.get("sellPrice");
        return el != null && !el.isJsonNull() ? el.getAsDouble() : -1;
    }

    /** Sell offer price (lowest sell order = what you'd list at). Same as BZ buyPrice. */
    public static double getSellOfferPrice(JsonObject products, String productId) {
        return getInstaBuyPrice(products, productId);
    }

    /** Buy order price (highest buy order = what you'd place a buy order at). Same as insta-sell price. */
    public static double getBuyOrderPrice(JsonObject products, String productId) {
        return getInstaSellPrice(products, productId);
    }

    /** AH lowest BIN price via Coflnet per-item API (cached 5 min). */
    public static double getLowestBin(JsonObject unusedBulkData, String itemId) {
        long now = System.currentTimeMillis();
        CachedPrice cached = lowestBinCache.get(itemId);
        if (cached != null && (now - cached.time) < CACHE_DURATION_MS) {
            return cached.price;
        }
        double price = fetchCoflnetPrice(itemId);
        lowestBinCache.put(itemId, new CachedPrice(price, now));
        return price;
    }

    // ── Formatting ─────────────────────────────────────────────────────────

    public static String formatCoins(double coins) {
        if (Math.abs(coins) >= 1_000_000_000) return String.format("%.3fB", coins / 1_000_000_000);
        if (Math.abs(coins) >= 1_000_000)     return String.format("%.1fM", coins / 1_000_000);
        if (Math.abs(coins) >= 1_000)         return String.format("%.1fk", coins / 1_000);
        return String.format("%.1f", coins);
    }

    /** Higher precision format for small per-unit values where differences matter. */
    public static String formatCoinsPrecise(double coins) {
        if (Math.abs(coins) >= 1_000_000) return String.format("%.2fM", coins / 1_000_000);
        if (Math.abs(coins) >= 1_000)     return String.format("%.2fk", coins / 1_000);
        return String.format("%.1f", coins);
    }

    // ── Fetch helpers ──────────────────────────────────────────────────────

    private static JsonObject fetchBazaar() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(BAZAAR_API).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "IslandTime-Mod");
            if (conn.getResponseCode() != 200) return null;
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.has("products")) return root.getAsJsonObject("products");
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Bazaar fetch failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Fetches the lowest BIN price for a single item from Coflnet.
     * Tries the per-item price endpoint first, falls back to active BIN listing.
     */
    private static double fetchCoflnetPrice(String itemId) {
        // Try the simple per-item price endpoint first
        double price = fetchCoflnetItemPrice(itemId);
        if (price > 0) return price;
        // Fall back to active BIN listings
        price = fetchCoflnetActiveBin(itemId);
        if (price > 0) return price;
        // Fall back to recent sold auctions (for items with no active BINs)
        return fetchCoflnetSold(itemId);
    }

    /** Coflnet /api/item/price/{tag}/bin — returns a simple lowest BIN price. */
    private static double fetchCoflnetItemPrice(String itemId) {
        try {
            String url = String.format(COFLNET_ITEM_API, itemId);
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "IslandTime-Mod");
            if (conn.getResponseCode() != 200) return -1;
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                JsonElement parsed = JsonParser.parseReader(reader);
                // Could be a number, object with "lowest", or other format
                if (parsed.isJsonPrimitive()) {
                    return parsed.getAsDouble();
                }
                if (parsed.isJsonObject()) {
                    JsonObject obj = parsed.getAsJsonObject();
                    if (obj.has("lowest")) return obj.get("lowest").getAsDouble();
                    if (obj.has("price")) return obj.get("price").getAsDouble();
                }
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Coflnet item price failed for " + itemId + ": " + e.getMessage());
        }
        return -1;
    }

    /** Coflnet /api/auctions/tag/{tag}/active/bin — returns sorted BIN listings. */
    private static double fetchCoflnetActiveBin(String itemId) {
        try {
            String url = String.format(COFLNET_ACTIVE_BIN_API, itemId);
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "IslandTime-Mod");
            if (conn.getResponseCode() != 200) return -1;
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonArray()) return -1;
                JsonArray arr = parsed.getAsJsonArray();
                if (arr.isEmpty()) return -1;
                // Results are sorted by price (lowest first) — take first entry
                JsonObject first = arr.get(0).getAsJsonObject();
                // Try multiple possible field names
                for (String field : new String[]{"price", "startingBid", "starting_bid"}) {
                    if (first.has(field)) {
                        double p = first.get(field).getAsDouble();
                        if (p > 0) return p;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Coflnet active BIN failed for " + itemId + ": " + e.getMessage());
        }
        return -1;
    }

    /** Coflnet /api/auctions/tag/{tag}/sold — recent sold auctions as price fallback. */
    private static double fetchCoflnetSold(String itemId) {
        try {
            String url = String.format(COFLNET_SOLD_API, itemId);
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "IslandTime-Mod");
            if (conn.getResponseCode() != 200) return -1;
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonArray()) return -1;
                JsonArray arr = parsed.getAsJsonArray();
                if (arr.isEmpty()) return -1;
                // Collect prices from recent sales and return the median
                List<Double> prices = new ArrayList<>();
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject sale = el.getAsJsonObject();
                    for (String field : new String[]{"price", "highestBid", "highest_bid", "startingBid", "starting_bid"}) {
                        if (sale.has(field)) {
                            double p = sale.get(field).getAsDouble();
                            if (p > 0) { prices.add(p); break; }
                        }
                    }
                }
                if (prices.isEmpty()) return -1;
                Collections.sort(prices);
                return prices.get(prices.size() / 2); // median
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Coflnet sold auctions failed for " + itemId + ": " + e.getMessage());
        }
        return -1;
    }

    // ── Cache entry ────────────────────────────────────────────────────────

    private static class CachedPrice {
        final double price;
        final long time;
        CachedPrice(double price, long time) {
            this.price = price;
            this.time = time;
        }
    }
}
