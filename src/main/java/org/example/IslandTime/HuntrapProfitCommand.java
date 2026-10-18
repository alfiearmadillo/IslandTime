package org.example.islandtime;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ranks Huntrap placement locations by estimated profit per hour.
 *
 * Shard prices come from the Bazaar (insta sell by default, or sell offer when configured), the same way
 * the other profit commands read them. The profit model lives in {@link HuntrapCalculator} and the
 * location/shard data in {@link HuntrapLocations}.
 */
public class HuntrapProfitCommand {

    private static final String PREFIX = "[HuntrapProfit] ";

    public static void execute(Minecraft client) {
        if (client.player == null) return;

        IslandTimeConfig config = ConfigManager.getConfig();
        HuntrapCalculator.TrapType trap = HuntrapCalculator.TrapType.fromIndex(
                config != null ? config.misc.huntrapType : HuntrapCalculator.TrapType.ASTRAL.ordinal());
        String fortuneText = config != null ? config.misc.huntrapHunterFortune : "0";
        boolean desertTemple = config != null && config.misc.huntrapDesertTemple;
        int forestLevel = clampLevel(config != null ? config.misc.huntrapForestTrapLevel : 0);
        int combatLevel = clampLevel(config != null ? config.misc.huntrapCombatTrapLevel : 0);
        boolean useSellOffer = config != null && config.misc.huntrapUseSellOffer;
        int topCount = config != null ? config.misc.huntrapTopCount : 5;

        double hunterFortune = HuntrapCalculator.parseHunterFortune(fortuneText);
        if (Double.isNaN(hunterFortune)) {
            client.player.sendSystemMessage(
                    Component.literal(PREFIX + "Hunter Fortune must be a number of 0 or more. Set it in /islandtime under Misc > Huntrap Profit.")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        final HuntrapCalculator.Settings settings =
                new HuntrapCalculator.Settings(trap, hunterFortune, desertTemple, forestLevel, combatLevel);
        final String sellLabel = useSellOffer ? "Sell Offer" : "Insta Sell";
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

                List<HuntrapCalculator.LocationResult> ranked = HuntrapCalculator.evaluateAll(settings,
                        shard -> getPrice(products, shard.bazaarId(), useSellOffer));

                List<HuntrapCalculator.LocationResult> priced = new ArrayList<>();
                List<HuntrapCalculator.LocationResult> unpriced = new ArrayList<>();
                for (HuntrapCalculator.LocationResult result : ranked) {
                    (result.priced() ? priced : unpriced).add(result);
                }

                List<HuntrapCalculator.LocationResult> shown = new ArrayList<>(priced);
                if (finalTopCount > 0 && shown.size() > finalTopCount) {
                    shown = new ArrayList<>(shown.subList(0, finalTopCount));
                }
                // Locations with no Bazaar price are only listed when showing everything
                List<HuntrapCalculator.LocationResult> shownUnpriced = finalTopCount <= 0 ? unpriced : List.of();
                final List<HuntrapCalculator.LocationResult> finalShown = shown;

                client.execute(() -> {
                    if (client.player == null) return;

                    client.player.sendSystemMessage(
                            Component.literal("═══ Huntrap Profit/Hour ═══").withStyle(ChatFormatting.GOLD));
                    client.player.sendSystemMessage(settingsLine(settings, sellLabel));

                    if (finalShown.isEmpty()) {
                        client.player.sendSystemMessage(
                                Component.literal("No Huntrap shard prices available on the Bazaar.").withStyle(ChatFormatting.RED));
                    }

                    int rank = 1;
                    for (HuntrapCalculator.LocationResult result : finalShown) {
                        sendLocation(client, rank++, result, settings, sellLabel);
                    }

                    for (HuntrapCalculator.LocationResult result : shownUnpriced) {
                        client.player.sendSystemMessage(
                                Component.literal(result.location().label()).withStyle(ChatFormatting.DARK_GRAY)
                                        .append(Component.literal(": shard prices unavailable").withStyle(ChatFormatting.RED)));
                    }
                });
            } catch (Exception e) {
                sendError(client, "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }, "IslandTime-HuntrapProfit").start();
    }

    // ── Chat output ────────────────────────────────────────────────────────

    private static MutableComponent settingsLine(HuntrapCalculator.Settings settings, String sellLabel) {
        return Component.literal(HuntrapCalculator.TRAP_COUNT + "x " + settings.trap().displayName
                + " | Hunter Fortune " + number(settings.hunterFortune())
                + " | Forest Trap " + settings.forestLevel()
                + " | Combat Trap " + settings.combatLevel()
                + " | Desert Temple " + (settings.desertTemple() ? "on" : "off")
                + " | " + sellLabel).withStyle(ChatFormatting.GRAY);
    }

    private static void sendLocation(Minecraft client, int rank, HuntrapCalculator.LocationResult result,
                                     HuntrapCalculator.Settings settings, String sellLabel) {
        HuntrapLocations.Location location = result.location();

        client.player.sendSystemMessage(
                Component.literal("#" + rank + " ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(location.label()).withStyle(ChatFormatting.YELLOW)));

        List<HuntrapCalculator.ShardResult> shards = result.shards();
        MutableComponent line = Component.empty();
        for (int i = 0; i < shards.size(); i++) {
            HuntrapCalculator.ShardResult shard = shards.get(i);
            if (i > 0) line.append(Component.literal(i == shards.size() - 1 ? " & " : ", ").withStyle(ChatFormatting.GRAY));

            String name = shard.entry().shard().name() + (shards.size() == 1 ? " Shard" : "");
            Component hover = shardHover(shard, result, settings, sellLabel);
            line.append(Component.literal(name)
                    .withStyle(shard.priced() ? rarityColor(shard.entry().shard().rarity()) : ChatFormatting.DARK_GRAY)
                    .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))));
        }
        line.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        Component locationHover = locationHover(result, sellLabel);
        line.append(Component.literal(PriceCache.formatCoins(result.profitPerHour()) + "/h").withStyle(ChatFormatting.GREEN)
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(locationHover))));
        client.player.sendSystemMessage(line);
    }

