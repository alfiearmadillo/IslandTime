package org.example.islandtime;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

        // If there is no HandledScreen already open to render the overlay, open a
        // standalone screen so the viewer works "anytime" from chat or the world.
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen)) {
            client.setScreen(new FavouriteGuiScreen());
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
    public static void render(DrawContext context, int mouseX, int mouseY) {
        if (!active || currentContainer == null || displayItems == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer tr = client.textRenderer;

        // Darken the background
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        context.fill(0, 0, sw, sh, 0x80000000);

        // Calculate layout
        int rows = (int) Math.ceil(currentContainer.size / 9.0);
        int cols = Math.min(currentContainer.size, 9);
        int gridW = cols * SLOT_SIZE;
        int gridH = rows * SLOT_SIZE;

        Text title = Text.literal(currentContainer.title).formatted(Formatting.WHITE);
        Text subtitle = Text.literal("Saved as: " + currentContainerName).formatted(Formatting.GRAY);
        Text hint = Text.literal("Press ESC or E to close").formatted(Formatting.DARK_GRAY);

        int titleW = tr.getWidth(title);
        int subtitleW = tr.getWidth(subtitle);
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
        context.drawText(tr, title, titleX, ty, -1, true);
        ty += titleH;

        // Draw subtitle
        int subtitleX = px + (panelW - subtitleW) / 2;
        context.drawText(tr, subtitle, subtitleX, ty, -1, false);
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
                context.drawItem(item, sx, sy);
                context.drawStackOverlay(tr, item, sx, sy);
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
        int hintX = px + (panelW - tr.getWidth(hint.getString())) / 2;
        context.drawText(tr, hint, hintX, hintY, -1, false);

        // Draw tooltip for hovered item
        if (hoveredIndex >= 0 && hoveredIndex < displayItems.size()) {
            ItemStack hovered = displayItems.get(hoveredIndex);
            if (!hovered.isEmpty()) {
                List<Text> tooltip = hovered.getTooltip(
                        Item.TooltipContext.create(client.world),
                        client.player,
                        client.options.advancedItemTooltips
                                ? TooltipType.ADVANCED
                                : TooltipType.BASIC);
                context.drawTooltip(tr, tooltip, mouseX, mouseY);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Item deserialization
    // -------------------------------------------------------------------------

    private static List<ItemStack> deserializeItems(FavouriteGuiManager.SavedContainer container) {
        List<ItemStack> items = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();

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
                NbtCompound nbt = StringNbtReader.parse(savedItem.nbtData);
                ItemStack stack = ItemStack.CODEC
                        .parse(client.getNetworkHandler() != null
                                ? client.getNetworkHandler().getRegistryManager().getOps(NbtOps.INSTANCE)
                                : DynamicRegistryManager.EMPTY.getOps(NbtOps.INSTANCE),
                                nbt)
                        .result()
                        .orElse(ItemStack.EMPTY);

                if (!stack.isEmpty()) {
                    items.set(savedItem.slotIndex, stack);
                }
            } catch (CommandSyntaxException e) {
                System.err.println("[FavouriteGuiViewer] Failed to parse NBT for item at slot " + savedItem.slotIndex + ": " + e.getMessage());
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

    private static void drawPanelBackground(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x + 1, y, x + w - 1, y + h, BG);
        ctx.fill(x, y + 1, x + 1, y + h - 1, BG);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, BG);

        ctx.fill(x + 2, y + 1, x + w - 2, y + 2, BORDER);
        ctx.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, BORDER);
        ctx.fill(x + 1, y + 2, x + 2, y + h - 2, BORDER);
        ctx.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, BORDER);
    }
}
