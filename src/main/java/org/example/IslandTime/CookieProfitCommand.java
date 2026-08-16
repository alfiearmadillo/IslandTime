package org.example.islandtime;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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

    // Bits cost per item
    private static final int KISMET_BITS = 1_350;
    private static final int HEAT_CORE_BITS = 3_000;
    private static final int MAGMA_BUCKET_HEAT_CORES = 1;
    private static final int PLASMA_BUCKET_HEAT_CORES = 3;

    // Material counts (non-heat-core)
    private static final int MAGMA_BUCKET_LAVA_BUCKETS = 2;
    private static final int PLASMA_BUCKET_LAVA_BUCKETS = 4;

    // Wiki: 5.2083% of bits per cookie every 30 min (250 * multiplier).
    // Takes ~10 hours of idle playtime to earn all bits without Bits Talisman.
    private static final double BASE_HOURS_TO_EARN_ALL_BITS = 10.0;

    // Bits Talisman: 10% chance to earn double per tick, draining the pool
    // faster. Same total bits, earned in ~9.09 hours instead of 10.
    private static final double BITS_TALISMAN_SPEED_MULTIPLIER = 1.1;

    public static void execute(Minecraft client) {
        if (client.player == null) return;

        IslandTimeConfig config = ConfigManager.getConfig();
        int baseBitsPerCookie = (config != null) ? config.misc.bitsPerCookie : 4800;
        boolean includeBitsTalisman = (config != null) && config.misc.includeBitsTalisman;

        // Bits Talisman doesn't create extra bits — same total, earned faster
        double hoursToEarn = BASE_HOURS_TO_EARN_ALL_BITS;
        if (includeBitsTalisman) {
            hoursToEarn = BASE_HOURS_TO_EARN_ALL_BITS / BITS_TALISMAN_SPEED_MULTIPLIER;
        }

        final double bitsPerHour = baseBitsPerCookie / hoursToEarn;

        if (baseBitsPerCookie <= 0) {
            client.player.sendSystemMessage(
                    Component.literal("Set your bits per cookie in /islandtime under Misc > Cookie Profit.")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        final double bitsPerCookie = baseBitsPerCookie;
        final double finalHoursToEarn = hoursToEarn;

        client.player.sendSystemMessage(
                Component.literal("Fetching Bazaar prices...").withStyle(ChatFormatting.GRAY));

        new Thread(() -> {
            try {
                JsonObject products = PriceCache.getBazaar();
                if (products == null) {
                    sendError(client, "Failed to fetch Bazaar data.");
                    return;
                }

                // Cookie cost (buy order)
                double cookieCost = PriceCache.getBuyOrderPrice(products, "BOOSTER_COOKIE");

                // Kismet Feather — sell offer
                double kismetSellOffer = PriceCache.getSellOfferPrice(products, "KISMET_FEATHER");

                // Enchanted Lava Bucket — insta-buy (material cost)
                double lavaBucketCost = PriceCache.getInstaBuyPrice(products, "ENCHANTED_LAVA_BUCKET");

                // Magma Bucket — insta-sell
                double magmaSell = PriceCache.getInstaSellPrice(products, "MAGMA_BUCKET");

                // Plasma Bucket — insta-sell
                double plasmaSell = PriceCache.getInstaSellPrice(products, "PLASMA_BUCKET");

                List<CookieItem> items = new ArrayList<>();

                // Kismet Feather
                if (kismetSellOffer > 0) {
                    items.add(new CookieItem("Kismet Feather", KISMET_BITS, kismetSellOffer, "Sell Offer", null));
                } else {
                    items.add(new CookieItem("Kismet Feather", KISMET_BITS, 0, null, "price unavailable"));
                }

                // Magma Bucket
                if (magmaSell > 0 && lavaBucketCost > 0) {
                    double magmaMaterialCost = MAGMA_BUCKET_LAVA_BUCKETS * lavaBucketCost;
                    double magmaProfit = magmaSell - magmaMaterialCost;
                    int magmaBits = MAGMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS;
                    items.add(new CookieItem("Magma Bucket", magmaBits, magmaProfit, "Insta Sell", null));
                } else {
                    items.add(new CookieItem("Magma Bucket", MAGMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS, 0, null, "price unavailable"));
                }

                // Plasma Bucket
                if (plasmaSell > 0 && lavaBucketCost > 0) {
                    double plasmaMaterialCost = PLASMA_BUCKET_LAVA_BUCKETS * lavaBucketCost;
                    double plasmaProfit = plasmaSell - plasmaMaterialCost;
                    int plasmaBits = PLASMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS;
                    items.add(new CookieItem("Plasma Bucket", plasmaBits, plasmaProfit, "Insta Sell", null));
                } else {
                    items.add(new CookieItem("Plasma Bucket", PLASMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS, 0, null, "price unavailable"));
                }

                // Sort by profit per bit descending
                items.sort((a, b) -> Double.compare(b.profitPerBit(), a.profitPerBit()));

                // Display
                client.execute(() -> {
                    client.player.sendSystemMessage(
                            Component.literal("═══ Cookie Profit Calculator ═══").withStyle(ChatFormatting.GOLD));
                    client.player.sendSystemMessage(
                            Component.literal("Bits/cookie: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.format("%d", baseBitsPerCookie)).withStyle(ChatFormatting.AQUA))
                                    .append(Component.literal("  Bits/hr: ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal(String.format("%.0f", bitsPerHour)).withStyle(ChatFormatting.AQUA))
                                    .append(Component.literal(includeBitsTalisman ? " \uD83C\uDD31" : "").withStyle(ChatFormatting.DARK_AQUA))
                                    .append(Component.literal("  Cookie cost: ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal(cookieCost > 0 ? PriceCache.formatCoins(cookieCost) : "N/A").withStyle(ChatFormatting.WHITE)));
                    client.player.sendSystemMessage(Component.literal(""));

                    for (CookieItem item : items) {
                        if (item.error != null) {
                            client.player.sendSystemMessage(
                                    Component.literal(item.name).withStyle(ChatFormatting.YELLOW)
                                            .append(Component.literal(" — " + item.error).withStyle(ChatFormatting.RED)));
                            continue;
                        }

                        double profitPerBit = item.profitPerBit();
                        // Net profit per cookie = (profit per bit * bits per cookie) - cookie cost
                        double netPerCookie = profitPerBit * bitsPerCookie - (cookieCost > 0 ? cookieCost : 0);
                        // Net per hour = profit/bit * bits/hr - cookie cost amortized over earning time
                        double netPerHour = cookieCost > 0
                                ? profitPerBit * bitsPerHour - cookieCost / finalHoursToEarn
                                : profitPerBit * bitsPerHour;
                        ChatFormatting netCookieColor = (netPerCookie > 0) ? ChatFormatting.GREEN : ChatFormatting.RED;
                        ChatFormatting perBitColor = (profitPerBit > 0) ? ChatFormatting.GREEN : ChatFormatting.RED;
                        ChatFormatting netHrColor = (netPerHour > 0) ? ChatFormatting.GREEN : ChatFormatting.RED;

                        client.player.sendSystemMessage(
                                Component.literal(item.name).withStyle(ChatFormatting.YELLOW)
                                        .append(Component.literal(" (" + item.bitsCost + " bits, " + item.priceType + ")").withStyle(ChatFormatting.GRAY)));
                        client.player.sendSystemMessage(
                                Component.literal("  Net/cookie: ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(cookieCost > 0 ? PriceCache.formatCoins(netPerCookie) : "N/A").withStyle(netCookieColor))
                                        .append(Component.literal("  Per bit: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(PriceCache.formatCoinsPrecise(profitPerBit)).withStyle(perBitColor))
                                        .append(Component.literal("  Net/hr: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(cookieCost > 0 ? PriceCache.formatCoins(netPerHour) + "/hr" : "N/A").withStyle(netHrColor)));
                        client.player.sendSystemMessage(Component.literal(""));
                    }

                    // Best option summary
                    if (!items.isEmpty() && items.get(0).error == null) {
                        CookieItem best = items.get(0);
                        double bestNetPerHour = cookieCost > 0
                                ? best.profitPerBit() * bitsPerHour - cookieCost / finalHoursToEarn
                                : best.profitPerBit() * bitsPerHour;
                        ChatFormatting bestColor = bestNetPerHour > 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
                        client.player.sendSystemMessage(
                                Component.literal("Best: ").withStyle(ChatFormatting.GOLD)
                                        .append(Component.literal(best.name).withStyle(ChatFormatting.YELLOW))
                                        .append(Component.literal(" at ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(PriceCache.formatCoinsPrecise(best.profitPerBit()) + "/bit").withStyle(bestColor))
                                        .append(Component.literal(cookieCost > 0 ? " (" + PriceCache.formatCoins(bestNetPerHour) + " net/hr)" : "").withStyle(bestColor)));
                    }
                });

            } catch (Exception e) {
                sendError(client, "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }, "IslandTime-CookieProfit").start();
    }

    private static void sendError(Minecraft client, String msg) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(
                        Component.literal("[CookieProfit] " + msg).withStyle(ChatFormatting.RED));
            }
        });
    }

    private static class CookieItem {
        final String name;
        final int bitsCost;
        final double profit;
        final String priceType;
        final String error;

        CookieItem(String name, int bitsCost, double profit, String priceType, String error) {
            this.name = name;
            this.bitsCost = bitsCost;
            this.profit = profit;
            this.priceType = priceType;
            this.error = error;
        }

        double profitPerBit() {
            return bitsCost > 0 ? profit / bitsCost : 0;
        }
    }
}
