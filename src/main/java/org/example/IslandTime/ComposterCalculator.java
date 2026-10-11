package org.example.islandtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers for the Garden composter: parsing of the tab-list / upgrade GUI text and the
 * "when will it be empty" estimate. The model mirrors SkyHanni's ComposterApi, i.e. a compost
 * cycle takes 10 minutes divided by (1 + 0.2 per Composter Speed level) and consumes 4000
 * Organic Matter and 2000 Fuel, both reduced by 1% per Cost Reduction level.
 */
final class ComposterCalculator {

    static final double BASE_TIME_PER_COMPOST_MS = 10 * 60_000.0;
    static final double SPEED_BONUS_PER_LEVEL = 0.2;
    static final double BASE_ORGANIC_MATTER_PER_COMPOST = 4_000.0;
    static final double BASE_FUEL_PER_COMPOST = 2_000.0;
    static final double COST_REDUCTION_PER_LEVEL = 0.01;

    private ComposterCalculator() {}

    enum Resource {
        ORGANIC_MATTER("Organic Matter"),
        FUEL("Fuel");

        final String label;

        Resource(String label) {
            this.label = label;
        }
    }

    /** The composter upgrades that influence how long the resources last. */
    enum Upgrade {
        COMPOSTER_SPEED("Composter Speed"),
        COST_REDUCTION("Cost Reduction");

        final String displayName;

        Upgrade(String displayName) {
            this.displayName = displayName;
        }
    }

    static final class Estimate {
        final long msUntilEmpty;
        final Resource limiting;

        Estimate(long msUntilEmpty, Resource limiting) {
            this.msUntilEmpty = msUntilEmpty;
            this.limiting = limiting;
        }
    }

    static final class UpgradeLevel {
        final Upgrade upgrade;
        final int level;

        UpgradeLevel(Upgrade upgrade, int level) {
            this.upgrade = upgrade;
            this.level = level;
        }
    }

    /** What the composter currently shows (tab-list widget or Composter menu). */
    static final class TabReading {
        final double organicMatter;
        final double fuel;
        /** Time until the current compost is done, 0 when inactive, -1 when the source did not show it. */
        final long timeLeftMs;

        TabReading(double organicMatter, double fuel, long timeLeftMs) {
            this.organicMatter = organicMatter;
            this.fuel = fuel;
            this.timeLeftMs = timeLeftMs;
        }

        boolean hasTimeLeft() {
            return timeLeftMs >= 0;
        }
    }

    /** One item of the Composter menu: its slot, name and lore lines (formatting already removed). */
    static final class MenuItem {
        final int slot;
        final String name;
        final List<String> lore;

        MenuItem(int slot, String name, List<String> lore) {
            this.slot = slot;
            this.name = name == null ? "" : name;
            this.lore = lore == null ? List.of() : lore;
        }
    }

    // -------------------------------------------------------------------------
    // Estimate
    // -------------------------------------------------------------------------

    static double timePerCompostMs(int speedLevel) {
        return BASE_TIME_PER_COMPOST_MS / (1.0 + Math.max(0, speedLevel) * SPEED_BONUS_PER_LEVEL);
    }

    static double organicMatterPerCompost(int costReductionLevel) {
        return BASE_ORGANIC_MATTER_PER_COMPOST * costMultiplier(costReductionLevel);
    }

    static double fuelPerCompost(int costReductionLevel) {
        return BASE_FUEL_PER_COMPOST * costMultiplier(costReductionLevel);
    }

    private static double costMultiplier(int costReductionLevel) {
        return 1.0 - Math.max(0, costReductionLevel) * COST_REDUCTION_PER_LEVEL;
    }

