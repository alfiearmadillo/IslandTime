package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Editor for the Huntrap data used by /it huntrapProfit: one row per location, with buttons to disable it (kept,
 * but left out of the calculation), open it (to edit which shards it catches and their weights) or delete it. The
 * header adds locations, opens the shard catalog, and restores the built-in data.
 */
public class HuntrapEditorScreen extends ListEditorScreen {

    private static final int TOGGLE_WIDTH = 50;
    private static final int EDIT_WIDTH = 40;
    private static final int DELETE_WIDTH = 20;
    static final int DISABLED_BACKGROUND = 0x40777777;

    private Button resetButton;

    public HuntrapEditorScreen() {
        this(null);
    }

    public HuntrapEditorScreen(Screen parent) {
        super(parent, "Huntrap Data");
    }

    @Override
    protected String screenTitle() {
        return "Huntrap Data";
    }

    @Override
    protected String emptyMessage() {
        return "No locations. Add one, or Reset To Defaults.";
    }

    @Override
    protected String idleStatus() {
        return HuntrapLocations.isCustomised()
                ? "Using your edited data (config/IslandTime/huntrap_locations.json)"
                : "Using the built-in data. Nothing is saved until you change something.";
    }

    @Override
    protected void addHeaderWidgets() {
        int addWidth = 100;
        int shardsWidth = 70;
        int resetWidth = 140;
        int gap = 6;
        int x = width / 2 - (addWidth + shardsWidth + resetWidth + gap * 2) / 2;

        addRenderableWidget(Button.builder(Component.literal("+ Add Location"), btn -> openAddLocation())
                .bounds(x, HEADER_Y, addWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Shards"),
                        btn -> Minecraft.getInstance().setScreen(new HuntrapShardsScreen(this, -1)))
                .bounds(x + addWidth + gap, HEADER_Y, shardsWidth, 20).build());
        resetButton = Button.builder(Component.literal("Reset To Defaults"), btn -> {
            if (consumeConfirm("reset")) {
                String error = HuntrapLocations.resetToDefaults();
                setStatus(error == null ? "Restored the built-in Huntrap data." : error, error == null);
            }
        }).bounds(x + addWidth + shardsWidth + gap * 2, HEADER_Y, resetWidth, 20).build();
        addRenderableWidget(resetButton);
    }

    @Override
    protected void onFrame(GuiGraphicsExtractor context) {
        if (resetButton != null) {
            resetButton.setMessage(Component.literal(isArmed("reset") ? "Click Again To Reset" : "Reset To Defaults"));
        }
    }

    private void openAddLocation() {
        openForm("Add Location", "Add",
                List.of(new TextField("Name", "", HuntrapLocations.MAX_LOCATION_NAME, "e.g. Crystal Hollows"),
                        new TextField("Placement", "", HuntrapLocations.MAX_PLACEMENT, "e.g. Underwater (optional)")),
                List.of(),
                (texts, choices) -> {
                    String error = HuntrapLocations.addLocation(texts[0], texts[1]);
                    if (error != null) return error;
                    setStatus("Added " + texts[0].strip() + ". Open it with Edit to add its shards.", true);
                    scrollToEnd();
                    return null;
                });
    }

    @Override
    protected List<Row> rows() {
        List<HuntrapLocations.Location> locations = HuntrapLocations.allIncludingEmpty();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < locations.size(); i++) {
            final int index = i;
            HuntrapLocations.Location location = locations.get(i);
            List<RowButton> buttons = new ArrayList<>();
            buttons.add(button(location.enabled() ? "Disable" : "Enable", TOGGLE_WIDTH,
                    location.enabled() ? 0xFFFFAA00 : 0xFF55FF55, () -> toggleLocation(index, location),
                    location.enabled() ? "Leave this location out of /it huntrapProfit, keeping its data"
                            : "Include this location in /it huntrapProfit again"));
            buttons.add(button("Edit", EDIT_WIDTH, 0xFF55FF55, () -> openLocation(index),
                    "Change the shards and weights of this location"));
            buttons.add(confirmButton("X", "?", DELETE_WIDTH, 0xFFFF5555, "loc:" + location.label(),
                    () -> deleteLocation(index, location.label()), "Delete this location"));
            String title = location.enabled() ? location.label() : location.label() + " (disabled)";
            int background = !location.enabled() ? DISABLED_BACKGROUND
                    : location.entries().isEmpty() ? 0x40AA5500 : 0;
            rows.add(new Row(title, describe(location), background, buttons));
        }
        return rows;
    }

    private void toggleLocation(int index, HuntrapLocations.Location location) {
        boolean enable = !location.enabled();
        String error = HuntrapLocations.setLocationEnabled(index, enable);
        setStatus(error == null ? (enable ? "Enabled " : "Disabled ") + location.label() + "." : error, error == null);
    }

    private void openLocation(int index) {
        Minecraft.getInstance().setScreen(new HuntrapLocationScreen(this, index));
    }

    private void deleteLocation(int index, String label) {
        String error = HuntrapLocations.removeLocation(index);
        setStatus(error == null ? "Deleted " + label + "." : error, error == null);
    }

    /** "3 shards: Ant 50.0%, Azure 30.0%, ..." Only shards that are switched on count towards the odds. */
    private static String describe(HuntrapLocations.Location location) {
        List<HuntrapLocations.Entry> entries = location.entries();
        if (entries.isEmpty()) return "No shards yet (skipped by /it huntrapProfit)";
        int active = location.activeCount();
        if (!location.enabled()) {
            return "Disabled (skipped by /it huntrapProfit)  -  " + entries.size()
                    + (entries.size() == 1 ? " shard" : " shards");
        }
        if (active == 0) return "Every shard here is disabled (skipped by /it huntrapProfit)";

        double total = location.totalWeight();
        StringBuilder sb = new StringBuilder();
        sb.append(active).append(active == 1 ? " shard: " : " shards: ");
        boolean first = true;
        for (HuntrapLocations.Entry entry : entries) {
            if (!entry.active()) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.shard().name()).append(' ')
                    .append(String.format(Locale.ROOT, "%.1f%%", entry.weight() / total * 100.0));
        }
        if (active < entries.size()) sb.append("  (+").append(entries.size() - active).append(" disabled)");
        return sb.toString();
    }
}
