package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the fuel data sets (see {@link FuelDataSets}): the built-in Main and Debug ones and any the player made.
 * View opens a set in the Fuel Day Tracker without changing anything. Use makes it the set that new fuel is
 * recorded into, and that choice is saved as the default.
 */
public class FuelDataSetsScreen extends ListEditorScreen {

    private static final int VIEW_WIDTH = 40;
    private static final int USE_WIDTH = 35;
    private static final int DELETE_WIDTH = 20;

    /** What a row says about a set. */
    private record Summary(int days, double profit, String latest) {
    }

    // Summaries of sets that are not in use come from their files, so they are read once and then kept
    private final Map<String, Summary> summaries = new HashMap<>();

    public FuelDataSetsScreen() {
        this(null);
    }

    public FuelDataSetsScreen(Screen parent) {
        super(parent, "Fuel Data Sets");
    }

    @Override
    protected String screenTitle() {
        return "Fuel Data Sets";
    }

    @Override
    protected String emptyMessage() {
        return "No data sets.";
    }

    @Override
    protected String idleStatus() {
        return "Use sets where new fuel is recorded. View only browses a set.";
    }

    @Override
    protected void addHeaderWidgets() {
        int buttonWidth = 120;
        addRenderableWidget(Button.builder(Component.literal("+ New Data Set"), btn -> openCreate())
                .bounds(width / 2 - buttonWidth / 2, HEADER_Y, buttonWidth, 20).build());
    }

    private void openCreate() {
        openForm("New Data Set", "Create",
                List.of(new TextField("Name", "", FuelDataSets.MAX_NAME_LENGTH, "e.g. Season 2")),
                List.of(new ChoiceField("Use it right away", List.of("No", "Yes"), 0)),
                (texts, choices) -> {
                    String problem = FuelDataSets.validateName(texts[0]);
                    if (problem != null) return problem;
                    FuelDataSets.DataSet created;
                    try {
                        created = FuelDataSets.create(texts[0]);
                    } catch (IllegalArgumentException e) {
                        return e.getMessage();
                    }
                    if (choices[0] == 1) {
                        useSet(created);
                    } else {
                        setStatus("Created \"" + created.name() + "\". Use it to start recording into it.", true);
                    }
                    scrollToEnd();
                    return null;
                });
    }

    @Override
    protected List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (FuelDataSets.DataSet set : FuelDataSets.all()) {
            boolean active = FuelDataSets.isActive(set.id());
            List<RowButton> buttons = new ArrayList<>();
            buttons.add(button("View", VIEW_WIDTH, 0xFF55FFFF, () -> view(set),
                    "Browse this data set in the Fuel Day Tracker",
                    "Read-only, and it doesn't change which set is in use"));
            if (!active) {
                buttons.add(button("Use", USE_WIDTH, 0xFF55FF55, () -> useSet(set),
                        "Record new fuel days into this data set",
                        "Saved as the default, so it is still used next time"));
            }
            if (!set.builtIn() && !active) {
                buttons.add(confirmButton("X", "?", DELETE_WIDTH, 0xFFFF5555, "delete:" + set.id(),
                        () -> delete(set), "Delete this data set and all of its days"));
            }

            Summary summary = summaryOf(set.id(), active);
            String title = set.name() + (active ? "  (in use)" : "");
            rows.add(new Row(title, describe(summary), active ? 0x5000AA00 : 0, buttons));
        }
        return rows;
    }

    private void view(FuelDataSets.DataSet set) {
        Minecraft.getInstance().setScreen(new FuelDayScreen(set.id(), this));
    }

    private void useSet(FuelDataSets.DataSet set) {
        String error = FuelCycleTracker.switchDataSet(set.id());
        if (error != null) {
            setStatus(error, false);
            return;
        }
        // Config values that mirror per-set data (mob-kill baseline, today's gabagool amount) must follow the switch
        ConfigManager.syncFromExistingConfigs();
        summaries.clear();
        setStatus("Now recording into \"" + set.name() + "\".", true);
    }

    private void delete(FuelDataSets.DataSet set) {
        String error = FuelDataSets.delete(set.id());
        summaries.remove(set.id());
        setStatus(error == null ? "Deleted \"" + set.name() + "\"." : error, error == null);
    }

    private Summary summaryOf(String id, boolean active) {
        // The set in use is in memory and changes as fuel is used, so it is worked out again, and any older copy dropped
        if (active) {
            summaries.remove(id);
            return summarize(FuelCycleTracker.getDayRecordsDescending());
        }
        return summaries.computeIfAbsent(id, key -> summarize(FuelCycleTracker.readDayRecordsDescending(key)));
    }

    private static Summary summarize(List<FuelCycleTracker.DayRecord> records) {
        long now = System.currentTimeMillis();
        double profit = 0;
        int started = 0;
        String latest = null;
        for (FuelCycleTracker.DayRecord record : records) {
            if (record.cycleStartMs > now) continue;
            started++;
            profit += record.getProfit(now);
            if (latest == null) latest = FuelCycleTracker.formatCycleRange(record);
        }
        return new Summary(started, profit, latest);
    }

    private static String describe(Summary summary) {
        if (summary.days() == 0) return "No days recorded yet";
        return summary.days() + (summary.days() == 1 ? " day" : " days") + ", profit "
                + PriceCache.formatCoins(summary.profit()) + ", latest " + summary.latest();
    }
}
