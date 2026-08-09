package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI screen for /it players — displays a scrollable player list with
 * add/edit/delete/party/invite controls.
 */
public class PlayerListScreen extends Screen {

    private static final int ROW_HEIGHT = 28;
    private static final int HEADER_HEIGHT = 40;
    private static final int BUTTON_SIZE = 20;
    private static final int PADDING = 4;

    private int scrollOffset = 0;
    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listWidth;

    // Add player dialog state
    private boolean addDialogOpen = false;
    private EditBox nameField;
    private EditBox noteField;

    // Edit dialog state
    private boolean editDialogOpen = false;
    private int editIndex = -1;
    private EditBox editNameField;
    private EditBox editNoteField;

    // Color picker dialog state
    private boolean colorPickerOpen = false;
    private int colorPickerIndex = -1;
    private static final int[] COLOR_OPTIONS = {
            0xFFFFFF, // White
            0xFF5555, // Red
            0xFF5500, // Orange
            0xFFFF55, // Yellow
            0x55FF55, // Green
            0x55FFFF, // Cyan
            0x5555FF, // Blue
            0xFF55FF, // Pink
            0xAA00AA, // Purple
            0xAAAAAA, // Gray
    };

    public PlayerListScreen() {
        super(Component.literal("Player List"));
    }

    @Override
    protected void init() {
        super.init();
        listLeft = width / 2 - 160;
        listWidth = 320;
        listTop = HEADER_HEIGHT + 10;
        listBottom = height - 10;

        if (!addDialogOpen && !editDialogOpen && !colorPickerOpen) {
            addRenderableWidget(Button.builder(Component.literal("+ Add Player"), btn -> openAddDialog())
                    .bounds(width / 2 - 50, 10, 100, 20)
                    .build());
        }
    }

