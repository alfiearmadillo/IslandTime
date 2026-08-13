package org.example.islandtime.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import org.example.islandtime.ConfigManager;
import org.example.islandtime.IslandTimeCommand;
import org.example.islandtime.IslandTimeConfig;
import org.example.islandtime.BackpackNbtParser;
import org.example.islandtime.BackpackHoverHandler;
import org.example.islandtime.FakeSlot;
import org.example.islandtime.FavouriteGuiViewer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractContainerScreen.class, priority = 1100)
public class HandledScreenMixin {

    @Shadow @Nullable protected Slot hoveredSlot;

    @Unique @Nullable private Slot islandtime$savedSlot = null;

    @Unique
    private static boolean islandtime$shouldBlockMouseInput() {
        return FavouriteGuiViewer.isActive() || BackpackHoverHandler.isFrozen();
    }

    // -------------------------------------------------------------------------
    // Render TAIL — runs AFTER vanilla has written hoveredSlot.
    // -------------------------------------------------------------------------

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void islandtime$afterRender(GuiGraphicsExtractor context,
                                        int mouseX, int mouseY,
                                        float delta,
                                        CallbackInfo ci) {

        // ── FavouriteGuiViewer active (overlay on top of a AbstractContainerScreen) ──
        if (FavouriteGuiViewer.isActive()) {
            FavouriteGuiViewer.render(context, mouseX, mouseY);

            ItemStack viewerHovered = FavouriteGuiViewer.getHoveredItem();

            // Remap hoveredSlot so keybinding mods (e.g. Firmament) read the
            // correct item rather than whatever happens to be under the cursor
            // in the underlying AbstractContainerScreen.
            hoveredSlot = viewerHovered.isEmpty() ? null : new FakeSlot(viewerHovered);

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
            hoveredSlot = null;
            BackpackHoverHandler.drawFrozen(context, mouseX, mouseY);
            return;
        }

        // ── Suppression: mouse hasn't moved since unfreeze ─────────────────
        if (BackpackHoverHandler.isFocusedSlotSuppressed(mouseX, mouseY)) {
            hoveredSlot = null;
            return;
        }

        // ── Normal backpack hover ──────────────────────────────────────────
        if (hoveredSlot == null) {
            BackpackHoverHandler.clearHovered();
            return;
        }

        ItemStack stack = hoveredSlot.getItem();
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

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void islandtime$cancelTooltipForBackpack(GuiGraphicsExtractor context,
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
    private void islandtime$blockClickWhileFrozen(MouseButtonEvent event,
                                                  boolean doubleClick,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (islandtime$shouldBlockMouseInput()) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void islandtime$blockDragWhileViewerActive(MouseButtonEvent event,
                                                       double dx, double dy,
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
    private void islandtime$beforeKeyPressed(KeyEvent event,
                                             CallbackInfoReturnable<Boolean> cir) {
        long window = Minecraft.getInstance().getWindow().handle();

        // ── Copy Skin from hovered item keybind ──────────────────────────────
        IslandTimeConfig cfg = ConfigManager.getConfig();
        if (cfg != null && cfg.misc.copySkinKeybindEnabled) {
            int copySkinKey = islandtime$parseKeyName(cfg.misc.copySkinKeybind);
            if (copySkinKey != GLFW.GLFW_KEY_UNKNOWN && event.key() == copySkinKey) {
                Slot slot = this.hoveredSlot;
                if (slot != null && !slot.getItem().isEmpty()) {
                    IslandTimeCommand.copySkinFromItem(Minecraft.getInstance(), slot.getItem());
                    cir.setReturnValue(true);
                    cir.cancel();
                    return;
                }
            }
        }

        // ── FavouriteGuiViewer active ──────────────────────────────────────
        if (FavouriteGuiViewer.isActive()) {
            boolean ePressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS;

            // Frozen backpack on top of the viewer — E/ESC unfreezes
            if (BackpackHoverHandler.isFrozen()) {
                if (ePressed || event.key() == GLFW.GLFW_KEY_ESCAPE) {
                    BackpackHoverHandler.requestUnfreeze();
                    cir.setReturnValue(true);
                    cir.cancel();
                    return;
                }
                // Remap hoveredSlot so Firmament sees the backpack-overlay hovered item
                ItemStack overlayItem = BackpackHoverHandler.getOverlayHoveredItem();
                if (!overlayItem.isEmpty()) {
                    islandtime$savedSlot = hoveredSlot;
                    hoveredSlot = new FakeSlot(overlayItem);
                }
                // ── Do NOT cancel ──────────────────────────────────────────
                // Letting the event fall through means mods like Firmament
                // (which inject into keyPressed at their own priority) can see
                // the key with hoveredSlot already remapped to the right item.
                // The TAIL inject below restores hoveredSlot afterwards.
                return;
            }

            // Close the viewer on E or ESC
            if (ePressed || event.key() == GLFW.GLFW_KEY_ESCAPE) {
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
            // hoveredSlot was already remapped to the viewer-hovered item in the
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
            if (ePressed || event.key() == GLFW.GLFW_KEY_ESCAPE) {
                BackpackHoverHandler.requestUnfreeze();
                cir.setReturnValue(true);
                cir.cancel();
                return;
            }

            ItemStack overlayItem = BackpackHoverHandler.getOverlayHoveredItem();
            if (!overlayItem.isEmpty()) {
                islandtime$savedSlot = hoveredSlot;
                hoveredSlot = new FakeSlot(overlayItem);
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
            islandtime$savedSlot = hoveredSlot;
            hoveredSlot = new FakeSlot(overlayItem);
        }
    }

    @Inject(method = "keyPressed", at = @At("TAIL"))
    private void islandtime$afterKeyPressed(KeyEvent event,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (islandtime$savedSlot != null) {
            hoveredSlot = islandtime$savedSlot;
            islandtime$savedSlot = null;
        }
    }

    @Unique
    private static int islandtime$parseKeyName(String keyName) {
        if (keyName == null || keyName.isBlank()) return GLFW.GLFW_KEY_UNKNOWN;
        // Expect format like "key.keyboard.v" — extract the last part
        String name = keyName.trim();
        String prefix = "key.keyboard.";
        if (name.startsWith(prefix)) {
            name = name.substring(prefix.length());
        }
        // Single letter/digit
        if (name.length() == 1) {
            char c = Character.toUpperCase(name.charAt(0));
            if (c >= 'A' && c <= 'Z') return GLFW.GLFW_KEY_A + (c - 'A');
            if (c >= '0' && c <= '9') return GLFW.GLFW_KEY_0 + (c - '0');
        }
        // Named keys
        return switch (name.toLowerCase()) {
            case "space" -> GLFW.GLFW_KEY_SPACE;
            case "left.shift", "left_shift" -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case "right.shift", "right_shift" -> GLFW.GLFW_KEY_RIGHT_SHIFT;
            case "left.control", "left_control" -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case "right.control", "right_control" -> GLFW.GLFW_KEY_RIGHT_CONTROL;
            case "left.alt", "left_alt" -> GLFW.GLFW_KEY_LEFT_ALT;
            case "right.alt", "right_alt" -> GLFW.GLFW_KEY_RIGHT_ALT;
            case "tab" -> GLFW.GLFW_KEY_TAB;
            case "enter" -> GLFW.GLFW_KEY_ENTER;
            case "backspace" -> GLFW.GLFW_KEY_BACKSPACE;
            case "insert" -> GLFW.GLFW_KEY_INSERT;
            case "delete" -> GLFW.GLFW_KEY_DELETE;
            case "home" -> GLFW.GLFW_KEY_HOME;
            case "end" -> GLFW.GLFW_KEY_END;
            case "page.up", "page_up" -> GLFW.GLFW_KEY_PAGE_UP;
            case "page.down", "page_down" -> GLFW.GLFW_KEY_PAGE_DOWN;
            case "f1" -> GLFW.GLFW_KEY_F1;
            case "f2" -> GLFW.GLFW_KEY_F2;
            case "f3" -> GLFW.GLFW_KEY_F3;
            case "f4" -> GLFW.GLFW_KEY_F4;
            case "f5" -> GLFW.GLFW_KEY_F5;
            case "f6" -> GLFW.GLFW_KEY_F6;
            case "f7" -> GLFW.GLFW_KEY_F7;
            case "f8" -> GLFW.GLFW_KEY_F8;
            case "f9" -> GLFW.GLFW_KEY_F9;
            case "f10" -> GLFW.GLFW_KEY_F10;
            case "f11" -> GLFW.GLFW_KEY_F11;
            case "f12" -> GLFW.GLFW_KEY_F12;
            case "comma", "," -> GLFW.GLFW_KEY_COMMA;
            case "period", "." -> GLFW.GLFW_KEY_PERIOD;
            case "slash", "/" -> GLFW.GLFW_KEY_SLASH;
            case "semicolon", ";" -> GLFW.GLFW_KEY_SEMICOLON;
            case "apostrophe", "'" -> GLFW.GLFW_KEY_APOSTROPHE;
            case "left.bracket", "left_bracket", "[" -> GLFW.GLFW_KEY_LEFT_BRACKET;
            case "right.bracket", "right_bracket", "]" -> GLFW.GLFW_KEY_RIGHT_BRACKET;
            case "backslash", "\\" -> GLFW.GLFW_KEY_BACKSLASH;
            case "grave.accent", "grave_accent", "`" -> GLFW.GLFW_KEY_GRAVE_ACCENT;
            case "minus", "-" -> GLFW.GLFW_KEY_MINUS;
            case "equal", "=" -> GLFW.GLFW_KEY_EQUAL;
            default -> GLFW.GLFW_KEY_UNKNOWN;
        };
    }
}
