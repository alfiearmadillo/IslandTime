package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

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
import java.util.regex.Pattern;

/**
 * Tracks the Garden composter the way SkyHanni does: the "Composter:" tab-list widget gives the
 * stored organic matter, fuel and time until the next compost, and the Composter Upgrades menu
 * gives the speed / cost reduction levels. From these it estimates when the composter runs
 * empty and sends Discord alerts before (optional) and when that happens.
 */
public class ComposterTracker {

    private static final Path FILE = Paths.get("config/IslandTime/composter.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern FORMAT_CODES = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    private static final String UPGRADES_TITLE = "Composter Upgrades";
    private static final long TAB_SCAN_INTERVAL_MS = 1_000L;
    private static final long UPGRADE_SCAN_INTERVAL_MS = 500L;
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
        ClientPacketListener connection = client.getConnection();
        if (connection == null) return;

        List<PlayerInfo> infos = new ArrayList<>(connection.getListedOnlinePlayers());
        infos.sort(TAB_ORDER);
        List<String> lines = new ArrayList<>(infos.size());
        for (PlayerInfo info : infos) {
            Component name = info.getTabListDisplayName();
            lines.add(name == null ? "" : stripFormatting(name.getString()));
        }

        ComposterCalculator.TabReading reading = ComposterCalculator.parseTabWidget(lines);
        if (reading == null) return;

        lastReading = reading;
        lastReadingAtMs = now;

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
        if (state.applyEstimate(now, now + estimate.msUntilEmpty, estimate.limiting.label)) {
            save();
        }
    }

    private static void readUpgradeMenu(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen containerScreen)) return;
        if (!(client.player.containerMenu instanceof ChestMenu menu)) return;
        if (!UPGRADES_TITLE.equals(stripFormatting(containerScreen.getTitle().getString()).trim())) return;

        int containerSize = menu.slots.size() - 36;
        int newSpeed = speedLevel;
        int newCostReduction = costReductionLevel;
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            ComposterCalculator.UpgradeLevel upgrade = ComposterCalculator.parseUpgradeName(
                    stripFormatting(stack.getHoverName().getString()));
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

    private static String stripFormatting(String text) {
        return text == null ? "" : FORMAT_CODES.matcher(text).replaceAll("");
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
            lines.add("Tab list: no Composter widget seen yet (enable the Composter tab widget and visit the Garden).");
        } else {
            String timeLeft = lastReading.timeLeftMs > 0
                    ? ComposterCalculator.formatDuration(lastReading.timeLeftMs) : "inactive";
            lines.add(String.format("Last tab reading (%s ago): Organic Matter %,d, Fuel %,d, Time Left %s",
                    ComposterCalculator.formatDuration(now - lastReadingAtMs),
                    (long) lastReading.organicMatter, (long) lastReading.fuel, timeLeft));
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
