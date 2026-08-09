package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
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

        if (!addDialogOpen && !editDialogOpen) {
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
            if (!input.isEmpty()) {
                String uuid = "";
                String name = input;
                if (input.length() > 16 && input.contains("-")) {
                    uuid = input;
                    name = "";
                }
                PlayerListManager.addPlayer(uuid, name, decodeNewlines(noteField.getValue().trim()));
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
        editIndex = -1;
        clearWidgets();
        init();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Background darkening
        context.fill(0, 0, width, height, 0xC0101010);

        // Title
        Component titleComp = Component.literal("Player List").withStyle(ChatFormatting.GOLD);
        int titleWidth = font.width(titleComp);
        context.text(font, titleComp, width / 2 - titleWidth / 2, 2, -1, true);

        // Draw player list (when no dialog is open)
        if (!addDialogOpen && !editDialogOpen) {
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

        // Render widgets (buttons, edit boxes) via parent
        super.extractRenderState(context, mouseX, mouseY, delta);

        // Tooltip (rendered after everything else so it's on top)
        if (!addDialogOpen && !editDialogOpen) {
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

            // Player head — try to get skin from tab list, fall back to default
            renderPlayerHead(context, entry, listLeft + PADDING, rowY + PADDING);

            // Name
            String displayName = entry.name.isEmpty() ? entry.uuid : entry.name;
            context.text(font, Component.literal(displayName).withStyle(ChatFormatting.WHITE),
                    listLeft + PADDING + 22, rowY + 4, -1, true);

            // Note preview — extract "Profile Match: X" or show first ~30 chars
            if (!entry.note.isEmpty()) {
                String preview = extractNotePreview(entry.note);
                context.text(font, Component.literal(preview).withStyle(ChatFormatting.GRAY),
                        listLeft + PADDING + 22, rowY + 15, 0xFFAAAAAA, true);
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
    }

    private void renderPlayerHead(GuiGraphicsExtractor context, PlayerListManager.PlayerEntry entry, int x, int y) {
        // Render a colored square with the player's initial as a head placeholder
        // Full skin rendering requires blit which isn't available on GuiGraphicsExtractor
        String displayName = entry.name.isEmpty() ? entry.uuid : entry.name;
        String initial = displayName.isEmpty() ? "?" : displayName.substring(0, 1).toUpperCase();

        // Check if player is online (green tint) or offline (gray)
        boolean online = false;
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null && !entry.name.isEmpty()) {
            for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
                if (info.getProfile().name().equalsIgnoreCase(entry.name)) {
                    online = true;
                    break;
                }
            }
        }

        int bgColor = online ? 0xFF2D5A2D : 0xFF3A3A3A;
        context.fill(x, y, x + 18, y + 18, bgColor);
        // Border
        context.fill(x, y, x + 18, y + 1, 0xFF555555);
        context.fill(x, y + 17, x + 18, y + 18, 0xFF555555);
        context.fill(x, y, x + 1, y + 18, 0xFF555555);
        context.fill(x + 17, y, x + 18, y + 18, 0xFF555555);
        // Initial letter centered
        Component initialComp = Component.literal(initial);
        int iw = font.width(initialComp);
        context.text(font, initialComp, x + 9 - iw / 2, y + 5, online ? 0xFF55FF55 : 0xFFCCCCCC, true);
    }

    /**
     * Extract a short preview from the note. Looks for "Profile Match: X" pattern,
     * otherwise shows first ~40 characters.
     */
    private String extractNotePreview(String note) {
        // Look for the profile match pattern in the Discord message format
        String marker = "Profile Match:";
        int idx = note.indexOf(marker);
        if (idx != -1) {
            // Extract from "Profile Match:" to end of that segment (newline or end)
            String sub = note.substring(idx);
            int newline = sub.indexOf('\n');
            if (newline == -1) newline = sub.length();
            String match = sub.substring(0, Math.min(newline, 50)).trim();
            return "\uD83D\uDD0D " + match; // 🔍 prefix
        }

        // Also check for the emoji prefix format
        marker = "\uD83D\uDD0D Profile Match:";
        idx = note.indexOf(marker);
        if (idx != -1) {
            String sub = note.substring(idx);
            int newline = sub.indexOf('\n');
            if (newline == -1) newline = sub.length();
            return sub.substring(0, Math.min(newline, 50)).trim();
        }

        // Fallback: show first 40 chars
        if (note.length() > 40) {
            return note.substring(0, 40) + "...";
        }
        return note;
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
     */
    private List<Component> buildNoteTooltip(String note) {
        List<Component> lines = new ArrayList<>();
        String[] parts = note.split("\n");
        for (String part : parts) {
            if (part.isEmpty()) {
                lines.add(Component.empty());
            } else {
                lines.add(Component.literal(part).withStyle(ChatFormatting.GRAY));
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

        if (addDialogOpen || editDialogOpen) return false;

        // Get mouse position from Minecraft
        Minecraft client = Minecraft.getInstance();
        double mouseX = client.mouseHandler.xpos() * (double) client.getWindow().getGuiScaledWidth() / (double) client.getWindow().getScreenWidth();
        double mouseY = client.mouseHandler.ypos() * (double) client.getWindow().getGuiScaledHeight() / (double) client.getWindow().getScreenHeight();

        // Check action button clicks in the player list
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
        if (addDialogOpen || editDialogOpen) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                closeDialog();
                return true;
            }
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
