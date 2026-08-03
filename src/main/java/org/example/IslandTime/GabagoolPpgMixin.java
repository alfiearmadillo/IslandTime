package org.example.islandtime.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.slot.Slot;
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
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("Amount:\\s*([\\d,]+)x?");

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

        // Only inject if the current screen has VERY_CRUDE_GABAGOOL in slot 11
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen == null || !(client.currentScreen instanceof HandledScreen<?> handledScreen)) return;

        try {
            Slot slot11 = handledScreen.getScreenHandler().getSlot(11);
            ItemStack slotStack = slot11.getStack();
            if (slotStack == null || slotStack.isEmpty()) return;

            NbtComponent customData = slotStack.get(DataComponentTypes.CUSTOM_DATA);
            if (customData == null) return;
            NbtCompound nbt = customData.copyNbt();
            if (!"VERY_CRUDE_GABAGOOL".equals(nbt.getString("id"))) return;
        } catch (Exception e) {
            return;
        }

        List<Text> tooltip = cir.getReturnValue();
        if (tooltip == null || tooltip.isEmpty()) return;

        // Check if this item has a "Total:" line (it's one of the 3 sell offer items)
        int totalLineIndex = -1;

        for (int i = 0; i < tooltip.size(); i++) {
            String plain = tooltip.get(i).getString();
            if (plain.startsWith("Total:") || plain.contains("Total: ")) {
                totalLineIndex = i;
            }
        }

        if (totalLineIndex == -1) return;

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

        // Find the selling quantity from the "Amount:" line
        int quantity = 192; // fallback
        for (int i = 0; i < tooltip.size(); i++) {
            String plain = tooltip.get(i).getString();
            Matcher amountMatcher = AMOUNT_PATTERN.matcher(plain);
            if (amountMatcher.find()) {
                try {
                    quantity = Integer.parseInt(amountMatcher.group(1).replace(",", ""));
                } catch (NumberFormatException e) {
                    // keep fallback
                }
                break;
            }
        }

        // Calculate PPG: total / quantity, rounded up to nearest 0.1
        double ppgRaw = totalCoins / (double) quantity;
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
