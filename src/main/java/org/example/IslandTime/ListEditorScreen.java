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

import java.util.ArrayList;
import java.util.List;

/**
 * Shared base for the list-editing screens (Huntrap data, fuel data sets), styled like the player list and
 * greenhouse blueprint screens: a title, a row of header buttons, a status line, and a scrollable list with one
 * row per item and right-aligned buttons. A small form dialog (text boxes and cycle buttons) is built in.
 *
 * ESC or E goes back to the screen this one was opened from (or closes it), except that ESC closes an open dialog
 * first, and E types normally while a text box is focused.
 */
abstract class ListEditorScreen extends Screen {

    protected static final int ROW_HEIGHT = 30;
    protected static final int BUTTON_HEIGHT = 20;
    protected static final int BUTTON_GAP = 3;
    protected static final int PADDING = 4;
    protected static final int LIST_WIDTH = 400;
    protected static final int HEADER_Y = 18;
    protected static final int DEFAULT_LIST_TOP = 56;

    private static final int STATUS_Y = 42;
    private static final long STATUS_MS = 6_000L;
    private static final long CONFIRM_MS = 3_000L;

    private static final int DIALOG_WIDTH = 240;
    private static final int DIALOG_FIELD_WIDTH = 220;
    private static final int DIALOG_FIELD_STEP = 38;

    /**
     * A button on the right of a row. A button with a {@code confirmKey} needs a second click within a few seconds
     * and shows {@code armedLabel} in between.
     */
    protected record RowButton(String label, String armedLabel, int width, int color, Runnable action,
                               String confirmKey, List<Component> tooltip) {
    }

    /** One row of the list. A {@code background} of 0 uses the usual alternating shade. */
    protected record Row(String title, String subtitle, int background, List<RowButton> buttons) {
    }

    protected record TextField(String label, String initial, int maxLength, String hint) {
    }

    protected record ChoiceField(String label, List<String> options, int initial) {
    }

    /** Runs when a form's Save button is pressed. Returns null when it worked, otherwise a message to show. */
    protected interface FormSubmit {
        String submit(String[] texts, int[] choices);
    }

    protected final Screen parent;

    private int scrollOffset = 0;
    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listWidth;
    private boolean draggingScrollbar = false;

    private String statusMessage = "";
    private boolean statusOk = true;
    private long statusUntilMs = 0;

    private String armedKey = "";
    private long armedUntilMs = 0;

    private String dialogTitle = null;
    private String dialogSaveLabel = "Save";
    private List<TextField> dialogTexts = List.of();
    private List<ChoiceField> dialogChoices = List.of();
    private String[] dialogTextValues = new String[0];
    private int[] dialogChoiceValues = new int[0];
    private FormSubmit dialogSubmit;
    private String dialogError = "";

    protected ListEditorScreen(Screen parent, String title) {
        super(Component.literal(title));
        this.parent = parent;
    }

    // -------------------------------------------------------------------------
    // What a screen provides
    // -------------------------------------------------------------------------

    /** The text at the top. */
    protected abstract String screenTitle();

    /** The rows to show, rebuilt every frame and on every click, so they always match the data. */
    protected abstract List<Row> rows();

    /** Adds the buttons (and text boxes) of the header. Not called while a dialog is open. */
    protected abstract void addHeaderWidgets();

    /** Shown in the middle of the screen when there are no rows. */
    protected String emptyMessage() {
        return "Nothing here yet.";
    }

    /** Gray text on the status line while no message is up. */
    protected String idleStatus() {
        return "";
    }

    /** The y the list starts at. Screens with a search box under the header return a larger value. */
    protected int listTop() {
        return DEFAULT_LIST_TOP;
    }

    /** True while a text box in the header has focus, so that E types instead of closing the screen. */
    protected boolean isTextInputFocused() {
        return false;
    }

    /** Called every frame while no dialog is open, before the list is drawn. */
    protected void onFrame(GuiGraphicsExtractor context) {
    }

    // -------------------------------------------------------------------------
    // Helpers for subclasses
    // -------------------------------------------------------------------------

    protected static RowButton button(String label, int width, int color, Runnable action, String... tooltip) {
        return new RowButton(label, label, width, color, action, null, lines(tooltip));
    }

    protected static RowButton confirmButton(String label, String armedLabel, int width, int color, String confirmKey,
                                             Runnable action, String... tooltip) {
        return new RowButton(label, armedLabel, width, color, action, confirmKey, lines(tooltip));
    }

    private static List<Component> lines(String... text) {
        List<Component> list = new ArrayList<>();
        for (String line : text) list.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        return list;
    }

