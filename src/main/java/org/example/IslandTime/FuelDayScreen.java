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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GUI screen for the fuel day tracker. Displays a scrollable list of per-fuel-day
 * fuel/profit records, one row per real 24h fuel-day (see FuelCycleTracker) — placing
 * a multi-fuel stack creates several rows immediately (the currently active day plus
 * future/queued ones), rather than one row per calendar date. Rows are colored on a
 * red → yellow → green gradient based on that cycle's AFK percentage, or, with the estimated return
 * switched on, by its estimated profit (a loss red, under 10m yellow, 10m and up green). The day that is
 * running right now has a white outline.
 * Every value is directly editable, similar to the
 * Block Highlights and Player List GUIs. While a day is still actively ticking, its AFK%
 * (and everything derived from it — mob-kill coins, profit) reflects progress against time
 * elapsed so far today rather than the full 24h, and refreshes once a second rather than
 * every render frame.
 *
 * The screen shows one fuel data set (see {@link FuelDataSets}): the one in use, or another one that is
 * only being browsed, which is read-only until "Use This Data Set" is pressed. With the estimated return
 * switched on, each started day gets a fourth line with the Inferno minions' estimated loot and profit
 * (see {@link InfernoReturnCalculator}).
 */
public class FuelDayScreen extends Screen {

    private static final int BASE_ROW_HEIGHT = 54;
    private static final int HEADER_HEIGHT = 64;
    private static final int HEADER_ROW_1_Y = 10;
    private static final int HEADER_ROW_2_Y = 34;
    private static final int BANNER_Y = 58;
    private static final int BUTTON_SIZE = 20;
    private static final int PADDING = 4;
    private static final int CURRENT_DAY_OUTLINE_COLOR = 0xFFFFFFFF;
    private static final int TOTAL_PROFIT_WIDGET_GAP = 12;
    private static final int TOTAL_PROFIT_WIDGET_WIDTH = 110;
    private static final int TOTAL_PROFIT_WIDGET_Y = 8;
    private static final int TOP_BUTTON_GAP = 8;
    private static final int FORCE_END_BUTTON_WIDTH = 150;
    private static final int ADD_DAY_BUTTON_WIDTH = 110;
    private static final int USE_BUTTON_WIDTH = 150;
    private static final int DATA_SETS_BUTTON_WIDTH = 70;
    private static final int MINIONS_BUTTON_WIDTH = 60;
    private static final int PROFIT_BUTTON_WIDTH = 135;
    private static final long STATUS_MS = 4_000L;

    /** A rectangle on screen, for the tooltips of the header buttons. */
    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** The data set to show, or null to always show the one in use. */
    private final String pinnedSetId;
    /** Where closing this screen goes. Never a {@link FuelDataSetsScreen}, see {@link FuelDataSetsScreen#exitScreen}. */
    final Screen parent;

    private Rect forceEndRect;
    private Rect useRect;
    private Rect dataSetsRect;
    private Rect minionsRect;
    private Rect profitRect;
    /** Whether the header was built for the data set in use, so it can be rebuilt when that changes. */
    private boolean builtForActive = true;

    // Records of a data set that is not in use are read from its file once, not every frame
    private List<FuelCycleTracker.DayRecord> foreignRecords = null;
    private String foreignRecordsId = null;

    private String statusText = "";
    private long statusUntilMs = 0;

    private int scrollOffset = 0;
    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listWidth;

    // Edit dialog
    private boolean editDialogOpen = false;
    private String editDate = null;
    private EditBox editMinionField;
    private EditBox editMobKillField;
    private EditBox editGabagoolPriceField;
    private EditBox editGabagoolAmountField;
    private EditBox editFuelCostField;
    private EditBox editNotesField;
    private EditBox editStartField;
    private EditBox editEndField;
    // Values the dialog was opened with, so saving only applies fields the user changed.
    private String initialGabagoolPriceText;
    private String initialStartText;
    private String initialEndText;
    private String editValidationError;

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

    // Estimates are worked out once per refresh above, not for every row on every frame
    private final Map<FuelCycleTracker.DayRecord, FuelDayEstimates.DayEstimate> estimateCache = new HashMap<>();

    /** Shows the data set in use, and keeps following it if another one is switched to. */
    public FuelDayScreen() {
        this(null, null);
    }

    /**
     * @param setId  the data set to show, or null for the one in use
     * @param parent the screen to go back to when this one is closed, or null to close it. If this is opened from
     *               the data sets screen, closing goes to whatever opened that instead.
     */
    public FuelDayScreen(String setId, Screen parent) {
        super(Component.literal("Fuel Day Tracker"));
        this.pinnedSetId = setId;
        this.parent = FuelDataSetsScreen.exitScreen(parent);
    }

    private String viewedSetId() {
        if (pinnedSetId != null && FuelDataSets.get(pinnedSetId) != null) return pinnedSetId;
        return FuelDataSets.activeId();
    }

    private boolean viewingActive() {
        return FuelDataSets.isActive(viewedSetId());
    }

    private String viewedSetName() {
        FuelDataSets.DataSet set = FuelDataSets.get(viewedSetId());
        return set != null ? set.name() : viewedSetId();
    }

