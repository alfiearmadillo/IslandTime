package org.example.islandtime;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * GUI screen for block highlight management.
 * Displays a scrollable list of highlight entries with controls for:
 * add, edit (target/color/range/intensity/through-walls), enable/disable, delete.
 */
public class BlockHighlightScreen extends Screen {

    private static final int ROW_HEIGHT = 36;
    private static final int HEADER_HEIGHT = 40;
    private static final int BUTTON_SIZE = 20;
    private static final int PADDING = 4;

    private int scrollOffset = 0;
    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listWidth;

    // Add dialog
    private boolean addDialogOpen = false;
    private EditBox addTargetField;
    private EditBox addColorField;
    private EditBox addRangeField;
    private EditBox addIntensityField;
    private EditBox addAreaField;
    private boolean addThroughWalls = true;

    // Edit dialog
    private boolean editDialogOpen = false;
    private int editIndex = -1;
    private EditBox editTargetField;
    private EditBox editColorField;
    private EditBox editRangeField;
    private EditBox editIntensityField;
    private EditBox editAreaField;

    // Color picker
    private boolean colorPickerOpen = false;
    private int colorPickerIndex = -1;

    // Scrollbar
    private boolean draggingScrollbar = false;

    private static final String[] COLOR_PRESETS = {
            "#ffff33", "#ff3333", "#33ff33", "#3366ff",
            "#ff33ff", "#33ffff", "#ffffff", "#ff8800",
            "#aa00aa", "#aaaaaa"
    };
    private static final String[] COLOR_PRESET_NAMES = {
            "Yellow", "Red", "Green", "Blue",
            "Magenta", "Cyan", "White", "Orange",
            "Purple", "Gray"
    };

    public BlockHighlightScreen() {
        super(Component.literal("Block Highlights"));
    }

    @Override
    protected void init() {
        super.init();
        listLeft = width / 2 - 180;
        listWidth = 360;
        listTop = HEADER_HEIGHT + 10;
        listBottom = height - 10;

        if (!addDialogOpen && !editDialogOpen && !colorPickerOpen) {
            addRenderableWidget(Button.builder(Component.literal("+ Add Highlight"), btn -> openAddDialog())
                    .bounds(width / 2 - 55, 10, 110, 20)
                    .build());
        }
    }

