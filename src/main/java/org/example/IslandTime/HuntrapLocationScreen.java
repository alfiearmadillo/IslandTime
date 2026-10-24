package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edits one Huntrap location: its name and placement, and the shards it can catch with their weights. Opened
 * from {@link HuntrapEditorScreen}.
 */
public class HuntrapLocationScreen extends ListEditorScreen {

    private static final int WEIGHT_WIDTH = 50;
    private static final int TOGGLE_WIDTH = 50;
    private static final int REMOVE_WIDTH = 20;

    private final int locationIndex;

    HuntrapLocationScreen(Screen parent, int locationIndex) {
        super(parent, "Huntrap Location");
        this.locationIndex = locationIndex;
    }

    private HuntrapLocations.Location location() {
        List<HuntrapLocations.Location> all = HuntrapLocations.allIncludingEmpty();
        return locationIndex >= 0 && locationIndex < all.size() ? all.get(locationIndex) : null;
    }

    @Override
    protected String screenTitle() {
        HuntrapLocations.Location location = location();
        return location == null ? "Huntrap Location" : location.label();
    }

    @Override
    protected String emptyMessage() {
        return location() == null ? "This location no longer exists." : "No shards here yet. Click '+ Add Shard'.";
    }

    @Override
    protected String idleStatus() {
        HuntrapLocations.Location location = location();
        if (location == null || location.entries().isEmpty()) return "";
        return "Odds are each shard's weight divided by the total. A blank weight uses the average of the others.";
    }

    @Override
    protected void addHeaderWidgets() {
        int renameWidth = 110;
        int addWidth = 100;
        int gap = 6;
        int x = width / 2 - (renameWidth + addWidth + gap) / 2;

        addRenderableWidget(Button.builder(Component.literal("Rename / Move"), btn -> openRename())
                .bounds(x, HEADER_Y, renameWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ Add Shard"),
                        btn -> Minecraft.getInstance().setScreen(new HuntrapShardsScreen(this, locationIndex)))
                .bounds(x + renameWidth + gap, HEADER_Y, addWidth, 20).build());
    }

    private void openRename() {
        HuntrapLocations.Location location = location();
        if (location == null) return;
        openForm("Rename / Move Location", "Save",
                List.of(new TextField("Name", location.name(), HuntrapLocations.MAX_LOCATION_NAME, ""),
                        new TextField("Placement", location.placement() == null ? "" : location.placement(),
                                HuntrapLocations.MAX_PLACEMENT, "e.g. Underwater (optional)")),
                List.of(),
                (texts, choices) -> {
                    String error = HuntrapLocations.updateLocation(locationIndex, texts[0], texts[1]);
                    if (error != null) return error;
                    setStatus("Saved.", true);
                    return null;
                });
    }

    private void openWeight(int entryIndex, HuntrapLocations.Entry entry) {
        String initial = entry.weightKnown() ? HuntrapLocations.formatWeight(entry.weight()) : "";
        openForm("Weight of " + entry.shard().name(), "Save",
                List.of(new TextField("Weight", initial, 16, "Blank = unknown (uses the average)")),
                List.of(),
                (texts, choices) -> {
                    String error = HuntrapLocations.validateWeightText(texts[0]);
                    if (error != null) return error;
                    error = HuntrapLocations.setWeight(locationIndex, entryIndex, HuntrapLocations.parseWeight(texts[0]));
                    if (error != null) return error;
                    setStatus("Saved the weight of " + entry.shard().name() + ".", true);
                    return null;
                });
    }

    private void removeEntry(int entryIndex, String shardName) {
        String error = HuntrapLocations.removeEntry(locationIndex, entryIndex);
        setStatus(error == null ? "Removed " + shardName + " from this location." : error, error == null);
    }

    @Override
    protected List<Row> rows() {
        HuntrapLocations.Location location = location();
        List<Row> rows = new ArrayList<>();
        if (location == null) return rows;

        double total = location.totalWeight();
        List<HuntrapLocations.Entry> entries = location.entries();
        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            HuntrapLocations.Entry entry = entries.get(i);
            String shardName = entry.shard().name();

            String weight = entry.weightKnown()
                    ? "Weight " + HuntrapLocations.formatWeight(entry.weight())
                    : "Weight unknown (" + HuntrapLocations.formatWeight(entry.weight()) + ", the average)";
            String odds = entry.active() ? String.format(Locale.ROOT, "%.1f%%", entry.weight() / total * 100.0)
                    : entry.enabled() ? "off: disabled in the shard list" : "disabled";
            String subtitle = weight + "  -  " + odds + "  -  "
                    + entry.shard().rarity().displayName + " " + entry.shard().category().displayName;

            List<RowButton> buttons = new ArrayList<>();
            String toggleTip = entry.enabled() ? "Treat this location as if it never caught this shard, keeping its weight"
                    : "Count this shard at this location again";
            buttons.add(entry.shard().enabled()
                    ? button(entry.enabled() ? "Disable" : "Enable", TOGGLE_WIDTH,
                            entry.enabled() ? 0xFFFFAA00 : 0xFF55FF55, () -> toggleEntry(index, entry), toggleTip)
                    : button(entry.enabled() ? "Disable" : "Enable", TOGGLE_WIDTH,
                            entry.enabled() ? 0xFFFFAA00 : 0xFF55FF55, () -> toggleEntry(index, entry), toggleTip,
                            "It is also disabled everywhere in the Shards list."));
            buttons.add(button("Weight", WEIGHT_WIDTH, 0xFF55FFFF, () -> openWeight(index, entry),
                    "Change this shard's weight at this location"));
            buttons.add(confirmButton("X", "?", REMOVE_WIDTH, 0xFFFF5555, "entry:" + shardName,
                    () -> removeEntry(index, shardName), "Remove this shard from this location"));
            String title = entry.active() ? shardName : shardName + " (disabled)";
            rows.add(new Row(title, subtitle, entry.active() ? 0 : HuntrapEditorScreen.DISABLED_BACKGROUND, buttons));
        }
        return rows;
    }

    private void toggleEntry(int entryIndex, HuntrapLocations.Entry entry) {
        boolean enable = !entry.enabled();
        String error = HuntrapLocations.setEntryEnabled(locationIndex, entryIndex, enable);
        setStatus(error == null ? (enable ? "Enabled " : "Disabled ") + entry.shard().name() + " here." : error,
                error == null);
    }
}
