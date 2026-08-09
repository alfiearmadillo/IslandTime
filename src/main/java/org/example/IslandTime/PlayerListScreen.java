package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;

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
            // "Add Player" button pinned to top
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
        noteField.setMaxLength(128);
        noteField.setHint(Component.literal("Note (optional)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(noteField);

        addRenderableWidget(Button.builder(Component.literal("Add"), btn -> {
            String input = nameField.getValue().trim();
            if (!input.isEmpty()) {
                String uuid = "";
                String name = input;
                // If input looks like a UUID, set it as uuid
                if (input.length() > 16 && input.contains("-")) {
                    uuid = input;
                    name = "";
                }
                PlayerListManager.addPlayer(uuid, name, noteField.getValue().trim());
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
        editNoteField.setMaxLength(128);
        editNoteField.setValue(entry.note);
        addRenderableWidget(editNoteField);

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> {
            PlayerListManager.updatePlayer(editIndex, editNameField.getValue().trim(), editNoteField.getValue().trim());
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
        context.text(font, titleComp, width / 2 - titleWidth / 2, 2, 0xFFFFFF, true);

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
            context.text(font, titleC, width / 2 - tw / 2, dialogY + 8, 0xFFFFFF, true);
        }
    }

    private void renderPlayerList(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        List<PlayerListManager.PlayerEntry> players = PlayerListManager.getPlayers();

        if (players.isEmpty()) {
            Component emptyMsg = Component.literal("No players added yet. Click '+ Add Player' to get started.")
                    .withStyle(ChatFormatting.GRAY);
            int emptyW = font.width(emptyMsg);
            context.text(font, emptyMsg, width / 2 - emptyW / 2, height / 2, 0xAAAAAA, true);
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

            // Player head placeholder (small colored square)
            context.fill(listLeft + PADDING, rowY + PADDING + 2, listLeft + PADDING + 8, rowY + PADDING + 10, 0xFF888888);

            // Name
            String displayName = entry.name.isEmpty() ? entry.uuid : entry.name;
            context.text(font, Component.literal(displayName), listLeft + PADDING + 14, rowY + 4, 0xFFFFFF, true);

            // Note
            if (!entry.note.isEmpty()) {
                context.text(font, Component.literal(entry.note), listLeft + PADDING + 14, rowY + 15, 0xAAAAAA, true);
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

            // Edit button
            drawSmallButton(context, btnX, btnY, "E", 0xFFFFFF55, mouseX, mouseY);
            btnX += BUTTON_SIZE + 2;

            // Delete button
            drawSmallButton(context, btnX, btnY, "X", 0xFFFF5555, mouseX, mouseY);
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
}
