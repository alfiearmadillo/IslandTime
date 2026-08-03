package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BackpackHoverHandler {

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private static ItemStack                          currentBackpack  = ItemStack.EMPTY;
    private static List<ItemStack>                    currentContents  = null;
    private static CompletableFuture<List<ItemStack>> parseFuture      = null;

    private static boolean frozen            = false;
    private static boolean pendingUnfreeze   = false;
    private static int     frozenMouseX      = 0;
    private static int     frozenMouseY      = 0;

    private static int lastMouseX = 0;
    private static int lastMouseY = 0;

    private static int overlayHoveredIndex = -1;

    /**
     * After unfreezing, suppress focusedSlot until the mouse moves away from
     * the position it was at when frozen. The anchor is frozenMouseX/Y because
     * the mouse is still physically at that position when the inspect closes
     * (the screen stays open; no cursor warp happens).
     *
     * The mixin TAIL checks this each frame and forces focusedSlot = null
     * while it's true, AFTER vanilla has already written its value.
     */
    private static boolean suppressFocusedSlot = false;
    private static int     suppressAnchorX     = 0;
    private static int     suppressAnchorY     = 0;

    // -------------------------------------------------------------------------
    // Public queries
    // -------------------------------------------------------------------------

    public static boolean hasBackpackHovered() {
        return parseFuture != null || currentContents != null || frozen;
    }

    public static boolean isShowingOverlay() {
        return currentContents != null && !frozen;
    }

    public static boolean isFrozen() {
        return frozen;
    }

    /**
     * Returns true if focusedSlot should be forced to null this render frame.
     * Automatically lifts suppression once mouseX/mouseY differs from the anchor.
     *
     * Called from the render TAIL inject after vanilla has written focusedSlot,
     * so returning true here causes the mixin to overwrite it back to null.
     */
    public static boolean isFocusedSlotSuppressed(int mouseX, int mouseY) {
        if (!suppressFocusedSlot) return false;
        if (mouseX != suppressAnchorX || mouseY != suppressAnchorY) {
            // Mouse has moved — lift suppression
            suppressFocusedSlot = false;
            return false;
        }
        return true;
    }

    public static ItemStack getOverlayHoveredItem() {
        if (currentContents == null
                || overlayHoveredIndex < 0
                || overlayHoveredIndex >= currentContents.size()) {
            return ItemStack.EMPTY;
        }
        return currentContents.get(overlayHoveredIndex);
    }

    // -------------------------------------------------------------------------
    // Called from AbstractContainerScreenMixin / FavouriteGuiScreen — render path
    // -------------------------------------------------------------------------

    /**
     * Overload used by FavouriteGuiScreen (not a AbstractContainerScreen) and any other
     * non-AbstractContainerScreen caller. The parentScreen parameter is unused internally,
     * so passing null is safe.
     */
    public static void onBackpackHovered(ItemStack backpackStack,
                                         GuiGraphicsExtractor context,
                                         int mouseX, int mouseY) {
        onBackpackHovered(backpackStack, context, mouseX, mouseY, null);
    }

    public static void onBackpackHovered(ItemStack backpackStack,
                                         GuiGraphicsExtractor context,
                                         int mouseX, int mouseY,
                                         AbstractContainerScreen<?> parentScreen) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if (backpackStack != currentBackpack) {
            currentBackpack = backpackStack;
            currentContents = null;
            overlayHoveredIndex = -1;
            if (parseFuture != null) parseFuture.cancel(false);
            parseFuture = BackpackNbtParser.parseAsync(backpackStack);
        }

        if (currentContents == null) {
            drawLoading(context, mouseX, mouseY);
            return;
        }

        drawOverlay(context, mouseX, mouseY);
    }

    public static void drawFrozen(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (!frozen || currentContents == null) return;
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        Minecraft client = Minecraft.getInstance();
        int sw = client.getWindow().getGuiScaledWidth();
        int sh = client.getWindow().getGuiScaledHeight();
        context.fill(0, 0, sw, sh, 0x80000000);

        drawOverlay(context, mouseX, mouseY);
    }

    public static void clearHovered() {
        if (frozen) return;
        currentBackpack = ItemStack.EMPTY;
        currentContents = null;
        overlayHoveredIndex = -1;
        if (parseFuture != null) { parseFuture.cancel(false); parseFuture = null; }
    }

    // -------------------------------------------------------------------------
    // Freeze / unfreeze
    // -------------------------------------------------------------------------

    public static void freeze() {
        if (currentContents == null) return;
        frozen       = true;
        frozenMouseX = lastMouseX;
        frozenMouseY = lastMouseY;
    }

    public static void requestUnfreeze() {
        pendingUnfreeze = true;
    }

    private static void doUnfreeze() {
        frozen          = false;
        pendingUnfreeze = false;

        // Anchor suppression to frozenMouseX/Y — that is exactly where the
        // mouse physically is when the player closes the inspect overlay
        // (the screen stays open, so no cursor warp occurs). focusedSlot will
        // be forced to null until the mouse physically moves away from here.
        suppressAnchorX     = frozenMouseX;
        suppressAnchorY     = frozenMouseY;
        suppressFocusedSlot = true;

        // Do NOT call clearHovered() here — the render TAIL will clean up
        // naturally once suppression ends and the cursor leaves the backpack.
    }

    // -------------------------------------------------------------------------
    // Tick — resolves pending unfreeze and parse future
    // -------------------------------------------------------------------------

    public static void tick(Minecraft client) {
        if (pendingUnfreeze) {
            doUnfreeze();
        }

        if (parseFuture != null && parseFuture.isDone()) {
            try {
                List<ItemStack> result = parseFuture.get();
                currentContents = (result != null) ? result : List.of();
            } catch (Exception e) {
                currentContents = List.of();
            }
            parseFuture = null;
        }
    }

    // -------------------------------------------------------------------------
    // Layout constants
    // -------------------------------------------------------------------------

    private static final int SLOT_SIZE   = 18;
    private static final int SLOT_INNER  = 16;
    private static final int COLS        = 9;
    private static final int PADDING     = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int SPACER      = 4;

    private static final int BG          = 0xF0100010;
    private static final int BORDER      = 0xFF28007F;
    private static final int SLOT_BG     = 0x40000000;
    private static final int SLOT_HOVER  = 0x60FFFFFF;
    private static final int SEP_COLOR   = 0x44FFFFFF;

    // -------------------------------------------------------------------------
    // Loading indicator
    // -------------------------------------------------------------------------

    private static void drawLoading(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        Font    tr     = client.font;
        String msg = "Loading...";
        int w = tr.width(msg) + PADDING * 2;
        int h = LINE_HEIGHT + PADDING * 2;

        int sw = client.getWindow().getGuiScaledWidth();
        int sh = client.getWindow().getGuiScaledHeight();
        int px = mouseX + 8;
        int py = mouseY - 16;
        if (px + w > sw - 4) px = mouseX - w - 4;
        if (py + h > sh - 4) py = sh - h - 4;
        if (py < 4) py = 4;

        drawPanelBackground(context, px, py, w, h);
        context.text(tr, msg, px + PADDING, py + PADDING, 0xFFAAAAAA, true);
    }

    // -------------------------------------------------------------------------
    // Main overlay draw
    // -------------------------------------------------------------------------

    private static void drawOverlay(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (currentContents == null) return;
        Minecraft client = Minecraft.getInstance();
        Font    tr     = client.font;

        List<Component> headerLines = currentBackpack.getTooltipLines(
                Item.TooltipContext.of(client.level),
                client.player,
                client.options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL);

        Component hintText = frozen
                ? Component.literal("E to close").withStyle(ChatFormatting.DARK_GRAY)
                : Component.literal("Press SHIFT to inspect").withStyle(ChatFormatting.DARK_GRAY);

        int rows   = (int) Math.ceil(currentContents.size() / (double) COLS);
        int gridW  = COLS * SLOT_SIZE;
        int gridH  = rows * SLOT_SIZE;

        int maxTextW = 0;
        for (Component line : headerLines) maxTextW = Math.max(maxTextW, tr.width(line));
        int contentW = Math.max(gridW, maxTextW);
        int panelW   = contentW + PADDING * 2;
        int headerH  = headerLines.size() * LINE_HEIGHT;
        int panelH   = PADDING + headerH + SPACER + 1 + SPACER + gridH + SPACER + LINE_HEIGHT + PADDING;

        int anchorX = frozen ? frozenMouseX : mouseX;
        int anchorY = frozen ? frozenMouseY : mouseY;

        int sw = client.getWindow().getGuiScaledWidth();
        int sh = client.getWindow().getGuiScaledHeight();
        int px = anchorX + 8;
        int py = anchorY - 16;
        if (px + panelW > sw - 4) px = anchorX - panelW - 4;
        if (py + panelH > sh - 4) py = sh - panelH - 4;
        if (py < 4) py = 4;

        drawPanelBackground(context, px, py, panelW, panelH);

        int ty = py + PADDING;
        for (Component line : headerLines) {
            context.text(tr, line, px + PADDING, ty, -1, true);
            ty += LINE_HEIGHT;
        }

        ty += SPACER;
        context.fill(px + PADDING, ty, px + panelW - PADDING, ty + 1, SEP_COLOR);
        ty += 1 + SPACER;

        int gridTop  = ty;
        int gridLeft = px + (panelW - gridW) / 2;
        overlayHoveredIndex = -1;

        for (int i = 0; i < currentContents.size(); i++) {
            int col = i % COLS;
            int row = i / COLS;
            int sx  = gridLeft + col * SLOT_SIZE;
            int sy  = gridTop  + row * SLOT_SIZE;

            context.fill(sx, sy, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_BG);

            ItemStack item = currentContents.get(i);
            if (!item.isEmpty()) {
                context.item(item, sx, sy);
                context.itemDecorations(tr, item, sx, sy);
            }

            if (mouseX >= sx && mouseX < sx + SLOT_SIZE
                    && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                overlayHoveredIndex = i;
                context.fill(sx, sy, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_HOVER);
            }
        }

        int hintY = gridTop + gridH + SPACER;
        int hintX = px + (panelW - tr.width(hintText.getString())) / 2;
        context.text(tr, hintText, hintX, hintY, -1, false);

        if (overlayHoveredIndex >= 0) {
            ItemStack hovered = currentContents.get(overlayHoveredIndex);
            if (!hovered.isEmpty()) {
                List<Component> tip = hovered.getTooltipLines(
                        Item.TooltipContext.of(client.level),
                        client.player,
                        client.options.advancedItemTooltips
                                ? TooltipFlag.Default.ADVANCED
                                : TooltipFlag.Default.NORMAL);
                context.tooltip(tr, tip, mouseX, mouseY);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Panel background
    // -------------------------------------------------------------------------

    private static void drawPanelBackground(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        ctx.fill(x + 1, y,     x + w - 1, y + h,     BG);
        ctx.fill(x,     y + 1, x + 1,     y + h - 1, BG);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, BG);

        ctx.fill(x + 2,     y + 1,     x + w - 2, y + 2,         BORDER);
        ctx.fill(x + 2,     y + h - 2, x + w - 2, y + h - 1,     BORDER);
        ctx.fill(x + 1,     y + 2,     x + 2,     y + h - 2,     BORDER);
        ctx.fill(x + w - 2, y + 2,     x + w - 1, y + h - 2,     BORDER);
    }
}
