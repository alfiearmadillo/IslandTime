package org.example.islandtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;

/**
 * Compares profit from spending Booster Cookie bits on various items.
 *
 * Currently supports:
 * - Kismet Feather (1,350 bits) — sell offer price
 * - Magma Bucket (3,000 bits for 1 Heat Core) — insta-sell minus material cost
 * - Plasma Bucket (9,000 bits for 3 Heat Cores) — insta-sell minus material cost
 *
 * Cookie price is sourced from Bazaar buy-order data.
 */
public class CookieProfitCommand {

    private static final String BAZAAR_API = "https://api.hypixel.net/v2/skyblock/bazaar";

    // Bits cost per item
    private static final int KISMET_BITS = 1_350;
    private static final int HEAT_CORE_BITS = 3_000;
    private static final int MAGMA_BUCKET_HEAT_CORES = 1;   // 1 heat core per magma bucket
    private static final int PLASMA_BUCKET_HEAT_CORES = 3;  // 2 for magma buckets + 1 for plasma

    // Material counts (non-heat-core)
    private static final int MAGMA_BUCKET_LAVA_BUCKETS = 2;   // 2 enchanted lava buckets
    private static final int PLASMA_BUCKET_LAVA_BUCKETS = 4;  // 4 enchanted lava buckets (2 per magma bucket × 2)

