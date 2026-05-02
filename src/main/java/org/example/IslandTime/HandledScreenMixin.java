package org.example.islandtime.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.example.islandtime.BackpackHoverHandler;
import org.example.islandtime.BackpackNbtParser;
import org.example.islandtime.FakeSlot;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = HandledScreen.class, priority = 1100)
public class HandledScreenMixin {

    @Shadow @Nullable protected Slot focusedSlot;

    @Unique @Nullable private Slot islandtime$savedSlot = null;

    // -------------------------------------------------------------------------
    // Render TAIL — this runs AFTER vanilla has already written focusedSlot
    // from its own getSlotAt call, so we can safely overwrite it here.
    //
    // While suppression is active (post-unfreeze, mouse hasn't moved yet):
    //   - force focusedSlot = null so no slot highlight shows and no other
    //     mod (e.g. Firmament keybindings) sees the wrong slot
    //   - skip backpack overlay logic
    //
    // While frozen:
    //   - draw the frozen overlay
    //   - focusedSlot was already nulled by vanilla because getSlotAt returns
    //     null for positions outside the screen (we're blocking clicks anyway)
    //
    // Normal case:
    //   - run backpack hover logic as before
    // -------------------------------------------------------------------------

    @Inject(method = "render", at = @At("TAIL"))
    private void islandtime$afterRender(DrawContext context,
                                        int mouseX, int mouseY,
                                        float delta,
                                        CallbackInfo ci) {
        // FavouriteGuiViewer takes priority over everything
        if (FavouriteGuiViewer.isActive()) {
            FavouriteGuiViewer.render(context, mouseX, mouseY);
            focusedSlot = null;
            return;
        }

        if (BackpackHoverHandler.isFrozen()) {
            focusedSlot = null;
            BackpackHoverHandler.drawFrozen(context, mouseX, mouseY);
            return;
        }

        // Suppression: vanilla has set focusedSlot to whatever is under the
        // mouse right now. We overwrite it to null until the mouse actually moves.
        if (BackpackHoverHandler.isFocusedSlotSuppressed(mouseX, mouseY)) {
            focusedSlot = null;
            return;
        }

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
    // Suppress vanilla tooltip while our overlay is active
    // -------------------------------------------------------------------------

    @Inject(method = "drawMouseoverTooltip", at = @At("HEAD"), cancellable = true)
    private void islandtime$cancelTooltipForBackpack(DrawContext context,
                                                     int mouseX, int mouseY,
                                                     CallbackInfo ci) {
        if (FavouriteGuiViewer.isActive() || BackpackHoverHandler.hasBackpackHovered()) {
            ci.cancel();
        }
    }

    // -------------------------------------------------------------------------
    // Block mouse clicks while frozen
    // -------------------------------------------------------------------------

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void islandtime$blockClickWhileFrozen(@Coerce Object input,
                                                  boolean handled,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (FavouriteGuiViewer.isActive() || BackpackHoverHandler.isFrozen()) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    // -------------------------------------------------------------------------
    // Key handling
    // -------------------------------------------------------------------------

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void islandtime$beforeKeyPressed(KeyInput input,
                                             CallbackInfoReturnable<Boolean> cir) {
        long window = MinecraftClient.getInstance().getWindow().getHandle();

        // Handle FavouriteGuiViewer close
        if (FavouriteGuiViewer.isActive()) {
            boolean ePressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS;
            if (ePressed || input.isEscape()) {
                FavouriteGuiViewer.close();
                cir.setReturnValue(true);
                cir.cancel();
                return;
            }

            // Block all other key input while viewer is active
            ItemStack hoveredItem = FavouriteGuiViewer.getHoveredItem();
            if (!hoveredItem.isEmpty()) {
                islandtime$savedSlot = focusedSlot;
                focusedSlot = new FakeSlot(hoveredItem);
            }
            return;
        }

        if (BackpackHoverHandler.isFrozen()) {
            boolean ePressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS;
            if (ePressed || input.isEscape()) {
                BackpackHoverHandler.requestUnfreeze();
                cir.setReturnValue(true);
                cir.cancel();
                return;
            }

            ItemStack overlayItem = BackpackHoverHandler.getOverlayHoveredItem();
            if (!overlayItem.isEmpty()) {
                islandtime$savedSlot = focusedSlot;
                focusedSlot = new FakeSlot(overlayItem);
            }
            return;
        }

        boolean shiftPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT)  == GLFW.GLFW_PRESS
                            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (shiftPressed && BackpackHoverHandler.isShowingOverlay()) {
            BackpackHoverHandler.freeze();
            cir.setReturnValue(true);
            cir.cancel();
            return;
        }

        ItemStack overlayItem = BackpackHoverHandler.getOverlayHoveredItem();
        if (!overlayItem.isEmpty()) {
            islandtime$savedSlot = focusedSlot;
            focusedSlot = new FakeSlot(overlayItem);
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
