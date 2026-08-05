package org.example.islandtime.mixin;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import org.example.islandtime.ComponentDisplayHandler;
import org.example.islandtime.TooltipOverrideConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ItemStack.class)
public abstract class TooltipOverrideMixin {

    @Unique
    private static boolean islandtime$inTooltipOverride = false;

    @Inject(
            method = "getTooltip",
            at = @At("HEAD"),
            cancellable = true
    )
    private void islandtime_overrideTooltipDisplay(
            Item.TooltipContext context,
            PlayerEntity player,
            TooltipType type,
            CallbackInfoReturnable<List<Text>> cir) {

        if (!TooltipOverrideConfig.isShowHiddenTooltips()) return;

        ItemStack self = (ItemStack) (Object) this;
        TooltipDisplayComponent display = self.getOrDefault(
                DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplayComponent.DEFAULT
        );

        if (!display.hideTooltip()) return;

        // Make a copy with hide_tooltip removed, get its tooltip, return that
        ItemStack copy = self.copy();
        copy.set(DataComponentTypes.TOOLTIP_DISPLAY,
                new TooltipDisplayComponent(false, display.hiddenComponents()));
        islandtime$inTooltipOverride = true;
        try {
            cir.setReturnValue(copy.getTooltip(context, player, type));
        } finally {
            islandtime$inTooltipOverride = false;
        }
    }

    @Inject(
            method = "getTooltip",
            at = @At("RETURN")
    )
    private void islandtime_appendComponentDisplay(
            Item.TooltipContext context,
            PlayerEntity player,
            TooltipType type,
            CallbackInfoReturnable<List<Text>> cir) {

        if (!ComponentDisplayHandler.isEnabled()) return;
        if (islandtime$inTooltipOverride) return;

        ItemStack self = (ItemStack) (Object) this;
        List<Text> tooltip = cir.getReturnValue();
        ComponentDisplayHandler.appendComponentLines(self, tooltip);
    }
}