    public static void execute(Minecraft client) {
        if (client.player == null) return;

        IslandTimeConfig config = ConfigManager.getConfig();
        int bitsPerCookie = (config != null) ? config.misc.bitsPerCookie : 4800;

        if (bitsPerCookie <= 0) {
            client.player.sendSystemMessage(
                    Component.literal("Set your bits per cookie in /islandtime under Misc > Cookie Profit.")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        client.player.sendSystemMessage(
                Component.literal("Fetching Bazaar prices...").withStyle(ChatFormatting.GRAY));

        new Thread(() -> {
            try {
                JsonObject products = fetchBazaar();
                if (products == null) {
                    sendError(client, "Failed to fetch Bazaar data.");
                    return;
                }

                // Cookie cost (buy order = what you'd pay placing a buy order)
                double cookieCost = getBuyOrderPrice(products, "BOOSTER_COOKIE");

                // Kismet Feather — sell offer price (lowest sell order = buyPrice in API)
                double kismetSellOffer = getSellOfferPrice(products, "KISMET_FEATHER");

                // Enchanted Lava Bucket — insta-buy price (for material cost)
                double lavaBucketCost = getInstaBuyPrice(products, "ENCHANTED_LAVA_BUCKET");

                // Magma Bucket — insta-sell price
                double magmaSell = getInstaSellPrice(products, "MAGMA_BUCKET");

                // Plasma Bucket — insta-sell price
                double plasmaSell = getInstaSellPrice(products, "PLASMA_BUCKET");

                // Calculate profits
                List<CookieItem> items = new ArrayList<>();

                // Kismet Feather
                if (kismetSellOffer > 0) {
                    double kismetProfit = kismetSellOffer;
                    double kismetPerBit = kismetProfit / KISMET_BITS;
                    items.add(new CookieItem("Kismet Feather", KISMET_BITS, kismetProfit, kismetPerBit, null));
                } else {
                    items.add(new CookieItem("Kismet Feather", KISMET_BITS, 0, 0, "price unavailable"));
                }

                // Magma Bucket
                if (magmaSell > 0 && lavaBucketCost > 0) {
                    double magmaMaterialCost = MAGMA_BUCKET_LAVA_BUCKETS * lavaBucketCost;
                    double magmaProfit = magmaSell - magmaMaterialCost;
                    int magmaBits = MAGMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS;
                    double magmaPerBit = magmaProfit / magmaBits;
                    items.add(new CookieItem("Magma Bucket", magmaBits, magmaProfit, magmaPerBit, null));
                } else {
                    items.add(new CookieItem("Magma Bucket", MAGMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS, 0, 0, "price unavailable"));
                }

                // Plasma Bucket
                if (plasmaSell > 0 && lavaBucketCost > 0) {
                    double plasmaMaterialCost = PLASMA_BUCKET_LAVA_BUCKETS * lavaBucketCost;
                    double plasmaProfit = plasmaSell - plasmaMaterialCost;
                    int plasmaBits = PLASMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS;
                    double plasmaPerBit = plasmaProfit / plasmaBits;
                    items.add(new CookieItem("Plasma Bucket", plasmaBits, plasmaProfit, plasmaPerBit, null));
                } else {
                    items.add(new CookieItem("Plasma Bucket", PLASMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS, 0, 0, "price unavailable"));
                }

                // Sort by profit per bit descending
                items.sort((a, b) -> Double.compare(b.profitPerBit, a.profitPerBit));

                // Display
                client.execute(() -> {
                    client.player.sendSystemMessage(
                            Component.literal("═══ Cookie Profit Calculator ═══").withStyle(ChatFormatting.GOLD));
                    client.player.sendSystemMessage(
                            Component.literal("Bits/cookie: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.valueOf(bitsPerCookie)).withStyle(ChatFormatting.AQUA))
                                    .append(Component.literal("  Cookie cost: ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal(cookieCost > 0 ? formatCoins(cookieCost) : "N/A").withStyle(ChatFormatting.WHITE)));
                    client.player.sendSystemMessage(Component.literal(""));

                    for (CookieItem item : items) {
                        if (item.error != null) {
                            client.player.sendSystemMessage(
                                    Component.literal(item.name).withStyle(ChatFormatting.YELLOW)
                                            .append(Component.literal(" — " + item.error).withStyle(ChatFormatting.RED)));
                            continue;
                        }

                        double profitPerCookie = item.profitPerBit * bitsPerCookie;
                        double netPerCookie = (cookieCost > 0) ? profitPerCookie - cookieCost : profitPerCookie;
                        ChatFormatting profitColor = (item.profitPerBit > 0) ? ChatFormatting.GREEN : ChatFormatting.RED;
                        ChatFormatting netColor = (netPerCookie > 0) ? ChatFormatting.GREEN : ChatFormatting.RED;

                        client.player.sendSystemMessage(
                                Component.literal(item.name).withStyle(ChatFormatting.YELLOW)
                                        .append(Component.literal(" (" + item.bitsCost + " bits)").withStyle(ChatFormatting.GRAY)));
                        client.player.sendSystemMessage(
                                Component.literal("  Profit: ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(formatCoins(item.profit)).withStyle(profitColor))
                                        .append(Component.literal("  Per bit: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(formatCoins(item.profitPerBit)).withStyle(profitColor)));
                        client.player.sendSystemMessage(
                                Component.literal("  Per cookie: ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(formatCoins(profitPerCookie)).withStyle(profitColor))
                                        .append(Component.literal("  Net (minus cookie): ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(cookieCost > 0 ? formatCoins(netPerCookie) : "N/A").withStyle(netColor)));
                        client.player.sendSystemMessage(Component.literal(""));
                    }

                    // Best option summary
                    if (!items.isEmpty() && items.get(0).error == null) {
                        CookieItem best = items.get(0);
                        double bestPerCookie = best.profitPerBit * bitsPerCookie;
                        double bestNet = (cookieCost > 0) ? bestPerCookie - cookieCost : bestPerCookie;
                        ChatFormatting bestColor = bestNet > 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
                        client.player.sendSystemMessage(
                                Component.literal("Best: ").withStyle(ChatFormatting.GOLD)
                                        .append(Component.literal(best.name).withStyle(ChatFormatting.YELLOW))
                                        .append(Component.literal(" at ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(formatCoins(best.profitPerBit) + "/bit").withStyle(bestColor))
                                        .append(Component.literal(cookieCost > 0 ? " (" + formatCoins(bestNet) + " net/cookie)" : "").withStyle(bestColor)));
                    }
                });

            } catch (Exception e) {
                sendError(client, "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }, "IslandTime-CookieProfit").start();
    }

    // ── Bazaar helpers ──────────────────────────────────────────────────────

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

    /** Insta-buy price (lowest sell order — what you'd pay to buy instantly). */
    private static double getInstaBuyPrice(JsonObject products, String productId) {
        JsonObject product = products.getAsJsonObject(productId);
        if (product == null) return -1;
        JsonObject qs = product.getAsJsonObject("quick_status");
        if (qs == null) return -1;
        JsonElement el = qs.get("buyPrice");
        return el != null && !el.isJsonNull() ? el.getAsDouble() : -1;
    }

    /** Insta-sell price (highest buy order — what you'd get selling instantly). */
    private static double getInstaSellPrice(JsonObject products, String productId) {
        JsonObject product = products.getAsJsonObject(productId);
        if (product == null) return -1;
        JsonObject qs = product.getAsJsonObject("quick_status");
        if (qs == null) return -1;
        JsonElement el = qs.get("sellPrice");
        return el != null && !el.isJsonNull() ? el.getAsDouble() : -1;
    }

    /** Sell offer price (lowest sell order — what you'd list a sell order at). Same as insta-buy price. */
    private static double getSellOfferPrice(JsonObject products, String productId) {
        return getInstaBuyPrice(products, productId);
    }

    /** Buy order price (highest buy order — what you'd place a buy order at). Same as insta-sell price. */
    private static double getBuyOrderPrice(JsonObject products, String productId) {
        return getInstaSellPrice(products, productId);
    }

    // ── formatting ──────────────────────────────────────────────────────────

    private static String formatCoins(double coins) {
        if (Math.abs(coins) >= 1_000_000) return String.format("%.1fM", coins / 1_000_000);
        if (Math.abs(coins) >= 1_000)     return String.format("%.1fk", coins / 1_000);
        return String.format("%.1f", coins);
    }

    private static void sendError(Minecraft client, String msg) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(
                        Component.literal("[CookieProfit] " + msg).withStyle(ChatFormatting.RED));
            }
        });
    }

    // ── result holder ───────────────────────────────────────────────────────

    private static class CookieItem {
        final String name;
        final int bitsCost;
        final double profit;
        final double profitPerBit;
        final String error;

        CookieItem(String name, int bitsCost, double profit, double profitPerBit, String error) {
            this.name = name;
            this.bitsCost = bitsCost;
            this.profit = profit;
            this.profitPerBit = profitPerBit;
            this.error = error;
        }
    }
}
