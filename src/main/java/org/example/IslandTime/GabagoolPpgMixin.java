package org.example.islandtime.mixin;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.example.islandtime.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(ItemStack.class)
public abstract class GabagoolPpgMixin {

    private static final Pattern COIN_PATTERN = Pattern.compile("([\\d,]+(?:\\.\\d+)?)\\s*coins?");

    @Inject(
            method = "getTooltip",
            at = @At("RETURN"),
            cancellable = true
    )
    private void islandtime_addPpgLine(
            Item.TooltipContext context,
            PlayerEntity player,
            TooltipType type,
            CallbackInfoReturnable<List<Text>> cir) {

        if (ConfigManager.getConfig() == null || !ConfigManager.getConfig().misc.showPpg) return;

        List<Text> tooltip = cir.getReturnValue();
        if (tooltip == null || tooltip.isEmpty()) return;

        // Check if this is a "Sell Offer Setup" item by scanning lore for that text
        boolean isSellOfferSetup = false;
        int totalLineIndex = -1;

        for (int i = 0; i < tooltip.size(); i++) {
            String plain = tooltip.get(i).getString();
            if (plain.contains("Sell Offer Setup")) {
                isSellOfferSetup = true;
            }
            if (plain.startsWith("Total:") || plain.contains("Total: ")) {
                totalLineIndex = i;
            }
        }

        if (!isSellOfferSetup || totalLineIndex == -1) return;

        // Extract the coin amount from the Total line
        String totalText = tooltip.get(totalLineIndex).getString();
        Matcher matcher = COIN_PATTERN.matcher(totalText);
        if (!matcher.find()) return;

        String amountStr = matcher.group(1).replace(",", "");
        double totalCoins;
        try {
            totalCoins = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            return;
        }

        // Calculate PPG: total / 192, rounded up to nearest 0.1
        double ppgRaw = totalCoins / 192.0;
        double ppgRounded = Math.ceil(ppgRaw * 10.0) / 10.0;

        // Format the PPG value
        String ppgFormatted = String.format("%,.1f", ppgRounded);

        // Build the PPG line in the same style as Total
        MutableText ppgLine = Text.literal("").styled(s -> s.withItalic(false));
        ppgLine.append(Text.literal("PPG: ").formatted(Formatting.GRAY));
        ppgLine.append(Text.literal(ppgFormatted + " coins").formatted(Formatting.GOLD));

        // Insert the PPG line right after the Total line
        List<Text> newTooltip = new ArrayList<>(tooltip);
        newTooltip.add(totalLineIndex + 1, ppgLine);
        cir.setReturnValue(newTooltip);
    }
}
