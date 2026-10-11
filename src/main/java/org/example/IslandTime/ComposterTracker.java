package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Tracks the Garden composter the way SkyHanni does: the "Composter:" tab-list widget gives the
 * stored organic matter, fuel and time until the next compost, and the Composter Upgrades menu
 * gives the speed / cost reduction levels. The Composter menu itself is read as a second source
 * for the stored amounts. From these it estimates when the composter runs empty and sends Discord
 * alerts before (optional) and when that happens.
 */
public class ComposterTracker {

    private static final Path FILE = Paths.get("config/IslandTime/composter.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String UPGRADES_TITLE = "Composter Upgrades";
    private static final long TAB_SCAN_INTERVAL_MS = 1_000L;
    private static final long UPGRADE_SCAN_INTERVAL_MS = 500L;
    /** The vanilla tab overlay only shows this many entries. */
    private static final int MAX_TAB_ENTRIES = 80;
    /** A Composter menu reading is ignored while the tab widget has been read within this time. */
    private static final long MENU_DEFERS_TO_TAB_MS = 60_000L;
    private static final int UNKNOWN_LEVEL = -1;

    /** Same order the vanilla tab overlay uses, so the widget lines come out as the player sees them. */
    private static final Comparator<PlayerInfo> TAB_ORDER =
            Comparator.<PlayerInfo>comparingInt(p -> -p.getTabListOrder())
                    .thenComparingInt(p -> p.getGameMode() == GameType.SPECTATOR ? 1 : 0)
                    .thenComparing((PlayerInfo p) -> p.getTeam() != null ? p.getTeam().getName() : "")
                    .thenComparing((PlayerInfo p) -> p.getProfile().name(), String::compareToIgnoreCase);

    private static final ComposterAlertState state = new ComposterAlertState();
    private static int speedLevel = UNKNOWN_LEVEL;
    private static int costReductionLevel = UNKNOWN_LEVEL;

    private static ComposterCalculator.TabReading lastReading;
    private static long lastReadingAtMs;
    private static String lastReadingSource = "";
    private static long lastTabReadingAtMs;
    private static ComposterCalculator.TabReading lastAppliedMenuReading;
    private static String lastMenuSnapshot = "";
    private static String lastUpgradeMenuSnapshot = "";
    private static long lastMenuSnapshotAtMs;
    private static long lastTabScanMs;
    private static long lastUpgradeScanMs;
    private static boolean upgradeHintShown;

    private ComposterTracker() {}

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public static synchronized void tick(Minecraft client) {
        long now = System.currentTimeMillis();

        if (isEnabled() && client != null && client.player != null && client.level != null) {
            if (now - lastUpgradeScanMs >= UPGRADE_SCAN_INTERVAL_MS) {
                lastUpgradeScanMs = now;
                readUpgradeMenu(client);
                readComposterMenu(client, now);
            }
            if (now - lastTabScanMs >= TAB_SCAN_INTERVAL_MS) {
                lastTabScanMs = now;
                readTabWidget(client, now);
            }
        }

        // Alerts are due regardless of where the player currently is (the composter keeps running).
        pollAlerts(now);
    }

    private static boolean isEnabled() {
        IslandTimeConfig config = ConfigManager.getConfig();
        return config != null && config.composter.enabled;
    }

    // -------------------------------------------------------------------------
    // Reading the game
    // -------------------------------------------------------------------------

    private static void readTabWidget(Minecraft client, long now) {
        List<String> lines = collectTabLines(client, null);
        if (lines.isEmpty()) return;

        ComposterCalculator.TabReading reading = ComposterCalculator.parseTabWidget(lines);
        if (reading == null) return;
        lastTabReadingAtMs = now;
        applyReading(client, reading, "tab list", now);
    }

    /**
     * Reads the Composter menu (right-click the composter), which shows the stored Organic Matter
     * and Fuel as "stored/capacity", as a second data source next to the tab widget.
     */
    private static void readComposterMenu(Minecraft client, long now) {
        if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)
                || !(client.player.containerMenu instanceof ChestMenu menu)) {
            lastAppliedMenuReading = null;
            return;
        }
        String title = ComposterCalculator.normalize(containerScreen.getTitle().getString());
        if (!title.toLowerCase(Locale.ROOT).contains("composter")) {
            lastAppliedMenuReading = null;
            return;
        }

        List<ComposterCalculator.MenuItem> items = collectMenuItems(menu);
        if (!isComposterMenuTitle(title)) {
            lastUpgradeMenuSnapshot = describeMenu(title, items);
            return;
        }
        lastMenuSnapshot = describeMenu(title, items);
        lastMenuSnapshotAtMs = now;

