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
 * Fetches Bazaar + Auction House prices for aging / aged item pairs
 * and displays profit-per-hour comparisons in chat.
 *
 * Aging items take 300 hours to complete.
 * Items in the Time Pocket age at 2× speed (150 real hours).
 *
 * Buy prices come from AH lowest BIN (moulberry API) or Bazaar.
 * Sell prices come from Bazaar (insta-sell or sell-offer, toggleable).
 */
public class AgingItemsCommand {

    private static final String BAZAAR_API = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String LOWEST_BIN_API = "https://moulberry.codes/lowestbin.json";
    private static final double AGING_HOURS = 300.0;
    private static final double TIME_POCKET_MULTIPLIER = 2.0;

    public static void execute(Minecraft client) {
        if (client.player == null) return;

        List<String[]> pairs = AgingItemsConfig.getItemPairs();
        if (pairs.isEmpty()) {
            client.player.sendSystemMessage(
                    Component.literal("No aging item pairs configured. Use /it agingItems add <agingId> <agedId> to add one.")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        int totalSlots = AgingItemsConfig.getTotalSlots();
        int timeBagSlots = AgingItemsConfig.getTimeBagSlots();
        int inventorySlots = AgingItemsConfig.getInventorySlots();

        IslandTimeConfig config = ConfigManager.getConfig();
        boolean useSellOffer = (config != null) && config.misc.agingUseSellOffer;

        client.player.sendSystemMessage(
                Component.literal("Fetching prices...").withStyle(ChatFormatting.GRAY));

        new Thread(() -> {
            try {
                JsonObject bazaarProducts = fetchBazaar();
                JsonObject lowestBins = fetchLowestBins();

                if (bazaarProducts == null) {
                    sendError(client, "Failed to fetch Bazaar data.");
                    return;
                }

                String sellLabel = useSellOffer ? "Sell Offer" : "Insta Sell";
                List<ItemResult> results = new ArrayList<>();

                for (String[] pair : pairs) {
                    String agingId = pair[0];
                    String agedId  = pair[1];

                    // Buy price: try BZ first, then AH lowest BIN
                    double buyPrice = getInstaBuyPrice(bazaarProducts, agingId);
                    boolean buyFromAH = false;
                    if (buyPrice < 0 && lowestBins != null) {
                        buyPrice = getLowestBin(lowestBins, agingId);
                        buyFromAH = (buyPrice > 0);
                    }

                    // Sell price: BZ insta-sell or sell-offer
                    double sellPrice;
                    if (useSellOffer) {
                        sellPrice = getSellOfferPrice(bazaarProducts, agedId);
                    } else {
                        sellPrice = getInstaSellPrice(bazaarProducts, agedId);
                    }

                    boolean buyUnavailable = (buyPrice <= 0);
                    boolean sellUnavailable = (sellPrice <= 0);

                    if (sellUnavailable && buyUnavailable) {
                        results.add(new ItemResult(agingId, agedId, -1, -1,
                                Double.NaN, Double.NaN, false, "N/A", sellLabel));
                        continue;
                    }
                    if (sellUnavailable) {
                        results.add(new ItemResult(agingId, agedId, buyPrice, -1,
                                Double.NaN, Double.NaN, buyFromAH, "aged item not on Bazaar", sellLabel));
                        continue;
                    }
                    if (buyUnavailable) {
                        // Can't find buy price anywhere — show sell only
                        results.add(new ItemResult(agingId, agedId, -1, sellPrice,
                                Double.NaN, Double.NaN, false, "buy price N/A (0 on AH)", sellLabel));
                        continue;
                    }

                    // Full profit calc
                    double profit = sellPrice - buyPrice;
                    double timeBagHours = AGING_HOURS / TIME_POCKET_MULTIPLIER;
                    double profitPerHourTimeBag = profit / timeBagHours;
                    double profitPerHourInventory = profit / AGING_HOURS;

                    results.add(new ItemResult(agingId, agedId, buyPrice, sellPrice,
                            profitPerHourTimeBag, profitPerHourInventory, buyFromAH, null, sellLabel));
                }

                // Sort by time-bag profit/hr descending, NaN at bottom
                results.sort((a, b) -> {
                    boolean aHas = !Double.isNaN(a.profitPerHourTimeBag);
                    boolean bHas = !Double.isNaN(b.profitPerHourTimeBag);
                    if (aHas && bHas) return Double.compare(b.profitPerHourTimeBag, a.profitPerHourTimeBag);
                    if (aHas) return -1;
                    if (bHas) return 1;
                    return Double.compare(b.sellPrice, a.sellPrice);
                });

                // Display
                client.execute(() -> {
                    client.player.sendSystemMessage(
                            Component.literal("═══ Aging Items Profit/Hour ═══").withStyle(ChatFormatting.GOLD));
                    client.player.sendSystemMessage(
                            Component.literal("Slots: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(timeBagSlots + " time-bag").withStyle(ChatFormatting.AQUA))
                                    .append(Component.literal(" + ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal(inventorySlots + " inventory").withStyle(ChatFormatting.AQUA))
                                    .append(Component.literal(" = " + totalSlots + " total").withStyle(ChatFormatting.WHITE))
                                    .append(Component.literal("  Sell mode: ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal(sellLabel).withStyle(ChatFormatting.AQUA)));
                    if (lowestBins == null) {
                        client.player.sendSystemMessage(
                                Component.literal("  (AH data unavailable — AH-only items may show N/A)").withStyle(ChatFormatting.RED));
                    }
                    client.player.sendSystemMessage(Component.literal(""));

                    for (ItemResult r : results) {
                        client.player.sendSystemMessage(
                                Component.literal(formatId(r.agingId)).withStyle(ChatFormatting.YELLOW)
                                        .append(Component.literal(" → ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(formatId(r.agedId)).withStyle(ChatFormatting.YELLOW)));

                        if (r.error != null) {
                            client.player.sendSystemMessage(
                                    Component.literal("  " + r.error).withStyle(ChatFormatting.RED));
                            client.player.sendSystemMessage(Component.literal(""));
                            continue;
                        }

                        String buyLabel = r.buyFromAH ? "Buy (AH BIN)" : "Buy (Insta Buy)";
                        ChatFormatting profitColor = (r.profitPerHourTimeBag > 0) ? ChatFormatting.GREEN : ChatFormatting.RED;
                        double profit = r.sellPrice - r.buyPrice;

                        client.player.sendSystemMessage(
                                Component.literal("  " + buyLabel + ": ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(formatCoins(r.buyPrice)).withStyle(ChatFormatting.WHITE))
                                        .append(Component.literal("  Sell (" + r.sellLabel + "): ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(formatCoins(r.sellPrice)).withStyle(ChatFormatting.WHITE))
                                        .append(Component.literal("  Profit: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(formatCoins(profit)).withStyle(profitColor)));
                        client.player.sendSystemMessage(
                                Component.literal("  Time Bag: ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(formatCoins(r.profitPerHourTimeBag) + "/hr").withStyle(profitColor))
                                        .append(Component.literal("  Inventory: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(formatCoins(r.profitPerHourInventory) + "/hr").withStyle(profitColor)));
                        client.player.sendSystemMessage(Component.literal(""));
                    }

                    // Estimated hourly income
                    if (totalSlots > 0) {
                        List<ItemResult> valid = new ArrayList<>();
                        for (ItemResult r : results) {
                            if (r.error == null && !Double.isNaN(r.profitPerHourTimeBag)) valid.add(r);
                        }
                        if (!valid.isEmpty()) {
                            double totalPerHour = 0;
                            int tbAssigned = 0, invAssigned = 0;
                            for (ItemResult r : valid) {
                                if (tbAssigned < timeBagSlots) {
                                    totalPerHour += r.profitPerHourTimeBag;
                                    tbAssigned++;
                                } else if (invAssigned < inventorySlots) {
                                    totalPerHour += r.profitPerHourInventory;
                                    invAssigned++;
                                } else break;
                            }
                            ChatFormatting totalColor = totalPerHour > 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
                            client.player.sendSystemMessage(
                                    Component.literal("Est. total income: ").withStyle(ChatFormatting.GOLD)
                                            .append(Component.literal(formatCoins(totalPerHour) + "/hr").withStyle(totalColor))
                                            .append(Component.literal(" (best " + (tbAssigned + invAssigned) + " items)").withStyle(ChatFormatting.GRAY)));
                        }
                    }
                });

            } catch (Exception e) {
                sendError(client, "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }, "IslandTime-AgingItems").start();
    }

    // ── API helpers ─────────────────────────────────────────────────────────

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
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(LOWEST_BIN_API).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "IslandTime-Mod");
            if (conn.getResponseCode() != 200) return null;
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Lowest BIN fetch failed: " + e.getMessage());
        }
        return null;
    }

    private static double getInstaBuyPrice(JsonObject products, String productId) {
        JsonObject product = products.getAsJsonObject(productId);
        if (product == null) return -1;
        JsonObject qs = product.getAsJsonObject("quick_status");
        if (qs == null) return -1;
        JsonElement el = qs.get("buyPrice");
        return el != null && !el.isJsonNull() ? el.getAsDouble() : -1;
    }

    private static double getInstaSellPrice(JsonObject products, String productId) {
        JsonObject product = products.getAsJsonObject(productId);
        if (product == null) return -1;
        JsonObject qs = product.getAsJsonObject("quick_status");
        if (qs == null) return -1;
        JsonElement el = qs.get("sellPrice");
        return el != null && !el.isJsonNull() ? el.getAsDouble() : -1;
    }

    /** Sell offer price (lowest sell order = what you'd list at). Same as BZ buyPrice. */
    private static double getSellOfferPrice(JsonObject products, String productId) {
        return getInstaBuyPrice(products, productId);
    }

    private static double getLowestBin(JsonObject lowestBins, String itemId) {
        JsonElement el = lowestBins.get(itemId);
        if (el == null || el.isJsonNull()) return -1;
        return el.getAsDouble();
    }

    // ── formatting ──────────────────────────────────────────────────────────

    private static String formatCoins(double coins) {
        if (Math.abs(coins) >= 1_000_000) return String.format("%.1fM", coins / 1_000_000);
        if (Math.abs(coins) >= 1_000)     return String.format("%.1fk", coins / 1_000);
        return String.format("%.1f", coins);
    }

    private static String formatId(String id) {
        String[] parts = id.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static void sendError(Minecraft client, String msg) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(
                        Component.literal("[AgingItems] " + msg).withStyle(ChatFormatting.RED));
            }
        });
    }

    // ── result holder ───────────────────────────────────────────────────────

    private static class ItemResult {
        final String agingId, agedId;
        final double buyPrice, sellPrice;
        final double profitPerHourTimeBag, profitPerHourInventory;
        final boolean buyFromAH;
        final String error;
        final String sellLabel;

        ItemResult(String agingId, String agedId, double buyPrice, double sellPrice,
                   double profitPerHourTimeBag, double profitPerHourInventory,
                   boolean buyFromAH, String error, String sellLabel) {
            this.agingId = agingId;
            this.agedId = agedId;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
            this.profitPerHourTimeBag = profitPerHourTimeBag;
            this.profitPerHourInventory = profitPerHourInventory;
            this.buyFromAH = buyFromAH;
            this.error = error;
            this.sellLabel = sellLabel;
        }
    }
}
