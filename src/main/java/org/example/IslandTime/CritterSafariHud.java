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

import java.util.ArrayList;
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

    /** How long a caught critter stays on the list, fading out, before it disappears. */
    private static final long FADE_MILLIS = 1000L;

    private static final int COLOR_UNCAUGHT = 0xFFAAAAAA;
    private static final int COLOR_ESCAPED = 0xFFFF5555;
    private static final int COLOR_CAUGHT = 0x55FF55;

    private static final int MIN_SCALE = 50;
    private static final int MAX_SCALE = 300;

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
                CritterSafariAnnouncer.pollAndSend();
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
     * Draws the widget with its top-left corner at the given position, honouring the widget scale.
     */
    public static void render(GuiGraphicsExtractor context, int x, int y) {
        float scale = getScale();
        context.pose().pushMatrix();
        context.pose().translate((float) x, (float) y);
        context.pose().scale(scale, scale);
        renderUnscaled(context);
        context.pose().popMatrix();
    }

    /**
     * Draws the widget at the origin in unscaled coordinates. Only critters that are still missing
     * are listed, so the widget shrinks as the run progresses.
     */
    private static void renderUnscaled(GuiGraphicsExtractor context) {
        Font font = Minecraft.getInstance().font;

        int width = getBaseWidth();
        int height = getBaseHeight();

        context.fill(0, 0, width, height, BG_COLOR);
        context.fill(0, 0, width, 1, BORDER_COLOR);
        context.fill(0, height - 1, width, height, BORDER_COLOR);
        context.fill(0, 0, 1, height, BORDER_COLOR);
        context.fill(width - 1, 0, width, height, BORDER_COLOR);

        Component title = title();
        context.text(font, title, (width - font.width(title)) / 2, PADDING, -1, true);

        int columnX = PADDING;
        int topY = PADDING + LINE_HEIGHT + 2;
        for (String biome : remainingBiomes()) {
            List<String> remaining = remainingCritters(biome);
            int rowY = topY;
            context.text(font, Component.literal(biome).withStyle(ChatFormatting.AQUA),
                    columnX, rowY, -1, true);
            rowY += LINE_HEIGHT;

            for (String critter : remaining) {
                int color = critterColor(critter);
                if (color != 0) {
                    context.text(font, Component.literal(critter), columnX, rowY, color, true);
                }
                rowY += LINE_HEIGHT;
            }

            columnX += columnWidth(font, biome, remaining) + COLUMN_GAP;
        }
    }

    /**
     * Colour for a critter row: red once it has broken out of a capsule, and a green that fades to
     * nothing over {@link #FADE_MILLIS} once it is caught. Returns 0 when the row has fully faded.
     */
    private static int critterColor(String critter) {
        long caughtTime = CritterSafariTracker.getCaughtTime(critter);
        if (caughtTime > 0) {
            long elapsed = System.currentTimeMillis() - caughtTime;
            if (elapsed >= FADE_MILLIS) return 0;
            int alpha = Math.min(255, (int) (255 * (1.0f - elapsed / (float) FADE_MILLIS)));
            if (alpha <= 0) return 0;
            return (alpha << 24) | COLOR_CAUGHT;
        }
        return CritterSafariTracker.hasEscaped(critter) ? COLOR_ESCAPED : COLOR_UNCAUGHT;
    }

    /** True while a caught critter is still fading out and therefore still occupies a row. */
    private static boolean isFading(String critter) {
        long caughtTime = CritterSafariTracker.getCaughtTime(critter);
        return caughtTime > 0 && System.currentTimeMillis() - caughtTime < FADE_MILLIS;
    }

    /** Header component: the name plus a centered caught/total counter. */
    private static Component title() {
        int caught = CritterSafariTracker.getCaughtCount();
        int total = CritterSafariTracker.getTotalCount();
        return Component.literal("Critter Safari ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(caught + "/" + total)
                        .withStyle(caught == total ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
    }

    /** Biomes that still have at least one uncaught critter. */
    private static List<String> remainingBiomes() {
        List<String> biomes = new ArrayList<>();
        for (String biome : CritterSafariTracker.getBiomes()) {
            if (!remainingCritters(biome).isEmpty()) biomes.add(biome);
        }
        return biomes;
    }

    /** Critters of the given biome still to catch, plus any that are still fading out. */
    private static List<String> remainingCritters(String biome) {
        List<String> remaining = new ArrayList<>();
        for (String critter : CritterSafariTracker.getCritters(biome)) {
            if (!CritterSafariTracker.isCaught(critter) || isFading(critter)) remaining.add(critter);
        }
        return remaining;
    }

    /** Scale factor applied to the whole widget. */
    public static float getScale() {
        IslandTimeConfig config = ConfigManager.getConfig();
        return (config == null ? 100 : clampScale(config.critterSafari.hudScale)) / 100.0f;
    }

    public static int getScalePercent() {
        IslandTimeConfig config = ConfigManager.getConfig();
        return config == null ? 100 : clampScale(config.critterSafari.hudScale);
    }

    /** Stores a new widget scale, clamped to the range offered by the config slider. */
    public static void setScalePercent(int percent) {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null) return;
        config.critterSafari.hudScale = clampScale(percent);
    }

    private static int clampScale(int percent) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, percent));
    }

    /**
     * Widget width in scaled GUI pixels.
     */
    public static int getWidth() {
        return Math.round(getBaseWidth() * getScale());
    }

    /**
     * Widget height in scaled GUI pixels.
     */
    public static int getHeight() {
        return Math.round(getBaseHeight() * getScale());
    }

    /** Widget width before the scale factor is applied. */
    private static int getBaseWidth() {
        Font font = Minecraft.getInstance().font;
        int width = 0;
        for (String biome : remainingBiomes()) {
            width += columnWidth(font, biome, remainingCritters(biome)) + COLUMN_GAP;
        }
        width -= COLUMN_GAP;
        return Math.max(width, font.width(title())) + PADDING * 2;
    }

    /** Widget height before the scale factor is applied. */
    private static int getBaseHeight() {
        int rows = 0;
        for (String biome : remainingBiomes()) {
            rows = Math.max(rows, remainingCritters(biome).size());
        }
        // title + biome header + longest critter column
        return PADDING * 2 + LINE_HEIGHT * (rows + (rows == 0 ? 0 : 1)) + LINE_HEIGHT + 2;
    }

    private static int columnWidth(Font font, String biome, List<String> critters) {
        int width = font.width(Component.literal(biome));
        for (String critter : critters) {
            width = Math.max(width, font.width(Component.literal(critter)));
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
