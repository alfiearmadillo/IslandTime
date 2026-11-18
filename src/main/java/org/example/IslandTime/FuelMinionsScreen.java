package org.example.islandtime;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists the minions in the active fuel cohort with when each one's fuel runs out, so a minion that has drifted
 * from the rest (time added to or taken off it by hand, a missed refuel) can be put right. Minions whose fuel
 * ends more than 8 hours from the average of the others are marked, and can be synced to that average in one
 * click. Only a minion's own expiry changes; fuel-day records are left as they are.
 */
public class FuelMinionsScreen extends ListEditorScreen {

    private static final long STEP_MS = 60L * 60 * 1000;
    private static final int STEP_WIDTH = 30;
    private static final int EDIT_WIDTH = 34;
    private static final int SYNC_WIDTH = 34;
    private static final int REMOVE_WIDTH = 20;
    private static final int OUT_OF_SYNC_BACKGROUND = 0x50FF5555;

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)\\s*([dhms])", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile("[+-]?\\s*(?:\\d+\\s*[dhms]\\s*)+", Pattern.CASE_INSENSITIVE);

    public FuelMinionsScreen(Screen parent) {
        super(parent, "Cohort Minions");
    }

    @Override
    protected String screenTitle() {
        return "Cohort Minions";
    }

    @Override
    protected String emptyMessage() {
        return "No minions have fuel running.";
    }

    @Override
    protected String idleStatus() {
        return "Red rows are more than 8h from the average of the other minions.";
    }

    @Override
    protected void addHeaderWidgets() {
        int buttonWidth = 170;
        addRenderableWidget(Button.builder(highlightLabel(), btn -> {
                    IslandTimeConfig config = ConfigManager.getConfig();
                    if (config == null) return;
                    config.fuelCycles.highlightOutOfSyncMinions = !config.fuelCycles.highlightOutOfSyncMinions;
                    ConfigManager.saveToFile();
                    btn.setMessage(highlightLabel());
                })
                .bounds(width / 2 - buttonWidth / 2, HEADER_Y, buttonWidth, 20).build());
    }

    private static Component highlightLabel() {
        IslandTimeConfig config = ConfigManager.getConfig();
        boolean on = config != null && config.fuelCycles.highlightOutOfSyncMinions;
        return Component.literal("Highlight Out of Sync: " + (on ? "ON" : "OFF"));
    }

    @Override
    protected List<Row> rows() {
        long now = System.currentTimeMillis();
        List<FuelCycleTracker.MinionExpiry> minions = FuelCycleTracker.getActiveMinionExpiries(now);
        Map<String, Long> outOfSync = FuelCycleTracker.getOutOfSyncMinions(now);

        List<Row> rows = new ArrayList<>();
        for (FuelCycleTracker.MinionExpiry minion : minions) {
            Long difference = outOfSync.get(minion.key);
            String subtitle = formatSpan(minion.expiresAt - now) + " left, ends "
                    + FuelCycleTracker.formatEditableTimestamp(minion.expiresAt);
            if (difference != null) subtitle += " (" + signedSpan(difference) + " vs the highest day)";

            List<RowButton> buttons = new ArrayList<>();
            buttons.add(button("-1h", STEP_WIDTH, 0xFFFFAA00, () -> adjust(minion, -STEP_MS),
                    "Take an hour off this minion's fuel"));
            buttons.add(button("+1h", STEP_WIDTH, 0xFF55FF55, () -> adjust(minion, STEP_MS),
                    "Add an hour to this minion's fuel"));
            buttons.add(button("Edit", EDIT_WIDTH, 0xFFFFFF55, () -> openEdit(minion),
                    "Set when this minion's fuel ends, or add or remove any amount of time"));
            if (difference != null) {
                buttons.add(button("Sync", SYNC_WIDTH, 0xFF55FFFF, () -> syncToRest(minion),
                        "Set this minion's fuel to end at the average of the minions with the most days left"));
            }
            buttons.add(confirmButton("X", "?", REMOVE_WIDTH, 0xFFFF5555, "remove:" + minion.key,
                    () -> remove(minion), "Remove this minion from the cohort, as if its fuel had been taken out"));

            String title = minion.name + " @ " + minion.position;
            rows.add(new Row(title, subtitle, difference != null ? OUT_OF_SYNC_BACKGROUND : 0, buttons));
        }
        return rows;
    }

    private void adjust(FuelCycleTracker.MinionExpiry minion, long deltaMs) {
        apply(minion, minion.expiresAt + deltaMs, "Moved " + minion.name + " @ " + minion.position + " by "
                + signedSpan(deltaMs) + ".");
    }

    private void syncToRest(FuelCycleTracker.MinionExpiry minion) {
        long average = FuelCycleTracker.highestDayAverageExpiry(System.currentTimeMillis());
        if (average < 0) {
            setStatus("There are no other minions to sync with.", false);
            return;
        }
        apply(minion, average, "Synced " + minion.name + " @ " + minion.position + " to the highest day.");
    }

    private void apply(FuelCycleTracker.MinionExpiry minion, long expiresAt, String success) {
        if (FuelCycleTracker.setMinionExpiry(minion.key, expiresAt)) {
            setStatus(success, true);
        } else {
            setStatus("That would leave no fuel on the minion. Use X to remove it instead.", false);
        }
    }

    private void remove(FuelCycleTracker.MinionExpiry minion) {
        FuelCycleTracker.onMinionFuelRemoved(minion.key);
        setStatus("Removed " + minion.name + " @ " + minion.position + ".", true);
    }

    private void openEdit(FuelCycleTracker.MinionExpiry minion) {
        String initial = FuelCycleTracker.formatEditableTimestamp(minion.expiresAt);
        openForm("Edit " + minion.name, "Save",
                List.of(new TextField("Fuel ends (dd/MM/yy HH:mm)", initial, 20, ""),
                        new TextField("Add / remove time", "", 24, "e.g. +1h 30m or -45m")),
                List.of(),
                (texts, choices) -> {
                    long base = minion.expiresAt;
                    // An untouched timestamp keeps the seconds the displayed one has no room for
                    if (!texts[0].trim().equals(initial)) {
                        try {
                            base = FuelCycleTracker.parseEditableTimestamp(texts[0]);
                        } catch (RuntimeException e) {
                            return "Use the format dd/MM/yy HH:mm";
                        }
                    }
                    String delta = texts[1].trim();
                    if (!delta.isEmpty()) {
                        Long deltaMs = parseDuration(delta);
                        if (deltaMs == null) return "Time to add must look like +1h 30m or -45m";
                        base += deltaMs;
                    }
                    if (!FuelCycleTracker.setMinionExpiry(minion.key, base)) {
                        return "The fuel has to end in the future. Use X to remove the minion instead.";
                    }
                    setStatus("Updated " + minion.name + " @ " + minion.position + ".", true);
                    return null;
                });
    }

    /**
     * Reads an amount of time such as {@code +1h 30m}, {@code -45m} or {@code 2d}, in days, hours, minutes and
     * seconds. A minus sign makes it negative. Returns null if the text isn't one.
     */
    static Long parseDuration(String text) {
        String trimmed = text.trim();
        if (!DURATION.matcher(trimmed).matches()) return null;

        long total = 0;
        Matcher matcher = DURATION_PART.matcher(trimmed);
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            total += amount * switch (Character.toLowerCase(matcher.group(2).charAt(0))) {
                case 'd' -> 86_400_000L;
                case 'h' -> 3_600_000L;
                case 'm' -> 60_000L;
                default -> 1_000L;
            };
        }
        return trimmed.startsWith("-") ? -total : total;
    }

    private static String signedSpan(long ms) {
        return (ms < 0 ? "-" : "+") + formatSpan(Math.abs(ms));
    }

    private static String formatSpan(long ms) {
        long totalMinutes = Math.max(0, ms) / 60_000;
        long days = totalMinutes / (24 * 60);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        return days > 0 ? String.format("%dd %02dh %02dm", days, hours, minutes)
                : String.format("%dh %02dm", hours, minutes);
    }
}
