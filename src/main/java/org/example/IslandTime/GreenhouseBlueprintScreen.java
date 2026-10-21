package org.example.islandtime;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI screen for the greenhouse blueprints, styled like the player list and fuel day screens: a
 * scrollable list with one row per saved layout. Each row can select the layout (the one the overlay
 * draws), copy a shareable import code for it to the clipboard, or delete it. The button at the top
 * imports a layout from a code on the clipboard.
 */
public class GreenhouseBlueprintScreen extends Screen {

    private static final int ROW_HEIGHT = 36;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SELECT_WIDTH = 50;
    private static final int COPY_WIDTH = 78;
    private static final int DELETE_WIDTH = 20;
    private static final int BUTTON_GAP = 3;
    private static final int PADDING = 4;
    private static final int IMPORT_BUTTON_WIDTH = 170;
    private static final int LIST_WIDTH = 400;

    /** How long a message under the title stays up. */
    private static final long STATUS_MS = 6_000L;
    /** How long a delete button stays armed after its first click. */
    private static final long DELETE_CONFIRM_MS = 3_000L;

    private enum Action { SELECT, COPY, DELETE }

    /** A row's button under the mouse. */
    private record Hit(GreenhouseBlueprint blueprint, Action action) {}

    private int scrollOffset = 0;
    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listWidth;
    private boolean draggingScrollbar = false;

    private String statusMessage = "";
    private boolean statusOk = true;
    private long statusUntilMs = 0;

    /** The blueprint whose delete button was clicked once and waits for a second click. */
    private String pendingDeleteId = "";
    private long pendingDeleteUntilMs = 0;

    public GreenhouseBlueprintScreen() {
        super(Component.literal("Greenhouse Blueprints"));
    }