        // The tab widget is live and the preferred source; the menu only fills in when it is missing.
        if (now - lastTabReadingAtMs < MENU_DEFERS_TO_TAB_MS) return;

        ComposterCalculator.TabReading reading = ComposterCalculator.parseMenu(items);
        if (reading == null) return;
        // A menu that is not refreshed by the server keeps showing the same values; applying those
        // again every scan would treat stale data as fresh. Only react to actual changes.
        if (sameReading(reading, lastAppliedMenuReading)) return;
        lastAppliedMenuReading = reading;
        applyReading(client, reading, "composter menu", now);
    }

    private static boolean sameReading(ComposterCalculator.TabReading a, ComposterCalculator.TabReading b) {
        return b != null && a.organicMatter == b.organicMatter && a.fuel == b.fuel && a.timeLeftMs == b.timeLeftMs;
    }

    private static boolean isComposterMenuTitle(String title) {
        String normalized = ComposterCalculator.normalize(title).toLowerCase(Locale.ROOT);
        return normalized.contains("composter") && !normalized.contains("upgrade");
    }

    private static String describeMenu(String title, List<ComposterCalculator.MenuItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("Menu title: \"").append(visible(title)).append("\"\n");
        for (ComposterCalculator.MenuItem item : items) {
            sb.append("  slot ").append(item.slot).append(": \"").append(visible(item.name)).append("\"\n");
            for (String line : item.lore) {
                sb.append("      ").append(visible(line)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Makes invisible characters (non-breaking spaces, control codes) show up in a dump. */
    private static String visible(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A0') {
                sb.append("<nbsp>");
            } else if (c < 0x20 || c == 0x7F) {
                sb.append(String.format("<0x%02X>", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void applyReading(Minecraft client, ComposterCalculator.TabReading reading,
                                     String source, long now) {
        lastReading = reading;
        lastReadingAtMs = now;
        lastReadingSource = source;

        // Without the time to the next compost there is nothing to estimate from.
        if (!reading.hasTimeLeft()) return;

        if (speedLevel == UNKNOWN_LEVEL || costReductionLevel == UNKNOWN_LEVEL) {
            if (!upgradeHintShown && client.player != null) {
                upgradeHintShown = true;
                client.player.sendSystemMessage(Component.literal(
                                "[IslandTime] Open the Composter Upgrades menu once so composter alerts can be calculated.")
                        .withStyle(ChatFormatting.YELLOW));
            }
            return;
        }

        ComposterCalculator.Estimate estimate = ComposterCalculator.estimate(
                reading.organicMatter, reading.fuel, reading.timeLeftMs, speedLevel, costReductionLevel);
        // An inactive composter that still has enough resources is not running out; leave the state alone.
        if (reading.timeLeftMs == 0 && estimate.msUntilEmpty > 0) return;
        if (state.applyEstimate(now, now + estimate.msUntilEmpty, estimate.limiting.label)) {
            save();
        }
    }

    /**
     * The tab list lines in the order the player sees them (first 80
     * entries). Entries without their own display name show their team prefix + name + suffix,
     * exactly like the vanilla overlay (Hypixel puts a lot of the SkyBlock tab text there).
     *
     * @param usedTeamFallback if not null, receives true for lines built from the team
     */
    private static List<String> collectTabLines(Minecraft client, List<Boolean> usedTeamFallback) {
        List<String> lines = new ArrayList<>();
        ClientPacketListener connection = client.getConnection();
        if (connection == null) return lines;

        List<PlayerInfo> infos = new ArrayList<>(connection.getListedOnlinePlayers());
        infos.sort(TAB_ORDER);
        if (infos.size() > MAX_TAB_ENTRIES) infos = infos.subList(0, MAX_TAB_ENTRIES);
        for (PlayerInfo info : infos) {
            Component name = info.getTabListDisplayName();
            boolean fallback = name == null;
            if (fallback) {
                name = PlayerTeam.formatNameForTeam(info.getTeam(), Component.literal(info.getProfile().name()));
            }
            lines.add(name.getString());
            if (usedTeamFallback != null) usedTeamFallback.add(fallback);
        }
        return lines;
    }

    /** The non-empty items of the open chest menu's container part (not the player inventory). */
    private static List<ComposterCalculator.MenuItem> collectMenuItems(ChestMenu menu) {
        List<ComposterCalculator.MenuItem> items = new ArrayList<>();
        int containerSize = Math.max(0, menu.slots.size() - 36);
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            List<String> lore = new ArrayList<>();
            ItemLore itemLore = stack.get(DataComponents.LORE);
            if (itemLore != null) {
                for (Component line : itemLore.lines()) {
                    lore.add(ComposterCalculator.normalize(line.getString()));
                }
            }
            items.add(new ComposterCalculator.MenuItem(
                    i, ComposterCalculator.normalize(stack.getHoverName().getString()), lore));
        }
        return items;
    }

    private static void readUpgradeMenu(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen containerScreen)) return;
        if (!(client.player.containerMenu instanceof ChestMenu menu)) return;
        if (!UPGRADES_TITLE.equalsIgnoreCase(ComposterCalculator.normalize(containerScreen.getTitle().getString()))) return;

        int containerSize = menu.slots.size() - 36;
        int newSpeed = speedLevel;
        int newCostReduction = costReductionLevel;
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            ComposterCalculator.UpgradeLevel upgrade = ComposterCalculator.parseUpgradeName(
                    ComposterCalculator.normalize(stack.getHoverName().getString()));
            if (upgrade == null) continue;
            if (upgrade.upgrade == ComposterCalculator.Upgrade.COMPOSTER_SPEED) {
                newSpeed = upgrade.level;
            } else {
                newCostReduction = upgrade.level;
            }
        }

        if (newSpeed != speedLevel || newCostReduction != costReductionLevel) {
            speedLevel = newSpeed;
            costReductionLevel = newCostReduction;
            save();
        }
    }

    // -------------------------------------------------------------------------
    // Alerts
    // -------------------------------------------------------------------------

    private static void pollAlerts(long now) {
        IslandTimeConfig config = ConfigManager.getConfig();
        boolean enabled = config != null && config.composter.enabled;
        long warnMs = enabled ? Math.max(0, config.composter.warnMinutes) * 60_000L : 0L;

        long remainingBefore = state.emptyAtMs - now;
        String limiting = state.limiting;
        ComposterAlertState.Action action = state.poll(now, warnMs);
        if (action == ComposterAlertState.Action.NONE) return;
        save();

        if (!enabled) return;
        if (action == ComposterAlertState.Action.WARNING) {
            int minutes = (int) Math.max(1L, Math.round(remainingBefore / 60_000.0));
            DiscordWebhookManager.sendComposterWarning(minutes, limiting);
        } else if (config.composter.alertWhenEmpty) {
            DiscordWebhookManager.sendComposterEmptyAlert(limiting);
        }
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /** Lines for the /it composter status command. */
    public static synchronized List<String> describe() {
        long now = System.currentTimeMillis();
        IslandTimeConfig config = ConfigManager.getConfig();
        List<String> lines = new ArrayList<>();

        if (config == null || !config.composter.enabled) {
            lines.add("Composter alerts are OFF (enable them in /islandtime > Composter).");
        } else {
            String warning = config.composter.warnMinutes > 0
                    ? config.composter.warnMinutes + " min before empty" : "off";
            lines.add("Alerts: warning " + warning + ", empty alert "
                    + (config.composter.alertWhenEmpty ? "on" : "off"));
        }

        if (speedLevel == UNKNOWN_LEVEL || costReductionLevel == UNKNOWN_LEVEL) {
            lines.add("Upgrades: unknown - open the Composter Upgrades menu once.");
        } else {
            lines.add("Upgrades: " + ComposterCalculator.Upgrade.COMPOSTER_SPEED.displayName + " " + speedLevel
                    + ", " + ComposterCalculator.Upgrade.COST_REDUCTION.displayName + " " + costReductionLevel);
        }

        if (lastReading == null) {
            lines.add("Composter data: none found yet.");
            lines.add(describeTabScan(Minecraft.getInstance()));
            lines.add("Enable the Composter tab widget (/widget, then visit the Garden) or just open the "
                    + "Composter menu. If it still is not found, run /it composter debug and send the output.");
        } else {
            String timeLeft = !lastReading.hasTimeLeft() ? "not shown by the " + lastReadingSource
                    : lastReading.timeLeftMs > 0 ? ComposterCalculator.formatDuration(lastReading.timeLeftMs)
                    : "inactive";
            lines.add(String.format("Last reading from %s (%s ago): Organic Matter %,d, Fuel %,d, Time Left %s",
                    lastReadingSource, ComposterCalculator.formatDuration(now - lastReadingAtMs),
                    (long) lastReading.organicMatter, (long) lastReading.fuel, timeLeft));
            if (!lastReading.hasTimeLeft()) {
                lines.add("The estimate needs the Time Left from the Composter tab widget (/widget).");
            }
        }

        if (state.emptyAtMs <= 0) {
            lines.add("Estimated empty: unknown");
        } else if (state.emptyAtMs <= now) {
            lines.add("Estimated empty: now (ran out of " + state.limiting + ")");
        } else {
            lines.add("Estimated empty: in " + ComposterCalculator.formatDuration(state.emptyAtMs - now)
                    + " (" + state.limiting + " runs out first)");
        }

        String webhook = DiscordWebhookManager.getWebhook();
        lines.add("Discord webhook: " + (webhook == null || webhook.isEmpty()
                ? "NOT set (/it webhook <url>)" : "set"));
        return lines;
    }

    private static String describeTabScan(Minecraft client) {
        List<String> tab = collectTabLines(client, null);
        if (tab.isEmpty()) return "Tab list: empty (not connected to a server?).";
        for (int i = 0; i < tab.size(); i++) {
            if (ComposterCalculator.normalize(tab.get(i)).equalsIgnoreCase("Composter:")) {
                return "Tab list: found the Composter: widget (line " + (i + 1)
                        + ") but could not read its Organic Matter / Fuel lines.";
            }
        }
        return "Tab list: " + tab.size() + " entries scanned, no Composter: widget in them.";
    }

    /**
     * Everything the tracker can see that is relevant to finding the composter: the tab list lines
     * (with invisible characters made visible), how they parse, and the last Composter menu that
     * was open. Meant to be copied to the clipboard and sent back when detection fails.
     */
    public static synchronized String dumpDiagnostics() {
        Minecraft client = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("=== IslandTime composter debug ===\n");

        List<Boolean> teamFallback = new ArrayList<>();
        List<String> tab = collectTabLines(client, teamFallback);
        sb.append("Tab entries scanned: ").append(tab.size()).append('\n');
        sb.append("Tab widget: ").append(describeReading(ComposterCalculator.parseTabWidget(tab))).append('\n');
        sb.append("Tab lines ([team] = built from the team prefix/suffix, no display name):\n");
        for (int i = 0; i < tab.size(); i++) {
            sb.append(String.format("  %2d: \"%s\"%s", i, visible(tab.get(i)), teamFallback.get(i) ? " [team]" : ""))
                    .append('\n');
        }

        sb.append("Composter menu: ");
        if (lastMenuSnapshot.isEmpty()) {
            sb.append("never opened this session (right-click the composter, close it, then run this again)\n");
        } else {
            sb.append("captured ").append(ComposterCalculator.formatDuration(now - lastMenuSnapshotAtMs)).append(" ago\n");
            sb.append(lastMenuSnapshot);
        }
        if (!lastUpgradeMenuSnapshot.isEmpty()) {
            sb.append("Composter Upgrades menu:\n").append(lastUpgradeMenuSnapshot);
        }

        sb.append("Upgrades: speed ").append(speedLevel == UNKNOWN_LEVEL ? "unknown" : speedLevel)
                .append(", cost reduction ").append(costReductionLevel == UNKNOWN_LEVEL ? "unknown" : costReductionLevel)
                .append('\n');
        return sb.toString();
    }

    private static String describeReading(ComposterCalculator.TabReading reading) {
        if (reading == null) return "not found";
        return String.format("Organic Matter %,d, Fuel %,d, Time Left %s", (long) reading.organicMatter,
                (long) reading.fuel, !reading.hasTimeLeft() ? "unknown"
                        : reading.timeLeftMs == 0 ? "inactive" : ComposterCalculator.formatDuration(reading.timeLeftMs));
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private static class Data {
        int speedLevel = UNKNOWN_LEVEL;
        int costReductionLevel = UNKNOWN_LEVEL;
        long emptyAtMs;
        String limiting = "";
        boolean armed;
        boolean warned;
    }

    public static synchronized void load() {
        if (!Files.exists(FILE)) return;
        try (BufferedReader reader = Files.newBufferedReader(FILE)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data == null) return;
            speedLevel = data.speedLevel;
            costReductionLevel = data.costReductionLevel;
            state.emptyAtMs = data.emptyAtMs;
            state.limiting = data.limiting != null ? data.limiting : "";
            state.armed = data.armed;
            state.warned = data.warned;
        } catch (Exception e) {
            System.err.println("Failed to load composter data: " + e.getMessage());
        }
    }

    public static synchronized void save() {
        Data data = new Data();
        data.speedLevel = speedLevel;
        data.costReductionLevel = costReductionLevel;
        data.emptyAtMs = state.emptyAtMs;
        data.limiting = state.limiting;
        data.armed = state.armed;
        data.warned = state.warned;

        Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        try {
            Files.createDirectories(FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(data, writer);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Failed to save composter data: " + e.getMessage());
        }
    }
}
