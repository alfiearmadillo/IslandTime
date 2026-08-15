package org.example.islandtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Caches Bazaar and AH lowest-BIN data for 5 minutes to avoid hammering APIs.
 */
public class PriceCache {

    private static final String BAZAAR_API = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String LOWEST_BIN_API = "https://moulberry.codes/lowestbin.json";
    private static final String LOWEST_BIN_FALLBACK = "https://lb.tricked.pro/lowestbin";
    private static final String LOWEST_BIN_FALLBACK_2 = "https://sky.coflnet.com/api/auctions/lowestBin";
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000; // 5 minutes

    private static JsonObject cachedBazaar = null;
    private static long bazaarCacheTime = 0;

    private static JsonObject cachedLowestBins = null;
    private static long lowestBinsCacheTime = 0;

    public static JsonObject getBazaar() {
        long now = System.currentTimeMillis();
        if (cachedBazaar != null && (now - bazaarCacheTime) < CACHE_DURATION_MS) {
            return cachedBazaar;
        }
        cachedBazaar = fetchBazaar();
        bazaarCacheTime = now;
        return cachedBazaar;
    }

    public static JsonObject getLowestBins() {
        long now = System.currentTimeMillis();
        if (cachedLowestBins != null && (now - lowestBinsCacheTime) < CACHE_DURATION_MS) {
            return cachedLowestBins;
        }
        cachedLowestBins = fetchLowestBins();
        lowestBinsCacheTime = now;
        return cachedLowestBins;
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

    /** AH lowest BIN price. */
    public static double getLowestBin(JsonObject lowestBins, String itemId) {
        JsonElement el = lowestBins.get(itemId);
        if (el == null || el.isJsonNull()) return -1;
        return el.getAsDouble();
    }

    // ── Formatting ─────────────────────────────────────────────────────────

    public static String formatCoins(double coins) {
        if (Math.abs(coins) >= 1_000_000) return String.format("%.1fM", coins / 1_000_000);
        if (Math.abs(coins) >= 1_000)     return String.format("%.1fk", coins / 1_000);
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

    private static JsonObject fetchLowestBins() {
        JsonObject result = fetchJson(LOWEST_BIN_API);
        if (result == null) {
            result = fetchJson(LOWEST_BIN_FALLBACK);
        }
        if (result == null) {
            result = fetchJson(LOWEST_BIN_FALLBACK_2);
        }
        return result;
    }

    private static JsonObject fetchJson(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "IslandTime-Mod");
            if (conn.getResponseCode() != 200) return null;
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Fetch failed for " + url + ": " + e.getMessage());
        }
        return null;
    }
}
