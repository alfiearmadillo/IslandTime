package org.example.islandtime.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.example.islandtime.ConfigManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(ItemStack.class)
public abstract class GabagoolPpgMixin {

    private static final Pattern COIN_PATTERN = Pattern.compile("([\\d,]+(?:\\.\\d+)?)\\s*coins?");
    private static final Pattern SELLING_PATTERN = Pattern.compile("Selling:\\s*([\\d,]+)\\s*x");

    @Inject(
            method = "getTooltipLines",
            at = @At("RETURN"),
            cancellable = true
    )
    private void islandtime_addPpgLine(
            Item.TooltipContext context,
            Player player,
            TooltipFlag type,
            CallbackInfoReturnable<List<Component>> cir) {

        if (ConfigManager.getConfig() == null || !ConfigManager.getConfig().misc.showPpg) return;

        // Only inject if the current screen has VERY_CRUDE_GABAGOOL in slot 11
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null || !(client.screen instanceof AbstractContainerScreen<?> handledScreen)) return;

        try {
            Slot slot11 = handledScreen.getMenu().getSlot(11);
            ItemStack slotStack = slot11.getItem();
            if (slotStack == null || slotStack.isEmpty()) return;

            CustomData customData = slotStack.get(DataComponents.CUSTOM_DATA);
            if (customData == null) return;
            CompoundTag nbt = customData.copyTag();
            String skyblockId = nbt.getStringOr("id", "");
            if (!skyblockId.contains("GABAGOOL")) return;
        } catch (Exception e) {
            return;
        }

        List<Component> tooltip = cir.getReturnValue();
        if (tooltip == null || tooltip.isEmpty()) return;

        // Check if this item has a "Total:" line (it's one of the 3 sell offer items)
        int totalLineIndex = -1;

        for (int i = 0; i < tooltip.size(); i++) {
            String plain = tooltip.get(i).getString();
            if (plain.contains("Total:")) {
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

        // Find the selling quantity from the "Selling:" line
        int sellingQty = 1; // fallback
        for (int i = 0; i < tooltip.size(); i++) {
            String plain = tooltip.get(i).getString();
            Matcher sellingMatcher = SELLING_PATTERN.matcher(plain);
            if (sellingMatcher.find()) {
                try {
                    sellingQty = Integer.parseInt(sellingMatcher.group(1).replace(",", ""));
                } catch (NumberFormatException e) {
                    // keep fallback
                }
                break;
            }
        }

        // Calculate PPG: total / (sellingQty * 192), rounded up to nearest 0.1
        // 192 gabagool per item, so total gabagool = sellingQty * 192
        double ppgRaw = totalCoins / (sellingQty * 192.0);
        double ppgRounded = Math.ceil(ppgRaw * 10.0) / 10.0;

        // Format the PPG value
        String ppgFormatted = String.format("%,.1f", ppgRounded);

        // Build the PPG line in the same style as Total
        MutableComponent ppgLine = Component.literal("").withStyle(s -> s.withItalic(false));
        ppgLine.append(Component.literal("PPG: ").withStyle(ChatFormatting.GRAY));
        ppgLine.append(Component.literal(ppgFormatted + " coins").withStyle(ChatFormatting.GOLD));

        // Insert the PPG line right after the Total line
        tooltip.add(totalLineIndex + 1, ppgLine);
    }
}
