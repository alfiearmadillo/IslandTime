package org.example.islandtime;

import java.util.Locale;

/**
 * How the backpack preview shows an item count. A real stack holds 1 to 99, but a backpack's count can be any
 * int, including 0 and the negative ones, so those are shown here instead of by the item's own count text.
 * Free of Minecraft code so it can be tested alone.
 */
final class BackpackCounts {

    /** The counts an {@code ItemStack} can hold and have drawn by the game as it is. */
    static final int MIN_STACK_COUNT = 1;
    static final int MAX_STACK_COUNT = 99;

    static boolean fitsStack(int count) {
        return count >= MIN_STACK_COUNT && count <= MAX_STACK_COUNT;
    }

    /**
     * A count short enough for a slot: up to 3 digits as they are, then 1.2k, 12k, 123k, 1.2M and so on up to
     * 2.1B. It never rounds up, so a shown 1.9k is at least that much, and a negative one keeps its sign.
     */
    static String shortText(int count) {
        long abs = Math.abs((long) count);
        if (abs < 1_000) return Integer.toString(count);
        long unit;
        char suffix;
        if (abs >= 1_000_000_000L) {
            unit = 1_000_000_000L;
            suffix = 'B';
        } else if (abs >= 1_000_000L) {
            unit = 1_000_000L;
            suffix = 'M';
        } else {
            unit = 1_000L;
            suffix = 'k';
        }
        long tenths = abs * 10 / unit;
        String number = tenths < 100
                ? tenths / 10 + (tenths % 10 != 0 ? "." + tenths % 10 : "")
                : Long.toString(abs / unit);
        return (count < 0 ? "-" : "") + number + suffix;
    }

    /** The whole count with thousands separators, for the tooltip. */
    static String exactText(int count) {
        return String.format(Locale.ROOT, "%,d", count);
    }

    private BackpackCounts() {
    }
}
