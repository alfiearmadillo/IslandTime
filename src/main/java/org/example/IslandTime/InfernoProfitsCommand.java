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
 * {@code /it infernoProfits [minions]}: what the minions of the current cohort earn in a fuel day on each of
 * the three fuels, using the speed and eyedrop settings of the estimated return in the Fuel Day Tracker.
 * The maths lives in {@link InfernoProfits} and {@link InfernoReturnCalculator}.
 */
public class InfernoProfitsCommand {

    private static final String PREFIX = "[InfernoProfits] ";
    private static final ChatFormatting[] GRADE_COLORS = {ChatFormatting.BLUE, ChatFormatting.DARK_PURPLE, ChatFormatting.GOLD};

    /** The set-up of the estimated return in the config, which is also what this command uses. */
    static InfernoReturnCalculator.Settings settingsFrom(IslandTimeConfig.FuelCycles fuel) {
        if (fuel == null) fuel = new IslandTimeConfig.FuelCycles();
        return new InfernoReturnCalculator.Settings(
                fuel.estimateMinionTier, fuel.estimateFuelGrade, fuel.estimateEyedrops, fuel.estimateFlycatcher,
                fuel.estimateSecondFlycatcher, fuel.estimateBeacon, fuel.estimatePostcard, fuel.estimateInfusion,
                fuel.estimateFreeWill, fuel.estimateSuperCompactor, fuel.estimateAfk,
                InfernoReturnCalculator.parsePrice(fuel.estimateFishPriceString, InfernoReturnCalculator.DEFAULT_FISH_PRICE),
                InfernoReturnCalculator.parsePrice(fuel.estimateHydraHeadPriceString,
                        InfernoReturnCalculator.DEFAULT_HYDRA_HEAD_PRICE));
    }