    @Override
    protected void init() {
        super.init();
        listWidth = LIST_WIDTH;
        listLeft = width / 2 - listWidth / 2;
        listTop = 62;
        listBottom = height - 10;

        addRenderableWidget(Button.builder(Component.literal("Import From Clipboard"), btn -> importFromClipboard())
                .bounds(width / 2 - IMPORT_BUTTON_WIDTH / 2, 18, IMPORT_BUTTON_WIDTH, 20)
                .build());
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void importFromClipboard() {
        GreenhouseBlueprintShare.Outcome outcome =
                GreenhouseBlueprintShare.importFromClipboard(Minecraft.getInstance());
        setStatus(outcome.message(), outcome.ok());
        if (outcome.ok()) scrollOffset = 0;
    }

    private void perform(Hit hit) {
        GreenhouseBlueprint blueprint = hit.blueprint();
        switch (hit.action()) {
            case SELECT -> {
                GreenhouseBlueprintManager.select(blueprint);
                GreenhouseBlueprintRenderer.requestRefresh();
                setStatus("Selected \"" + blueprint.name + "\".", true);
            }
            case COPY -> {
                GreenhouseBlueprintShare.Outcome outcome =
                        GreenhouseBlueprintShare.copyToClipboard(Minecraft.getInstance(), blueprint);
                setStatus(outcome.message(), outcome.ok());
            }
            case DELETE -> {
                long now = System.currentTimeMillis();
                if (blueprint.id.equals(pendingDeleteId) && now < pendingDeleteUntilMs) {
                    GreenhouseBlueprintManager.delete(blueprint);
                    GreenhouseBlueprintRenderer.requestRefresh();
                    pendingDeleteId = "";
                    setStatus("Deleted \"" + blueprint.name + "\".", true);
                } else {
                    pendingDeleteId = blueprint.id;
                    pendingDeleteUntilMs = now + DELETE_CONFIRM_MS;
                }
            }
        }
    }

    private void setStatus(String message, boolean ok) {
        statusMessage = message;
        statusOk = ok;
        statusUntilMs = System.currentTimeMillis() + STATUS_MS;
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    }

    private int deleteX() {
        return listLeft + listWidth - DELETE_WIDTH - PADDING;
    }

    private int copyX() {
        return deleteX() - BUTTON_GAP - COPY_WIDTH;
    }

    private int selectX() {
        return copyX() - BUTTON_GAP - SELECT_WIDTH;
    }

    private int buttonY(int rowY) {
        return rowY + (ROW_HEIGHT - BUTTON_HEIGHT) / 2;
    }

    /** The y of a row, or {@link Integer#MIN_VALUE} if it is scrolled out of view. */
    private int rowY(int index) {
        int y = listTop + (index - scrollOffset) * ROW_HEIGHT;
        return y < listTop || y + ROW_HEIGHT > listBottom ? Integer.MIN_VALUE : y;
    }

    private Hit hitAt(double mouseX, double mouseY, List<GreenhouseBlueprint> blueprints) {
        for (int i = 0; i < blueprints.size(); i++) {
            int rowY = rowY(i);
            if (rowY == Integer.MIN_VALUE) continue;
            int btnY = buttonY(rowY);
            GreenhouseBlueprint blueprint = blueprints.get(i);
            if (isInBounds(mouseX, mouseY, selectX(), btnY, SELECT_WIDTH, BUTTON_HEIGHT)) {
                return new Hit(blueprint, Action.SELECT);
            }
            if (isInBounds(mouseX, mouseY, copyX(), btnY, COPY_WIDTH, BUTTON_HEIGHT)) {
                return new Hit(blueprint, Action.COPY);
            }
            if (isInBounds(mouseX, mouseY, deleteX(), btnY, DELETE_WIDTH, BUTTON_HEIGHT)) {
                return new Hit(blueprint, Action.DELETE);
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xC0101010);

        Component title = Component.literal("Greenhouse Blueprints").withStyle(ChatFormatting.GOLD);
        context.text(font, title, width / 2 - font.width(title) / 2, 6, -1, true);

        long now = System.currentTimeMillis();
        if (!statusMessage.isEmpty() && now < statusUntilMs) {
            String shown = statusMessage;
            int maxWidth = width - 20;
            if (font.width(shown) > maxWidth) {
                shown = font.plainSubstrByWidth(shown, maxWidth - font.width("...")) + "...";
            }
            ChatFormatting style = statusOk ? ChatFormatting.GREEN : ChatFormatting.RED;
            context.text(font, Component.literal(shown).withStyle(style), width / 2 - font.width(shown) / 2, 44,
                    statusOk ? 0xFF55FF55 : 0xFFFF5555, true);
        }

        List<GreenhouseBlueprint> blueprints = GreenhouseBlueprintManager.getAll();
        renderList(context, blueprints, mouseX, mouseY, now);

        super.extractRenderState(context, mouseX, mouseY, delta);

        renderTooltip(context, blueprints, mouseX, mouseY);
    }

    private void renderList(GuiGraphicsExtractor context, List<GreenhouseBlueprint> blueprints, int mouseX,
                            int mouseY, long now) {
        if (blueprints.isEmpty()) {
            Component empty = Component.literal("No blueprints yet. Copy a planted greenhouse with "
                    + "/it blueprint copy, or import a code.").withStyle(ChatFormatting.GRAY);
            context.text(font, empty, width / 2 - font.width(empty) / 2, height / 2, 0xFFAAAAAA, true);
            return;
        }

        GreenhouseBlueprint active = GreenhouseBlueprintManager.getActive();
        int visibleRows = visibleRows();
        int maxScroll = Math.max(0, blueprints.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        int textWidth = selectX() - listLeft - PADDING * 2;

        for (int i = 0; i < blueprints.size(); i++) {
            int rowY = rowY(i);
            if (rowY == Integer.MIN_VALUE) continue;
            GreenhouseBlueprint blueprint = blueprints.get(i);
            boolean isActive = blueprint == active;

            int bg = isActive ? 0x5000AA00 : (i % 2 == 0 ? 0x40000000 : 0x20000000);
            context.fill(listLeft, rowY, listLeft + listWidth, rowY + ROW_HEIGHT - 1, bg);

            Component nameComp = Component.literal(fit(blueprint.name, textWidth - (isActive ? 60 : 0)))
                    .withStyle(ChatFormatting.BOLD, ChatFormatting.WHITE);
            context.text(font, nameComp, listLeft + PADDING, rowY + 5, 0xFFFFFFFF, true);
            if (isActive) {
                context.text(font, Component.literal("Selected").withStyle(ChatFormatting.GREEN),
                        listLeft + PADDING + font.width(nameComp) + 6, rowY + 5, 0xFF55FF55, true);
            }
            context.text(font, Component.literal(fit(describe(blueprint), textWidth)).withStyle(ChatFormatting.GRAY),
                    listLeft + PADDING, rowY + 19, 0xFFAAAAAA, true);

            int btnY = buttonY(rowY);
            drawButton(context, selectX(), btnY, SELECT_WIDTH, isActive ? "Using" : "Use",
                    isActive ? 0xFF888888 : 0xFF55FF55, mouseX, mouseY);
            drawButton(context, copyX(), btnY, COPY_WIDTH, "Copy Code", 0xFF55FFFF, mouseX, mouseY);
            boolean armed = blueprint.id.equals(pendingDeleteId) && now < pendingDeleteUntilMs;
            drawButton(context, deleteX(), btnY, DELETE_WIDTH, armed ? "?" : "X", 0xFFFF5555, mouseX, mouseY);
        }

        if (blueprints.size() > visibleRows) {
            int scrollbarX = listLeft + listWidth + 2;
            int effectiveBottom = listTop + visibleRows * ROW_HEIGHT;
            int trackHeight = effectiveBottom - listTop;
            context.fill(scrollbarX, listTop, scrollbarX + 4, effectiveBottom, 0x40FFFFFF);
            float thumbRatio = (float) visibleRows / blueprints.size();
            int thumbHeight = Math.max(10, (int) (trackHeight * thumbRatio));
            float scrollRatio = maxScroll > 0 ? (float) scrollOffset / maxScroll : 0;
            int thumbY = listTop + (int) ((trackHeight - thumbHeight) * scrollRatio);
            context.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xAAFFFFFF);
        }
    }

    /** "2 boards, 41 crops, Imported, turned 90° and mirrored". */
    private static String describe(GreenhouseBlueprint blueprint) {
        int crops = 0;
        for (GreenhouseBlueprint.Board board : blueprint.boards) crops += board.placements.size();
        StringBuilder sb = new StringBuilder();
        sb.append(blueprint.boards.size()).append(blueprint.boards.size() == 1 ? " board, " : " boards, ")
                .append(crops).append(crops == 1 ? " crop" : " crops");
        if (!blueprint.source.isEmpty()) sb.append(", ").append(blueprint.source);
        if (blueprint.rotation != 0 || blueprint.mirrored) {
            sb.append(", turned ").append(Math.floorMod(blueprint.rotation, 4) * 90).append("\u00b0");
            if (blueprint.mirrored) sb.append(" and mirrored");
        }
        return sb.toString();
    }

    private String fit(String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    private void renderTooltip(GuiGraphicsExtractor context, List<GreenhouseBlueprint> blueprints, int mouseX,
                               int mouseY) {
        Hit hit = hitAt(mouseX, mouseY, blueprints);
        if (hit == null) return;

        List<Component> lines = new ArrayList<>();
        switch (hit.action()) {
            case SELECT -> lines.add(Component.literal("Draw this layout over the greenhouse plot you are "
                    + "standing in").withStyle(ChatFormatting.GRAY));
            case COPY -> {
                lines.add(Component.literal("Copy an import code for this layout").withStyle(ChatFormatting.GRAY));
                lines.add(Component.literal("Paste it anywhere to share it, and the other player imports it "
                        + "with Import From Clipboard").withStyle(ChatFormatting.DARK_GRAY));
            }
            case DELETE -> lines.add(Component.literal(hit.blueprint().id.equals(pendingDeleteId)
                            && System.currentTimeMillis() < pendingDeleteUntilMs
                            ? "Click again to delete this layout" : "Delete this layout (click twice)")
                    .withStyle(ChatFormatting.RED));
        }
        context.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    private void drawButton(GuiGraphicsExtractor context, int x, int y, int w, String label, int color, int mouseX,
                            int mouseY) {
        boolean hovered = isInBounds(mouseX, mouseY, x, y, w, BUTTON_HEIGHT);
        context.fill(x, y, x + w, y + BUTTON_HEIGHT, hovered ? 0x80FFFFFF : 0x60000000);
        Component labelComp = Component.literal(label);
        context.text(font, labelComp, x + w / 2 - font.width(labelComp) / 2, y + (BUTTON_HEIGHT - 8) / 2, color, true);
    }

    // -------------------------------------------------------------------------
    // Input handling
    // -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;

        Minecraft client = Minecraft.getInstance();
        double mouseX = client.mouseHandler.xpos() * (double) client.getWindow().getGuiScaledWidth() / (double) client.getWindow().getScreenWidth();
        double mouseY = client.mouseHandler.ypos() * (double) client.getWindow().getGuiScaledHeight() / (double) client.getWindow().getScreenHeight();

        List<GreenhouseBlueprint> blueprints = GreenhouseBlueprintManager.getAll();
        if (blueprints.size() > visibleRows()) {
            int scrollbarX = listLeft + listWidth + 2;
            if (isInBounds(mouseX, mouseY, scrollbarX - 2, listTop, 8, listBottom - listTop)) {
                draggingScrollbar = true;
                updateScrollFromMouse(mouseY, blueprints.size());
                return true;
            }
        }

        Hit hit = hitAt(mouseX, mouseY, blueprints);
        if (hit != null) {
            perform(hit);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollOffset -= (int) verticalAmount;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (draggingScrollbar) {
            Minecraft client = Minecraft.getInstance();
            double mouseY = client.mouseHandler.ypos() * (double) client.getWindow().getGuiScaledHeight() / (double) client.getWindow().getScreenHeight();
            updateScrollFromMouse(mouseY, GreenhouseBlueprintManager.getAll().size());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    private void updateScrollFromMouse(double mouseY, int count) {
        int maxScroll = Math.max(0, count - visibleRows());
        float ratio = (float) (mouseY - listTop) / (listBottom - listTop);
        ratio = Math.max(0, Math.min(1, ratio));
        scrollOffset = Math.round(ratio * maxScroll);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_E) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private static boolean isInBounds(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