    /** The records being shown: live ones for the data set in use, otherwise read once from the set's file. */
    private List<FuelCycleTracker.DayRecord> records() {
        String id = viewedSetId();
        if (FuelDataSets.isActive(id)) return FuelCycleTracker.getDayRecordsDescending();
        if (foreignRecords == null || !id.equals(foreignRecordsId)) {
            foreignRecords = FuelCycleTracker.readDayRecordsDescending(id);
            foreignRecordsId = id;
        }
        return foreignRecords;
    }

    private static IslandTimeConfig.ProfitCalculators fuelConfig() {
        IslandTimeConfig config = ConfigManager.getConfig();
        return config != null ? config.profitCalculators : null;
    }

    /** Whether the data set being shown works out its profit from the estimated return rather than the tracked one. */
    private boolean estimateEnabled() {
        return FuelDataSets.isEstimated(viewedSetId());
    }

    private int rowHeight() {
        return BASE_ROW_HEIGHT;
    }

    private static Component profitModeLabel(boolean estimated) {
        return Component.literal("Profit: ")
                .append(Component.literal(estimated ? "Estimated" : "Actual")
                        .withStyle(estimated ? ChatFormatting.AQUA : ChatFormatting.GREEN));
    }

    @Override
    protected void init() {
        super.init();
        listLeft = width / 2 - 200;
        listWidth = 400;
        listTop = HEADER_HEIGHT + 10;
        listBottom = height - 10;

        forceEndRect = null;
        useRect = null;
        dataSetsRect = null;
        minionsRect = null;
        profitRect = null;
        builtForActive = viewingActive();
        estimateCache.clear();
        if (estimateEnabled()) PriceCache.requestBazaarRefreshAsync();

        if (!editDialogOpen) {
            if (builtForActive) {
                int controlsWidth = FORCE_END_BUTTON_WIDTH + TOP_BUTTON_GAP + ADD_DAY_BUTTON_WIDTH;
                int controlsX = width / 2 - controlsWidth / 2;
                forceEndRect = new Rect(controlsX, HEADER_ROW_1_Y, FORCE_END_BUTTON_WIDTH, 20);
                addRenderableWidget(Button.builder(Component.literal("Force End Cohort"), btn -> {
                            FuelCycleTracker.forceEndCohort();
                        })
                        .bounds(controlsX, HEADER_ROW_1_Y, FORCE_END_BUTTON_WIDTH, 20)
                        .build());
                addRenderableWidget(Button.builder(Component.literal("+ Add Day"), btn -> addQueuedDay())
                        .bounds(controlsX + FORCE_END_BUTTON_WIDTH + TOP_BUTTON_GAP, HEADER_ROW_1_Y, ADD_DAY_BUTTON_WIDTH, 20)
                        .build());
            } else {
                useRect = new Rect(width / 2 - USE_BUTTON_WIDTH / 2, HEADER_ROW_1_Y, USE_BUTTON_WIDTH, 20);
                addRenderableWidget(Button.builder(Component.literal("Use This Data Set"), btn -> useViewedSet())
                        .bounds(useRect.x(), useRect.y(), useRect.w(), useRect.h())
                        .build());
            }

            int minionsWidth = builtForActive ? MINIONS_BUTTON_WIDTH + TOP_BUTTON_GAP : 0;
            int row2Width = DATA_SETS_BUTTON_WIDTH + minionsWidth + PROFIT_BUTTON_WIDTH + TOP_BUTTON_GAP;
            int x = width / 2 - row2Width / 2;
            dataSetsRect = new Rect(x, HEADER_ROW_2_Y, DATA_SETS_BUTTON_WIDTH, 20);
            addRenderableWidget(Button.builder(Component.literal("Data Sets"),
                            btn -> Minecraft.getInstance().setScreen(new FuelDataSetsScreen(this)))
                    .bounds(dataSetsRect.x(), dataSetsRect.y(), dataSetsRect.w(), dataSetsRect.h())
                    .build());
            x += DATA_SETS_BUTTON_WIDTH + TOP_BUTTON_GAP;

            // Only the data set in use has a cohort of minions running
            if (builtForActive) {
                minionsRect = new Rect(x, HEADER_ROW_2_Y, MINIONS_BUTTON_WIDTH, 20);
                addRenderableWidget(Button.builder(Component.literal("Minions"),
                                btn -> Minecraft.getInstance().setScreen(new FuelMinionsScreen(this)))
                        .bounds(minionsRect.x(), minionsRect.y(), minionsRect.w(), minionsRect.h())
                        .build());
                x += MINIONS_BUTTON_WIDTH + TOP_BUTTON_GAP;
            }

            // The mode belongs to the data set being shown, whether or not it is the one in use
            profitRect = new Rect(x, HEADER_ROW_2_Y, PROFIT_BUTTON_WIDTH, 20);
            addRenderableWidget(Button.builder(profitModeLabel(estimateEnabled()), btn -> {
                        boolean estimated = !estimateEnabled();
                        FuelDataSets.setEstimated(viewedSetId(), estimated);
                        estimateCache.clear();
                        btn.setMessage(profitModeLabel(estimated));
                        if (estimated) PriceCache.requestBazaarRefreshAsync();
                    })
                    .bounds(profitRect.x(), profitRect.y(), profitRect.w(), profitRect.h())
                    .build());
        }
    }