    /**
     * @param organicMatter    organic matter currently stored in the composter
     * @param fuel             fuel currently stored in the composter
     * @param timeLeftMs       time until the compost currently being made is done (0 when inactive)
     * @param speedLevel       Composter Speed upgrade level
     * @param costReductionLevel Cost Reduction upgrade level
     */
    static Estimate estimate(double organicMatter, double fuel, long timeLeftMs,
                             int speedLevel, int costReductionLevel) {
        double timePer = timePerCompostMs(speedLevel);
        long nextCompostMs = Math.max(0L, timeLeftMs);
        // The compost in progress has already consumed part of its resources.
        double fraction = Math.min(1.0, nextCompostMs / timePer);

        long organicMatterMs = msUntilResourceRunsOut(organicMatter, fraction,
                organicMatterPerCompost(costReductionLevel), timePer);
        long fuelMs = msUntilResourceRunsOut(fuel, fraction,
                fuelPerCompost(costReductionLevel), timePer);

        Resource limiting = organicMatterMs <= fuelMs ? Resource.ORGANIC_MATTER : Resource.FUEL;
        return new Estimate(nextCompostMs + Math.min(organicMatterMs, fuelMs), limiting);
    }

    private static long msUntilResourceRunsOut(double amount, double fraction, double perCompost, double timePer) {
        if (perCompost <= 0) return Long.MAX_VALUE / 4;
        double remaining = amount - fraction * perCompost;
        double cycles = Math.max(0.0, Math.floor(remaining / perCompost));
        return (long) (timePer * cycles);
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private static final String NUMBER = "[0-9][0-9,]*(?:\\.[0-9]+)?";
    private static final String SLASH = "[/\u2044\u2215]";
    private static final Pattern AMOUNT = Pattern.compile(
            "^(" + NUMBER + ")\\s*([kKmMbB])?(?:\\s*" + SLASH + "\\s*" + NUMBER + "\\s*[kKmMbB]?)?$");
    private static final Pattern RATIO = Pattern.compile(
            "(" + NUMBER + ")\\s*([kKmMbB])?\\s*" + SLASH + "\\s*(" + NUMBER + ")\\s*([kKmMbB])?");
    private static final Pattern DURATION = Pattern.compile(
            "^(?:(\\d+)\\s*d)?\\s*(?:(\\d+)\\s*h)?\\s*(?:(\\d+)\\s*m)?\\s*(?:(\\d+)\\s*s)?$");
    private static final Pattern CLOCK = Pattern.compile("^(\\d+):(\\d{2})(?::(\\d{2}))?$");
    private static final Pattern TIME_LEFT = Pattern.compile("(?i)time left\\s*:?\\s*(.+)$");
    private static final Pattern UPGRADE_NAME = Pattern.compile("^(Composter Speed|Cost Reduction)(?: ([IVXLC]+))?$");
    private static final Pattern FORMAT_CODES = Pattern.compile("\u00A7[0-9a-fk-orA-FK-OR]");

    private static final int MAX_WIDGET_LINES = 8;
    private static final int MENU_ORGANIC_MATTER_SLOT = 2;
    private static final int MENU_FUEL_SLOT = 7;
    /** Smallest capacity a "stored/capacity" ratio can have; filters out unrelated "1/2" style text. */
    private static final double MENU_MIN_CAPACITY = 10_000.0;

    /**
     * Strips formatting codes and turns every kind of exotic whitespace (non-breaking spaces,
     * tabs, runs of spaces) into single plain spaces, then trims.
     */
    static String normalize(String text) {
        if (text == null) return "";
        String cleaned = FORMAT_CODES.matcher(text).replaceAll("")
                .replace('\u00A0', ' ').replace('\u2007', ' ').replace('\u202F', ' ');
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    /**
     * Reads the Garden "Composter:" tab widget out of the tab-list lines, in tab order. The widget
     * is the header followed by `Organic Matter: X`, `Fuel: Y` and `Time Left: Z` (or INACTIVE),
     * and ends at the first blank line. Amounts may be written as "stored/capacity". The matching
     * is lenient: any whitespace and letter case is accepted and, if the header itself cannot be
     * found, the widget is located through its Organic Matter line.
     *
     * @return the reading, or null when the widget is absent or Organic Matter / Fuel are missing
     */
    static TabReading parseTabWidget(List<String> rawLines) {
        List<String> lines = new ArrayList<>(rawLines.size());
        for (String raw : rawLines) lines.add(normalize(raw));

        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equalsIgnoreCase("Composter:")) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase(Locale.ROOT).startsWith("organic matter:")) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) return null;

