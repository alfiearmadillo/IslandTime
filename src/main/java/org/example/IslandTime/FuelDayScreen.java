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

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * GUI screen for the fuel day tracker. Displays a scrollable list of per-fuel-day
 * fuel/profit records, one row per real 24h fuel-day (see FuelCycleTracker) — placing
 * a multi-fuel stack creates several rows immediately (the currently active day plus
 * future/queued ones), rather than one row per calendar date. Rows are colored on a
 * red → yellow → green gradient
 * based on that cycle's AFK percentage. Every value is directly editable, similar to the
 * Block Highlights and Player List GUIs. While a day is still actively ticking, its AFK%
 * (and everything derived from it — mob-kill coins, profit) reflects progress against time
 * elapsed so far today rather than the full 24h, and refreshes once a second rather than
 * every render frame.
 */
public class FuelDayScreen extends Screen {

    private static final int ROW_HEIGHT = 54;
    private static final int HEADER_HEIGHT = 40;
    private static final int BUTTON_SIZE = 20;
    private static final int PADDING = 4;
    private static final int TOTAL_PROFIT_WIDGET_GAP = 12;
    private static final int TOTAL_PROFIT_WIDGET_WIDTH = 110;

    private int scrollOffset = 0;
    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listWidth;

    // Add dialog
    private boolean addDialogOpen = false;
    private EditBox addDateField;

    // Edit dialog
    private boolean editDialogOpen = false;
    private String editDate = null;
    private EditBox editMinionField;
    private EditBox editMobKillField;
    private EditBox editGabagoolPriceField;
    private EditBox editGabagoolAmountField;
    private EditBox editFuelCostField;
    private EditBox editNotesField;

    // Scrollbar
    private boolean draggingScrollbar = false;

    // Live values (AFK%, mob-kill coins, profit) are frozen to whatever they were at the
    // last refresh and only recomputed once per second (see extractRenderState()), rather
    // than recalculating off a constantly-moving "now" on every render frame — so the
    // numbers you see visibly change once a second while watching this GUI live, not on
    // every single frame.
    private static final long LIVE_VALUE_REFRESH_INTERVAL_MS = 1000L;
    private long displayNowMs = System.currentTimeMillis();
    private long lastLiveValueRefreshMs = 0;

    public FuelDayScreen() {
        super(Component.literal("Fuel Day Tracker"));
    }

    @Override
    protected void init() {
        super.init();
        listLeft = width / 2 - 200;
        listWidth = 400;
        listTop = HEADER_HEIGHT + 10;
        listBottom = height - 10;

        if (!addDialogOpen && !editDialogOpen) {
            addRenderableWidget(Button.builder(Component.literal("+ Add Day"), btn -> openAddDialog())
                    .bounds(width / 2 - 55, 10, 110, 20)
                    .build());
        }
    }

    // -------------------------------------------------------------------------
    // Dialogs
    // -------------------------------------------------------------------------

