package org.example.islandtime;

import java.util.List;
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

    /** What the "Composter:" tab-list widget currently shows. */
    static final class TabReading {
        final double organicMatter;
        final double fuel;
        /** Time until the current compost is done, 0 when the composter is inactive. */
        final long timeLeftMs;

        TabReading(double organicMatter, double fuel, long timeLeftMs) {
            this.organicMatter = organicMatter;
            this.fuel = fuel;
            this.timeLeftMs = timeLeftMs;
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

    private static final Pattern AMOUNT = Pattern.compile("^([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?$");
    private static final Pattern DURATION = Pattern.compile(
            "^(?:(\\d+)\\s*d)?\\s*(?:(\\d+)\\s*h)?\\s*(?:(\\d+)\\s*m)?\\s*(?:(\\d+)\\s*s)?$");
    private static final Pattern UPGRADE_NAME = Pattern.compile("^(Composter Speed|Cost Reduction)(?: ([IVXLC]+))?$");

    private static final int MAX_WIDGET_LINES = 8;

    /**
     * Reads the Garden "Composter:" tab widget out of the (formatting-free) tab-list lines, in
     * tab order. The widget is the header followed by ` Organic Matter: X`, ` Fuel: Y` and
     * ` Time Left: Z` (or INACTIVE) and ends at the first blank line.
     *
     * @return the reading, or null when the widget is absent or incomplete
     */
    static TabReading parseTabWidget(List<String> lines) {
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("Composter:")) {
                header = i;
                break;
            }
        }
        if (header < 0) return null;

        double organicMatter = -1;
        double fuel = -1;
        long timeLeftMs = -1;
        int end = Math.min(lines.size(), header + 1 + MAX_WIDGET_LINES);
        for (int i = header + 1; i < end; i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (key.equalsIgnoreCase("Organic Matter")) {
                organicMatter = parseAmount(value);
            } else if (key.equalsIgnoreCase("Fuel")) {
                fuel = parseAmount(value);
            } else if (key.equalsIgnoreCase("Time Left")) {
                timeLeftMs = value.equalsIgnoreCase("INACTIVE") ? 0L : parseDurationMs(value);
            }
        }
        if (organicMatter < 0 || fuel < 0 || timeLeftMs < 0) return null;
        return new TabReading(organicMatter, fuel, timeLeftMs);
    }

    /** Parses "1,234", "23.4k", "1.2M"... Returns -1 when the text is not an amount. */
    static double parseAmount(String text) {
        if (text == null) return -1;
        Matcher matcher = AMOUNT.matcher(text.trim());
        if (!matcher.matches()) return -1;
        try {
            double value = Double.parseDouble(matcher.group(1).replace(",", ""));
            String suffix = matcher.group(2);
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
