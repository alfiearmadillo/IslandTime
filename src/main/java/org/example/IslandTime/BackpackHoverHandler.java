package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class BackpackHoverHandler {

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private static ItemStack       currentBackpack = ItemStack.EMPTY;
    private static List<ItemStack> currentContents = null;

    private static boolean pendingScreenOpen = false;
    private static Screen  pendingParent     = null;

    private static int overlayHoveredIndex = -1;

    // -------------------------------------------------------------------------
    // Public queries
    // -------------------------------------------------------------------------

    public static boolean hasBackpackHovered() {
        return currentContents != null;
    }

    public static boolean isShowingOverlay() {
        return currentContents != null && !isShiftDown();
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
    // Called from HandledScreenMixin
    // -------------------------------------------------------------------------

    public static void onBackpackHovered(ItemStack backpackStack,
                                         DrawContext context,
                                         int mouseX, int mouseY,
                                         HandledScreen<?> parentScreen) {
        if (backpackStack != currentBackpack) {
            currentBackpack = backpackStack;
            currentContents = BackpackNbtParser.parse(backpackStack);
        }
        if (currentContents == null) return;

        if (isShiftDown()) {
            pendingScreenOpen = true;
            pendingParent     = parentScreen;
            return;
        }

        drawOverlay(context, mouseX, mouseY);
    }

    public static void clearHovered() {
        currentBackpack     = ItemStack.EMPTY;
        currentContents     = null;
        overlayHoveredIndex = -1;
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public static void tick(MinecraftClient client) {
        if (pendingScreenOpen && currentContents != null) {
            pendingScreenOpen = false;
            Screen parent = pendingParent;
            pendingParent = null;
            client.setScreen(new BackpackPreviewScreen(List.copyOf(currentContents), parent));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isShiftDown() {
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT)  == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private static final int SLOT_SIZE   = 18;
    private static final int SLOT_INNER  = 16;
    private static final int COLS        = 9;
    private static final int PADDING     = 8;
    private static final int LINE_HEIGHT = 10;
    private static final int SPACER      = 4;

    // Vanilla tooltip colours
    private static final int BG          = 0xF0100010;
    private static final int BORDER_TOP  = 0x505000FF;
    private static final int BORDER_BOT  = 0x5028007F;
    private static final int SLOT_BG     = 0x40000000;
    private static final int SLOT_HOVER  = 0x60FFFFFF;
    private static final int SEP_COLOR   = 0x44FFFFFF;

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    private static void drawOverlay(DrawContext context, int mouseX, int mouseY) {
        if (currentContents == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer    tr     = client.textRenderer;

        // --- Collect header lines from the real tooltip ----------------------
        // getTooltip() returns the full list vanilla + mods would render, so
        // colours and mod-injected lines (prices, SkyBlock id, etc.) are preserved.
        List<Text> headerLines = currentBackpack.getTooltip(
                Item.TooltipContext.create(client.world),
                client.player,
                client.options.advancedItemTooltips ? TooltipType.ADVANCED : TooltipType.BASIC);

        Text hintText = Text.literal("Press SHIFT to inspect")
                            .formatted(Formatting.DARK_GRAY);

        // --- Panel dimensions -----------------------------------------------
        // Width: always sized to fit exactly 9 item columns + padding.
        // We intentionally ignore text widths to avoid runaway-width bugs.
        int rows   = (int) Math.ceil(currentContents.size() / (double) COLS);
        int gridW  = COLS * SLOT_SIZE;
        int gridH  = rows * SLOT_SIZE;

        // Panel must be wide enough for both the header text and the item grid.
        int maxTextW = 0;
        for (Text line : headerLines) maxTextW = Math.max(maxTextW, tr.getWidth(line));
        int contentW = Math.max(gridW, maxTextW);
        int panelW   = contentW + PADDING * 2;

        // Height: header lines + separator + grid + hint + padding
        int headerH = headerLines.size() * LINE_HEIGHT;
        int panelH  = PADDING
                    + headerH
                    + SPACER
                    + 1           // separator line
                    + SPACER
                    + gridH
                    + SPACER
                    + LINE_HEIGHT // hint
                    + PADDING;

        // --- Position (centered horizontally on cursor; clamp to screen edges) ---
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        int px = mouseX + 14;
        int py = mouseY - 12;
        if (px + panelW > sw - 4) px = mouseX - panelW - 4;
        if (py + panelH > sh - 4) py = sh - panelH - 4;
        if (py < 4) py = 4;

        // --- Vanilla-style background + border ------------------------------
        // 1-px outer border (solid top colour so there's no transparent gap)
        context.fill(px - 1, py - 1, px + panelW + 1, py + panelH + 1, BORDER_TOP);
        // Dark inner background
        context.fill(px, py, px + panelW, py + panelH, BG);
        // Left border gradient strip
        context.fillGradient(px, py + 1, px + 1, py + panelH - 1, BORDER_TOP, BORDER_BOT);
        // Right border gradient strip
        context.fillGradient(px + panelW - 1, py + 1, px + panelW, py + panelH - 1, BORDER_TOP, BORDER_BOT);
        // Top & bottom solid border lines
        context.fill(px + 1, py,            px + panelW - 1, py + 1,          BORDER_TOP);
        context.fill(px + 1, py + panelH - 1, px + panelW - 1, py + panelH, BORDER_BOT);

        // --- Header lines (name + rarity) -----------------------------------
        int ty = py + PADDING;
        for (Text line : headerLines) {
            context.drawText(tr, line, px + PADDING, ty, -1, true);
            ty += LINE_HEIGHT;
        }

        // --- Separator line -------------------------------------------------
        ty += SPACER;
        context.fill(px + PADDING, ty, px + panelW - PADDING, ty + 1, SEP_COLOR);
        ty += 1 + SPACER;

        // --- Item grid ------------------------------------------------------
        int gridTop = ty;
        int gridLeft = px + (panelW - gridW) / 2;  // centre grid within panel
        overlayHoveredIndex = -1;

        for (int i = 0; i < currentContents.size(); i++) {
            int col = i % COLS;
            int row = i / COLS;
            int sx  = gridLeft + col * SLOT_SIZE;
            int sy  = gridTop  + row * SLOT_SIZE;

            context.fill(sx, sy, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_BG);

            ItemStack item = currentContents.get(i);
            if (!item.isEmpty()) {
                context.drawItem(item, sx, sy);
                context.drawStackOverlay(tr, item, sx, sy);
            }

            if (mouseX >= sx && mouseX < sx + SLOT_INNER
                    && mouseY >= sy && mouseY < sy + SLOT_INNER) {
                overlayHoveredIndex = i;
                context.fill(sx, sy, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_HOVER);
            }
        }

        // --- Hint line ------------------------------------------------------
        int hintY = gridTop + gridH + SPACER;
        int hintX = px + (panelW - tr.getWidth(hintText.getString())) / 2;
        context.drawText(tr, hintText, hintX, hintY, -1, false);

        // --- Inner item tooltip (vanilla List<Text> drawTooltip) ------------
        if (overlayHoveredIndex >= 0) {
            ItemStack hovered = currentContents.get(overlayHoveredIndex);
            if (!hovered.isEmpty()) {
                List<Text> tip = hovered.getTooltip(
                        Item.TooltipContext.create(client.world),
                        client.player,
                        client.options.advancedItemTooltips
                                ? TooltipType.ADVANCED
                                : TooltipType.BASIC);
                // Draw offset from cursor so it doesn't obscure the hovered cell
                context.drawTooltip(tr, tip, mouseX + 18, mouseY + 4);
            }
        }
    }
}