    private void openAddDialog() {
        addDialogOpen = true;
        clearWidgets();

        int dialogX = width / 2 - 100;
        int dialogY = height / 2 - 40;

        addDateField = new EditBox(font, dialogX, dialogY + 14, 200, 20, Component.literal("Date"));
        addDateField.setMaxLength(10);
        addDateField.setValue(LocalDate.now().toString());
        addDateField.setHint(Component.literal("YYYY-MM-DD").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(addDateField);

        addRenderableWidget(Button.builder(Component.literal("Add"), btn -> {
            String date = addDateField.getValue().trim();
            if (isValidDate(date)) {
                FuelCycleTracker.addManualDayRecord(date);
                closeDialog();
            }
        }).bounds(dialogX, dialogY + 48, 95, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> closeDialog())
                .bounds(dialogX + 105, dialogY + 48, 95, 20).build());

        setFocused(addDateField);
    }

    private void openEditDialog(String date) {
        FuelCycleTracker.DayRecord record = FuelCycleTracker.getDayRecord(date);
        if (record == null) return;

        editDialogOpen = true;
        editDate = date;
        clearWidgets();

        int dialogX = width / 2 - 130;
        int dialogY = height / 2 - 127;

        editMinionField = new EditBox(font, dialogX, dialogY + 20, 240, 20, Component.literal("Minions"));
        editMinionField.setMaxLength(8);
        editMinionField.setValue(record.manualMinionCount != null
                ? String.valueOf(record.manualMinionCount)
                : "");
        editMinionField.setHint(Component.literal("auto: " + record.minionsRefueledAuto + " (or +/-N)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(editMinionField);

        editMobKillField = new EditBox(font, dialogX, dialogY + 54, 240, 20, Component.literal("Coins @ 100%"));
        editMobKillField.setMaxLength(16);
        editMobKillField.setValue(String.valueOf(record.mobKillCoinsAt100));
        addRenderableWidget(editMobKillField);

        editGabagoolPriceField = new EditBox(font, dialogX, dialogY + 88, 115, 20, Component.literal("Gabagool Price"));
        editGabagoolPriceField.setMaxLength(16);
        editGabagoolPriceField.setValue(String.valueOf(record.gabagoolPrice));
        addRenderableWidget(editGabagoolPriceField);

        editGabagoolAmountField = new EditBox(font, dialogX + 125, dialogY + 88, 115, 20, Component.literal("Gabagool Amount"));
        editGabagoolAmountField.setMaxLength(16);
        editGabagoolAmountField.setValue(record.manualGabagoolAmount != null
                ? String.valueOf(record.manualGabagoolAmount)
                : "");
        editGabagoolAmountField.setHint(Component.literal("auto: " + record.gabagoolAmountAuto + " (or +/-N)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(editGabagoolAmountField);

        editFuelCostField = new EditBox(font, dialogX, dialogY + 122, 240, 20, Component.literal("Fuel Cost"));
        editFuelCostField.setMaxLength(16);
        editFuelCostField.setValue(record.manualFuelCost != null
                ? String.valueOf(record.manualFuelCost)
                : "");
        editFuelCostField.setHint(Component.literal("auto: " + String.format("%.1f", record.fuelCost) + " (or +/-N)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(editFuelCostField);

        editNotesField = new EditBox(font, dialogX, dialogY + 156, 240, 20, Component.literal("Notes"));
        editNotesField.setMaxLength(128);
        editNotesField.setValue(record.notes != null ? record.notes : "");
        addRenderableWidget(editNotesField);

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> {
            String minionText = editMinionField.getValue().trim();
            if (minionText.isEmpty()) {
                FuelCycleTracker.setManualMinionCount(editDate, null);
            } else {
                try {
                    int minionValue = (int) Math.round(resolveDeltaOrAbsolute(minionText, record.minionsRefueledAuto));
                    FuelCycleTracker.setManualMinionCount(editDate, minionValue);
                } catch (NumberFormatException ignored) {}
            }
            try {
                FuelCycleTracker.setMobKillCoinsAt100(editDate, Double.parseDouble(editMobKillField.getValue().trim()));
            } catch (NumberFormatException ignored) {}
            try {
                FuelCycleTracker.setGabagoolPrice(editDate, Double.parseDouble(editGabagoolPriceField.getValue().trim()));
            } catch (NumberFormatException ignored) {}
            String gabagoolAmountText = editGabagoolAmountField.getValue().trim();
            if (gabagoolAmountText.isEmpty()) {
                FuelCycleTracker.clearManualGabagoolAmount(editDate);
            } else {
                try {
                    double amountValue = resolveDeltaOrAbsolute(gabagoolAmountText, record.gabagoolAmountAuto);
                    FuelCycleTracker.setGabagoolAmount(editDate, amountValue);
                } catch (NumberFormatException ignored) {}
            }
            String fuelCostText = editFuelCostField.getValue().trim();
            if (fuelCostText.isEmpty()) {
                FuelCycleTracker.clearManualFuelCost(editDate);
            } else {
                try {
                    double fuelCostValue = resolveDeltaOrAbsolute(fuelCostText, record.fuelCost);
                    FuelCycleTracker.setFuelCost(editDate, fuelCostValue);
                } catch (NumberFormatException ignored) {}
            }
            FuelCycleTracker.setNotes(editDate, editNotesField.getValue());
            closeDialog();
        }).bounds(dialogX, dialogY + 190, 115, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> closeDialog())
                .bounds(dialogX + 125, dialogY + 190, 115, 20).build());

        setFocused(editMinionField);
    }

    /**
     * Parses an override field's text as either an absolute replacement value, or — when it
     * starts with an explicit {@code +} or {@code -} sign (e.g. {@code +100000} to correct for
     * a missed sack message, or {@code -20000} to back out an over-count) — a delta to apply
     * on top of {@code autoValue}. None of these override fields ever legitimately hold a
     * negative absolute value (minion count, gabagool amount, and fuel cost are all
     * non-negative), so a leading sign unambiguously means "relative to auto" rather than
     * "set to this negative number". The result is clamped to zero.
     */
    private static double resolveDeltaOrAbsolute(String text, double autoValue) throws NumberFormatException {
        double parsed = Double.parseDouble(text);
        if (text.startsWith("+") || text.startsWith("-")) {
            return Math.max(0.0, autoValue + parsed);
        }
        return Math.max(0.0, parsed);
    }

    private void closeDialog() {
        addDialogOpen = false;
        editDialogOpen = false;
        editDate = null;
        clearWidgets();
        init();
    }

    private boolean isValidDate(String date) {
        try {
            LocalDate.parse(date);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xC0101010);

        long realNow = System.currentTimeMillis();
        if (realNow - lastLiveValueRefreshMs >= LIVE_VALUE_REFRESH_INTERVAL_MS) {
            displayNowMs = realNow;
            lastLiveValueRefreshMs = realNow;
        }

        Component titleComp = Component.literal("Fuel Day Tracker").withStyle(ChatFormatting.GOLD);
        int titleWidth = font.width(titleComp);
        context.text(font, titleComp, width / 2 - titleWidth / 2, 2, -1, true);

        if (!addDialogOpen && !editDialogOpen) {
            renderEntryList(context, mouseX, mouseY);
        }

        if (addDialogOpen) {
            int dialogX = width / 2 - 110;
            int dialogY = height / 2 - 50;
            context.fill(dialogX, dialogY, dialogX + 220, dialogY + 90, 0xEE000000);
            Component title = Component.literal("Add Day");
            int tw = font.width(title);
            context.text(font, title, width / 2 - tw / 2, dialogY + 4, -1, true);
            context.text(font, Component.literal("Date:").withStyle(ChatFormatting.GRAY),
                    dialogX + 10, dialogY + 18, 0xFFAAAAAA, true);
        }

        if (editDialogOpen) {
            int dialogX = width / 2 - 130;
            int dialogY = height / 2 - 127;
            context.fill(dialogX, dialogY, dialogX + 260, dialogY + 234, 0xEE000000);
            FuelCycleTracker.DayRecord editRecord = editDate != null ? FuelCycleTracker.getDayRecord(editDate) : null;
            Component title = Component.literal("Edit " + (editRecord != null ? FuelCycleTracker.formatCycleRange(editRecord) : editDate));
            int tw = font.width(title);
            context.text(font, title, width / 2 - tw / 2, dialogY + 4, -1, true);

            context.text(font, Component.literal("Minions (blank = auto):").withStyle(ChatFormatting.GRAY),
                    dialogX + 10, dialogY + 10, 0xFFAAAAAA, true);
            context.text(font, Component.literal("Coins @ 100% AFK:").withStyle(ChatFormatting.GRAY),
                    dialogX + 10, dialogY + 44, 0xFFAAAAAA, true);
            context.text(font, Component.literal("Gabagool Price:").withStyle(ChatFormatting.GRAY),
                    dialogX + 10, dialogY + 78, 0xFFAAAAAA, true);
            context.text(font, Component.literal("Amount:").withStyle(ChatFormatting.GRAY),
                    dialogX + 135, dialogY + 78, 0xFFAAAAAA, true);
            context.text(font, Component.literal("Fuel Cost (blank = auto):").withStyle(ChatFormatting.GRAY),
                    dialogX + 10, dialogY + 112, 0xFFAAAAAA, true);
            context.text(font, Component.literal("Notes:").withStyle(ChatFormatting.GRAY),
                    dialogX + 10, dialogY + 146, 0xFFAAAAAA, true);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void renderEntryList(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        List<FuelCycleTracker.DayRecord> records = FuelCycleTracker.getDayRecordsDescending();

        if (records.isEmpty()) {
            Component emptyMsg = Component.literal("No fuel data yet. Fuel a minion to start tracking!")
                    .withStyle(ChatFormatting.GRAY);
            int emptyW = font.width(emptyMsg);
            context.text(font, emptyMsg, width / 2 - emptyW / 2, height / 2, 0xFFAAAAAA, true);
            return;
        }

        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;
        int maxScroll = Math.max(0, records.size() - visibleRows);
        scrollOffset = Math.min(scrollOffset, maxScroll);
        scrollOffset = Math.max(0, scrollOffset);

        for (int i = 0; i < records.size(); i++) {
            int drawIndex = i - scrollOffset;
            int rowY = listTop + drawIndex * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) continue;

            FuelCycleTracker.DayRecord record = records.get(i);
            boolean isFutureDay = record.cycleStartMs > displayNowMs;

            int bgColor = isFutureDay ? 0x50404040 : afkGradientColor(record.getAfkPercent(displayNowMs), 0x50);
            context.fill(listLeft, rowY, listLeft + listWidth, rowY + ROW_HEIGHT - 1, bgColor);

            int btnX = listLeft + listWidth - (BUTTON_SIZE + 2) * 2;

            // Line 1: date + profit (queued/future days don't have a profit yet — they
            // haven't started, so nothing has actually happened on them to profit from)
            context.text(font, Component.literal(FuelCycleTracker.formatCycleRange(record)).withStyle(ChatFormatting.BOLD, ChatFormatting.WHITE),
                    listLeft + PADDING, rowY + 3, 0xFFFFFFFF, true);
            if (isFutureDay) {
                String queuedStr = "Queued";
                int queuedW = font.width(queuedStr);
                context.text(font, Component.literal(queuedStr).withStyle(ChatFormatting.YELLOW), btnX - queuedW - 8, rowY + 3,
                        0xFFFFFF55, true);
            } else {
                double profit = record.getProfit(displayNowMs);
                ChatFormatting profitStyle = profit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
                String profitStr = "Profit: " + PriceCache.formatCoins(profit);
                int profitW = font.width(profitStr);
                context.text(font, Component.literal(profitStr).withStyle(profitStyle), btnX - profitW - 8, rowY + 3,
                        profit >= 0 ? 0xFF55FF55 : 0xFFFF5555, true);
            }

            // Line 2: minions, fuel cost — and AFK% too, once this day has actually started
            String line2 = "Minions: " + record.getEffectiveMinionCount()
                    + (record.manualMinionCount != null ? " (manual)" : "")
                    + (isFutureDay ? "" : "  AFK: " + String.format("%.1f%%", record.getAfkPercent(displayNowMs)))
                    + "  Fuel Cost: " + PriceCache.formatCoins(record.getEffectiveFuelCost())
                    + (record.manualFuelCost != null ? " (manual)" : "");
            context.text(font, Component.literal(line2).withStyle(ChatFormatting.GRAY),
                    listLeft + PADDING, rowY + 18, 0xFFCCCCCC, true);

            // Line 3: mob-kill coins, gabagool revenue, notes — not applicable yet for a
            // day that hasn't started, so it's skipped entirely rather than showing a
            // row of misleading zeroes
            if (!isFutureDay) {
                String line3 = "Coins: " + PriceCache.formatCoins(record.getMobKillCoins(displayNowMs))
                        + "  Gabagool: " + PriceCache.formatCoins(record.getGabagoolRevenue())
                        + " (" + record.getEffectiveGabagoolAmount() + (record.manualGabagoolAmount != null ? " manual" : " auto") + ")"
                        + (record.notes != null && !record.notes.isBlank() ? "  \"" + record.notes + "\"" : "");
                int maxW = listWidth - (BUTTON_SIZE + 2) * 2 - PADDING * 2;
                if (font.width(line3) > maxW) {
                    line3 = font.plainSubstrByWidth(line3, maxW - font.width("...")) + "...";
                }
                context.text(font, Component.literal(line3).withStyle(ChatFormatting.DARK_GRAY),
                        listLeft + PADDING, rowY + 33, 0xFF999999, true);
            }

            // Buttons
            int btnY = rowY + (ROW_HEIGHT - BUTTON_SIZE) / 2;
            drawSmallButton(context, btnX, btnY, "\u270F", 0xFF55FFFF, mouseX, mouseY);
            btnX += BUTTON_SIZE + 2;
            drawSmallButton(context, btnX, btnY, "X", 0xFFFF5555, mouseX, mouseY);
        }

        // Scrollbar
        if (records.size() > visibleRows) {
            int scrollbarX = listLeft + listWidth + 2;
            int scrollbarW = 4;
            int trackHeight = listBottom - listTop;
            context.fill(scrollbarX, listTop, scrollbarX + scrollbarW, listBottom, 0x40FFFFFF);
            float thumbRatio = (float) visibleRows / records.size();
            int thumbHeight = Math.max(10, (int) (trackHeight * thumbRatio));
            float scrollRatio = maxScroll > 0 ? (float) scrollOffset / maxScroll : 0;
            int thumbY = listTop + (int) ((trackHeight - thumbHeight) * scrollRatio);
            context.fill(scrollbarX, thumbY, scrollbarX + scrollbarW, thumbY + thumbHeight, 0xAAFFFFFF);
        }

        renderTotalProfitWidget(context, records);
    }

    /**
     * Small standalone box summing the final profit of every tracked day (not just the
     * ones currently scrolled into view). Anchored to the top-right corner of the screen
     * (rather than requiring extra space to the right of the centered, fixed-width list)
     * so it stays visible at any window size/GUI scale — the list is 400px wide and
     * always centered, so at narrower GUI scales there may not be any room left of the
     * screen edge to the right of it.
     */
    private void renderTotalProfitWidget(GuiGraphicsExtractor context, List<FuelCycleTracker.DayRecord> records) {
        double totalProfit = 0.0;
        for (FuelCycleTracker.DayRecord record : records) {
            totalProfit += record.getProfit(displayNowMs);
        }

        int widgetW = TOTAL_PROFIT_WIDGET_WIDTH;
        int widgetX = width - widgetW - TOTAL_PROFIT_WIDGET_GAP;
        int widgetY = HEADER_HEIGHT - 32;
        int widgetH = 36;

        context.fill(widgetX, widgetY, widgetX + widgetW, widgetY + widgetH, 0x80000000);

        Component label = Component.literal("Total Profit").withStyle(ChatFormatting.GRAY);
        context.text(font, label, widgetX + PADDING, widgetY + 4, 0xFFAAAAAA, true);

        ChatFormatting totalStyle = totalProfit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
        Component totalComp = Component.literal(PriceCache.formatCoins(totalProfit)).withStyle(totalStyle);
        context.text(font, totalComp, widgetX + PADDING, widgetY + 18,
                totalProfit >= 0 ? 0xFF55FF55 : 0xFFFF5555, true);
    }

    /**
     * Interpolates a red → yellow → green color based on a 0-100 percentage,
     * with the given alpha byte prefixed.
     */
    private static int afkGradientColor(double percent, int alpha) {
        percent = Math.max(0, Math.min(100, percent));
        int r, g, b = 0x55;
        if (percent <= 50) {
            float t = (float) (percent / 50.0);
            r = 0xFF;
            g = lerp(0x55, 0xFF, t);
        } else {
            float t = (float) ((percent - 50) / 50.0);
            r = lerp(0xFF, 0x55, t);
            g = 0xFF;
        }
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int from, int to, float t) {
        return from + Math.round((to - from) * t);
    }

    private void drawSmallButton(GuiGraphicsExtractor context, int x, int y, String label, int color, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + BUTTON_SIZE && mouseY >= y && mouseY < y + BUTTON_SIZE;
        int bg = hovered ? 0x80FFFFFF : 0x60000000;
        context.fill(x, y, x + BUTTON_SIZE, y + BUTTON_SIZE, bg);
        Component labelComp = Component.literal(label);
        int lw = font.width(labelComp);
        context.text(font, labelComp, x + BUTTON_SIZE / 2 - lw / 2, y + (BUTTON_SIZE - 8) / 2, color, true);
    }

    // -------------------------------------------------------------------------
    // Input handling
    // -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;

        Minecraft client = Minecraft.getInstance();
        double mouseX = client.mouseHandler.xpos() * (double) client.getWindow().getGuiScaledWidth() / (double) client.getWindow().getScreenWidth();
        double mouseY = client.mouseHandler.ypos() * (double) client.getWindow().getGuiScaledHeight() / (double) client.getWindow().getScreenHeight();

        if (addDialogOpen || editDialogOpen) return false;

        List<FuelCycleTracker.DayRecord> records = FuelCycleTracker.getDayRecordsDescending();
        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;

        // Scrollbar
        if (records.size() > visibleRows) {
            int scrollbarX = listLeft + listWidth + 2;
            int scrollbarW = 4;
            if (isInBounds(mouseX, mouseY, scrollbarX - 2, listTop, scrollbarW + 4, listBottom - listTop)) {
                draggingScrollbar = true;
                updateScrollFromMouse(mouseY);
                return true;
            }
        }

        for (int i = 0; i < records.size(); i++) {
            int drawIndex = i - scrollOffset;
            int rowY = listTop + drawIndex * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) continue;

            int btnX = listLeft + listWidth - (BUTTON_SIZE + 2) * 2;
            int btnY = rowY + (ROW_HEIGHT - BUTTON_SIZE) / 2;

            if (isInBounds(mouseX, mouseY, btnX, btnY, BUTTON_SIZE, BUTTON_SIZE)) {
                openEditDialog(records.get(i).date);
                return true;
            }
            btnX += BUTTON_SIZE + 2;

            if (isInBounds(mouseX, mouseY, btnX, btnY, BUTTON_SIZE, BUTTON_SIZE)) {
                FuelCycleTracker.removeDayRecord(records.get(i).date);
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
        List<FuelCycleTracker.DayRecord> records = FuelCycleTracker.getDayRecordsDescending();
        int visibleRows = (listBottom - listTop) / ROW_HEIGHT;
        int maxScroll = Math.max(0, records.size() - visibleRows);
        float ratio = (float) (mouseY - listTop) / (listBottom - listTop);
        ratio = Math.max(0, Math.min(1, ratio));
        scrollOffset = Math.round(ratio * maxScroll);
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
}