    // ── Hover breakdowns ───────────────────────────────────────────────────

    private static Component shardHover(HuntrapCalculator.ShardResult result, HuntrapCalculator.LocationResult location,
                                        HuntrapCalculator.Settings settings, String sellLabel) {
        HuntrapLocations.Shard shard = result.entry().shard();
        HuntrapLocations.Location loc = location.location();
        MutableComponent text = Component.empty();

        text.append(Component.literal(shard.name() + " Shard").withStyle(rarityColor(shard.rarity()), ChatFormatting.BOLD));
        text.append(Component.literal("\n" + shard.rarity().displayName + " " + shard.category().displayName + " shard at "
                + loc.label()).withStyle(ChatFormatting.GRAY));

        // Price
        if (result.priced()) {
            row(text, "Price (" + sellLabel + ")", PriceCache.formatCoins(result.price()));
        } else {
            row(text, "Price (" + sellLabel + ")", "unavailable, counted as 0");
        }

        // Odds
        String oddsNote = loc.equalOdds()
                ? (loc.entries().size() == 1 ? "only shard here" : "equal odds, " + loc.entries().size() + " shards")
                : "weight " + number(result.entry().weight()) + " of " + number(loc.totalWeight());
        row(text, "Chance per catch", percent(result.odds()) + " (" + oddsNote + ")");

        // Hunter Fortune
        row(text, "Shards per catch", String.format(Locale.ROOT, "%.2f", settings.shardsPerCatch())
                + " (1 + " + number(settings.hunterFortune()) + " Hunter Fortune / 100)");

        // Catch time
        List<String> bonuses = new ArrayList<>();
        if (settings.desertTemple()) {
            bonuses.add("+" + number(HuntrapCalculator.DESERT_TEMPLE_SPEED_BONUS * 100) + "% Desert Temple");
        }
        int attributeLevel = settings.attributeLevel(shard.category());
        if (attributeLevel > 0) {
            bonuses.add("+" + attributeLevel + "% " + shard.category().displayName + " Trap");
        }
        row(text, "Base catch time", number(HuntrapCalculator.AVERAGE_BASE_HOURS) + "h (average of "
                + number(HuntrapCalculator.MIN_BASE_HOURS) + "-" + number(HuntrapCalculator.MAX_BASE_HOURS) + "h)");
        row(text, settings.trap().displayName, "x" + String.format(Locale.ROOT, "%.2f", settings.trap().timeMultiplier())
                + " time (-" + settings.trap().timeReductionPercent + "%)");
        row(text, "Speed bonuses", bonuses.isEmpty() ? "none" : String.join(", ", bonuses)
                + " = x" + String.format(Locale.ROOT, "%.2f", result.speed()));
        if (shard.category() == HuntrapLocations.Category.WATER && (settings.forestLevel() > 0 || settings.combatLevel() > 0)) {
            text.append(Component.literal("\nForest/Combat Trap levels do not apply to Water shards")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        row(text, "Catch time", hours(result.catchHours()));
        if (loc.entries().size() > 1) {
            row(text, "Average at this location", hours(location.meanCatchHours()) + " (weighted by chance)");
        }

        // Profit
        double catchesPerHour = HuntrapCalculator.TRAP_COUNT / location.meanCatchHours();
        row(text, "Catches per hour", HuntrapCalculator.TRAP_COUNT + " traps / " + hours(location.meanCatchHours())
                + " = " + String.format(Locale.ROOT, "%.3f", catchesPerHour));
        row(text, "Profit/hour", String.format(Locale.ROOT, "%.3f", catchesPerHour)
                + " x " + String.format(Locale.ROOT, "%.2f", settings.shardsPerCatch())
                + " x " + percent(result.odds())
                + " x " + PriceCache.formatCoins(result.price())
                + " = " + PriceCache.formatCoins(result.profitPerHour()) + "/h");
        return text;
    }

    private static Component locationHover(HuntrapCalculator.LocationResult result, String sellLabel) {
        MutableComponent text = Component.empty();
        text.append(Component.literal(result.location().label()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        for (HuntrapCalculator.ShardResult shard : result.shards()) {
            text.append(Component.literal("\n" + shard.entry().shard().name() + ": ")
                    .withStyle(rarityColor(shard.entry().shard().rarity())));
            text.append(Component.literal(shard.priced()
                    ? PriceCache.formatCoins(shard.profitPerHour()) + "/h (" + percent(shard.odds()) + " of catches, "
                            + PriceCache.formatCoins(shard.price()) + " each)"
                    : "price unavailable").withStyle(ChatFormatting.WHITE));
        }
        text.append(Component.literal("\nTotal: ").withStyle(ChatFormatting.GRAY));
        text.append(Component.literal(PriceCache.formatCoins(result.profitPerHour()) + "/h").withStyle(ChatFormatting.GREEN));
        text.append(Component.literal(" with " + HuntrapCalculator.TRAP_COUNT + " traps, "
                + hours(result.meanCatchHours()) + " average catch time (" + sellLabel + ")").withStyle(ChatFormatting.GRAY));
        text.append(Component.literal("\nHover each shard for its full breakdown").withStyle(ChatFormatting.DARK_GRAY));
        return text;
    }

    private static void row(MutableComponent text, String label, String value) {
        text.append(Component.literal("\n" + label + ": ").withStyle(ChatFormatting.GRAY));
        text.append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static double getPrice(JsonObject products, String productId, boolean sellOffer) {
        return sellOffer
                ? PriceCache.getSellOfferPrice(products, productId)
                : PriceCache.getInstaSellPrice(products, productId);
    }

    private static int clampLevel(int level) {
        return Math.max(0, Math.min(HuntrapCalculator.MAX_ATTRIBUTE_LEVEL, level));
    }

    private static ChatFormatting rarityColor(HuntrapLocations.Rarity rarity) {
        return switch (rarity) {
            case COMMON -> ChatFormatting.WHITE;
            case UNCOMMON -> ChatFormatting.GREEN;
            case RARE -> ChatFormatting.BLUE;
            case EPIC -> ChatFormatting.DARK_PURPLE;
            case LEGENDARY -> ChatFormatting.GOLD;
        };
    }

    /** 20 -> "20", 1250.5 -> "1250.5", 0.25 -> "0.25" */
    private static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return String.format(Locale.ROOT, "%d", (long) value);
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
    }

    private static String hours(double hours) {
        return String.format(Locale.ROOT, "%.2fh", hours);
    }

    private static void sendError(Minecraft client, String msg) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(
                        Component.literal(PREFIX + msg).withStyle(ChatFormatting.RED));
            }
        });
    }
}
