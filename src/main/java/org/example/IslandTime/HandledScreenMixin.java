package org.example.islandtime.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.example.islandtime.BackpackHoverHandler;
import org.example.islandtime.BackpackNbtParser;
import org.example.islandtime.FakeSlot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * priority = 1100 ensures our HEAD injection runs before Firmament's (default 1000).
 * In Mixin, higher-priority mixins are applied later, meaning their HEAD injectors
 * are inserted closer to the original method entry and therefore execute first.
 */
@Mixin(value = HandledScreen.class, priority = 1100)
public class HandledScreenMixin {

    @Shadow @Nullable protected Slot focusedSlot;

    @Unique @Nullable private Slot islandtime$savedSlot = null;

    // -------------------------------------------------------------------------
    // Cancel vanilla backpack tooltip when our overlay is active
    // -------------------------------------------------------------------------

    @Inject(method = "drawMouseoverTooltip", at = @At("HEAD"), cancellable = true)
    private void islandtime$cancelTooltipForBackpack(DrawContext context,
                                                     int mouseX, int mouseY,
                                                     CallbackInfo ci) {
        // Cancel whenever a backpack is hovered — including the shift-held frame
        // where isShowingOverlay() returns false but the preview screen is about
        // to open. Without this, the vanilla tooltip flickers for one frame.
        if (BackpackHoverHandler.hasBackpackHovered()) {
            ci.cancel();
        }
    }

    // -------------------------------------------------------------------------
    // Draw overlay after everything else has rendered
    // -------------------------------------------------------------------------

    @Inject(method = "render", at = @At("TAIL"))
    private void islandtime$afterRender(DrawContext context,
                                        int mouseX, int mouseY,
                                        float delta,
                                        CallbackInfo ci) {
        if (focusedSlot == null) {
            BackpackHoverHandler.clearHovered();
            return;
        }

        ItemStack stack = focusedSlot.getStack();
        if (stack.isEmpty() || !BackpackNbtParser.hasBackpackData(stack)) {
            BackpackHoverHandler.clearHovered();
            return;
        }

        HandledScreen<?> self = (HandledScreen<?>) (Object) this;
        BackpackHoverHandler.onBackpackHovered(stack, context, mouseX, mouseY, self);
    }

    // -------------------------------------------------------------------------
    // Swap focusedSlot → FakeSlot(inner item) before keyPressed runs,
    // so Firmament (and any other mod) reads the overlay-hovered item.
    // -------------------------------------------------------------------------

    @Inject(method = "keyPressed", at = @At("HEAD"))
    private void islandtime$beforeKeyPressed(KeyInput input,
                                             CallbackInfoReturnable<Boolean> cir) {
        ItemStack overlayItem = BackpackHoverHandler.getOverlayHoveredItem();
        if (!overlayItem.isEmpty()) {
            islandtime$savedSlot = focusedSlot;
            focusedSlot = new FakeSlot(overlayItem);
            System.out.println("[IslandTime] focusedSlot swapped → " + overlayItem.getName().getString());
        }
    }

    @Inject(method = "keyPressed", at = @At("TAIL"))
    private void islandtime$afterKeyPressed(KeyInput input,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (islandtime$savedSlot != null) {
            focusedSlot = islandtime$savedSlot;
            islandtime$savedSlot = null;
        }
    }
}