    /** Makes the data set being browsed the one new fuel days are recorded into. */
    private void useViewedSet() {
        String id = viewedSetId();
        String name = viewedSetName();
        String error = FuelCycleTracker.switchDataSet(id);
        if (error == null) {
            // Config values that mirror per-set data (mob-kill baseline, today's gabagool amount) must follow the switch
            ConfigManager.syncFromExistingConfigs();
            setStatus("Now using \"" + name + "\"", true);
        } else {
            setStatus(error, false);
        }
        foreignRecords = null;
        foreignRecordsId = null;
        scrollOffset = 0;
        clearWidgets();
        init();
    }

    private boolean statusOk = true;

    private void setStatus(String message, boolean ok) {
        statusText = message;
        statusOk = ok;
        statusUntilMs = System.currentTimeMillis() + STATUS_MS;
    }

    // -------------------------------------------------------------------------
    // Dialogs
    // -------------------------------------------------------------------------

    /**
     * Appends a new queued day carrying over the previous day's minion count and fuel
     * cost — no manual input needed, matching how queuing extra fuel-days from an
     * actual fuel placement works.
     */
    private void addQueuedDay() {
        FuelCycleTracker.appendQueuedDay();
    }

    private void openEditDialog(String date) {
        FuelCycleTracker.DayRecord record = FuelCycleTracker.getDayRecord(date);
        if (record == null) return;

        editDialogOpen = true;
        editDate = date;
        editValidationError = null;
        clearWidgets();

        int dialogX = width / 2 - 130;
        int dialogY = height / 2 - 170;

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
        initialGabagoolPriceText = String.valueOf(record.gabagoolPrice);
        editGabagoolPriceField.setValue(initialGabagoolPriceText);
        editGabagoolPriceField.setHint(Component.literal("blank = default").withStyle(ChatFormatting.GRAY));
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
        editFuelCostField.setHint(Component.literal("auto: " + String.format("%.1f", record.getEffectiveFuelCost()) + " (or +/-N, 108k/15.2m)").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(editFuelCostField);

        editNotesField = new EditBox(font, dialogX, dialogY + 156, 240, 20, Component.literal("Notes"));
        editNotesField.setMaxLength(128);
        editNotesField.setValue(record.notes != null ? record.notes : "");
        if (record.hasAutoGabagoolPrice()) {
            editNotesField.setHint(Component.literal(FuelCycleTracker.AUTO_PRICE_NOTE).withStyle(ChatFormatting.GRAY));
        }
        addRenderableWidget(editNotesField);

        long startMs = record.cycleStartMs > 0 ? record.cycleStartMs : System.currentTimeMillis();
        long endMs = record.cycleEndMs > startMs ? record.cycleEndMs : startMs + FuelCycleTracker.DAY_MS;
        editStartField = new EditBox(font, dialogX, dialogY + 190, 240, 20, Component.literal("Start"));
        editStartField.setMaxLength(20);
        initialStartText = FuelCycleTracker.formatEditableTimestamp(startMs);
        editStartField.setValue(initialStartText);
        addRenderableWidget(editStartField);

        editEndField = new EditBox(font, dialogX, dialogY + 224, 240, 20, Component.literal("End"));
        editEndField.setMaxLength(20);
        initialEndText = FuelCycleTracker.formatEditableTimestamp(endMs);
        editEndField.setValue(initialEndText);
        addRenderableWidget(editEndField);

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> {
            // Re-saving untouched (minute-rounded) timestamps would needlessly turn this and
            // every later day into manually-timed records, so only apply them when edited.
            boolean timesChanged = !editStartField.getValue().trim().equals(initialStartText)
                    || !editEndField.getValue().trim().equals(initialEndText);
            long start = 0;
            long end = 0;
            if (timesChanged) {
                try {
                    start = FuelCycleTracker.parseEditableTimestamp(editStartField.getValue());
                    end = FuelCycleTracker.parseEditableTimestamp(editEndField.getValue());
                    if (end <= start) throw new IllegalArgumentException("end before start");
                } catch (RuntimeException ignored) {
                    editValidationError = "Invalid timestamps (use dd/MM/yy HH:mm)";
                    return;
                }
            }

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
            // Only an actual edit counts as a manual price; leaving the field untouched keeps
            // an auto-applied default (and its "temp auto price" comment) in place.
            String gabagoolPriceText = editGabagoolPriceField.getValue().trim();
            if (gabagoolPriceText.isEmpty()) {
                FuelCycleTracker.clearManualGabagoolPrice(editDate);
            } else if (!gabagoolPriceText.equals(initialGabagoolPriceText)) {
                try {
                    FuelCycleTracker.setGabagoolPrice(editDate, parseFlexibleNumber(gabagoolPriceText));
                } catch (NumberFormatException ignored) {}
            }
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
                    double fuelCostValue = resolveDeltaOrAbsolute(fuelCostText, record.getEffectiveFuelCost());
                    FuelCycleTracker.setFuelCost(editDate, fuelCostValue);
                } catch (NumberFormatException ignored) {}
            }
            FuelCycleTracker.setNotes(editDate, editNotesField.getValue());
            if (timesChanged && !FuelCycleTracker.setCycleTimes(editDate, start, end)) {
                editValidationError = "Could not save cycle timestamps";
                return;
            }
            closeDialog();
        }).bounds(dialogX, dialogY + 258, 115, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> closeDialog())
                .bounds(dialogX + 125, dialogY + 258, 115, 20).build());

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
        double parsed = parseFlexibleNumber(text);
        if (text.startsWith("+") || text.startsWith("-")) {
            return Math.max(0.0, autoValue + parsed);
        }
        return Math.max(0.0, parsed);
    }

    /**
     * Parses ordinary decimal values plus compact coin-style suffixes used by the GUI,
     * such as {@code 108k}, {@code 15.2m}, or {@code 1.5b}.
     */
    private static double parseFlexibleNumber(String text) throws NumberFormatException {
        String normalized = text.trim().replace(",", "").toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) throw new NumberFormatException("empty number");

        double multiplier = 1.0;
        char suffix = normalized.charAt(normalized.length() - 1);
        if (suffix == 'k' || suffix == 'm' || suffix == 'b' || suffix == 't') {
            multiplier = switch (suffix) {
                case 'k' -> 1_000.0;
                case 'm' -> 1_000_000.0;
                case 'b' -> 1_000_000_000.0;
                default -> 1_000_000_000_000.0;
            };
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }

        if (normalized.isEmpty() || normalized.equals("+") || normalized.equals("-")) {
            throw new NumberFormatException("missing number");
        }
        return Double.parseDouble(normalized) * multiplier;
    }

    private void closeDialog() {
        editDialogOpen = false;
        editDate = null;
        editValidationError = null;
        clearWidgets();
        init();
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
            estimateCache.clear();
            if (estimateEnabled()) PriceCache.requestBazaarRefreshAsync();
        }

        // The data set in use changed (e.g. from the Data Sets screen), so the buttons no longer fit it
        if (!editDialogOpen && builtForActive != viewingActive()) {
            clearWidgets();
            init();
        }

        String setName = viewedSetName();
        Component titleComp = Component.literal("Fuel Day Tracker - " + fitTitle(setName)).withStyle(ChatFormatting.GOLD);
        int titleWidth = font.width(titleComp);
        context.text(font, titleComp, width / 2 - titleWidth / 2, 2, -1, true);

        if (!editDialogOpen) {
            renderBanner(context, setName, realNow);
            renderEntryList(context, mouseX, mouseY);
            renderCohortExpiryTooltip(context, mouseX, mouseY);
            renderHeaderTooltips(context, mouseX, mouseY);
            renderEstimateTooltip(context, mouseX, mouseY);
        }

        if (editDialogOpen) {
            int dialogX = width / 2 - 130;
            int dialogY = height / 2 - 170;
            context.fill(dialogX, dialogY, dialogX + 260, dialogY + 302, 0xEE000000);
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
            context.text(font, Component.literal("Start (dd/MM/yy HH:mm):").withStyle(ChatFormatting.GRAY),
                    dialogX + 10, dialogY + 180, 0xFFAAAAAA, true);
            context.text(font, Component.literal("End (dd/MM/yy HH:mm):").withStyle(ChatFormatting.GRAY),
                    dialogX + 10, dialogY + 214, 0xFFAAAAAA, true);
            if (editValidationError != null) {
                context.text(font, Component.literal(editValidationError).withStyle(ChatFormatting.RED),
                        dialogX + 10, dialogY + 246, 0xFFFF5555, true);
            }
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void renderEntryList(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        List<FuelCycleTracker.DayRecord> records = records();

        if (records.isEmpty()) {
            Component emptyMsg = Component.literal(builtForActive
                            ? "No fuel data yet. Fuel a minion to start tracking!"
                            : "This data set has no fuel data yet.")
                    .withStyle(ChatFormatting.GRAY);
            int emptyW = font.width(emptyMsg);
            context.text(font, emptyMsg, width / 2 - emptyW / 2, height / 2, 0xFFAAAAAA, true);
            return;
        }

        int rowHeight = rowHeight();
        boolean showEstimate = estimateEnabled();
        int visibleRows = (listBottom - listTop) / rowHeight;
        int maxScroll = Math.max(0, records.size() - visibleRows);
        scrollOffset = Math.min(scrollOffset, maxScroll);
        scrollOffset = Math.max(0, scrollOffset);

        for (int i = 0; i < records.size(); i++) {
            int drawIndex = i - scrollOffset;
            int rowY = listTop + drawIndex * rowHeight;
            if (rowY + rowHeight < listTop || rowY > listBottom) continue;

            FuelCycleTracker.DayRecord record = records.get(i);
            boolean isFutureDay = record.cycleStartMs > displayNowMs;
            double estimatedProfit = showEstimate && !isFutureDay ? estimateFor(record).profit() : Double.NaN;

            int bgColor;
            if (isFutureDay) bgColor = 0x50404040;
            else if (showEstimate) bgColor = profitBandBackground(FuelDayEstimates.band(estimatedProfit));
            else bgColor = afkGradientColor(record.getAfkPercent(displayNowMs), 0x50);
            context.fill(listLeft, rowY, listLeft + listWidth, rowY + rowHeight - 1, bgColor);
            if (record.isInProgress(displayNowMs)) drawCurrentDayOutline(context, rowY, rowHeight);

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
                double profit = showEstimate ? estimatedProfit : record.getProfit(displayNowMs);
                boolean noPrice = Double.isNaN(profit);
                FuelDayEstimates.ProfitBand band = showEstimate ? FuelDayEstimates.band(profit)
                        : noPrice ? FuelDayEstimates.ProfitBand.UNKNOWN
                        : profit >= 0 ? FuelDayEstimates.ProfitBand.GOOD : FuelDayEstimates.ProfitBand.LOSS;
                String profitStr = (showEstimate ? "Est. profit: " : "Profit: ")
                        + (noPrice ? "n/a" : PriceCache.formatCoins(profit));
                int profitW = font.width(profitStr);
                context.text(font, Component.literal(profitStr).withStyle(profitBandStyle(band)),
                        btnX - profitW - 8, rowY + 3, profitBandTextColor(band), true);
            }

            // Line 2: minions, fuel cost — and AFK% too, once this day has actually started
            String line2 = "Minions: " + record.getEffectiveMinionCount()
                    + (record.manualMinionCount != null ? " (manual)" : "")
                    + (isFutureDay ? "" : "  AFK: " + String.format("%.1f%%", record.getAfkPercent(displayNowMs)))
                    + "  Fuel Cost: " + PriceCache.formatCoins(record.getEffectiveFuelCost())
                    + (record.manualFuelCost != null ? " (manual)" : "");
            context.text(font, Component.literal(line2).withStyle(ChatFormatting.GRAY),
                    listLeft + PADDING, rowY + 18, 0xFFCCCCCC, true);

            // Line 3: the real mob-kill coins, gabagool revenue and notes, or in estimated mode the estimated
            // return — not applicable yet for a day that hasn't started, so it's skipped entirely rather than
            // showing a row of misleading zeroes
            if (!isFutureDay) {
                String notes = !record.getDisplayNotes().isBlank() ? "  \"" + record.getDisplayNotes() + "\"" : "";
                String warped = record.isTimeWarped()
                        ? "  Time warped " + formatNumber(record.timeWarpedMs / 3_600_000.0) + "h" : "";
                String line3;
                if (showEstimate) {
                    line3 = estimateLine(estimateFor(record)) + warped + notes;
                } else {
                    line3 = "Coins: " + PriceCache.formatCoins(record.getMobKillCoins(displayNowMs))
                            + "  Gabagool: " + PriceCache.formatCoins(record.getGabagoolRevenue())
                            + " (" + record.getEffectiveGabagoolAmount() + (record.manualGabagoolAmount != null ? " manual" : " auto") + ")"
                            + warped + notes;
                }
                int maxW = listWidth - (BUTTON_SIZE + 2) * 2 - PADDING * 2;
                if (font.width(line3) > maxW) {
                    line3 = font.plainSubstrByWidth(line3, maxW - font.width("...")) + "...";
                }
                context.text(font, Component.literal(line3).withStyle(showEstimate ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY),
                        listLeft + PADDING, rowY + 33, showEstimate ? 0xFF55FFFF : 0xFF999999, true);
            }

            // Buttons. A data set that is only being browsed can't be edited.
            if (builtForActive) {
                int btnY = rowY + (rowHeight - BUTTON_SIZE) / 2;
                drawSmallButton(context, btnX, btnY, "\u270F", 0xFF55FFFF, mouseX, mouseY);
                btnX += BUTTON_SIZE + 2;
                drawSmallButton(context, btnX, btnY, "X", 0xFFFF5555, mouseX, mouseY);
            }
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
        boolean showEstimate = estimateEnabled();
        double totalProfit = 0.0;
        for (FuelCycleTracker.DayRecord record : records) {
            if (showEstimate) {
                if (record.cycleStartMs > displayNowMs) continue;
                double estimated = estimateFor(record).profit();
                if (!Double.isNaN(estimated)) totalProfit += estimated;
            } else {
                totalProfit += record.getProfit(displayNowMs);
            }
        }

        int widgetW = TOTAL_PROFIT_WIDGET_WIDTH;
        int widgetX = width - widgetW - TOTAL_PROFIT_WIDGET_GAP;
        int widgetY = TOTAL_PROFIT_WIDGET_Y;
        int widgetH = 36;

        context.fill(widgetX, widgetY, widgetX + widgetW, widgetY + widgetH, 0x80000000);

        Component label = Component.literal(fit(showEstimate ? "Est. Total Profit" : "Total Profit", widgetW - PADDING * 2))
                .withStyle(ChatFormatting.GRAY);
        context.text(font, label, widgetX + PADDING, widgetY + 4, 0xFFAAAAAA, true);

        ChatFormatting totalStyle = totalProfit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
        Component totalComp = Component.literal(PriceCache.formatCoins(totalProfit)).withStyle(totalStyle);
        context.text(font, totalComp, widgetX + PADDING, widgetY + 18,
                totalProfit >= 0 ? 0xFF55FF55 : 0xFFFF5555, true);
    }

    private String fit(String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    /** A data set name short enough not to run into the Total Profit box. */
    private String fitTitle(String name) {
        return fit(name, Math.max(40, width - 2 * (TOTAL_PROFIT_WIDGET_WIDTH + TOTAL_PROFIT_WIDGET_GAP) - 120));
    }

    /** What the Inferno minions should have returned on a day, from the settings in the config. */
    private FuelDayEstimates.DayEstimate estimateFor(FuelCycleTracker.DayRecord record) {
        return estimateCache.computeIfAbsent(record, r -> FuelDayEstimates.estimate(r,
                InfernoProfitsCommand.settingsFrom(fuelConfig()), PriceCache.peekBazaar()));
    }

    /** The detail line of a row in estimated mode. */
    private static String estimateLine(FuelDayEstimates.DayEstimate day) {
        InfernoReturnCalculator.Estimate e = day.estimate();
        String grade = InfernoReturnCalculator.GRADE_NAMES[day.settings().fuelGrade()]
                + (day.mixedGrades() ? " (mixed)" : day.gradeFromFueling() ? "" : " (default)");
        if (!e.priced()) return "Est. return: n/a (no Bazaar price yet)  " + grade;
        String eyedrops = e.eyedropTotal() > 0 ? "  Eyedrops: " + PriceCache.formatCoins(e.eyedropTotal()) : "";
        String unpriced = day.priced() ? "" : "  (eyedrop prices missing)";
        return "Est. return: " + PriceCache.formatCoins(e.value()) + eyedrops + unpriced + "  " + grade;
    }

    private void renderBanner(GuiGraphicsExtractor context, String setName, long now) {
        String text;
        int color;
        if (!builtForActive) {
            text = "Viewing \"" + setName + "\" (read-only). New fuel is recorded into \""
                    + FuelDataSets.active().name() + "\".";
            color = 0xFFFFFF55;
        } else if (!statusText.isEmpty() && now < statusUntilMs) {
            text = statusText;
            color = statusOk ? 0xFF55FF55 : 0xFFFF5555;
        } else {
            return;
        }
        text = fit(text, width - 20);
        context.text(font, Component.literal(text), width / 2 - font.width(text) / 2, BANNER_Y, color, true);
    }

    private void renderHeaderTooltips(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        if (useRect != null && useRect.contains(mouseX, mouseY)) {
            lines.add(Component.literal("Record new fuel days into \"" + viewedSetName() + "\"").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Your current data stays in \"" + FuelDataSets.active().name()
                    + "\" and can be switched back to").withStyle(ChatFormatting.DARK_GRAY));
        } else if (dataSetsRect != null && dataSetsRect.contains(mouseX, mouseY)) {
            lines.add(Component.literal("Create, view and switch between data sets").withStyle(ChatFormatting.GRAY));
        } else if (minionsRect != null && minionsRect.contains(mouseX, mouseY)) {
            lines.add(Component.literal("Edit the fuel time of each minion in the cohort").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Fixes minions that have drifted out of sync").withStyle(ChatFormatting.DARK_GRAY));
        } else if (profitRect != null && profitRect.contains(mouseX, mouseY)) {
            lines.add(Component.literal("Profit for \"" + viewedSetName() + "\"").withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal("Actual: the coins and gabagool the minions really made, as tracked").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Estimated: worked out from the minions' speed instead, for when you").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("aren't AFKing: one spawn and one kill per Crude Gabagool, item drops").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("only, valued at the Bazaar sell offer from the end of each day").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("The fuel grade comes from the fueling messages, and a time-warped").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("day still gets its full 24 hours").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Each data set has its own setting, used here, in the Data Sets menu").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Hover a row's blue line for the details").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Speed boosts: /it > Profit Calculators > Inferno Profits").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!lines.isEmpty()) context.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    /** Hovering the blue line of a row explains how its estimate was worked out. */
    private void renderEstimateTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (!estimateEnabled()) return;
        List<FuelCycleTracker.DayRecord> records = records();
        int rowHeight = rowHeight();
        for (int i = 0; i < records.size(); i++) {
            int rowY = listTop + (i - scrollOffset) * rowHeight;
            if (rowY + rowHeight < listTop || rowY > listBottom) continue;
            if (!isInBounds(mouseX, mouseY, listLeft, rowY + 30, listWidth - (BUTTON_SIZE + 2) * 2, rowHeight - 30)) continue;

            FuelCycleTracker.DayRecord record = records.get(i);
            if (record.cycleStartMs > displayNowMs) return;
            context.setComponentTooltipForNextFrame(font, estimateTooltip(estimateFor(record)), mouseX, mouseY);
            return;
        }
    }

    private static List<Component> estimateTooltip(FuelDayEstimates.DayEstimate day) {
        InfernoReturnCalculator.Estimate e = day.estimate();
        InfernoReturnCalculator.Settings s = day.settings();
        InfernoReturnCalculator.Prices prices = day.prices();
        List<Component> lines = new ArrayList<>();

        lines.add(Component.literal("Estimated return").withStyle(ChatFormatting.GOLD));
        for (InfernoReturnCalculator.Contribution bonus : e.bonuses()) {
            lines.add(Component.literal("  +" + formatNumber(bonus.percent()) + "% " + bonus.name()).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.literal("Speed bonus: +" + formatNumber(e.speedBonusPercent()) + "% (added together)")
                .withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal(InfernoReturnCalculator.GRADE_NAMES[s.fuelGrade()] + ": +"
                + formatNumber(InfernoReturnCalculator.GRADE_BONUS[s.fuelGrade()] * 100)
                + "% speed, multiplied with the bonus above").withStyle(ChatFormatting.GRAY));
        String gradeSource = day.mixedGrades()
                ? "Fuel grade: the most used one that day (more than one was placed)"
                : day.gradeFromFueling() ? "Fuel grade: from the fueling messages"
                : "Fuel grade: your default in the settings (no fueling message was seen)";
        lines.add(Component.literal(gradeSource).withStyle(day.gradeFromFueling() && !day.mixedGrades()
                ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
        lines.add(Component.literal("Tier " + s.minionTier() + ": "
                + formatNumber(InfernoReturnCalculator.BASE_SECONDS[s.minionTier() - 1]) + "s base, "
                + String.format(Locale.ROOT, "%.2f", e.secondsPerAction()) + "s per action").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Running: " + String.format(Locale.ROOT, "%.1f", day.productionMs() / 3_600_000.0) + "h, "
                + String.format(Locale.ROOT, "%.1f", e.harvestsPerMinion()) + " harvests per minion")
                .withStyle(ChatFormatting.GRAY));
        if (day.timeWarpedMs() > 0) {
            lines.add(Component.literal("Time warped: the day was "
                    + formatNumber((day.productionMs() - day.timeWarpedMs()) / 3_600_000.0)
                    + "h long, but the minions still ran the full " + formatNumber(day.productionMs() / 3_600_000.0) + "h")
                    .withStyle(ChatFormatting.YELLOW));
        }
        lines.add(Component.literal("Crude Gabagool: " + String.format(Locale.ROOT, "%,.1f", e.crudeTotal())
                + (e.compacted() ? " = " + String.format(Locale.ROOT, "%,.2f", e.veryCrudeTotal())
                + " Very Crude (compacted, with decimals)" : "")).withStyle(ChatFormatting.GRAY));

        if (!e.priced()) {
            lines.add(Component.literal("No Bazaar price for Crude Gabagool yet").withStyle(ChatFormatting.RED));
            return lines;
        }

        if (e.compacted() && !e.usedCrudePriceForCompacted()) {
            lines.add(Component.literal("Very Crude price: "
                    + PriceCache.formatCoinsPrecise(prices.get(InfernoReturnCalculator.VERY_CRUDE_GABAGOOL_ID)))
                    .withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.literal("Crude price: "
                    + PriceCache.formatCoinsPrecise(prices.get(InfernoReturnCalculator.CRUDE_GABAGOOL_ID)))
                    .withStyle(ChatFormatting.GRAY));
            if (e.usedCrudePriceForCompacted()) {
                lines.add(Component.literal("No Very Crude price was available, so Crude was used")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
        if (!e.rareDrops().isEmpty()) {
            lines.add(Component.literal(s.eyedrops()
                    ? "Hypergolic rare drops (Capsaicin Eyedrops: x" + formatNumber(InfernoReturnCalculator.EYEDROPS_MULTIPLIER) + "):"
                    : "Hypergolic rare drops:").withStyle(ChatFormatting.LIGHT_PURPLE));
            for (InfernoReturnCalculator.DropLine drop : e.rareDrops()) {
                String amount = String.format(Locale.ROOT, "%,.3f", drop.amount());
                String worth = drop.price() <= 0 && !drop.assumed() ? " (no Bazaar price)"
                        : " = " + PriceCache.formatCoins(drop.value())
                        + (drop.assumed() ? " (at " + PriceCache.formatCoins(drop.price()) + " each, the price you set)" : "");
                lines.add(Component.literal("  " + amount + " " + drop.name() + worth).withStyle(ChatFormatting.GRAY));
            }
        }
        InfernoReturnCalculator.EyedropCost eyedrops = e.eyedropCost();
        if (eyedrops != null) {
            lines.add(Component.literal("Capsaicin Eyedrops, used every day: " + eyedrops.crafts() + " crafts = "
                    + eyedrops.eyedrops() + " eyedrops for " + e.minions() + " minions").withStyle(ChatFormatting.LIGHT_PURPLE));
            for (InfernoReturnCalculator.DropLine item : eyedrops.ingredients()) {
                String worth = item.price() <= 0 && !item.assumed() ? " (no Bazaar price)"
                        : " = " + PriceCache.formatCoins(item.value())
                        + (item.assumed() ? " (at " + PriceCache.formatCoins(item.price()) + " each, the price you set)" : "");
                lines.add(Component.literal("  " + String.format(Locale.ROOT, "%,.0f", item.amount()) + " " + item.name() + worth)
                        .withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.literal(Double.isNaN(eyedrops.total()) ? "  Eyedrops cost: n/a"
                    : "  Eyedrops cost: " + PriceCache.formatCoins(eyedrops.total()) + " ("
                    + PriceCache.formatCoins(eyedrops.perCraft()) + " per craft)").withStyle(ChatFormatting.GRAY));
        } else if (s.eyedrops() && s.fuelGrade() != InfernoReturnCalculator.GRADE_HYPERGOLIC) {
            lines.add(Component.literal("Capsaicin Eyedrops only work in Hypergolic fuel: not counted")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.add(Component.literal(day.priceSource()).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("Est. return: " + PriceCache.formatCoins(e.value())).withStyle(ChatFormatting.AQUA));
        String eyedropPart = eyedrops == null ? ""
                : Double.isNaN(eyedrops.total()) ? " - eyedrops (n/a)"
                : " - eyedrops (" + PriceCache.formatCoins(eyedrops.total()) + ")";
        String profitFormula = "Est. profit = return - fuel cost (" + PriceCache.formatCoins(day.fuelCost()) + ")"
                + eyedropPart + ", no mob-kill coins";
        lines.add(Component.literal(profitFormula).withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private void renderCohortExpiryTooltip(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (forceEndRect == null || !forceEndRect.contains(mouseX, mouseY)) return;

        List<Component> lines = new java.util.ArrayList<>();
        List<FuelCycleTracker.MinionExpiry> expiries =
                FuelCycleTracker.getActiveMinionExpiries(System.currentTimeMillis());
        if (expiries.isEmpty()) {
            lines.add(Component.literal("No active cohort").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.literal("Minion fuel expiry (soonest first)")
                    .withStyle(ChatFormatting.GOLD));
            long now = System.currentTimeMillis();
            Map<String, Long> outOfSync = FuelCycleTracker.getOutOfSyncMinions(now);
            for (FuelCycleTracker.MinionExpiry expiry : expiries) {
                String label = expiry.name;
                if (expiry.position != null && !expiry.position.isBlank()) {
                    label += " @ " + expiry.position;
                }
                lines.add(Component.literal(label + " — "
                                + formatRemaining(expiry.expiresAt - now)
                                + " (" + FuelCycleTracker.formatEditableTimestamp(expiry.expiresAt) + ")"
                                + (outOfSync.containsKey(expiry.key) ? " - out of sync" : ""))
                        .withStyle(outOfSync.containsKey(expiry.key) ? ChatFormatting.RED : ChatFormatting.GRAY));
            }
        }
        context.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    private static String formatRemaining(long remainingMs) {
        long totalSeconds = Math.max(0, remainingMs) / 1000;
        long days = totalSeconds / 86_400;
        long hours = (totalSeconds % 86_400) / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long seconds = totalSeconds % 60;
        return days > 0
                ? String.format("%dd %02dh %02dm", days, hours, minutes)
                : String.format("%02dh %02dm %02ds", hours, minutes, seconds);
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

    /** Row background in estimated mode: red for a loss, yellow below 10m, green from 10m up, gray with no price. */
    private static int profitBandBackground(FuelDayEstimates.ProfitBand band) {
        return switch (band) {
            case GOOD -> 0x5055FF55;
            case LOW -> 0x50FFFF55;
            case LOSS -> 0x50FF5555;
            case UNKNOWN -> 0x50404040;
        };
    }

    private static int profitBandTextColor(FuelDayEstimates.ProfitBand band) {
        return switch (band) {
            case GOOD -> 0xFF55FF55;
            case LOW -> 0xFFFFFF55;
            case LOSS -> 0xFFFF5555;
            case UNKNOWN -> 0xFFAAAAAA;
        };
    }

    private static ChatFormatting profitBandStyle(FuelDayEstimates.ProfitBand band) {
        return switch (band) {
            case GOOD -> ChatFormatting.GREEN;
            case LOW -> ChatFormatting.YELLOW;
            case LOSS -> ChatFormatting.RED;
            case UNKNOWN -> ChatFormatting.GRAY;
        };
    }

    /** A border just inside the row of the fuel day that is running right now. */
    private void drawCurrentDayOutline(GuiGraphicsExtractor context, int rowY, int rowHeight) {
        int right = listLeft + listWidth;
        int bottom = rowY + rowHeight - 1;
        int color = CURRENT_DAY_OUTLINE_COLOR;
        context.fill(listLeft, rowY, right, rowY + 2, color);
        context.fill(listLeft, bottom - 2, right, bottom, color);
        context.fill(listLeft, rowY, listLeft + 2, bottom, color);
        context.fill(right - 2, rowY, right, bottom, color);
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

        if (editDialogOpen) return false;

        List<FuelCycleTracker.DayRecord> records = records();
        int rowHeight = rowHeight();
        int visibleRows = (listBottom - listTop) / rowHeight;

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

        // Another data set is only browsed, so its rows have no buttons
        if (!builtForActive) return false;

        for (int i = 0; i < records.size(); i++) {
            int drawIndex = i - scrollOffset;
            int rowY = listTop + drawIndex * rowHeight;
            if (rowY + rowHeight < listTop || rowY > listBottom) continue;

            int btnX = listLeft + listWidth - (BUTTON_SIZE + 2) * 2;
            int btnY = rowY + (rowHeight - BUTTON_SIZE) / 2;

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
        if (editDialogOpen) return false;
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
        List<FuelCycleTracker.DayRecord> records = records();
        int visibleRows = (listBottom - listTop) / rowHeight();
        int maxScroll = Math.max(0, records.size() - visibleRows);
        float ratio = (float) (mouseY - listTop) / (listBottom - listTop);
        ratio = Math.max(0, Math.min(1, ratio));
        scrollOffset = Math.round(ratio * maxScroll);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (editDialogOpen) {
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
