package org.example.islandtime;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.*;

/**
 * Compares profit from spending Booster Cookie bits on various items.
 *
 * Supports:
 * - Kismet Feather (1,350 bits) — sell offer price
 * - Magma Bucket (3,000 bits for 1 Heat Core) — insta-sell minus material cost
 * - Plasma Bucket (9,000 bits for 3 Heat Cores) — insta-sell minus material cost
 * - Ultimate Carrot Candy (8,000 bits for Upgrade) — insta-sell 10x minus 8x Superb Carrot Candy
 * - XL Minion Storage (1,500 bits for X-pender) — insta-sell minus Large Storage cost
 * - XXL Minion Storage (3,000 bits for 2 X-penders) — insta-sell minus Large Storage cost
 * - Bitbug Shard (5,000 bits) — insta-sell
 * - Inferno Fuel Block x64 (3,600 bits, or 3,120 with Blaze Slayer IX) — insta-sell
 * - 7 Levelling Enchantments (4,000-6,000 bits) — insta-sell
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

    // Ultimate Carrot Candy — 8,000 bits for Upgrade + 8x Superb Carrot Candy → produces 10 candies
    private static final int ULTIMATE_CARROT_CANDY_UPGRADE_BITS = 8_000;
    private static final int ULTIMATE_CARROT_CANDY_SUPERB_COUNT = 8;
    private static final int ULTIMATE_CARROT_CANDY_OUTPUT_COUNT = 10;

    // Minion Storage X-pender — 1,500 bits each
    // XL = Large Storage + 1 X-pender; XXL = Large Storage + 2 X-penders
    private static final int XPENDER_BITS = 1_500;

    // Bitbug Shard — 5,000 bits each, direct BZ sale
    private static final int BITBUG_SHARD_BITS = 5_000;

    // Inferno Fuel Block x64 — 3,600 bits for 64, or 3,120 bits with Blaze Slayer IX
    private static final int INFERNO_FUEL_BITS_64 = 3_600;
    private static final int INFERNO_FUEL_BITS_64_SLAYER_PERK = 3_120;

    // Levelling Enchantments — most cost 4,000 bits, Hecatomb costs 6,000
    private static final String[][] LEVELLING_ENCHANTMENTS = {
            {"ENCHANTMENT_COMPACT_1", "Compact", "4000"},
            {"ENCHANTMENT_EXPERTISE_1", "Expertise", "4000"},
            {"ENCHANTMENT_CULTIVATING_1", "Cultivating", "4000"},
            {"ENCHANTMENT_CHAMPION_1", "Champion", "4000"},
            {"ENCHANTMENT_HECATOMB_1", "Hecatomb", "6000"},
            {"ENCHANTMENT_TOXOPHILITE_1", "Toxophilite", "4000"},
            {"ENCHANTMENT_SMARTY_PANTS_1", "Smarty Pants", "4000"},
    };

    // Wiki: 5.2083% of bits per cookie every 30 min (250 * multiplier).
    // Takes ~10 hours of idle playtime to earn all bits without Bits Talisman.
    private static final double BASE_HOURS_TO_EARN_ALL_BITS = 10.0;

    // Bits Talisman: 10% chance to earn double per tick, draining the pool
    // faster. Same total bits, earned in ~9.09 hours instead of 10.
    private static final double BITS_TALISMAN_SPEED_MULTIPLIER = 1.1;

    /** Returns true if the sell mode config value means "Sell Offer" (index 1). */
    private static boolean isSellOffer(int sellMode) {
        return sellMode == 1;
    }

    private static double getPrice(JsonObject products, String productId, boolean sellOffer) {
        return sellOffer
                ? PriceCache.getSellOfferPrice(products, productId)
                : PriceCache.getInstaSellPrice(products, productId);
    }

    private static String sellLabel(boolean sellOffer) {
        return sellOffer ? "Sell Offer" : "Insta Sell";
    }

    public static void execute(Minecraft client) {
        if (client.player == null) return;

        IslandTimeConfig config = ConfigManager.getConfig();
        int baseBitsPerCookie = (config != null) ? config.misc.bitsPerCookie : 4800;
        boolean includeBitsTalisman = (config != null) && config.misc.includeBitsTalisman;
        boolean blazeSlayerPerk = (config != null) && config.misc.infernoFuelBlazeSlayerPerk;
        int topCount = (config != null) ? config.misc.cookieTopCount : 0;

        // Per-item sell mode overrides
        boolean kismetSellOffer = (config != null) && isSellOffer(config.misc.kismetSellMode);
        boolean magmaSellOffer = (config != null) && isSellOffer(config.misc.magmaBucketSellMode);
        boolean plasmaSellOffer = (config != null) && isSellOffer(config.misc.plasmaBucketSellMode);
        boolean carrotSellOffer = (config != null) && isSellOffer(config.misc.ultimateCarrotCandySellMode);
        boolean xlSellOffer = (config != null) && isSellOffer(config.misc.xlStorageSellMode);
        boolean xxlSellOffer = (config != null) && isSellOffer(config.misc.xxlStorageSellMode);
        boolean bitbugSellOffer = (config != null) && isSellOffer(config.misc.bitbugShardSellMode);
        boolean fuelSellOffer = (config != null) && isSellOffer(config.misc.infernoFuelSellMode);
        boolean enchSellOffer = (config != null) && isSellOffer(config.misc.enchantmentSellMode);

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
        final int finalTopCount = topCount;

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

                // Enchanted Lava Bucket — insta-buy (material cost)
                double lavaBucketCost = PriceCache.getInstaBuyPrice(products, "ENCHANTED_LAVA_BUCKET");

                // Large Storage — insta-buy (material cost)
                double largeStorageCost = PriceCache.getInstaBuyPrice(products, "LARGE_ENCHANTED_CHEST");

                // Superb Carrot Candy — insta-buy (material cost)
                double superbCarrotCandyCost = PriceCache.getInstaBuyPrice(products, "SUPERB_CARROT_CANDY");

                List<CookieItem> items = new ArrayList<>();

                // Kismet Feather
                {
                    double price = getPrice(products, "KISMET_FEATHER", kismetSellOffer);
                    if (price > 0) {
                        String breakdown = "Kismet Feather " + sellLabel(kismetSellOffer) + ": " + PriceCache.formatCoins(price)
                                + "\nProfit: " + PriceCache.formatCoins(price);
                        items.add(new CookieItem("Kismet Feather", KISMET_BITS, price, sellLabel(kismetSellOffer), null, breakdown));
                    } else {
                        items.add(new CookieItem("Kismet Feather", KISMET_BITS, 0, null, "price unavailable", null));
                    }
                }

                // Magma Bucket
                {
                    double magmaPrice = getPrice(products, "MAGMA_BUCKET", magmaSellOffer);
                    if (magmaPrice > 0 && lavaBucketCost > 0) {
                        double magmaMaterialCost = MAGMA_BUCKET_LAVA_BUCKETS * lavaBucketCost;
                        double magmaProfit = magmaPrice - magmaMaterialCost;
                        int magmaBits = MAGMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS;
                        String breakdown = "Magma Bucket " + sellLabel(magmaSellOffer) + ": " + PriceCache.formatCoins(magmaPrice)
                                + "\n" + MAGMA_BUCKET_LAVA_BUCKETS + "x Ench. Lava Bucket cost: " + PriceCache.formatCoins(lavaBucketCost) + " each = " + PriceCache.formatCoins(magmaMaterialCost)
                                + "\nProfit: " + PriceCache.formatCoins(magmaPrice) + " - " + PriceCache.formatCoins(magmaMaterialCost) + " = " + PriceCache.formatCoins(magmaProfit);
                        items.add(new CookieItem("Magma Bucket", magmaBits, magmaProfit, sellLabel(magmaSellOffer), null, breakdown));
                    } else {
                        items.add(new CookieItem("Magma Bucket", MAGMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS, 0, null, "price unavailable", null));
                    }
                }

                // Plasma Bucket
                {
                    double plasmaPrice = getPrice(products, "PLASMA_BUCKET", plasmaSellOffer);
                    if (plasmaPrice > 0 && lavaBucketCost > 0) {
                        double plasmaMaterialCost = PLASMA_BUCKET_LAVA_BUCKETS * lavaBucketCost;
                        double plasmaProfit = plasmaPrice - plasmaMaterialCost;
                        int plasmaBits = PLASMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS;
                        String breakdown = "Plasma Bucket " + sellLabel(plasmaSellOffer) + ": " + PriceCache.formatCoins(plasmaPrice)
                                + "\n" + PLASMA_BUCKET_LAVA_BUCKETS + "x Ench. Lava Bucket cost: " + PriceCache.formatCoins(lavaBucketCost) + " each = " + PriceCache.formatCoins(plasmaMaterialCost)
                                + "\nProfit: " + PriceCache.formatCoins(plasmaPrice) + " - " + PriceCache.formatCoins(plasmaMaterialCost) + " = " + PriceCache.formatCoins(plasmaProfit);
                        items.add(new CookieItem("Plasma Bucket", plasmaBits, plasmaProfit, sellLabel(plasmaSellOffer), null, breakdown));
                    } else {
                        items.add(new CookieItem("Plasma Bucket", PLASMA_BUCKET_HEAT_CORES * HEAT_CORE_BITS, 0, null, "price unavailable", null));
                    }
                }

                // Ultimate Carrot Candy (8,000 bits for Upgrade + 8x Superb → 10 candies)
                {
                    double carrotPrice = getPrice(products, "ULTIMATE_CARROT_CANDY", carrotSellOffer);
                    if (carrotPrice > 0 && superbCarrotCandyCost > 0) {
                        double materialCost = ULTIMATE_CARROT_CANDY_SUPERB_COUNT * superbCarrotCandyCost;
                        double revenue = ULTIMATE_CARROT_CANDY_OUTPUT_COUNT * carrotPrice;
                        double profit = revenue - materialCost;
                        String breakdown = ULTIMATE_CARROT_CANDY_OUTPUT_COUNT + "x Ult. Carrot Candy " + sellLabel(carrotSellOffer) + ": " + PriceCache.formatCoins(carrotPrice) + " each = " + PriceCache.formatCoins(revenue)
                                + "\n" + ULTIMATE_CARROT_CANDY_SUPERB_COUNT + "x Superb Carrot Candy cost: " + PriceCache.formatCoins(superbCarrotCandyCost) + " each = " + PriceCache.formatCoins(materialCost)
                                + "\nProfit: " + PriceCache.formatCoins(revenue) + " - " + PriceCache.formatCoins(materialCost) + " = " + PriceCache.formatCoins(profit);
                        items.add(new CookieItem("Ultimate Carrot Candy", ULTIMATE_CARROT_CANDY_UPGRADE_BITS, profit, sellLabel(carrotSellOffer), null, breakdown));
                    } else {
                        items.add(new CookieItem("Ultimate Carrot Candy", ULTIMATE_CARROT_CANDY_UPGRADE_BITS, 0, null, "price unavailable", null));
                    }
                }

                // XL Minion Storage (1,500 bits X-pender + Large Storage)
                {
                    double xlPrice = getPrice(products, "XLARGE_ENCHANTED_CHEST", xlSellOffer);
                    if (xlPrice > 0 && largeStorageCost > 0) {
                        double xlProfit = xlPrice - largeStorageCost;
                        String breakdown = "XL Storage " + sellLabel(xlSellOffer) + ": " + PriceCache.formatCoins(xlPrice)
                                + "\nLarge Storage cost: " + PriceCache.formatCoins(largeStorageCost)
                                + "\nProfit: " + PriceCache.formatCoins(xlPrice) + " - " + PriceCache.formatCoins(largeStorageCost) + " = " + PriceCache.formatCoins(xlProfit);
                        items.add(new CookieItem("XL Minion Storage", XPENDER_BITS, xlProfit, sellLabel(xlSellOffer), null, breakdown));
                    } else {
                        items.add(new CookieItem("XL Minion Storage", XPENDER_BITS, 0, null, "price unavailable", null));
                    }
                }

                // XXL Minion Storage (2 X-penders = 3,000 bits + Large Storage)
                {
                    double xxlPrice = getPrice(products, "XXLARGE_ENCHANTED_CHEST", xxlSellOffer);
                    if (xxlPrice > 0 && largeStorageCost > 0) {
                        double xxlProfit = xxlPrice - largeStorageCost;
                        String breakdown = "XXL Storage " + sellLabel(xxlSellOffer) + ": " + PriceCache.formatCoins(xxlPrice)
                                + "\nLarge Storage cost: " + PriceCache.formatCoins(largeStorageCost)
                                + "\nProfit: " + PriceCache.formatCoins(xxlPrice) + " - " + PriceCache.formatCoins(largeStorageCost) + " = " + PriceCache.formatCoins(xxlProfit);
                        items.add(new CookieItem("XXL Minion Storage", XPENDER_BITS * 2, xxlProfit, sellLabel(xxlSellOffer), null, breakdown));
                    } else {
                        items.add(new CookieItem("XXL Minion Storage", XPENDER_BITS * 2, 0, null, "price unavailable", null));
                    }
                }

                // Bitbug Shard (5,000 bits, direct sale)
                {
                    double bitbugPrice = getPrice(products, "SHARD_BITBUG", bitbugSellOffer);
                    if (bitbugPrice > 0) {
                        String breakdown = "Bitbug Shard " + sellLabel(bitbugSellOffer) + ": " + PriceCache.formatCoins(bitbugPrice)
                                + "\nProfit: " + PriceCache.formatCoins(bitbugPrice);
                        items.add(new CookieItem("Bitbug Shard", BITBUG_SHARD_BITS, bitbugPrice, sellLabel(bitbugSellOffer), null, breakdown));
                    } else {
                        items.add(new CookieItem("Bitbug Shard", BITBUG_SHARD_BITS, 0, null, "price unavailable", null));
                    }
                }

                // Inferno Fuel Block x64 (3,600 bits or 3,120 with Blaze Slayer IX)
                {
                    double fuelPrice = getPrice(products, "INFERNO_FUEL_BLOCK", fuelSellOffer);
                    if (fuelPrice > 0) {
                        int fuelBits = blazeSlayerPerk ? INFERNO_FUEL_BITS_64_SLAYER_PERK : INFERNO_FUEL_BITS_64;
                        double profit = 64.0 * fuelPrice;
                        String slayerNote = blazeSlayerPerk ? " (Slayer Perk)" : "";
                        String label = sellLabel(fuelSellOffer) + " x64" + slayerNote;
                        String breakdown = "64x Inferno Fuel Block " + sellLabel(fuelSellOffer) + ": " + PriceCache.formatCoins(fuelPrice) + " each = " + PriceCache.formatCoins(profit)
                                + "\nBits cost: " + fuelBits + slayerNote
                                + "\nProfit: " + PriceCache.formatCoins(profit);
                        items.add(new CookieItem("Inferno Fuel Block x64", fuelBits, profit, label, null, breakdown));
                    } else {
                        int fuelBits = blazeSlayerPerk ? INFERNO_FUEL_BITS_64_SLAYER_PERK : INFERNO_FUEL_BITS_64;
                        items.add(new CookieItem("Inferno Fuel Block x64", fuelBits, 0, null, "price unavailable", null));
                    }
                }

                // Levelling Enchantments
                for (String[] ench : LEVELLING_ENCHANTMENTS) {
                    String bzId = ench[0];
                    String name = ench[1];
                    int enchBits = Integer.parseInt(ench[2]);
                    double enchPrice = getPrice(products, bzId, enchSellOffer);
                    if (enchPrice > 0) {
                        String breakdown = name + " I " + sellLabel(enchSellOffer) + ": " + PriceCache.formatCoins(enchPrice)
                                + "\nProfit: " + PriceCache.formatCoins(enchPrice);
                        items.add(new CookieItem(name + " I", enchBits, enchPrice, sellLabel(enchSellOffer), null, breakdown));
                    } else {
                        items.add(new CookieItem(name + " I", enchBits, 0, null, "price unavailable", null));
                    }
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

                    int shown = 0;
                    for (CookieItem item : items) {
                        if (item.error != null) {
                            if (finalTopCount <= 0) {
                                client.player.sendSystemMessage(
                                        Component.literal(item.name).withStyle(ChatFormatting.YELLOW)
                                                .append(Component.literal(" — " + item.error).withStyle(ChatFormatting.RED)));
                            }
                            continue;
                        }

                        if (finalTopCount > 0 && shown >= finalTopCount) continue;
                        shown++;

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

                        // Build hover text with calc breakdown
                        Component hoverText = Component.literal(item.calcBreakdown != null ? item.calcBreakdown : item.name)
                                .withStyle(ChatFormatting.WHITE);

                        // Line 1: item name + bits + sell mode (with hover showing calc breakdown)
                        client.player.sendSystemMessage(
                                Component.literal(item.name).withStyle(ChatFormatting.YELLOW)
                                        .append(Component.literal(" (" + item.bitsCost + " bits, " + item.priceType + ")").withStyle(ChatFormatting.GRAY))
                                        .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hoverText))));

                        // Line 2: net/cookie, per bit, net/hr (with same hover showing calc breakdown)
                        client.player.sendSystemMessage(
                                Component.literal("  Net/cookie: ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(cookieCost > 0 ? PriceCache.formatCoins(netPerCookie) : "N/A").withStyle(netCookieColor))
                                        .append(Component.literal("  Per bit: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(PriceCache.formatCoinsPrecise(profitPerBit)).withStyle(perBitColor))
                                        .append(Component.literal("  Net/hr: ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(cookieCost > 0 ? PriceCache.formatCoins(netPerHour) + "/hr" : "N/A").withStyle(netHrColor))
                                        .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hoverText))));
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
        final String calcBreakdown;

        CookieItem(String name, int bitsCost, double profit, String priceType, String error, String calcBreakdown) {
            this.name = name;
            this.bitsCost = bitsCost;
            this.profit = profit;
            this.priceType = priceType;
            this.error = error;
            this.calcBreakdown = calcBreakdown;
        }

        double profitPerBit() {
            return bitsCost > 0 ? profit / bitsCost : 0;
        }
    }
}