        double organicMatter = -1;
        double fuel = -1;
        long timeLeftMs = -1;
        boolean started = false;
        int end = Math.min(lines.size(), start + MAX_WIDGET_LINES);
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                if (started) break;
                continue;
            }
            int colon = line.indexOf(':');
            String key = (colon < 0 ? line : line.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : line.substring(colon + 1).trim();
            switch (key) {
                case "organic matter" -> {
                    started = true;
                    organicMatter = parseAmount(value);
                }
                case "fuel" -> {
                    started = true;
                    fuel = parseAmount(value);
                }
                case "time left" -> {
                    started = true;
                    timeLeftMs = parseTimeLeft(value);
                }
                case "stored compost" -> started = true;
                default -> {
                    // Another widget's header directly after ours ends the section.
                    if (started && line.endsWith(":")) i = end;
                }
            }
        }
        if (organicMatter < 0 || fuel < 0) return null;
        return new TabReading(organicMatter, fuel, timeLeftMs);
    }

    /**
     * Reads the Composter menu (the right-click menu, not the upgrades one). Organic Matter and
     * Fuel are shown as "stored/capacity" in the lore of their items; a "Time Left" line is used
     * if the menu has one.
     *
     * @return the reading (timeLeftMs is -1 when the menu has no "Time Left"), or null when
     *         Organic Matter or Fuel could not be found
     */
    static TabReading parseMenu(List<MenuItem> items) {
        double organicMatter = findMenuAmount(items, "organic", MENU_ORGANIC_MATTER_SLOT);
        double fuel = findMenuAmount(items, "fuel", MENU_FUEL_SLOT);
        if (organicMatter < 0 || fuel < 0) return null;

        long timeLeftMs = -1;
        for (MenuItem item : items) {
            timeLeftMs = findTimeLeft(item.name);
            for (int i = 0; timeLeftMs < 0 && i < item.lore.size(); i++) {
                timeLeftMs = findTimeLeft(item.lore.get(i));
            }
            if (timeLeftMs >= 0) break;
        }
        return new TabReading(organicMatter, fuel, timeLeftMs);
    }

    /** The stored amount of the item whose name contains {@code nameHint}, else of the item in {@code fallbackSlot}. */
    private static double findMenuAmount(List<MenuItem> items, String nameHint, int fallbackSlot) {
        for (MenuItem item : items) {
            if (item.name.toLowerCase(Locale.ROOT).contains(nameHint)) {
                double amount = storedAmount(item);
                if (amount >= 0) return amount;
            }
        }
        for (MenuItem item : items) {
            if (item.slot == fallbackSlot) {
                double amount = storedAmount(item);
                if (amount >= 0) return amount;
            }
        }
        return -1;
    }

    private static double storedAmount(MenuItem item) {
        double amount = firstRatioStored(item.name);
        for (int i = 0; amount < 0 && i < item.lore.size(); i++) {
            amount = firstRatioStored(item.lore.get(i));
        }
        return amount;
    }

    /** The "stored" part of the first "stored/capacity" in the text, or -1. */
    private static double firstRatioStored(String text) {
        Matcher matcher = RATIO.matcher(normalize(text));
        while (matcher.find()) {
            double stored = applySuffix(matcher.group(1), matcher.group(2));
            double capacity = applySuffix(matcher.group(3), matcher.group(4));
            if (stored >= 0 && capacity >= MENU_MIN_CAPACITY) return stored;
        }
        return -1;
    }

    private static long findTimeLeft(String text) {
        Matcher matcher = TIME_LEFT.matcher(normalize(text));
        return matcher.find() ? parseTimeLeft(matcher.group(1)) : -1;
    }

    /** "INACTIVE" is 0, a duration is its length in ms, anything else is -1. */
    static long parseTimeLeft(String value) {
        if (value == null) return -1;
        String trimmed = value.trim();
        return trimmed.equalsIgnoreCase("INACTIVE") ? 0L : parseDurationMs(trimmed);
    }

    /**
     * Parses "1,234", "23.4k", "1.2M" and also "stored/capacity" such as "67,464/100k" (the stored
     * part is returned). Returns -1 when the text is not an amount.
     */
    static double parseAmount(String text) {
        if (text == null) return -1;
        Matcher matcher = AMOUNT.matcher(text.trim());
        if (!matcher.matches()) return -1;
        return applySuffix(matcher.group(1), matcher.group(2));
    }

    private static double applySuffix(String number, String suffix) {
        try {
            double value = Double.parseDouble(number.replace(",", ""));
            if (suffix != null) {
                switch (Character.toLowerCase(suffix.charAt(0))) {
                    case 'k' -> value *= 1_000.0;
                    case 'm' -> value *= 1_000_000.0;
                    case 'b' -> value *= 1_000_000_000.0;
                    default -> { }
                }
            }
            return value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Parses "1d 2h 3m 4s" style durations. Returns -1 when the text is not a duration. */
    static long parseDurationMs(String text) {
        if (text == null) return -1;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return -1;
        Matcher clock = CLOCK.matcher(trimmed);
        if (clock.matches()) {
            try {
                long first = Long.parseLong(clock.group(1));
                long second = Long.parseLong(clock.group(2));
                if (clock.group(3) == null) return first * 60_000L + second * 1_000L;
                return first * 3_600_000L + second * 60_000L + Long.parseLong(clock.group(3)) * 1_000L;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        Matcher matcher = DURATION.matcher(trimmed);
        if (!matcher.matches()) return -1;
        long[] unitMs = {86_400_000L, 3_600_000L, 60_000L, 1_000L};
        long total = 0;
        boolean any = false;
        for (int i = 0; i < unitMs.length; i++) {
            String group = matcher.group(i + 1);
            if (group == null) continue;
            try {
                total += Long.parseLong(group) * unitMs[i];
                any = true;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return any ? total : -1;
    }

    /** Parses a roman numeral. An empty string means level 0. Returns -1 when invalid. */
    static int parseRoman(String text) {
        if (text == null) return -1;
        String roman = text.trim().toUpperCase();
        if (roman.isEmpty()) return 0;
        int total = 0;
        int previous = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int value = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> -1;
            };
            if (value < 0) return -1;
            if (value < previous) {
                total -= value;
            } else {
                total += value;
                previous = value;
            }
        }
        return total;
    }

    /** Parses an upgrade item name such as "Composter Speed XII". Returns null for other items. */
    static UpgradeLevel parseUpgradeName(String itemName) {
        if (itemName == null) return null;
        Matcher matcher = UPGRADE_NAME.matcher(itemName.trim());
        if (!matcher.matches()) return null;
        String numeral = matcher.group(2);
        int level = numeral == null ? 0 : parseRoman(numeral);
        if (level < 0) return null;
        Upgrade upgrade = matcher.group(1).equals(Upgrade.COMPOSTER_SPEED.displayName)
                ? Upgrade.COMPOSTER_SPEED : Upgrade.COST_REDUCTION;
        return new UpgradeLevel(upgrade, level);
    }

    // -------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------

    /** "2h 05m", "12m 03s", "45s". Rounds up to whole seconds. */
    static String formatDuration(long ms) {
        long totalSeconds = Math.max(0L, (ms + 999L) / 1000L);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format("%dh %02dm", hours, minutes);
        if (minutes > 0) return String.format("%dm %02ds", minutes, seconds);
        return seconds + "s";
    }
}