    private void openAddDialog() {
        addDialogOpen = true;
        clearWidgets();

        int dialogX = width / 2 - 100;
        int dialogY = height / 2 - 50;

        nameField = new EditBox(font, dialogX, dialogY, 200, 20, Component.literal("Name or UUID"));
        nameField.setMaxLength(64);
        nameField.setHint(Component.literal("Player name or UUID").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(nameField);

        noteField = new EditBox(font, dialogX, dialogY + 28, 200, 20, Component.literal("Note"));
        noteField.setMaxLength(2048);
        noteField.setHint(Component.literal("Note (paste Discord message)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(noteField);

        addRenderableWidget(Button.builder(Component.literal("Add"), btn -> {
            String input = nameField.getValue().trim();
            String noteText = decodeNewlines(noteField.getValue().trim());
            // If no name provided, try to extract from party/visit command in note
            if (input.isEmpty()) {
                input = extractPlayerNameFromNote(noteText);
            }
            if (!input.isEmpty()) {
                String uuid = "";
                String name = input;
                if (input.length() > 16 && input.contains("-")) {
                    uuid = input;
                    name = "";
                }
                PlayerListManager.addPlayer(uuid, name, noteText);
                closeDialog();
            }
        }).bounds(dialogX, dialogY + 58, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> closeDialog())
                .bounds(dialogX + 105, dialogY + 58, 95, 20).build());

        setFocused(nameField);
    }

    private void openEditDialog(int index) {
        PlayerListManager.PlayerEntry entry = PlayerListManager.getPlayers().get(index);
        editDialogOpen = true;
        editIndex = index;
        clearWidgets();

        int dialogX = width / 2 - 100;
        int dialogY = height / 2 - 50;

        editNameField = new EditBox(font, dialogX, dialogY, 200, 20, Component.literal("Name"));
        editNameField.setMaxLength(64);
        editNameField.setValue(entry.name);
        addRenderableWidget(editNameField);

        editNoteField = new EditBox(font, dialogX, dialogY + 28, 200, 20, Component.literal("Note"));
        editNoteField.setMaxLength(2048);
        editNoteField.setValue(encodeNewlines(entry.note));
        addRenderableWidget(editNoteField);

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> {
            PlayerListManager.updatePlayer(editIndex, editNameField.getValue().trim(), decodeNewlines(editNoteField.getValue().trim()));
            closeDialog();
        }).bounds(dialogX, dialogY + 58, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> closeDialog())
                .bounds(dialogX + 105, dialogY + 58, 95, 20).build());

        setFocused(editNameField);
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
        // Full background darkening
        context.fill(0, 0, width, height, 0xC0101010);

        // Render chat mirror on top of the darkening, to the left of the player list
        renderChatMirror(context);

        // Title
        Component titleComp = Component.literal("Player List").withStyle(ChatFormatting.GOLD);
        int titleWidth = font.width(titleComp);
        context.text(font, titleComp, width / 2 - titleWidth / 2, 2, -1, true);

        // Draw player list (when no dialog is open)
        if (!addDialogOpen && !editDialogOpen && !colorPickerOpen) {
            renderPlayerList(context, mouseX, mouseY);
        }

        // Draw dialog background
        if (addDialogOpen || editDialogOpen) {
            int dialogX = width / 2 - 110;
            int dialogY = height / 2 - 65;
            context.fill(dialogX, dialogY, dialogX + 220, dialogY + 140, 0xEE000000);
            String title = addDialogOpen ? "Add Player" : "Edit Player";
            Component titleC = Component.literal(title);
            int tw = font.width(titleC);
            context.text(font, titleC, width / 2 - tw / 2, dialogY + 8, -1, true);
        }

        // Draw color picker
        if (colorPickerOpen) {
            renderColorPicker(context, mouseX, mouseY);
        }

        // Render widgets (buttons, edit boxes) via parent
        super.extractRenderState(context, mouseX, mouseY, delta);

        // Tooltip (rendered after everything else so it's on top)
        if (!addDialogOpen && !editDialogOpen && !colorPickerOpen) {
            renderTooltip(context, mouseX, mouseY);
        }
    }

    private void renderPlayerList(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        List<PlayerListManager.PlayerEntry> players = PlayerListManager.getPlayers();

        if (players.isEmpty()) {
            Component emptyMsg = Component.literal("No players added yet. Click '+ Add Player' to get started.")
                    .withStyle(ChatFormatting.GRAY);
            int emptyW = font.width(emptyMsg);
            context.text(font, emptyMsg, width / 2 - emptyW / 2, height / 2, 0xFFAAAAAA, true);
            return;
        }

        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;
        int maxScroll = Math.max(0, players.size() - visibleRows);
        scrollOffset = Math.min(scrollOffset, maxScroll);
        scrollOffset = Math.max(0, scrollOffset);

        for (int i = 0; i < players.size(); i++) {
            int drawIndex = i - scrollOffset;
            int rowY = listTop + drawIndex * ROW_HEIGHT;

            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) continue;

            PlayerListManager.PlayerEntry entry = players.get(i);

            // Row background
            int bgColor = (i % 2 == 0) ? 0x40000000 : 0x20000000;
            context.fill(listLeft, rowY, listLeft + listWidth, rowY + ROW_HEIGHT - 1, bgColor);

            // Name (bold, with saved color)
            String displayName = entry.name.isEmpty() ? entry.uuid : entry.name;
            context.text(font, Component.literal(displayName).withStyle(ChatFormatting.BOLD),
                    listLeft + PADDING, rowY + 4, 0xFF000000 | entry.color, true);

            // Note preview
            if (!entry.note.isEmpty()) {
                String preview = extractNotePreview(entry.note);
                context.text(font, Component.literal(preview).withStyle(ChatFormatting.GRAY),
                        listLeft + PADDING, rowY + 15, 0xFFAAAAAA, true);
            }

            // Action buttons on the right
            int btnX = listLeft + listWidth - (BUTTON_SIZE + 2) * 4;
            int btnY = rowY + (ROW_HEIGHT - BUTTON_SIZE) / 2;

            // Party button
            drawSmallButton(context, btnX, btnY, "P", 0xFF55FF55, mouseX, mouseY);
            btnX += BUTTON_SIZE + 2;

            // Invite button
            drawSmallButton(context, btnX, btnY, "I", 0xFF55FFFF, mouseX, mouseY);
            btnX += BUTTON_SIZE + 2;

            // Edit button (pencil emoji)
            drawSmallButton(context, btnX, btnY, "\u270F", 0xFFFFFF55, mouseX, mouseY);
            btnX += BUTTON_SIZE + 2;

            // Delete button
            drawSmallButton(context, btnX, btnY, "X", 0xFFFF5555, mouseX, mouseY);
        }

        // Scrollbar
        if (players.size() > visibleRows) {
            int scrollbarX = listLeft + listWidth + 2;
            int scrollbarW = 4;
            int trackHeight = listBottom - listTop;

            // Track background
            context.fill(scrollbarX, listTop, scrollbarX + scrollbarW, listBottom, 0x40FFFFFF);

            // Thumb
            float thumbRatio = (float) visibleRows / players.size();
            int thumbHeight = Math.max(10, (int) (trackHeight * thumbRatio));
            float scrollRatio = maxScroll > 0 ? (float) scrollOffset / maxScroll : 0;
            int thumbY = listTop + (int) ((trackHeight - thumbHeight) * scrollRatio);
            context.fill(scrollbarX, thumbY, scrollbarX + scrollbarW, thumbY + thumbHeight, 0xAAFFFFFF);
        }
    }

