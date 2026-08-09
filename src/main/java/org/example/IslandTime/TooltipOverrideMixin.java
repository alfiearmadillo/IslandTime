package org.example.islandtime.mixin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
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
            method = "getTooltipLines",
            at = @At("HEAD"),
            cancellable = true
    )
    private void islandtime_overrideTooltipDisplay(
            Item.TooltipContext context,
            Player player,
            TooltipFlag type,
            CallbackInfoReturnable<List<Component>> cir) {

        if (!TooltipOverrideConfig.isShowHiddenTooltips()) return;

        ItemStack self = (ItemStack) (Object) this;
        TooltipDisplay display = self.getOrDefault(
                DataComponents.TOOLTIP_DISPLAY,
                TooltipDisplay.DEFAULT
        );

        if (!display.hideTooltip()) return;

        // Make a copy with hide_tooltip removed, get its tooltip, return that
        ItemStack copy = self.copy();
        copy.set(DataComponents.TOOLTIP_DISPLAY,
                new TooltipDisplay(false, display.hiddenComponents()));
        islandtime$inTooltipOverride = true;
        try {
            cir.setReturnValue(copy.getTooltipLines(context, player, type));
        } finally {
            islandtime$inTooltipOverride = false;
        }
    }

    @Inject(
            method = "getTooltipLines",
            at = @At("RETURN")
    )
    private void islandtime_appendComponentDisplay(
            Item.TooltipContext context,
            Player player,
            TooltipFlag type,
            CallbackInfoReturnable<List<Component>> cir) {

        if (!ComponentDisplayHandler.isEnabled()) return;
        if (islandtime$inTooltipOverride) return;

        ItemStack self = (ItemStack) (Object) this;
        List<Component> tooltip = cir.getReturnValue();
        ComponentDisplayHandler.appendComponentLines(self, tooltip);
    }
}
