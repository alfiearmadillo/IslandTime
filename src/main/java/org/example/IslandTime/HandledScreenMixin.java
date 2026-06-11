package org.example.islandtime.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.KeyboardInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import org.example.islandtime.BackpackHoverHandler;
import org.example.islandtime.BackpackNbtParser;
import org.example.islandtime.FakeSlot;
import org.example.islandtime.FavouriteGuiViewer;
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

@Mixin(value = AbstractContainerScreen.class, priority = 1100)
public class AbstractContainerScreenMixin {

    @Shadow @Nullable protected Slot focusedSlot;

    @Unique @Nullable private Slot islandtime$savedSlot = null;

    @Unique
    private static boolean islandtime$shouldBlockMouseInput() {
        return FavouriteGuiViewer.isActive() || BackpackHoverHandler.isFrozen();
    }

    // -------------------------------------------------------------------------
    // Render TAIL — runs AFTER vanilla has written focusedSlot.
    // -------------------------------------------------------------------------

    @Inject(method = "render", at = @At("TAIL"))
    private void islandtime$afterRender(GuiGraphics context,
                                        int mouseX, int mouseY,
                                        float delta,
                                        CallbackInfo ci) {

        // ── FavouriteGuiViewer active (overlay on top of a AbstractContainerScreen) ──
        if (FavouriteGuiViewer.isActive()) {
            FavouriteGuiViewer.render(context, mouseX, mouseY);

            ItemStack viewerHovered = FavouriteGuiViewer.getHoveredItem();

            // Remap focusedSlot so keybinding mods (e.g. Firmament) read the
            // correct item rather than whatever happens to be under the cursor
            // in the underlying AbstractContainerScreen.
            focusedSlot = viewerHovered.isEmpty() ? null : new FakeSlot(viewerHovered);

            // Frozen backpack inside the viewer takes priority
            if (BackpackHoverHandler.isFrozen()) {
                BackpackHoverHandler.drawFrozen(context, mouseX, mouseY);
                return;
            }

            // Backpack hover for items within the viewer
            if (!viewerHovered.isEmpty() && BackpackNbtParser.hasBackpackData(viewerHovered)) {
                AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
                BackpackHoverHandler.onBackpackHovered(viewerHovered, context, mouseX, mouseY, self);
            } else {
                BackpackHoverHandler.clearHovered();
            }
            return;
        }

        // ── Frozen backpack (standalone, no viewer) ────────────────────────
        if (BackpackHoverHandler.isFrozen()) {
            focusedSlot = null;
            BackpackHoverHandler.drawFrozen(context, mouseX, mouseY);
            return;
        }

        // ── Suppression: mouse hasn't moved since unfreeze ─────────────────
        if (BackpackHoverHandler.isFocusedSlotSuppressed(mouseX, mouseY)) {
            focusedSlot = null;
            return;
        }

        // ── Normal backpack hover ──────────────────────────────────────────
        if (focusedSlot == null) {
            BackpackHoverHandler.clearHovered();
            return;
        }

        ItemStack stack = focusedSlot.getStack();
        if (stack.isEmpty() || !BackpackNbtParser.hasBackpackData(stack)) {
            BackpackHoverHandler.clearHovered();
            return;
        }

        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        BackpackHoverHandler.onBackpackHovered(stack, context, mouseX, mouseY, self);
    }

    // -------------------------------------------------------------------------
    // Suppress vanilla tooltip while our overlay is active
    // -------------------------------------------------------------------------

    @Inject(method = "drawMouseoverTooltip", at = @At("HEAD"), cancellable = true)
    private void islandtime$cancelTooltipForBackpack(GuiGraphics context,
                                                     int mouseX, int mouseY,
                                                     CallbackInfo ci) {
        if (FavouriteGuiViewer.isActive() || BackpackHoverHandler.hasBackpackHovered()) {
            ci.cancel();
        }
    }

    // -------------------------------------------------------------------------
    // Block mouse clicks / drag / scroll while frozen or viewer active
    // -------------------------------------------------------------------------

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void islandtime$blockClickWhileFrozen(@Coerce Object input,
                                                  boolean handled,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (islandtime$shouldBlockMouseInput()) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void islandtime$blockDragWhileViewerActive(@Coerce Object input,
                                                       double mouseX, double mouseY,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (islandtime$shouldBlockMouseInput()) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void islandtime$blockScrollWhileViewerActive(double mouseX, double mouseY,
                                                         double horizontalAmount, double verticalAmount,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (islandtime$shouldBlockMouseInput()) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    // -------------------------------------------------------------------------
    // Key handling
    // -------------------------------------------------------------------------

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void islandtime$beforeKeyPressed(KeyboardInput input,
                                             CallbackInfoReturnable<Boolean> cir) {
        long window = Minecraft.getInstance().getWindow().getHandle();

        // ── FavouriteGuiViewer active ──────────────────────────────────────
        if (FavouriteGuiViewer.isActive()) {
            boolean ePressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS;

            // Frozen backpack on top of the viewer — E/ESC unfreezes
            if (BackpackHoverHandler.isFrozen()) {
                if (ePressed || input.isEscape()) {
                    BackpackHoverHandler.requestUnfreeze();
                    cir.setReturnValue(true);
                    cir.cancel();
                    return;
                }
                // Remap focusedSlot so Firmament sees the backpack-overlay hovered item
                ItemStack overlayItem = BackpackHoverHandler.getOverlayHoveredItem();
                if (!overlayItem.isEmpty()) {
                    islandtime$savedSlot = focusedSlot;
                    focusedSlot = new FakeSlot(overlayItem);
                }
                // ── Do NOT cancel ──────────────────────────────────────────
                // Letting the event fall through means mods like Firmament
                // (which inject into keyPressed at their own priority) can see
                // the key with focusedSlot already remapped to the right item.
                // The TAIL inject below restores focusedSlot afterwards.
                return;
            }

            // Close the viewer on E or ESC
            if (ePressed || input.isEscape()) {
                FavouriteGuiViewer.close();
                cir.setReturnValue(true);
                cir.cancel();
                return;
            }

            // SHIFT → freeze/inspect a backpack that is currently being previewed
            boolean shiftPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT)  == GLFW.GLFW_PRESS
                                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
            if (shiftPressed && BackpackHoverHandler.isShowingOverlay()) {
                BackpackHoverHandler.freeze();
                cir.setReturnValue(true);
                cir.cancel();
                return;
            }

            // For all other keys (e.g. Firmament Ctrl+C copy):
            // focusedSlot was already remapped to the viewer-hovered item in the
            // render TAIL, so Firmament will read the correct item.
            // We deliberately do NOT cancel here — the event falls through to
            // any other mods that inject into keyPressed.
            // Vanilla AbstractContainerScreen slot operations are safe because the FakeSlot
            // blocks all mutations (canInsert/canTakeItems return false).
            return;
        }

        // ── Standalone frozen backpack (no viewer) ─────────────────────────
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

        // ── Normal backpack overlay — SHIFT freezes it ─────────────────────
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
    private void islandtime$afterKeyPressed(KeyboardInput input,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (islandtime$savedSlot != null) {
            focusedSlot = islandtime$savedSlot;
            islandtime$savedSlot = null;
        }
    }
}
