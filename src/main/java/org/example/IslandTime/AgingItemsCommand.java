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
 * Fetches Bazaar prices for aging / aged item pairs and displays
 * profit-per-hour comparisons in chat.
 *
 * Aging items take 300 hours to complete (gaining stats each hour).
 * Items in the Time Pocket age at 2× speed (150 real hours).
 */
public class AgingItemsCommand {

    private static final String BAZAAR_API = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final double AGING_HOURS = 300.0;
    private static final double TIME_POCKET_MULTIPLIER = 2.0;

    /**
     * Run the comparison and print results to chat.
     * Called from the command thread — does HTTP on a background thread.
     */
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

        client.player.sendSystemMessage(
                Component.literal("Fetching Bazaar prices...").withStyle(ChatFormatting.GRAY));

        // Network on a background thread
        new Thread(() -> {
            try {
                JsonObject bazaarProducts = fetchBazaar();
                if (bazaarProducts == null) {
                    sendError(client, "Failed to fetch Bazaar data.");
                    return;
                }

                List<ItemResult> results = new ArrayList<>();

                for (String[] pair : pairs) {
                    String agingId = pair[0];
                    String agedId  = pair[1];

                    double buyPrice  = getInstaBuyPrice(bazaarProducts, agingId);
                    double sellPrice = getInstaSellPrice(bazaarProducts, agedId);

                    if (buyPrice < 0 || sellPrice < 0) {
                        results.add(new ItemResult(agingId, agedId, buyPrice, sellPrice,
                                Double.NaN, Double.NaN, "not on Bazaar"));
                        continue;
                    }

                    double profit = sellPrice - buyPrice;
                    // Time Pocket items age at 2x speed (150 real hours), inventory items at 1x (300 hours)
                    double timeBagHours = AGING_HOURS / TIME_POCKET_MULTIPLIER;
                    double inventoryHours = AGING_HOURS;
                    double profitPerHourTimeBag = profit / timeBagHours;
                    double profitPerHourInventory = profit / inventoryHours;

                    results.add(new ItemResult(agingId, agedId, buyPrice, sellPrice,
                            profitPerHourTimeBag, profitPerHourInventory, null));
                }

                // Sort by time-bag profit/hr descending (NaN at bottom)
                results.sort((a, b) -> {
                    if (Double.isNaN(a.profitPerHourTimeBag) && Double.isNaN(b.profitPerHourTimeBag)) return 0;
                    if (Double.isNaN(a.profitPerHourTimeBag)) return 1;
                    if (Double.isNaN(b.profitPerHourTimeBag)) return -1;
                    return Double.compare(b.profitPerHourTimeBag, a.profitPerHourTimeBag);
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
                                    .append(Component.literal(" = " + totalSlots + " total").withStyle(ChatFormatting.WHITE)));
                    client.player.sendSystemMessage(Component.literal(""));

                    for (ItemResult r : results) {
                        if (r.error != null) {
                            client.player.sendSystemMessage(
                                    Component.literal(formatId(r.agingId)).withStyle(ChatFormatting.YELLOW)
                                            .append(Component.literal(" → ").withStyle(ChatFormatting.GRAY))
                                            .append(Component.literal(formatId(r.agedId)).withStyle(ChatFormatting.YELLOW))
                                            .append(Component.literal(" — " + r.error).withStyle(ChatFormatting.RED)));
                            continue;
                        }

                        ChatFormatting profitColor = (r.profitPerHourTimeBag > 0) ? ChatFormatting.GREEN : ChatFormatting.RED;

                        client.player.sendSystemMessage(
                                Component.literal(formatId(r.agingId)).withStyle(ChatFormatting.YELLOW)
                                        .append(Component.literal(" → ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(formatId(r.agedId)).withStyle(ChatFormatting.YELLOW)));
                        client.player.sendSystemMessage(
                                Component.literal("  Buy: ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(formatCoins(r.buyPrice)).withStyle(ChatFormatting.WHITE))
                                        .append(Component.literal("  Sell: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(formatCoins(r.sellPrice)).withStyle(ChatFormatting.WHITE))
                                        .append(Component.literal("  Profit: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(formatCoins(r.sellPrice - r.buyPrice)).withStyle(profitColor)));
                        client.player.sendSystemMessage(
                                Component.literal("  Time Bag: ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(formatCoins(r.profitPerHourTimeBag) + "/hr").withStyle(profitColor))
                                        .append(Component.literal("  Inventory: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(formatCoins(r.profitPerHourInventory) + "/hr").withStyle(profitColor)));
                        client.player.sendSystemMessage(Component.literal(""));
                    }

                    // Show estimated hourly income with configured slots
                    if (totalSlots > 0 && !results.isEmpty()) {
                        // Assign best items to time-bag slots first, then inventory
                        List<ItemResult> valid = new ArrayList<>();
                        for (ItemResult r : results) {
                            if (r.error == null && !Double.isNaN(r.profitPerHourTimeBag)) valid.add(r);
                        }
                        if (!valid.isEmpty()) {
                            double totalPerHour = 0;
                            int tbAssigned = 0;
                            int invAssigned = 0;
                            for (ItemResult r : valid) {
                                if (tbAssigned < timeBagSlots) {
                                    totalPerHour += r.profitPerHourTimeBag;
                                    tbAssigned++;
                                } else if (invAssigned < inventorySlots) {
                                    totalPerHour += r.profitPerHourInventory;
                                    invAssigned++;
                                } else {
                                    break;
                                }
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

    /** Get the instant-buy price (lowest sell order) for a product. */
    private static double getInstaBuyPrice(JsonObject products, String productId) {
        JsonObject product = products.getAsJsonObject(productId);
        if (product == null) return -1;
        JsonObject quickStatus = product.getAsJsonObject("quick_status");
        if (quickStatus == null) return -1;
        JsonElement el = quickStatus.get("buyPrice");
        return el != null && !el.isJsonNull() ? el.getAsDouble() : -1;
    }

    /** Get the instant-sell price (highest buy order) for a product. */
    private static double getInstaSellPrice(JsonObject products, String productId) {
        JsonObject product = products.getAsJsonObject(productId);
        if (product == null) return -1;
        JsonObject quickStatus = product.getAsJsonObject("quick_status");
        if (quickStatus == null) return -1;
        JsonElement el = quickStatus.get("sellPrice");
        return el != null && !el.isJsonNull() ? el.getAsDouble() : -1;
    }

    // ── formatting ──────────────────────────────────────────────────────────

    private static String formatCoins(double coins) {
        if (coins >= 1_000_000) return String.format("%.1fM", coins / 1_000_000);
        if (coins >= 1_000)     return String.format("%.1fk", coins / 1_000);
        return String.format("%.1f", coins);
    }

    /** BOTTLE_OF_JYRRE → Bottle Of Jyrre */
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
        final String error;

        ItemResult(String agingId, String agedId, double buyPrice, double sellPrice,
                   double profitPerHourTimeBag, double profitPerHourInventory, String error) {
            this.agingId = agingId;
            this.agedId = agedId;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
            this.profitPerHourTimeBag = profitPerHourTimeBag;
            this.profitPerHourInventory = profitPerHourInventory;
            this.error = error;
        }
    }
}
