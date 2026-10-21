package org.example.islandtime;

import java.util.List;

/**
 * The maths behind the Enchanted Clock's "TIME WARP!" for minion fuel-days. Kept free of
 * any Minecraft/Fabric types so it can be reasoned about (and tested) on its own.
 *
 * <p>Using the clock moves every minion's timeline {@code amount} closer: each fuel-day
 * boundary that still lies in the future happens {@code amount} earlier in real time.
 * A boundary that is closer than {@code amount} can't move into the past, so it lands on
 * "now" and the part of the warp it couldn't absorb is taken from the day after it (that
 * day's boundary is shifted by the full amount like any other). The net effect is
 * exactly "everything from now on is {@code amount} sooner".
 *
 * <p>The cohort's day-slots are nominal 24h steps from {@code cohortStart}. A warp is
 * remembered as {@code (at, amount)} and applied, in order, to those nominal boundaries to
 * find where each slot really starts and ends.
 */
final class MinionTimeWarp {

    /** One use of the clock: at {@link #atMs}, everything after it moved {@link #amountMs} sooner. */
    public static class Warp {
        public long atMs;
        public long amountMs;

        public Warp() {}

        public Warp(long atMs, long amountMs) {
            this.atMs = atMs;
            this.amountMs = amountMs;
        }
    }

    private MinionTimeWarp() {}

    /**
     * Moves {@code boundary} {@code amount} sooner when it is still in the future at
     * {@code at}, never earlier than {@code at} itself. A boundary that already passed is
     * left alone.
     */
    static long shiftBoundary(long boundary, long at, long amount) {
        if (amount <= 0 || boundary <= at) return boundary;
        return Math.max(at, boundary - amount);
    }

    /** Wall-clock end of day-slot {@code index} (= the start of slot {@code index + 1}). */
    static long slotEnd(long cohortStart, long dayMs, int index, List<Warp> warps) {
        long boundary = cohortStart + (long) (index + 1) * dayMs;
        for (Warp warp : warps) {
            // A warp from before this cohort existed never touched its minions.
            if (warp.atMs < cohortStart) continue;
            boundary = shiftBoundary(boundary, warp.atMs, warp.amountMs);
        }
        return boundary;
    }

    /** Wall-clock start of day-slot {@code index}. Slot 0 always starts with the cohort. */
    static long slotStart(long cohortStart, long dayMs, int index, List<Warp> warps) {
        return index <= 0 ? cohortStart : slotEnd(cohortStart, dayMs, index - 1, warps);
    }

    /**
     * The day-slot {@code time} falls in. With no warps this is plain
     * {@code (time - cohortStart) / dayMs}; warps only ever pull boundaries earlier, so the
     * answer is never below that starting guess.
     */
    static int dayIndexAt(long cohortStart, long dayMs, long time, List<Warp> warps) {
        int index = (int) Math.max(0, (time - cohortStart) / dayMs);
        while (slotEnd(cohortStart, dayMs, index, warps) <= time) index++;
        return index;
    }

    /** Total time warped after {@code sinceMs}. */
    static long warpedSince(List<Warp> warps, long sinceMs) {
        long total = 0;
        for (Warp warp : warps) {
            if (warp.atMs > sinceMs) total += warp.amountMs;
        }
        return total;
    }
}