    private void openAddDialog() {
        addDialogOpen = true;
        IslandTimeConfig config = ConfigManager.getConfig();
        addThroughWalls = config.blockHighlight.throughWalls;
        clearWidgets();

        int dialogX = width / 2 - 120;
        int dialogY = height / 2 - 88;

        addTargetField = new EditBox(font, dialogX, dialogY + 14, 240, 20, Component.literal("Target"));
        addTargetField.setMaxLength(256);
        addTargetField.setHint(Component.literal("Block ID, sb:ID, or skull:HASH").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(addTargetField);

        addColorField = new EditBox(font, dialogX, dialogY + 48, 240, 20, Component.literal("Color"));
        addColorField.setMaxLength(32);
        int colorIdx = Math.max(0, Math.min(config.blockHighlight.color, BlockHighlightRenderer.COLOR_NAMES.length - 1));
        addColorField.setValue(BlockHighlightRenderer.COLOR_NAMES[colorIdx].toLowerCase());
        addColorField.setHint(Component.literal("Color: yellow, #ff0000, etc.").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(addColorField);

        addRangeField = new EditBox(font, dialogX, dialogY + 82, 115, 20, Component.literal("Range"));
        addRangeField.setMaxLength(5);
        addRangeField.setValue(String.valueOf(config.blockHighlight.range));
        addRangeField.setHint(Component.literal("Range (1-320)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(addRangeField);

        addIntensityField = new EditBox(font, dialogX + 125, dialogY + 82, 115, 20, Component.literal("Intensity"));
        addIntensityField.setMaxLength(3);
        addIntensityField.setValue(String.valueOf(config.blockHighlight.intensity));
        addIntensityField.setHint(Component.literal("Intensity (0-100)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(addIntensityField);

        addAreaField = new EditBox(font, dialogX, dialogY + 116, 240, 20, Component.literal("Area"));
        addAreaField.setMaxLength(64);
        addAreaField.setHint(Component.literal("Area (blank = everywhere)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(addAreaField);

        // Through Walls toggle
        addRenderableWidget(Button.builder(
                Component.literal("Walls: " + (addThroughWalls ? "ON" : "OFF")),
                btn -> {
                    addThroughWalls = !addThroughWalls;
                    btn.setMessage(Component.literal("Walls: " + (addThroughWalls ? "ON" : "OFF")));
                }
        ).bounds(dialogX, dialogY + 144, 115, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Add"), btn -> {
            String target = addTargetField.getValue().trim();
            String color = addColorField.getValue().trim();
            if (!target.isEmpty()) {
                if (color.isEmpty()) color = "yellow";
                BlockHighlightManager.HighlightEntry entry = new BlockHighlightManager.HighlightEntry(target, color);
                try {
                    entry.range = Math.max(1, Math.min(320, Integer.parseInt(addRangeField.getValue().trim())));
                } catch (NumberFormatException ignored) {}
                try {
                    entry.intensity = Math.max(0, Math.min(100, Integer.parseInt(addIntensityField.getValue().trim())));
                } catch (NumberFormatException ignored) {}
                entry.throughWalls = addThroughWalls;
                entry.area = addAreaField.getValue().trim();
                BlockHighlightManager.getEntries().add(entry);
                BlockHighlightManager.save();
                closeDialog();
            }
        }).bounds(dialogX + 125, dialogY + 144, 55, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> closeDialog())
                .bounds(dialogX + 185, dialogY + 144, 55, 20).build());

        setFocused(addTargetField);
    }

    private void openEditDialog(int index) {
        BlockHighlightManager.HighlightEntry entry = BlockHighlightManager.getEntries().get(index);
        editDialogOpen = true;
        editIndex = index;
        clearWidgets();

        int dialogX = width / 2 - 120;
        int dialogY = height / 2 - 88;

        editTargetField = new EditBox(font, dialogX, dialogY + 14, 240, 20, Component.literal("Target"));
        editTargetField.setMaxLength(256);
        editTargetField.setValue(entry.target != null ? entry.target : "");
        addRenderableWidget(editTargetField);

        editColorField = new EditBox(font, dialogX, dialogY + 48, 240, 20, Component.literal("Color"));
        editColorField.setMaxLength(32);
        editColorField.setValue(entry.color != null ? entry.color : "");
        addRenderableWidget(editColorField);

        editRangeField = new EditBox(font, dialogX, dialogY + 82, 115, 20, Component.literal("Range"));
        editRangeField.setMaxLength(5);
        editRangeField.setValue(String.valueOf(entry.range));
        editRangeField.setHint(Component.literal("Range (1-320)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(editRangeField);

        editIntensityField = new EditBox(font, dialogX + 125, dialogY + 82, 115, 20, Component.literal("Intensity"));
        editIntensityField.setMaxLength(3);
        editIntensityField.setValue(String.valueOf(entry.intensity));
        editIntensityField.setHint(Component.literal("Intensity (0-100)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(editIntensityField);

        editAreaField = new EditBox(font, dialogX, dialogY + 116, 240, 20, Component.literal("Area"));
        editAreaField.setMaxLength(64);
        editAreaField.setValue(entry.area != null ? entry.area : "");
        editAreaField.setHint(Component.literal("Area (blank = everywhere)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(editAreaField);

        // Through Walls toggle
        addRenderableWidget(Button.builder(
                Component.literal("Walls: " + (entry.throughWalls ? "ON" : "OFF")),
                btn -> {
                    BlockHighlightManager.HighlightEntry e = BlockHighlightManager.getEntries().get(editIndex);
                    e.throughWalls = !e.throughWalls;
                    btn.setMessage(Component.literal("Walls: " + (e.throughWalls ? "ON" : "OFF")));
                }
        ).bounds(dialogX, dialogY + 144, 115, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> {
            BlockHighlightManager.HighlightEntry e = BlockHighlightManager.getEntries().get(editIndex);
            e.target = editTargetField.getValue().trim();
            e.color = editColorField.getValue().trim();
            try {
                e.range = Math.max(1, Math.min(320, Integer.parseInt(editRangeField.getValue().trim())));
            } catch (NumberFormatException ignored) {}
            try {
                e.intensity = Math.max(0, Math.min(100, Integer.parseInt(editIntensityField.getValue().trim())));
            } catch (NumberFormatException ignored) {}
            e.area = editAreaField.getValue().trim();
            BlockHighlightManager.save();
            closeDialog();
        }).bounds(dialogX + 125, dialogY + 144, 55, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> closeDialog())
                .bounds(dialogX + 185, dialogY + 144, 55, 20).build());

        setFocused(editTargetField);
    }

    private void closeDialog() {
        addDialogOpen = false;
        editDialogOpen = false;
        colorPickerOpen = false;
        editIndex = -1;
        colorPickerIndex = -1;
        clearWidgets();
        init();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xC0101010);

        // Title
        Component titleComp = Component.literal("Block Highlights").withStyle(ChatFormatting.GOLD);
        int titleWidth = font.width(titleComp);
        context.text(font, titleComp, width / 2 - titleWidth / 2, 2, -1, true);

        if (!addDialogOpen && !editDialogOpen && !colorPickerOpen) {
            renderEntryList(context, mouseX, mouseY);
        }

        if (addDialogOpen || editDialogOpen) {
            int dialogX = width / 2 - 130;
            int dialogY = height / 2 - 100;
            int dialogH = 182;
            context.fill(dialogX, dialogY, dialogX + 260, dialogY + dialogH, 0xEE000000);
            String title = addDialogOpen ? "Add Highlight" : "Edit Highlight";
            Component titleC = Component.literal(title);
            int tw = font.width(titleC);
            context.text(font, titleC, width / 2 - tw / 2, dialogY + 4, -1, true);

            // Labels (same layout for both add and edit)
            int baseY = height / 2 - 88;
            context.text(font, Component.literal("Target:").withStyle(ChatFormatting.GRAY),
                    width / 2 - 120, baseY + 4, 0xFFAAAAAA, true);
            context.text(font, Component.literal("Color:").withStyle(ChatFormatting.GRAY),
                    width / 2 - 120, baseY + 38, 0xFFAAAAAA, true);
            context.text(font, Component.literal("Range:").withStyle(ChatFormatting.GRAY),
                    width / 2 - 120, baseY + 72, 0xFFAAAAAA, true);
            context.text(font, Component.literal("Intensity:").withStyle(ChatFormatting.GRAY),
                    width / 2 - 120 + 125, baseY + 72, 0xFFAAAAAA, true);
            context.text(font, Component.literal("Area:").withStyle(ChatFormatting.GRAY),
                    width / 2 - 120, baseY + 106, 0xFFAAAAAA, true);
        }

        if (colorPickerOpen) {
            renderColorPicker(context, mouseX, mouseY);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void renderEntryList(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        List<BlockHighlightManager.HighlightEntry> entries = BlockHighlightManager.getEntries();

        if (entries.isEmpty()) {
            Component emptyMsg = Component.literal("No highlights configured. Click '+ Add Highlight' or use /it waila on a block.")
                    .withStyle(ChatFormatting.GRAY);
            int emptyW = font.width(emptyMsg);
            int maxW = listWidth - 20;
            if (emptyW > maxW) {
                // Wrap text manually
                String str = emptyMsg.getString();
                String line1 = font.plainSubstrByWidth(str, maxW);
                String line2 = str.substring(line1.length());
                context.text(font, Component.literal(line1).withStyle(ChatFormatting.GRAY),
                        listLeft + 10, height / 2 - 6, 0xFFAAAAAA, true);
                context.text(font, Component.literal(line2).withStyle(ChatFormatting.GRAY),
                        listLeft + 10, height / 2 + 6, 0xFFAAAAAA, true);
            } else {
                context.text(font, emptyMsg, width / 2 - emptyW / 2, height / 2, 0xFFAAAAAA, true);
            }
            return;
        }

        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;
        int maxScroll = Math.max(0, entries.size() - visibleRows);
        scrollOffset = Math.min(scrollOffset, maxScroll);
        scrollOffset = Math.max(0, scrollOffset);

        for (int i = 0; i < entries.size(); i++) {
            int drawIndex = i - scrollOffset;
            int rowY = listTop + drawIndex * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) continue;

            BlockHighlightManager.HighlightEntry entry = entries.get(i);

            // Row background
            int bgColor = (i % 2 == 0) ? 0x40000000 : 0x20000000;
            if (!entry.enabled) bgColor = 0x30333333; // darker for disabled
            context.fill(listLeft, rowY, listLeft + listWidth, rowY + ROW_HEIGHT - 1, bgColor);

            // Color swatch
            float[] parsedColor = BlockHighlightRenderer.parseColor(entry.color);
            if (parsedColor != null) {
                int rgb = ((int) (parsedColor[0] * 255) << 16)
                        | ((int) (parsedColor[1] * 255) << 8)
                        | (int) (parsedColor[2] * 255);
                context.fill(listLeft + PADDING, rowY + 4, listLeft + PADDING + 14, rowY + 18, 0xFF000000 | rgb);
            }

            // Target name (truncate to fit before buttons)
            String displayName = entry.target != null ? entry.target : "(empty)";
            int nameStartX = listLeft + PADDING + 18;
            int nameMaxWidth = (listLeft + listWidth - (BUTTON_SIZE + 2) * 4 - 4) - nameStartX;
            if (font.width(displayName) > nameMaxWidth) {
                displayName = font.plainSubstrByWidth(displayName, nameMaxWidth - font.width("...")) + "...";
            }
            ChatFormatting nameStyle = entry.enabled ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY;
            context.text(font, Component.literal(displayName).withStyle(ChatFormatting.BOLD, nameStyle),
                    listLeft + PADDING + 18, rowY + 4, entry.enabled ? 0xFFFFFFFF : 0xFF666666, true);

            // Details line: range, intensity, walls, area
            String details = "Range:" + entry.range + " Intensity:" + entry.intensity + " Walls:" + (entry.throughWalls ? "Y" : "N");
            if (entry.area != null && !entry.area.isBlank()) {
                details += " Area:" + entry.area;
            }
            context.text(font, Component.literal(details).withStyle(ChatFormatting.GRAY),
                    listLeft + PADDING + 18, rowY + 16, 0xFF888888, true);

            // Action buttons on the right
            int btnX = listLeft + listWidth - (BUTTON_SIZE + 2) * 4;
            int btnY = rowY + (ROW_HEIGHT - BUTTON_SIZE) / 2;

            // Enable/disable toggle
            String toggleLabel = entry.enabled ? "\u2714" : "\u2718"; // ✔ or ✘
            int toggleColor = entry.enabled ? 0xFF55FF55 : 0xFFFF5555;
            drawSmallButton(context, btnX, btnY, toggleLabel, toggleColor, mouseX, mouseY);
            btnX += BUTTON_SIZE + 2;

            // Color button
            drawSmallButton(context, btnX, btnY, "C", 0xFFFFFF55, mouseX, mouseY);
            btnX += BUTTON_SIZE + 2;

            // Edit button
            drawSmallButton(context, btnX, btnY, "\u270F", 0xFF55FFFF, mouseX, mouseY);
            btnX += BUTTON_SIZE + 2;

            // Delete button
            drawSmallButton(context, btnX, btnY, "X", 0xFFFF5555, mouseX, mouseY);
        }

        // Scrollbar
        if (entries.size() > visibleRows) {
            int scrollbarX = listLeft + listWidth + 2;
            int scrollbarW = 4;
            int trackHeight = listBottom - listTop;
            context.fill(scrollbarX, listTop, scrollbarX + scrollbarW, listBottom, 0x40FFFFFF);
            float thumbRatio = (float) visibleRows / entries.size();
            int thumbHeight = Math.max(10, (int) (trackHeight * thumbRatio));
            float scrollRatio = maxScroll > 0 ? (float) scrollOffset / maxScroll : 0;
            int thumbY = listTop + (int) ((trackHeight - thumbHeight) * scrollRatio);
            context.fill(scrollbarX, thumbY, scrollbarX + scrollbarW, thumbY + thumbHeight, 0xAAFFFFFF);
        }
    }

    private void renderColorPicker(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int dialogW = 160;
        int dialogH = 80;
        int dialogX = width / 2 - dialogW / 2;
        int dialogY = height / 2 - dialogH / 2;

        context.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, 0xEE000000);

        Component title = Component.literal("Pick Color");
        int tw = font.width(title);
        context.text(font, title, width / 2 - tw / 2, dialogY + 6, -1, true);

        int swatchSize = 20;
        int gap = 4;
        int colsPerRow = 5;
        int startX = dialogX + (dialogW - colsPerRow * (swatchSize + gap) + gap) / 2;
        int startY = dialogY + 22;

        for (int i = 0; i < COLOR_PRESETS.length; i++) {
            int col = i % colsPerRow;
            int row = i / colsPerRow;
            int sx = startX + col * (swatchSize + gap);
            int sy = startY + row * (swatchSize + gap);

            float[] rgb = BlockHighlightRenderer.parseColor(COLOR_PRESETS[i]);
            if (rgb == null) continue;
            int color = ((int) (rgb[0] * 255) << 16) | ((int) (rgb[1] * 255) << 8) | (int) (rgb[2] * 255);
            context.fill(sx, sy, sx + swatchSize, sy + swatchSize, 0xFF000000 | color);

            if (mouseX >= sx && mouseX < sx + swatchSize && mouseY >= sy && mouseY < sy + swatchSize) {
                context.fill(sx, sy, sx + swatchSize, sy + 1, -1);
                context.fill(sx, sy + swatchSize - 1, sx + swatchSize, sy + swatchSize, -1);
                context.fill(sx, sy, sx + 1, sy + swatchSize, -1);
                context.fill(sx + swatchSize - 1, sy, sx + swatchSize, sy + swatchSize, -1);
            }

            // Mark current color
            if (colorPickerIndex >= 0 && colorPickerIndex < BlockHighlightManager.getEntries().size()) {
                String currentColor = BlockHighlightManager.getEntries().get(colorPickerIndex).color;
                if (COLOR_PRESETS[i].equalsIgnoreCase(currentColor)) {
                    context.text(font, Component.literal("•"), sx + swatchSize / 2 - 2, sy + swatchSize / 2 - 4, 0xFF000000, false);
                }
            }
        }
    }

    private void drawSmallButton(GuiGraphicsExtractor context, int x, int y, String label, int color, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + BUTTON_SIZE && mouseY >= y && mouseY < y + BUTTON_SIZE;
        int bg = hovered ? 0x80FFFFFF : 0x60000000;
        context.fill(x, y, x + BUTTON_SIZE, y + BUTTON_SIZE, bg);
        Component labelComp = Component.literal(label);
        int lw = font.width(labelComp);
        context.text(font, labelComp, x + BUTTON_SIZE / 2 - lw / 2, y + (BUTTON_SIZE - 8) / 2, color, true);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;

        Minecraft client = Minecraft.getInstance();
        double mouseX = client.mouseHandler.xpos() * (double) client.getWindow().getGuiScaledWidth() / (double) client.getWindow().getScreenWidth();
        double mouseY = client.mouseHandler.ypos() * (double) client.getWindow().getGuiScaledHeight() / (double) client.getWindow().getScreenHeight();

        // Color picker clicks
        if (colorPickerOpen) {
            int dialogW = 160;
            int dialogH = 80;
            int dialogX = width / 2 - dialogW / 2;
            int dialogY = height / 2 - dialogH / 2;
            int swatchSize = 20;
            int gap = 4;
            int colsPerRow = 5;
            int startX = dialogX + (dialogW - colsPerRow * (swatchSize + gap) + gap) / 2;
            int startY = dialogY + 22;

            for (int i = 0; i < COLOR_PRESETS.length; i++) {
                int col = i % colsPerRow;
                int row = i / colsPerRow;
                int sx = startX + col * (swatchSize + gap);
                int sy = startY + row * (swatchSize + gap);
                if (isInBounds(mouseX, mouseY, sx, sy, swatchSize, swatchSize)) {
                    BlockHighlightManager.setColor(colorPickerIndex, COLOR_PRESETS[i]);
                    closeDialog();
                    return true;
                }
            }
            closeDialog();
            return true;
        }

        if (addDialogOpen || editDialogOpen) return false;

        // Scrollbar
        List<BlockHighlightManager.HighlightEntry> entries = BlockHighlightManager.getEntries();
        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;
        if (entries.size() > visibleRows) {
            int scrollbarX = listLeft + listWidth + 2;
            int scrollbarW = 4;
            if (isInBounds(mouseX, mouseY, scrollbarX - 2, listTop, scrollbarW + 4, listBottom - listTop)) {
                draggingScrollbar = true;
                updateScrollFromMouse(mouseY);
                return true;
            }
        }

        // Entry buttons
        for (int i = 0; i < entries.size(); i++) {
            int drawIndex = i - scrollOffset;
            int rowY = listTop + drawIndex * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) continue;

            int btnX = listLeft + listWidth - (BUTTON_SIZE + 2) * 4;
            int btnY = rowY + (ROW_HEIGHT - BUTTON_SIZE) / 2;

            // Enable/disable
            if (isInBounds(mouseX, mouseY, btnX, btnY, BUTTON_SIZE, BUTTON_SIZE)) {
                BlockHighlightManager.setEnabled(i, !entries.get(i).enabled);
                return true;
            }
            btnX += BUTTON_SIZE + 2;

            // Color
            if (isInBounds(mouseX, mouseY, btnX, btnY, BUTTON_SIZE, BUTTON_SIZE)) {
                colorPickerOpen = true;
                colorPickerIndex = i;
                clearWidgets();
                return true;
            }
            btnX += BUTTON_SIZE + 2;

            // Edit
            if (isInBounds(mouseX, mouseY, btnX, btnY, BUTTON_SIZE, BUTTON_SIZE)) {
                openEditDialog(i);
                return true;
            }
            btnX += BUTTON_SIZE + 2;

            // Delete
            if (isInBounds(mouseX, mouseY, btnX, btnY, BUTTON_SIZE, BUTTON_SIZE)) {
                BlockHighlightManager.removeEntry(i);
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (addDialogOpen || editDialogOpen) return false;
        scrollOffset -= (int) verticalAmount;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (draggingScrollbar) {
            Minecraft client = Minecraft.getInstance();
            double mouseY = client.mouseHandler.ypos() * (double) client.getWindow().getGuiScaledHeight() / (double) client.getWindow().getScreenHeight();
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    private void updateScrollFromMouse(double mouseY) {
        List<BlockHighlightManager.HighlightEntry> entries = BlockHighlightManager.getEntries();
        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;
        int maxScroll = Math.max(0, entries.size() - visibleRows);
        float ratio = (float) (mouseY - listTop) / (listBottom - listTop);
        ratio = Math.max(0, Math.min(1, ratio));
        scrollOffset = Math.round(ratio * maxScroll);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (addDialogOpen || editDialogOpen || colorPickerOpen) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                closeDialog();
                return true;
            }
            if (colorPickerOpen) return true;
            return super.keyPressed(event);
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_E) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private boolean isInBounds(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