    /**
     * Renders a scaled-down mirror of recent chat messages to the left of the player list.
     */
    private void renderChatMirror(GuiGraphicsExtractor context) {
        Minecraft client = Minecraft.getInstance();
        ChatComponent chat = client.gui.getChat();
        List<ChatComponent.OverlayMessage> recentMessages = chat.getRecentChat();

        if (recentMessages == null || recentMessages.isEmpty()) return;

        // Position chat to the left of the player list, at the bottom
        int chatX = 4;
        int chatMaxWidth = listLeft - 10;
        int lineHeight = 9;
        int maxLines = 20;
        int startY = height - 40; // Start from bottom, go up

        int count = Math.min(recentMessages.size(), maxLines);
        for (int i = 0; i < count; i++) {
            ChatComponent.OverlayMessage msg = recentMessages.get(i);
            Component text = msg.content();
            if (text == null) continue;

            int y = startY - (i * lineHeight);
            // Truncate if too wide
            String str = text.getString();
            if (font.width(str) > chatMaxWidth) {
                str = font.plainSubstrByWidth(str, chatMaxWidth - 6) + "...";
                context.text(font, Component.literal(str), chatX, y, 0xFFCCCCCC, true);
            } else {
                context.text(font, text, chatX, y, 0xFFCCCCCC, true);
            }
        }
    }

    /**
     * Renders the color picker dialog — a grid of color swatches.
     */
    private void renderColorPicker(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int dialogW = 160;
        int dialogH = 80;
        int dialogX = width / 2 - dialogW / 2;
        int dialogY = height / 2 - dialogH / 2;

        context.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, 0xEE000000);

        // Title
        Component title = Component.literal("Pick Name Color");
        int tw = font.width(title);
        context.text(font, title, width / 2 - tw / 2, dialogY + 6, -1, true);

        // Color swatches: 5 per row, 2 rows
        int swatchSize = 20;
        int gap = 4;
        int colsPerRow = 5;
        int startX = dialogX + (dialogW - colsPerRow * (swatchSize + gap) + gap) / 2;
        int startY = dialogY + 22;

