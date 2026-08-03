package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side viewer for saved container layouts.
 * Displays a read-only GUI overlay similar to BackpackHoverHandler but for saved containers.
 */
public class FavouriteGuiViewer {

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private static boolean active = false;
    private static String currentContainerName = null;
    private static FavouriteGuiManager.SavedContainer currentContainer = null;
    private static List<ItemStack> displayItems = null;
    private static int hoveredIndex = -1;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static boolean isActive() {
        return active;
    }

    /**
     * Open a saved container for viewing
     * @return true if the container was found and opened, false otherwise
     */
    public static boolean open(String name) {
        FavouriteGuiManager.SavedContainer container = FavouriteGuiManager.getSavedContainer(name);
        if (container == null) {
            return false;
        }

        currentContainerName = name;
        currentContainer = container;
        displayItems = deserializeItems(container);
        active = true;
        hoveredIndex = -1;

        // If there is no AbstractContainerScreen already open to render the overlay, open a
        // standalone screen so the viewer works "anytime" from chat or the world.
        // Deferred via client.execute() so the chat screen's own close logic
        // (which runs after the command returns) cannot overwrite our screen.
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof AbstractContainerScreen)) {
            client.execute(() -> client.setScreen(new FavouriteGuiScreen()));
        }

        return true;
    }

    /**
     * Close the viewer
     */
    public static void close() {
        active = false;
        currentContainerName = null;
        currentContainer = null;
        displayItems = null;
        hoveredIndex = -1;
    }

    /**
     * Get the currently hovered item for tooltip/interaction handling
     */
    public static ItemStack getHoveredItem() {
        if (displayItems == null || hoveredIndex < 0 || hoveredIndex >= displayItems.size()) {
            return ItemStack.EMPTY;
        }
        return displayItems.get(hoveredIndex);
    }

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    /**
     * Render the viewer overlay - called from a screen render method
     */
    public static void render(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (!active || currentContainer == null || displayItems == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        Font tr = client.font;

        // Darken the background
        int sw = client.getWindow().getGuiScaledWidth();
        int sh = client.getWindow().getGuiScaledHeight();
        context.fill(0, 0, sw, sh, 0x80000000);

        // Calculate layout
        int rows = (int) Math.ceil(currentContainer.size / 9.0);
        int cols = Math.min(currentContainer.size, 9);
        int gridW = cols * SLOT_SIZE;
        int gridH = rows * SLOT_SIZE;

        Component title = Component.literal(currentContainer.title).withStyle(ChatFormatting.WHITE);
        Component subtitle = Component.literal("Saved as: " + currentContainerName).withStyle(ChatFormatting.GRAY);
        Component hint = Component.literal("Press ESC or E to close").withStyle(ChatFormatting.DARK_GRAY);

        int titleW = tr.width(title);
        int subtitleW = tr.width(subtitle);
        int contentW = Math.max(gridW, Math.max(titleW, subtitleW));
        int panelW = contentW + PADDING * 2;

        int titleH = LINE_HEIGHT;
        int subtitleH = LINE_HEIGHT;
        int panelH = PADDING + titleH + subtitleH + SPACER + 1 + SPACER + gridH + SPACER + LINE_HEIGHT + PADDING;

        // Center the panel
        int px = (sw - panelW) / 2;
        int py = (sh - panelH) / 2;

        // Draw panel background
        drawPanelBackground(context, px, py, panelW, panelH);

        // Draw title
        int ty = py + PADDING;
        int titleX = px + (panelW - titleW) / 2;
        context.text(tr, title, titleX, ty, -1, true);
        ty += titleH;

        // Draw subtitle
        int subtitleX = px + (panelW - subtitleW) / 2;
        context.text(tr, subtitle, subtitleX, ty, -1, false);
        ty += subtitleH + SPACER;

        // Draw separator
        context.fill(px + PADDING, ty, px + panelW - PADDING, ty + 1, SEP_COLOR);
        ty += 1 + SPACER;

        // Draw grid
        int gridTop = ty;
        int gridLeft = px + (panelW - gridW) / 2;
        hoveredIndex = -1;

        for (int i = 0; i < currentContainer.size; i++) {
            int col = i % 9;
            int row = i / 9;
            int sx = gridLeft + col * SLOT_SIZE;
            int sy = gridTop + row * SLOT_SIZE;

            // Slot background
            context.fill(sx, sy, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_BG);

            // Draw item if present
            ItemStack item = i < displayItems.size() ? displayItems.get(i) : ItemStack.EMPTY;
            if (!item.isEmpty()) {
                context.item(item, sx, sy);
                context.itemDecorations(tr, item, sx, sy);
            }

            // Hover highlight
            if (mouseX >= sx && mouseX < sx + SLOT_SIZE
                    && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                hoveredIndex = i;
                context.fill(sx, sy, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_HOVER);
            }
        }

        // Draw hint text
        int hintY = gridTop + gridH + SPACER;
        int hintX = px + (panelW - tr.width(hint.getString())) / 2;
        context.text(tr, hint, hintX, hintY, -1, false);

        // Draw tooltip for hovered item — suppressed when BackpackHoverHandler is
        // showing its own overlay on top (it draws its own tooltip for the item
        // inside the backpack, and we don't want both stacked on screen).
        if (hoveredIndex >= 0 && hoveredIndex < displayItems.size()
                && !BackpackHoverHandler.hasBackpackHovered()) {
            ItemStack hovered = displayItems.get(hoveredIndex);
            if (!hovered.isEmpty()) {
                List<Component> tooltip = hovered.getTooltipLines(
                        Item.TooltipContext.of(client.level),
                        client.player,
                        client.options.advancedItemTooltips
                                ? TooltipFlag.Default.ADVANCED
                                : TooltipFlag.Default.NORMAL);
                context.tooltip(tr, tooltip, mouseX, mouseY);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Item deserialization
    // -------------------------------------------------------------------------

    private static List<ItemStack> deserializeItems(FavouriteGuiManager.SavedContainer container) {
        List<ItemStack> items = new ArrayList<>();
        Minecraft client = Minecraft.getInstance();

        // Initialize with empty stacks
        for (int i = 0; i < container.size; i++) {
            items.add(ItemStack.EMPTY);
        }

        // Deserialize saved items
        for (FavouriteGuiManager.SavedItem savedItem : container.items) {
            if (savedItem.slotIndex < 0 || savedItem.slotIndex >= container.size) {
                continue; // Skip invalid slot indices
            }

            try {
                RegistryAccess reg = client.getConnection() != null
                        ? client.getConnection().registryAccess()
                        : RegistryAccess.EMPTY;
                ItemStack stack = ItemStack.CODEC
                        .parse(RegistryOps.create(NbtOps.INSTANCE, reg), savedItem.nbt)
                        .result()
                        .orElse(ItemStack.EMPTY);

                if (!stack.isEmpty()) {
                    items.set(savedItem.slotIndex, stack);
                }
            } catch (Exception e) {
                System.err.println("[FavouriteGuiViewer] Failed to deserialize item at slot " + savedItem.slotIndex + ": " + e.getMessage());
            }
        }

        return items;
    }

    // -------------------------------------------------------------------------
    // Layout constants
    // -------------------------------------------------------------------------

    private static final int SLOT_SIZE = 18;
    private static final int SLOT_INNER = 16;
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int SPACER = 4;

    private static final int BG = 0xF0100010;
    private static final int BORDER = 0xFF28007F;
    private static final int SLOT_BG = 0x40000000;
    private static final int SLOT_HOVER = 0x60FFFFFF;
    private static final int SEP_COLOR = 0x44FFFFFF;

    // -------------------------------------------------------------------------
    // Panel background
    // -------------------------------------------------------------------------

    private static void drawPanelBackground(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        ctx.fill(x + 1, y, x + w - 1, y + h, BG);
        ctx.fill(x, y + 1, x + 1, y + h - 1, BG);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, BG);

        ctx.fill(x + 2, y + 1, x + w - 2, y + 2, BORDER);
        ctx.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, BORDER);
        ctx.fill(x + 1, y + 2, x + 2, y + h - 2, BORDER);
        ctx.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, BORDER);
    }
}
