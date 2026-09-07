package org.example.islandtime;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * Draws the Critter Safari tracker widget, listing every unique critter of the current run
 * grouped by biome with caught/uncaught state and a caught/total progress counter.
 *
 * <p>The widget is only drawn while the player is inside the Critter Safari, using the same tab-list
 * area detection the block highlight feature uses ({@link BlockHighlightRenderer#detectCurrentArea()}).
 * Its position is stored as a screen percentage in the MoulConfig config so it stays put at any
 * resolution, and can be dragged around in {@link CritterSafariHudScreen}.
 */
public class CritterSafariHud {

    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int COLUMN_GAP = 8;

    private static final int BG_COLOR = 0xC0000000;
    private static final int BORDER_COLOR = 0xFF28007F;

    /**
     * Keywords matched against the area reported by the tab list. Hypixel shortens the island name
     * (the area line reads "Safari" rather than "Critter Safari"), and inside the instance the line
     * can show the biome sub-area instead, so the check is a substring match over all of them.
     * The "Critter Safari Entrance" in Torrhus Canyon is deliberately not part of the instance.
     */
    private static final List<String> SAFARI_AREA_KEYWORDS = List.of(
            "safari", "critter", "forest biome", "cavern biome", "icy biome", "icy caves", "haunted biome");

    private CritterSafariHud() {}

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers the widget as a HUD element and hooks the chat listener that feeds
     * {@link CritterSafariTracker} (catches, and the Safari Manager's new-run line).
     */
    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                CritterSafariTracker.onChatMessage(message.getString());
            }
        });

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("islandtime", "critter_safari"),
                (graphics, deltaTracker) -> renderHud(graphics));
    }

    // -------------------------------------------------------------------------
    // Visibility
    // -------------------------------------------------------------------------

    /**
     * Returns true when the tab list reports an area belonging to the Critter Safari.
     */
    public static boolean isInSafariArea() {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config != null && !config.critterSafari.onlyInSafari) return true;

        String area = BlockHighlightRenderer.detectCurrentArea();
        if (area == null) return false;
        String normalized = area.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("entrance")) return false;
        for (String keyword : SAFARI_AREA_KEYWORDS) {
            if (normalized.contains(keyword)) return true;
        }
        return false;
    }

    private static boolean isEnabled() {
        IslandTimeConfig config = ConfigManager.getConfig();
        return config != null && config.critterSafari.enabled;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * Called from the registered HUD element every frame.
     */
    public static void renderHud(GuiGraphicsExtractor context) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        if (client.options != null && client.options.hideGui) return;
        // The move screen draws its own preview, so don't double-draw underneath it.
        if (client.screen instanceof CritterSafariHudScreen) return;
        if (!isEnabled() || !isInSafariArea()) return;

        render(context, getX(), getY());
    }

    /**
     * Draws the widget with its top-left corner at the given position.
     */
    public static void render(GuiGraphicsExtractor context, int x, int y) {
        Font font = Minecraft.getInstance().font;
        List<String> biomes = CritterSafariTracker.getBiomes();

        int width = getWidth();
        int height = getHeight();

        context.fill(x, y, x + width, y + height, BG_COLOR);
        context.fill(x, y, x + width, y + 1, BORDER_COLOR);
        context.fill(x, y + height - 1, x + width, y + height, BORDER_COLOR);
        context.fill(x, y, x + 1, y + height, BORDER_COLOR);
        context.fill(x + width - 1, y, x + width, y + height, BORDER_COLOR);

        Component title = Component.literal("Critter Safari ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(
                                CritterSafariTracker.getCaughtCount() + "/" + CritterSafariTracker.getTotalCount())
                        .withStyle(CritterSafariTracker.getCaughtCount() == CritterSafariTracker.getTotalCount()
                                ? ChatFormatting.GREEN
                                : ChatFormatting.YELLOW));
        context.text(font, title, x + PADDING, y + PADDING, -1, true);

        int columnX = x + PADDING;
        int topY = y + PADDING + LINE_HEIGHT + 2;
        for (String biome : biomes) {
            int rowY = topY;
            context.text(font, Component.literal(biome).withStyle(ChatFormatting.AQUA),
                    columnX, rowY, -1, true);
            rowY += LINE_HEIGHT;

            for (String critter : CritterSafariTracker.getCritters(biome)) {
                boolean isCaught = CritterSafariTracker.isCaught(critter);
                Component line = Component.literal((isCaught ? "\u2714 " : "\u2718 ") + critter)
                        .withStyle(isCaught ? ChatFormatting.GREEN : ChatFormatting.GRAY);
                context.text(font, line, columnX, rowY, -1, true);
                rowY += LINE_HEIGHT;
            }

            columnX += columnWidth(font, biome) + COLUMN_GAP;
        }
    }

    /**
     * Widget width in scaled GUI pixels.
     */
    public static int getWidth() {
        Font font = Minecraft.getInstance().font;
        int width = 0;
        for (String biome : CritterSafariTracker.getBiomes()) {
            width += columnWidth(font, biome) + COLUMN_GAP;
        }
        width -= COLUMN_GAP;
        int titleWidth = font.width(Component.literal("Critter Safari "
                + CritterSafariTracker.getCaughtCount() + "/" + CritterSafariTracker.getTotalCount()));
        return Math.max(width, titleWidth) + PADDING * 2;
    }

    /**
     * Widget height in scaled GUI pixels.
     */
    public static int getHeight() {
        int rows = 0;
        for (String biome : CritterSafariTracker.getBiomes()) {
            rows = Math.max(rows, CritterSafariTracker.getCritters(biome).size());
        }
        // title + biome header + longest critter column
        return PADDING * 2 + LINE_HEIGHT * (rows + 2) + 2;
    }

    private static int columnWidth(Font font, String biome) {
        int width = font.width(Component.literal(biome));
        for (String critter : CritterSafariTracker.getCritters(biome)) {
            width = Math.max(width, font.width(Component.literal("\u2714 " + critter)));
        }
        return width;
    }

    // -------------------------------------------------------------------------
    // Position (stored as a percentage of the free screen space)
    // -------------------------------------------------------------------------

    public static int getX() {
        Minecraft client = Minecraft.getInstance();
        int free = Math.max(0, client.getWindow().getGuiScaledWidth() - getWidth());
        return Math.round(free * getXPercent() / 100.0f);
    }

    public static int getY() {
        Minecraft client = Minecraft.getInstance();
        int free = Math.max(0, client.getWindow().getGuiScaledHeight() - getHeight());
        return Math.round(free * getYPercent() / 100.0f);
    }

    public static int getXPercent() {
        IslandTimeConfig config = ConfigManager.getConfig();
        return config == null ? 50 : clampPercent(config.critterSafari.hudX);
    }

    public static int getYPercent() {
        IslandTimeConfig config = ConfigManager.getConfig();
        return config == null ? 30 : clampPercent(config.critterSafari.hudY);
    }

    /**
     * Stores a new widget position, converting screen pixels back into percentages.
     */
    public static void setPosition(int x, int y) {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null) return;
        Minecraft client = Minecraft.getInstance();
        int freeX = Math.max(1, client.getWindow().getGuiScaledWidth() - getWidth());
        int freeY = Math.max(1, client.getWindow().getGuiScaledHeight() - getHeight());
        config.critterSafari.hudX = clampPercent(Math.round(x * 100.0f / freeX));
        config.critterSafari.hudY = clampPercent(Math.round(y * 100.0f / freeY));
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
