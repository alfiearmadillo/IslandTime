package org.example.islandtime.mixin;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.nbt.CompoundTag;
import org.example.islandtime.ConfigManager;
import org.example.islandtime.IslandTimeConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Re-dyes Adaptive armor pieces client-side to the color stored in their
 * custom_data "color" tag (format "R:G:B", 0-255 each) instead of the
 * forced default leather color.
 */
@Mixin(ItemStack.class)
public abstract class AdaptiveColorMixin {

    private static final Set<String> ADAPTIVE_IDS = Set.of(
        "ADAPTIVE_CHESTPLATE", "ADAPTIVE_LEGGINGS", "ADAPTIVE_BOOTS",
        "STARRED_ADAPTIVE_CHESTPLATE", "STARRED_ADAPTIVE_LEGGINGS", "STARRED_ADAPTIVE_BOOTS"
    );

    @SuppressWarnings("unchecked")
    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private <T> void islandtime_overrideAdaptiveColor(
            DataComponentType<? extends T> type,
            CallbackInfoReturnable<T> cir) {

        if (type != DataComponents.DYED_COLOR) return;

        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !config.misc.trueAdaptiveColor) return;

        // Already has a dyed color (adaptive armor always does)
        if (cir.getReturnValue() == null) return;

        ItemStack self = (ItemStack) (Object) this;
        CustomData customData = self.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return;

        CompoundTag nbt = customData.copyTag();
        String id = nbt.getStringOr("id", "");
        if (!ADAPTIVE_IDS.contains(id)) return;

        String colorStr = nbt.getStringOr("color", "");
        if (colorStr.isEmpty()) return;

        // Parse "R:G:B" format
        String[] parts = colorStr.split(":");
        if (parts.length != 3) return;

        try {
            int r = Integer.parseInt(parts[0]);
            int g = Integer.parseInt(parts[1]);
            int b = Integer.parseInt(parts[2]);
            int rgb = (r << 16) | (g << 8) | b;
            cir.setReturnValue((T) new DyedItemColor(rgb));
        } catch (NumberFormatException ignored) {
            // Malformed color tag — keep the original
        }
    }
}