    protected final void setStatus(String message, boolean ok) {
        statusMessage = message;
        statusOk = ok;
        statusUntilMs = System.currentTimeMillis() + STATUS_MS;
    }

    /** True while {@code key} was clicked once recently and is waiting for a second click. */
    protected final boolean isArmed(String key) {
        return key != null && key.equals(armedKey) && System.currentTimeMillis() < armedUntilMs;
    }

    /** The first click arms {@code key} and returns false. A second click soon after returns true. */
    protected final boolean consumeConfirm(String key) {
        if (isArmed(key)) {
            armedKey = "";
            return true;
        }
        armedKey = key;
        armedUntilMs = System.currentTimeMillis() + CONFIRM_MS;
        return false;
    }

    protected final void scrollToEnd() {
        scrollOffset = Integer.MAX_VALUE / 2;
    }

    protected final boolean isDialogOpen() {
        return dialogTitle != null;
    }

    protected final String fit(String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    protected final int contentLeft() {
        return listLeft;
    }

    protected final int contentWidth() {
        return listWidth;
    }

    /** Opens a form. Nothing else on the screen reacts until it is saved or cancelled. */
    protected final void openForm(String title, String saveLabel, List<TextField> texts, List<ChoiceField> choices,
                                  FormSubmit submit) {
        dialogTitle = title;
        dialogSaveLabel = saveLabel;
        dialogTexts = texts;
        dialogChoices = choices;
        dialogTextValues = new String[texts.size()];
        for (int i = 0; i < texts.size(); i++) dialogTextValues[i] = texts.get(i).initial();
        dialogChoiceValues = new int[choices.size()];
        for (int i = 0; i < choices.size(); i++) dialogChoiceValues[i] = choices.get(i).initial();
        dialogSubmit = submit;
        dialogError = "";
        clearWidgets();
        init();
    }

    protected final void closeDialog() {
        dialogTitle = null;
        dialogSubmit = null;
        dialogError = "";
        clearWidgets();
        init();
    }

    private void submitDialog() {
        if (dialogSubmit == null) return;
        String error = dialogSubmit.submit(dialogTextValues.clone(), dialogChoiceValues.clone());
        if (error == null) {
            closeDialog();
        } else {
            dialogError = error;
        }
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        listWidth = LIST_WIDTH;
        listLeft = width / 2 - listWidth / 2;
        listTop = listTop();
        listBottom = height - 10;

        if (dialogTitle != null) {
            buildDialogWidgets();
        } else {
            addHeaderWidgets();
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    }

    private void clampScroll(int rowCount) {
        int maxScroll = Math.max(0, rowCount - visibleRows());
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    /** The y of a row, or {@link Integer#MIN_VALUE} if it is scrolled out of view. */
    private int rowY(int index) {
        int y = listTop + (index - scrollOffset) * ROW_HEIGHT;
        return y < listTop || y + ROW_HEIGHT > listBottom ? Integer.MIN_VALUE : y;
    }

    private int buttonY(int rowY) {
        return rowY + (ROW_HEIGHT - BUTTON_HEIGHT) / 2;
    }

    /** The x of a row's button, with the buttons packed against the right edge of the list. */
    private int buttonX(Row row, int index) {
        int end = listLeft + listWidth - PADDING;
        for (int i = row.buttons().size() - 1; i >= 0; i--) {
            int start = end - row.buttons().get(i).width();
            if (i == index) return start;
            end = start - BUTTON_GAP;
        }
        return end;
    }

    private int textRight(Row row) {
        return row.buttons().isEmpty() ? listLeft + listWidth - PADDING : buttonX(row, 0) - PADDING;
    }

    /** The row button under the mouse, or null. */
    private RowButton buttonAt(double mouseX, double mouseY, List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            int rowY = rowY(i);
            if (rowY == Integer.MIN_VALUE) continue;
            Row row = rows.get(i);
            for (int j = 0; j < row.buttons().size(); j++) {
                RowButton button = row.buttons().get(j);
                if (isInBounds(mouseX, mouseY, buttonX(row, j), buttonY(rowY), button.width(), BUTTON_HEIGHT)) {
                    return button;
                }
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

        Component title = Component.literal(fit(screenTitle(), width - 20)).withStyle(ChatFormatting.GOLD);
        context.text(font, title, width / 2 - font.width(title) / 2, 6, -1, true);

        long now = System.currentTimeMillis();
        List<Row> rows = List.of();
        if (dialogTitle == null) {
            onFrame(context);
            rows = rows();
            renderStatus(context, now);
            renderList(context, rows, mouseX, mouseY);
        } else {
            renderDialogBackground(context);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);

        if (dialogTitle == null) renderTooltip(context, rows, mouseX, mouseY);
    }

    private void renderStatus(GuiGraphicsExtractor context, long now) {
        String shown;
        int color;
        if (!statusMessage.isEmpty() && now < statusUntilMs) {
            shown = statusMessage;
            color = statusOk ? 0xFF55FF55 : 0xFFFF5555;
        } else {
            shown = idleStatus();
            color = 0xFFAAAAAA;
        }
        if (shown.isEmpty()) return;
        shown = fit(shown, width - 20);
        context.text(font, Component.literal(shown), width / 2 - font.width(shown) / 2, STATUS_Y, color, true);
    }

    private void renderList(GuiGraphicsExtractor context, List<Row> rows, int mouseX, int mouseY) {
        if (rows.isEmpty()) {
            Component empty = Component.literal(emptyMessage()).withStyle(ChatFormatting.GRAY);
            context.text(font, empty, width / 2 - font.width(empty) / 2, height / 2, 0xFFAAAAAA, true);
            return;
        }

        clampScroll(rows.size());
        int visibleRows = visibleRows();
        int maxScroll = Math.max(0, rows.size() - visibleRows);

        for (int i = 0; i < rows.size(); i++) {
            int rowY = rowY(i);
            if (rowY == Integer.MIN_VALUE) continue;
            Row row = rows.get(i);

            int bg = row.background() != 0 ? row.background() : (i % 2 == 0 ? 0x40000000 : 0x20000000);
            context.fill(listLeft, rowY, listLeft + listWidth, rowY + ROW_HEIGHT - 1, bg);

            int textWidth = textRight(row) - listLeft - PADDING;
            context.text(font, Component.literal(fit(row.title(), textWidth)).withStyle(ChatFormatting.BOLD,
                    ChatFormatting.WHITE), listLeft + PADDING, rowY + 5, 0xFFFFFFFF, true);
            context.text(font, Component.literal(fit(row.subtitle(), textWidth)).withStyle(ChatFormatting.GRAY),
                    listLeft + PADDING, rowY + 17, 0xFFAAAAAA, true);

            for (int j = 0; j < row.buttons().size(); j++) {
                RowButton button = row.buttons().get(j);
                boolean armed = isArmed(button.confirmKey());
                drawButton(context, buttonX(row, j), buttonY(rowY), button.width(),
                        armed ? button.armedLabel() : button.label(), button.color(), mouseX, mouseY);
            }
        }

        if (rows.size() > visibleRows) {
            int scrollbarX = listLeft + listWidth + 2;
            int effectiveBottom = listTop + visibleRows * ROW_HEIGHT;
            int trackHeight = effectiveBottom - listTop;
            context.fill(scrollbarX, listTop, scrollbarX + 4, effectiveBottom, 0x40FFFFFF);
            float thumbRatio = (float) visibleRows / rows.size();
            int thumbHeight = Math.max(10, (int) (trackHeight * thumbRatio));
            float scrollRatio = maxScroll > 0 ? (float) scrollOffset / maxScroll : 0;
            int thumbY = listTop + (int) ((trackHeight - thumbHeight) * scrollRatio);
            context.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xAAFFFFFF);
        }
    }

    private void renderTooltip(GuiGraphicsExtractor context, List<Row> rows, int mouseX, int mouseY) {
        RowButton hit = buttonAt(mouseX, mouseY, rows);
        if (hit == null || hit.tooltip().isEmpty()) return;
        List<Component> lines = new ArrayList<>(hit.tooltip());
        if (hit.confirmKey() != null) {
            lines.add(Component.literal(isArmed(hit.confirmKey()) ? "Click again to confirm" : "Click twice to confirm")
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
    // The form dialog
    // -------------------------------------------------------------------------

    private int dialogHeight() {
        int fields = dialogTexts.size() + dialogChoices.size();
        return 24 + fields * DIALOG_FIELD_STEP + 16 + 30;
    }

    private int dialogX() {
        return width / 2 - DIALOG_WIDTH / 2;
    }

    private int dialogY() {
        return Math.max(2, height / 2 - dialogHeight() / 2);
    }

    private int fieldY(int index) {
        return dialogY() + 24 + index * DIALOG_FIELD_STEP;
    }

    private void buildDialogWidgets() {
        int fieldX = dialogX() + (DIALOG_WIDTH - DIALOG_FIELD_WIDTH) / 2;
        EditBox first = null;

        for (int i = 0; i < dialogTexts.size(); i++) {
            final int index = i;
            TextField field = dialogTexts.get(i);
            EditBox box = new EditBox(font, fieldX, fieldY(i) + 11, DIALOG_FIELD_WIDTH, 20,
                    Component.literal(field.label()));
            box.setMaxLength(field.maxLength());
            box.setValue(dialogTextValues[i]);
            if (!field.hint().isEmpty()) {
                box.setHint(Component.literal(field.hint()).withStyle(ChatFormatting.GRAY));
            }
            box.setResponder(value -> dialogTextValues[index] = value);
            addRenderableWidget(box);
            if (first == null) first = box;
        }

        for (int i = 0; i < dialogChoices.size(); i++) {
            final int index = i;
            ChoiceField field = dialogChoices.get(i);
            int y = fieldY(dialogTexts.size() + i) + 11;
            addRenderableWidget(Button.builder(Component.literal(field.options().get(dialogChoiceValues[i])), btn -> {
                dialogChoiceValues[index] = (dialogChoiceValues[index] + 1) % field.options().size();
                btn.setMessage(Component.literal(field.options().get(dialogChoiceValues[index])));
            }).bounds(fieldX, y, DIALOG_FIELD_WIDTH, 20).build());
        }

        int buttonsY = dialogY() + dialogHeight() - 28;
        int half = (DIALOG_FIELD_WIDTH - 10) / 2;
        addRenderableWidget(Button.builder(Component.literal(dialogSaveLabel), btn -> submitDialog())
                .bounds(fieldX, buttonsY, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> closeDialog())
                .bounds(fieldX + half + 10, buttonsY, half, 20).build());

        if (first != null) setFocused(first);
    }

    private void renderDialogBackground(GuiGraphicsExtractor context) {
        int x = dialogX();
        int y = dialogY();
        context.fill(x, y, x + DIALOG_WIDTH, y + dialogHeight(), 0xEE000000);

        Component title = Component.literal(dialogTitle);
        context.text(font, title, width / 2 - font.width(title) / 2, y + 6, -1, true);

        int labelX = x + (DIALOG_WIDTH - DIALOG_FIELD_WIDTH) / 2;
        for (int i = 0; i < dialogTexts.size(); i++) {
            context.text(font, Component.literal(dialogTexts.get(i).label() + ":").withStyle(ChatFormatting.GRAY),
                    labelX, fieldY(i) + 1, 0xFFAAAAAA, true);
        }
        for (int i = 0; i < dialogChoices.size(); i++) {
            context.text(font, Component.literal(dialogChoices.get(i).label() + ":").withStyle(ChatFormatting.GRAY),
                    labelX, fieldY(dialogTexts.size() + i) + 1, 0xFFAAAAAA, true);
        }

        if (!dialogError.isEmpty()) {
            int errorY = y + dialogHeight() - 28 - 12;
            String shown = fit(dialogError, DIALOG_WIDTH - 12);
            context.text(font, Component.literal(shown), width / 2 - font.width(shown) / 2, errorY, 0xFFFF5555, true);
        }
    }

    // -------------------------------------------------------------------------
    // Input handling
    // -------------------------------------------------------------------------

    private static double guiMouseX() {
        Minecraft client = Minecraft.getInstance();
        return client.mouseHandler.xpos() * (double) client.getWindow().getGuiScaledWidth()
                / (double) client.getWindow().getScreenWidth();
    }

    private static double guiMouseY() {
        Minecraft client = Minecraft.getInstance();
        return client.mouseHandler.ypos() * (double) client.getWindow().getGuiScaledHeight()
                / (double) client.getWindow().getScreenHeight();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (dialogTitle != null) return true;

        double mouseX = guiMouseX();
        double mouseY = guiMouseY();

        List<Row> rows = rows();
        if (rows.size() > visibleRows()) {
            int scrollbarX = listLeft + listWidth + 2;
            if (isInBounds(mouseX, mouseY, scrollbarX - 2, listTop, 8, listBottom - listTop)) {
                draggingScrollbar = true;
                updateScrollFromMouse(mouseY, rows.size());
                return true;
            }
        }

        RowButton hit = buttonAt(mouseX, mouseY, rows);
        if (hit != null) {
            if (hit.confirmKey() == null || consumeConfirm(hit.confirmKey())) hit.action().run();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (dialogTitle != null) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        scrollOffset -= (int) verticalAmount;
        clampScroll(rows().size());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (draggingScrollbar) {
            updateScrollFromMouse(guiMouseY(), rows().size());
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
        if (dialogTitle != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                closeDialog();
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                submitDialog();
                return true;
            }
            return super.keyPressed(event);
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (isTextInputFocused()) return super.keyPressed(event);
        if (event.key() == GLFW.GLFW_KEY_E) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    protected static boolean isInBounds(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
