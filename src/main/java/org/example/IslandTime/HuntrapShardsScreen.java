package org.example.islandtime;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Huntrap shard catalog. Opened from {@link HuntrapEditorScreen} to add, edit and delete shards (a shard's
 * name, Bazaar id, rarity and category), or from {@link HuntrapLocationScreen} to pick shards to add to that
 * location.
 */
public class HuntrapShardsScreen extends ListEditorScreen {

    private static final int ROW_BUTTON_WIDTH = 45;
    private static final int DELETE_WIDTH = 20;
    private static final int SEARCH_HEIGHT = 16;

    /** The location shards are being added to, or -1 when this is just the catalog. */
    private final int locationIndex;

    private EditBox searchField;
    private String searchText = "";

    HuntrapShardsScreen(Screen parent, int locationIndex) {
        super(parent, "Huntrap Shards");
        this.locationIndex = locationIndex;
    }

    private boolean picking() {
        return locationIndex >= 0;
    }

    private HuntrapLocations.Location location() {
        List<HuntrapLocations.Location> all = HuntrapLocations.allIncludingEmpty();
        return locationIndex >= 0 && locationIndex < all.size() ? all.get(locationIndex) : null;
    }

    @Override
    protected String screenTitle() {
        if (!picking()) return "Huntrap Shards";
        HuntrapLocations.Location location = location();
        return location == null ? "Add Shard" : "Add Shard To " + location.label();
    }

    @Override
    protected String emptyMessage() {
        if (!searchText.isBlank()) return "No shards match your search.";
        return picking() ? "Every shard is already here. Create a new one with '+ New Shard'." : "No shards.";
    }

    @Override
    protected String idleStatus() {
        return picking() ? "Click Add on a shard to give it a weight at this location." : "";
    }

    @Override
    protected int listTop() {
        return DEFAULT_LIST_TOP + SEARCH_HEIGHT + 6;
    }

    @Override
    protected boolean isTextInputFocused() {
        return searchField != null && searchField.isFocused();
    }

    @Override
    protected void addHeaderWidgets() {
        int newWidth = 100;
        addRenderableWidget(Button.builder(Component.literal("+ New Shard"), btn -> openShardForm(null))
                .bounds(width / 2 - newWidth / 2, HEADER_Y, newWidth, 20).build());

        searchField = new EditBox(font, contentLeft(), DEFAULT_LIST_TOP, contentWidth(), SEARCH_HEIGHT,
                Component.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setHint(Component.literal("Search by name or Bazaar id...").withStyle(ChatFormatting.GRAY));
        searchField.setValue(searchText);
        searchField.setResponder(value -> searchText = value);
        addRenderableWidget(searchField);
    }

    private void openShardForm(HuntrapLocations.Shard existing) {
        List<String> rarities = new ArrayList<>();
        for (HuntrapLocations.Rarity rarity : HuntrapLocations.Rarity.values()) rarities.add(rarity.displayName);
        List<String> categories = new ArrayList<>();
        for (HuntrapLocations.Category category : HuntrapLocations.Category.values()) categories.add(category.displayName);

        openForm(existing == null ? "New Shard" : "Edit " + existing.name(), existing == null ? "Create" : "Save",
                List.of(new TextField("Name", existing == null ? "" : existing.name(), HuntrapLocations.MAX_SHARD_NAME, ""),
                        new TextField("Bazaar id", existing == null ? "SHARD_" : existing.bazaarId(),
                                HuntrapLocations.MAX_BAZAAR_ID, "e.g. SHARD_ANT")),
                List.of(new ChoiceField("Rarity", rarities, existing == null ? 0 : existing.rarity().ordinal()),
                        new ChoiceField("Category", categories, existing == null ? 0 : existing.category().ordinal())),
                (texts, choices) -> {
                    HuntrapLocations.Rarity rarity = HuntrapLocations.Rarity.values()[choices[0]];
                    HuntrapLocations.Category category = HuntrapLocations.Category.values()[choices[1]];
                    String error = existing == null
                            ? HuntrapLocations.addShard(texts[0], texts[1], rarity, category)
                            : HuntrapLocations.updateShard(existing.name(), texts[0], texts[1], rarity, category);
                    if (error != null) return error;
                    setStatus((existing == null ? "Created " : "Saved ") + texts[0].strip() + ".", true);
                    return null;
                });
    }

    private void openAddToLocation(HuntrapLocations.Shard shard) {
        openForm("Add " + shard.name(), "Add",
                List.of(new TextField("Weight", "", 16, "Blank = unknown (uses the average)")),
                List.of(),
                (texts, choices) -> {
                    String error = HuntrapLocations.validateWeightText(texts[0]);
                    if (error != null) return error;
                    error = HuntrapLocations.addEntry(locationIndex, shard.name(), HuntrapLocations.parseWeight(texts[0]));
                    if (error != null) return error;
                    setStatus("Added " + shard.name() + ".", true);
                    return null;
                });
    }

    private void deleteShard(String name) {
        String error = HuntrapLocations.removeShard(name);
        setStatus(error == null ? "Deleted " + name + "." : error, error == null);
    }

    @Override
    protected List<Row> rows() {
        List<HuntrapLocations.Shard> shards = new ArrayList<>(HuntrapLocations.shards());
        shards.sort(Comparator.comparing((HuntrapLocations.Shard s) -> s.name().toLowerCase(Locale.ROOT)));

        Set<String> present = new HashSet<>();
        HuntrapLocations.Location location = location();
        if (picking() && location != null) {
            for (HuntrapLocations.Entry entry : location.entries()) present.add(entry.shard().name());
        }

        String query = searchText.trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        for (HuntrapLocations.Shard shard : shards) {
            if (present.contains(shard.name())) continue;
            if (!query.isEmpty() && !shard.name().toLowerCase(Locale.ROOT).contains(query)
                    && !shard.bazaarId().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }

            int count = HuntrapLocations.locationsWith(shard.name());
            String subtitle = shard.rarity().displayName + " " + shard.category().displayName + "  -  "
                    + shard.bazaarId() + "  -  " + count + (count == 1 ? " location" : " locations");

            List<RowButton> buttons = new ArrayList<>();
            if (picking()) {
                buttons.add(button("Add", ROW_BUTTON_WIDTH, 0xFF55FF55, () -> openAddToLocation(shard),
                        "Add this shard to " + (location == null ? "the location" : location.label())));
            }
            buttons.add(button("Edit", ROW_BUTTON_WIDTH, 0xFF55FFFF, () -> openShardForm(shard),
                    "Change this shard's name, Bazaar id, rarity or category"));
            if (!picking()) {
                buttons.add(confirmButton("X", "?", DELETE_WIDTH, 0xFFFF5555, "shard:" + shard.name(),
                        () -> deleteShard(shard.name()), "Delete this shard",
                        count == 0 ? "It isn't at any location." : "It is also removed from " + count
                                + (count == 1 ? " location." : " locations.")));
            }
            rows.add(new Row(shard.name(), subtitle, 0, buttons));
        }
        return rows;
    }
}
