package org.example.islandtime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Time and grade maths for fuel-days, kept free of any Minecraft types so it can be tested on its own.
 *
 * <p>A fuel-day produces for 24 hours of real time. Two things make its recorded start-to-end span differ from that:
 * an Enchanted Clock time warp cuts a day short (a 1 hour warp turns a day into a 23 hour span, but the minions still
 * ran for the full day), and a start/end typed in by hand in local time spans 23 or 25 hours when the clocks change
 * in between, even though the day was meant as 24 hours.
 */
final class FuelDayTime {

    static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** How far off a whole number of days a span can be and still count as "meant to be" that many days. */
    private static final long WHOLE_DAY_TOLERANCE_MS = 60_000L;

    private FuelDayTime() {
    }

    /**
     * How long the minions produced during a day that runs from {@code startMs} to {@code endMs}.
     *
     * <p>That is the span plus the time a warp took off it. A span that is not a whole number of days but becomes
     * one once the clock change between its start and end is taken out is a typed-in day that crossed a clock
     * change, and counts as that whole number of days. A span that already is whole days (an automatic day, or one
     * a warp shortened) is left alone, as is any other custom span.
     */
    static long productionMs(long startMs, long endMs, long timeWarpedMs, ZoneId zone) {
        if (endMs <= startMs) return DAY_MS;
        long span = (endMs - startMs) + Math.max(0, timeWarpedMs);
        if (isWholeDays(span)) return span;

        long clockShift = offsetMs(endMs, zone) - offsetMs(startMs, zone);
        if (clockShift == 0) return span;
        long adjusted = span + clockShift;
        return adjusted > 0 && isWholeDays(adjusted) ? adjusted : span;
    }

    /** The calendar date of day-slot {@code dayIndex} of a cohort: one date per slot even across a clock change. */
    static String slotDate(long cohortStartMs, int dayIndex, ZoneId zone) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(cohortStartMs), zone).plusDays(dayIndex).toString();
    }

    /**
     * The fuel grade used for most of the units in {@code counts} (indexed by grade), or -1 when there are none.
     * A tie goes to the higher grade.
     */
    static int majorityGrade(int[] counts) {
        if (counts == null) return -1;
        int best = -1;
        int bestCount = 0;
        for (int grade = 0; grade < counts.length; grade++) {
            if (counts[grade] > 0 && counts[grade] >= bestCount) {
                best = grade;
                bestCount = counts[grade];
            }
        }
        return best;
    }

    /** Whether more than one grade of fuel is counted. */
    static boolean mixedGrades(int[] counts) {
        if (counts == null) return false;
        int used = 0;
        for (int count : counts) {
            if (count > 0) used++;
        }
        return used > 1;
    }

    private static boolean isWholeDays(long ms) {
        if (ms < DAY_MS - WHOLE_DAY_TOLERANCE_MS) return false;
        long days = Math.round((double) ms / DAY_MS);
        return Math.abs(ms - days * DAY_MS) <= WHOLE_DAY_TOLERANCE_MS;
    }

    private static long offsetMs(long epochMs, ZoneId zone) {
        return zone.getRules().getOffset(Instant.ofEpochMilli(epochMs)).getTotalSeconds() * 1000L;
    }
}