        for (int i = 0; i < COLOR_OPTIONS.length; i++) {
            int col = i % colsPerRow;
            int row = i / colsPerRow;
            int sx = startX + col * (swatchSize + gap);
            int sy = startY + row * (swatchSize + gap);
            int color = COLOR_OPTIONS[i];

            // Swatch fill
            context.fill(sx, sy, sx + swatchSize, sy + swatchSize, 0xFF000000 | color);

            // Highlight if hovered
            if (mouseX >= sx && mouseX < sx + swatchSize && mouseY >= sy && mouseY < sy + swatchSize) {
                context.fill(sx, sy, sx + swatchSize, sy + 1, -1);
                context.fill(sx, sy + swatchSize - 1, sx + swatchSize, sy + swatchSize, -1);
                context.fill(sx, sy, sx + 1, sy + swatchSize, -1);
                context.fill(sx + swatchSize - 1, sy, sx + swatchSize, sy + swatchSize, -1);
            }

            // Mark current color with a dot
            if (colorPickerIndex >= 0 && colorPickerIndex < PlayerListManager.getPlayers().size()) {
                int currentColor = PlayerListManager.getPlayers().get(colorPickerIndex).color;
                if (color == currentColor) {
                    context.text(font, Component.literal("•"), sx + swatchSize / 2 - 2, sy + swatchSize / 2 - 4, 0xFF000000, false);
                }
            }
        }
    }

    /**
     * Extract a short preview from the note. Shows "Rule, ItemName" if found,
     * otherwise shows first ~40 characters.
     */
    private String extractNotePreview(String note) {
        String rule = extractFieldNextLine(note, "Rule");
        String itemName = extractFieldNextLine(note, "Item Name");

        if (rule != null || itemName != null) {
            StringBuilder sb = new StringBuilder();
            if (rule != null) sb.append(rule);
            if (rule != null && itemName != null) sb.append(", ");
            if (itemName != null) sb.append(itemName);
            return sb.toString();
        }

        // Fallback: show first 40 chars
        if (note.length() > 40) {
            return note.substring(0, 40) + "...";
        }
        return note;
    }

    /**
     * Extract the value on the NEXT line after a label line (e.g. "Item Name\nfarmhand helmet").
     */
    private String extractFieldNextLine(String note, String label) {
        String[] lines = note.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].trim().equalsIgnoreCase(label)) {
                String value = lines[i + 1].trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * Extract player name from /party or /visit command in the note.
     * Looks for lines like "/party DK_wind02" or "/visit DK_wind02".
     */
    private String extractPlayerNameFromNote(String note) {
        String[] lines = note.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("/party ") && trimmed.length() > 7) {
                return trimmed.substring(7).trim();
            }
            if (trimmed.startsWith("/visit ") && trimmed.length() > 7) {
                return trimmed.substring(7).trim();
            }
        }
        return "";
    }

    /**
     * Render tooltip when hovering over the left portion of a player row
     * (head, name, or note area — everything to the left of action buttons).
     */
    private void renderTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        List<PlayerListManager.PlayerEntry> players = PlayerListManager.getPlayers();

        for (int i = 0; i < players.size(); i++) {
            int drawIndex = i - scrollOffset;
            int rowY = listTop + drawIndex * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) continue;

            // Left area is from listLeft to the start of action buttons
            int buttonsStartX = listLeft + listWidth - (BUTTON_SIZE + 2) * 4;
            if (mouseX >= listLeft && mouseX < buttonsStartX
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {

                PlayerListManager.PlayerEntry entry = players.get(i);
                if (!entry.note.isEmpty()) {
                    List<Component> tooltipLines = buildNoteTooltip(entry.note);
                    context.setComponentTooltipForNextFrame(font, tooltipLines, mouseX, mouseY);
                }
                break;
            }
        }
    }

    /**
     * Build a multi-line tooltip from the note content.
     * Formats "label\nvalue" pairs as "Label: value" with bold white titles and gray values.
     * Hides Party/Visit commands, Extra Attrs, and the Profile Match line.
     */
    private List<Component> buildNoteTooltip(String note) {
        List<Component> lines = new ArrayList<>();
        String[] parts = note.split("\n");

        // Known labels that have their value on the next line
        List<String> knownLabels = List.of("Item Name", "Rule", "Profile", "Location",
                "Party Player", "Visit Player", "Extra Attrs");
        // Labels to hide from tooltip (including their value lines)
        List<String> hiddenLabels = List.of("Party Player", "Visit Player");

        for (int i = 0; i < parts.length; i++) {
            String line = parts[i].trim();
            if (line.isEmpty()) continue;

            // Hide the Profile Match line entirely (starts with 🔍 or "Profile Match:")
            if (line.startsWith("\uD83D\uDD0D") || line.startsWith("Profile Match:")) {
                continue;
            }

            // Check if this line is a known label
            boolean isLabel = false;
            for (String label : knownLabels) {
                if (line.equalsIgnoreCase(label)) {
                    isLabel = true;
                    // Check if hidden
                    boolean hidden = false;
                    for (String h : hiddenLabels) {
                        if (line.equalsIgnoreCase(h)) { hidden = true; break; }
                    }
                    if (hidden) {
                        // Skip all following value lines until next known label or end
                        // For Extra Attrs, skip multiple lines
                        if (line.equalsIgnoreCase("Extra Attrs")) {
                            while (i + 1 < parts.length) {
                                String next = parts[i + 1].trim();
                                boolean isNextLabel = false;
                                for (String l : knownLabels) {
                                    if (next.equalsIgnoreCase(l)) { isNextLabel = true; break; }
                                }
                                if (isNextLabel || next.isEmpty()) break;
                                i++;
                            }
                        } else {
                            i++; // skip the single value line
                        }
                    } else if (line.equalsIgnoreCase("Extra Attrs")) {
                        // Show the extra attrs values without the heading
                        while (i + 1 < parts.length) {
                            String next = parts[i + 1].trim();
                            boolean isNextLabel = false;
                            for (String l : knownLabels) {
                                if (next.equalsIgnoreCase(l)) { isNextLabel = true; break; }
                            }
                            if (isNextLabel || next.isEmpty()) break;
                            i++;
                            lines.add(Component.literal(next).withStyle(ChatFormatting.GRAY));
                        }
                    } else if (i + 1 < parts.length) {
                        String value = parts[i + 1].trim();
                        // Bold white title + gray value
                        Component titlePart = Component.literal(label + ": ").withStyle(ChatFormatting.BOLD, ChatFormatting.WHITE);
                        Component valuePart = Component.literal(value).withStyle(ChatFormatting.GRAY);
                        lines.add(Component.empty().append(titlePart).append(valuePart));
                        i++; // skip value line
                    }
                    break;
                }
            }
            if (!isLabel) {
                // Hide lines starting with /party or /visit
                if (!line.startsWith("/party") && !line.startsWith("/visit")) {
                    lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        return lines;
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

        // Get mouse position from Minecraft
        Minecraft client = Minecraft.getInstance();
        double mouseX = client.mouseHandler.xpos() * (double) client.getWindow().getGuiScaledWidth() / (double) client.getWindow().getScreenWidth();
        double mouseY = client.mouseHandler.ypos() * (double) client.getWindow().getGuiScaledHeight() / (double) client.getWindow().getScreenHeight();

        // Handle color picker clicks
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

            for (int i = 0; i < COLOR_OPTIONS.length; i++) {
                int col = i % colsPerRow;
                int row = i / colsPerRow;
                int sx = startX + col * (swatchSize + gap);
                int sy = startY + row * (swatchSize + gap);
                if (isInBounds(mouseX, mouseY, sx, sy, swatchSize, swatchSize)) {
                    PlayerListManager.updateColor(colorPickerIndex, COLOR_OPTIONS[i]);
                    closeDialog();
                    return true;
                }
            }
            // Click outside closes
            closeDialog();
            return true;
        }

        if (addDialogOpen || editDialogOpen) return false;

        // Check action button clicks and name/note area clicks in the player list
        List<PlayerListManager.PlayerEntry> players = PlayerListManager.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            int drawIndex = i - scrollOffset;
            int rowY = listTop + drawIndex * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) continue;

            int btnX = listLeft + listWidth - (BUTTON_SIZE + 2) * 4;
            int btnY = rowY + (ROW_HEIGHT - BUTTON_SIZE) / 2;

            PlayerListManager.PlayerEntry entry = players.get(i);
            String cmdName = entry.name.isEmpty() ? entry.uuid : entry.name;

            // Party
            if (isInBounds(mouseX, mouseY, btnX, btnY, BUTTON_SIZE, BUTTON_SIZE)) {
                sendCommand("party " + cmdName);
                return true;
            }
            btnX += BUTTON_SIZE + 2;

            // Invite
            if (isInBounds(mouseX, mouseY, btnX, btnY, BUTTON_SIZE, BUTTON_SIZE)) {
                sendCommand("invite " + cmdName);
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
                PlayerListManager.removePlayer(i);
                return true;
            }

            // Click on name/description area (left of action buttons) opens color picker
            int buttonsStartX = listLeft + listWidth - (BUTTON_SIZE + 2) * 4;
            if (isInBounds(mouseX, mouseY, listLeft, rowY, buttonsStartX - listLeft, ROW_HEIGHT)) {
                colorPickerOpen = true;
                colorPickerIndex = i;
                clearWidgets();
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
    public boolean keyPressed(KeyEvent event) {
        if (addDialogOpen || editDialogOpen || colorPickerOpen) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                closeDialog();
                return true;
            }
            if (colorPickerOpen) return true; // swallow other keys
            // Intercept Ctrl+V paste for note fields to preserve newlines
            long window = Minecraft.getInstance().getWindow().handle();
            boolean ctrlHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
            if (event.key() == GLFW.GLFW_KEY_V && ctrlHeld) {
                EditBox activeNote = addDialogOpen ? noteField : editNoteField;
                if (activeNote != null && activeNote.isFocused()) {
                    String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
                    if (clipboard != null && !clipboard.isEmpty()) {
                        // Convert real newlines to literal \n for single-line EditBox storage
                        String encoded = clipboard.replace("\n", "\\n").replace("\r", "");
                        activeNote.insertText(encoded);
                        return true;
                    }
                }
            }
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

    private void sendCommand(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.connection.sendCommand(command);
        }
    }

    /**
     * Encode real newlines as the literal string "\\n" for display in single-line EditBox.
     */
    private static String encodeNewlines(String text) {
        return text.replace("\n", "\\n");
    }

    /**
     * Decode the literal string "\\n" back to real newlines for storage.
     */
    private static String decodeNewlines(String text) {
        return text.replace("\\n", "\n");
    }
}