    /** @param minionsOverride how many minions to work with instead of the current cohort's, or null */
    public static void execute(Minecraft client, Integer minionsOverride) {
        if (client.player == null) return;

        IslandTimeConfig config = ConfigManager.getConfig();
        final InfernoReturnCalculator.Settings settings = settingsFrom(config != null ? config.fuelCycles : null);

        final boolean manual = minionsOverride != null;
        final int minions = manual ? minionsOverride : cohortMinions();
        if (minions <= 0) {
            client.player.sendSystemMessage(
                    Component.literal(PREFIX + "No fuel cohort is running, so there are no minions to count. "
                                    + "Use /it infernoProfits <minions> to pick how many.")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        client.player.sendSystemMessage(
                Component.literal("Fetching Bazaar prices...").withStyle(ChatFormatting.GRAY));

        new Thread(() -> {
            try {
                JsonObject products = PriceCache.getBazaar();
                if (products == null) {
                    sendError(client, "Failed to fetch Bazaar data.");
                    return;
                }
                InfernoReturnCalculator.Prices sellOffers = InfernoProfits.sellOffers(products);
                List<InfernoProfits.GradeProfit> profits = InfernoProfits.compute(settings, minions, sellOffers, products);

                client.execute(() -> {
                    if (client.player == null) return;
                    client.player.sendSystemMessage(
                            Component.literal("═══ Inferno Minion Profit per Fuel Day ═══").withStyle(ChatFormatting.GOLD));
                    client.player.sendSystemMessage(settingsLine(settings, minions, manual, profits));
                    for (InfernoProfits.GradeProfit profit : profits) {
                        sendGrade(client, profit, settings, sellOffers);
                    }
                    client.player.sendSystemMessage(
                            Component.literal("Loot at the Bazaar sell offer, fuel at the buy order (Gabagool Distillate), "
                                            + "no mob-kill coins. Hover a fuel for the details.")
                                    .withStyle(ChatFormatting.DARK_GRAY));
                });
            } catch (Exception e) {
                sendError(client, "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }, "IslandTime-InfernoProfits").start();
    }

    /** The minions of the cohort that is running: the current day's count (which a manual count overrides). */
    private static int cohortMinions() {
        if (!FuelCycleTracker.hasActiveCohort()) return 0;
        FuelCycleTracker.DayRecord today = FuelCycleTracker.getToday();
        int counted = today != null ? today.getEffectiveMinionCount() : 0;
        if (counted > 0) return counted;
        return FuelCycleTracker.getActiveMinionExpiries(System.currentTimeMillis()).size();
    }

    // ── Chat output ────────────────────────────────────────────────────────

    private static MutableComponent settingsLine(InfernoReturnCalculator.Settings settings, int minions, boolean manual,
                                                 List<InfernoProfits.GradeProfit> profits) {
        InfernoReturnCalculator.Estimate any = profits.get(0).estimate();
        List<MutableComponent> hover = new ArrayList<>();
        hover.add(Component.literal("Speed bonus (added together, then multiplied by the fuel)")
                .withStyle(ChatFormatting.GOLD));
        for (InfernoReturnCalculator.Contribution bonus : any.bonuses()) {
            hover.add(Component.literal("  +" + number(bonus.percent()) + "% " + bonus.name()).withStyle(ChatFormatting.GRAY));
        }
        hover.add(Component.literal("Total: +" + number(any.speedBonusPercent()) + "%").withStyle(ChatFormatting.WHITE));
        for (int grade = 0; grade < InfernoReturnCalculator.GRADE_NAMES.length; grade++) {
            hover.add(Component.literal(InfernoProfits.GRADE_RARITIES[grade] + " fuel: x"
                    + number(1 + InfernoReturnCalculator.GRADE_BONUS[grade]) + ", "
                    + String.format(Locale.ROOT, "%.2f", profits.get(grade).estimate().secondsPerAction())
                    + "s per action").withStyle(ChatFormatting.GRAY));
        }
        Component tooltip = join(hover);
        return Component.literal(minions + (minions == 1 ? " minion" : " minions")
                        + (manual ? " (your count)" : " (current cohort)")
                        + " | Tier " + settings.minionTier()
                        + " | +" + number(any.speedBonusPercent()) + "% speed"
                        + " | Eyedrops " + (settings.eyedrops() ? "on" : "off")
                        + " | Super Compactor " + (settings.superCompactor() ? "on" : "off"))
                .withStyle(ChatFormatting.GRAY)
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(tooltip)));
    }

    private static void sendGrade(Minecraft client, InfernoProfits.GradeProfit profit,
                                  InfernoReturnCalculator.Settings settings, InfernoReturnCalculator.Prices sellOffers) {
        InfernoReturnCalculator.Estimate e = profit.estimate();
        Component hover = gradeHover(profit, settings, sellOffers);

        MutableComponent head = Component.literal(profit.rarity() + " (" + profit.fuelName() + ")")
                .withStyle(GRADE_COLORS[profit.grade()])
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
        head.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        if (profit.priced()) {
            head.append(Component.literal(PriceCache.formatCoins(profit.profit()))
                    .withStyle(profit.profit() >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
            head.append(Component.literal(" profit").withStyle(ChatFormatting.GRAY));
        } else {
            head.append(Component.literal("n/a (" + missing(profit) + ")").withStyle(ChatFormatting.RED));
        }
        client.player.sendSystemMessage(head);

        MutableComponent detail = Component.literal("  Return ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(e.priced() ? PriceCache.formatCoins(e.value()) : "n/a")
                        .withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" - Fuel ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Double.isNaN(profit.fuelCost()) ? "n/a" : PriceCache.formatCoins(profit.fuelCost()))
                        .withStyle(ChatFormatting.YELLOW));
        if (e.eyedropCost() != null) {
            detail.append(Component.literal(" - Eyedrops ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(Double.isNaN(e.eyedropTotal()) ? "n/a" : PriceCache.formatCoins(e.eyedropTotal()))
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        client.player.sendSystemMessage(detail);
    }

    private static String missing(InfernoProfits.GradeProfit profit) {
        if (profit.fuel() == null) return "a fuel ingredient has no Bazaar price";
        if (!profit.estimate().priced()) return "no Bazaar price for Crude Gabagool";
        return "an eyedrop ingredient has no Bazaar price";
    }

    private static Component gradeHover(InfernoProfits.GradeProfit profit, InfernoReturnCalculator.Settings settings,
                                        InfernoReturnCalculator.Prices sellOffers) {
        InfernoReturnCalculator.Estimate e = profit.estimate();
        List<MutableComponent> lines = new ArrayList<>();
        lines.add(Component.literal(profit.fuelName() + " (" + profit.rarity() + "): " + e.minions()
                + (e.minions() == 1 ? " minion" : " minions") + ", one whole fuel day").withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal(String.format(Locale.ROOT, "%.2f", e.secondsPerAction()) + "s per action, "
                + String.format(Locale.ROOT, "%,.1f", e.harvestsPerMinion()) + " harvests per minion")
                .withStyle(ChatFormatting.GRAY));

        lines.add(Component.literal("Loot (Bazaar sell offer):").withStyle(ChatFormatting.AQUA));
        double crudePrice = sellOffers.get(InfernoReturnCalculator.CRUDE_GABAGOOL_ID);
        double veryCrudePrice = sellOffers.get(InfernoReturnCalculator.VERY_CRUDE_GABAGOOL_ID);
        if (e.compacted() && !e.usedCrudePriceForCompacted()) {
            lines.add(priced("Very Crude Gabagool (compacted)", e.veryCrudeTotal(), veryCrudePrice, false));
        } else {
            lines.add(priced("Crude Gabagool", e.crudeTotal(), crudePrice, false));
            if (e.usedCrudePriceForCompacted()) {
                lines.add(Component.literal("    no Very Crude price, so Crude was used").withStyle(ChatFormatting.YELLOW));
            }
        }
        for (InfernoReturnCalculator.DropLine drop : e.rareDrops()) {
            lines.add(priced(drop.name(), drop.amount(), drop.price(), drop.assumed()));
        }
        if (profit.grade() != InfernoReturnCalculator.GRADE_HYPERGOLIC) {
            lines.add(Component.literal("  No rare drops on this fuel").withStyle(ChatFormatting.DARK_GRAY));
        } else if (settings.eyedrops()) {
            lines.add(Component.literal("  Drop chances x" + number(InfernoReturnCalculator.EYEDROPS_MULTIPLIER)
                    + " with Capsaicin Eyedrops").withStyle(ChatFormatting.DARK_GRAY));
        }

        lines.add(Component.literal("Fuel (Bazaar buy order, Gabagool Distillate):").withStyle(ChatFormatting.YELLOW));
        PriceCache.FuelCostBreakdown fuel = profit.fuel();
        if (fuel == null) {
            lines.add(Component.literal("  A fuel ingredient has no Bazaar price").withStyle(ChatFormatting.RED));
        } else {
            int minions = fuel.fuelCount;
            lines.add(priced("Enchanted Coal", (double) PriceCache.enchantedCoalQty(profit.grade()) * minions,
                    fuel.enchantedCoalPrice, false));
            lines.add(priced("Enchanted Sulphur", PriceCache.enchantedSulphurQty(profit.grade()) * minions,
                    fuel.enchantedSulphurPrice, false));
            lines.add(priced("Very Crude Gabagool", PriceCache.VERY_CRUDE_GABAGOOL_QTY_BY_GRADE[profit.grade()] * minions,
                    fuel.veryCrudeGabagoolPrice, false));
            lines.add(priced("Crude Gabagool Distillate", 6.0 * minions, fuel.selectedDistillatePrice, false));
            lines.add(priced("Inferno Fuel Block", 2.0 * minions, fuel.fuelBlockPrice, false));
            lines.add(Component.literal("  " + PriceCache.formatCoins(fuel.unitCost) + " per fuel").withStyle(ChatFormatting.GRAY));
        }

        InfernoReturnCalculator.EyedropCost eyedrops = e.eyedropCost();
        if (eyedrops != null) {
            lines.add(Component.literal("Capsaicin Eyedrops, one per minion every day (" + eyedrops.crafts()
                    + " crafts = " + eyedrops.eyedrops() + "):").withStyle(ChatFormatting.LIGHT_PURPLE));
            for (InfernoReturnCalculator.DropLine item : eyedrops.ingredients()) {
                lines.add(priced(item.name(), item.amount(), item.price(), item.assumed()));
            }
        } else if (settings.eyedrops()) {
            lines.add(Component.literal("Capsaicin Eyedrops only work in Hypergolic fuel: not counted")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (profit.priced()) {
            lines.add(Component.literal("Profit = return " + PriceCache.formatCoins(e.value()) + " - fuel "
                    + PriceCache.formatCoins(profit.fuelCost())
                    + (eyedrops != null ? " - eyedrops " + PriceCache.formatCoins(e.eyedropTotal()) : "")
                    + " = " + PriceCache.formatCoins(profit.profit())).withStyle(ChatFormatting.WHITE));
        }
        return join(lines);
    }

    /** "  1.5 Inferno Apex @ 100M = 150M"; partial amounts are fine, and give that fraction of the price. */
    private static MutableComponent priced(String name, double amount, double price, boolean assumed) {
        String worth;
        if (price <= 0) {
            worth = assumed ? " (no price set)" : " (no Bazaar price)";
        } else {
            worth = " @ " + PriceCache.formatCoinsPrecise(price) + " = " + PriceCache.formatCoins(amount * price)
                    + (assumed ? " (the price you set)" : "");
        }
        return Component.literal("  " + InfernoProfits.formatAmount(amount) + "x " + name + worth)
                .withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent join(List<MutableComponent> lines) {
        MutableComponent joined = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) joined.append(Component.literal("\n"));
            joined.append(lines.get(i));
        }
        return joined;
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static void sendError(Minecraft client, String message) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(
                        Component.literal(PREFIX + message).withStyle(ChatFormatting.RED));
            }
        });
    }
}
