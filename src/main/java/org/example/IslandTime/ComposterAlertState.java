package org.example.islandtime;

/**
 * Alert state machine for the composter. It is fed estimates of when the composter will run
 * empty and decides when the "almost empty" warning and the "empty" alert are due, making sure
 * each of them fires at most once per refill.
 */
final class ComposterAlertState {

    /** An estimate this much later than the previous one means the composter was refilled. */
    static final long REFILL_THRESHOLD_MS = 30_000L;
    /** Empty alerts older than this (e.g. after the PC slept) are dropped instead of sent late. */
    static final long MAX_LATE_MS = 5 * 60_000L;
    /** Estimates moving less than this are not worth persisting. */
    private static final long PERSIST_DRIFT_MS = 60_000L;

    enum Action { NONE, WARNING, EMPTY }

    /** Estimated epoch millis at which the composter runs empty, 0 when unknown. */
    long emptyAtMs;
    /** Label of the resource that runs out first. */
    String limiting = "";
    /** True while an empty alert is pending for the current refill. */
    boolean armed;
    /** True once the warning was handled for the current refill. */
    boolean warned;

    /**
     * Feeds a new estimate.
     *
     * @return true when the state changed enough to be worth persisting
     */
    boolean applyEstimate(long now, long newEmptyAtMs, String newLimiting) {
        long oldEmptyAtMs = emptyAtMs;
        boolean oldArmed = armed;
        boolean oldWarned = warned;

        if (newEmptyAtMs <= now) {
            if (armed) {
                // Seen empty before the pending alert went out: let poll() raise it.
                emptyAtMs = Math.min(emptyAtMs, now);
            } else {
                // Already empty (or not running): there is nothing left to warn about.
                warned = false;
                emptyAtMs = now;
            }
            limiting = newLimiting;
        } else {
            boolean refilled = oldEmptyAtMs <= 0 || newEmptyAtMs > oldEmptyAtMs + REFILL_THRESHOLD_MS;
            if (!refilled && !armed) {
                // The alert for this run already went out; wait for the next refill.
                return false;
            }
            if (refilled) {
                armed = true;
                warned = false;
            }
            emptyAtMs = newEmptyAtMs;
            limiting = newLimiting;
        }

        return armed != oldArmed
                || warned != oldWarned
                || Math.abs(emptyAtMs - oldEmptyAtMs) > PERSIST_DRIFT_MS;
    }

    /**
     * Returns the alert that is due now (if any) and records it as handled.
     *
     * @param warnMs how long before empty to warn, 0 to not warn
     */
    Action poll(long now, long warnMs) {
        if (!armed || emptyAtMs <= 0) return Action.NONE;

        long remaining = emptyAtMs - now;
        if (remaining <= 0) {
            armed = false;
            warned = true;
            return -remaining <= MAX_LATE_MS ? Action.EMPTY : Action.NONE;
        }
        if (!warned && warnMs > 0 && remaining <= warnMs) {
            warned = true;
            return Action.WARNING;
        }
        return Action.NONE;
    }
}
