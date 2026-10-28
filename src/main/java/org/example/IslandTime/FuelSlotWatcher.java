package org.example.islandtime;

import org.example.islandtime.FuelSlotChange.Reading;
import org.example.islandtime.FuelSlotChange.Result;

/**
 * Watches one open minion GUI's fuel slot and decides when fuel was added. Kept free of Minecraft types
 * (it only sees {@link Reading}s) so its behaviour can be checked against a recorded GUI debug log.
 *
 * <p>What Hypixel actually does when fuel is placed in the slot, as recorded by the GUI debug log:
 * <ol>
 *   <li>The client predicts the click: the placed fuel (no "Time Remaining" line) appears in the slot and
 *       the display item it replaced lands on the cursor.</li>
 *   <li>The server empties the cursor and re-sends the slot with a stack count of 3 but the old,
 *       unchanged time remaining. That count is a leftover, not the amount of fuel.</li>
 *   <li>Up to a second later its once-a-second refresh sends the real slot: count 1, time remaining one
 *       day longer.</li>
 * </ol>
 * The refresh also re-issues the item with a fresh uuid every second even when nothing changed, and it is
 * never sent when the GUI is closed first, so the placement has to be remembered from step 1 to be counted.
 */
final class FuelSlotWatcher {

    /** How long a changed reading must hold before it is believed, in ticks. */
    static final int SETTLING_TICKS = 3;

    /** Where a debug log of what the watcher decides goes; the watcher stays silent without one. */
    interface Tracer {
        Tracer NONE = new Tracer() {
            public boolean enabled() { return false; }
            public void transition(String line) {}
            public void event(String line) {}
            public void endRun(String why) {}
        };

        boolean enabled();

        /** A state that may hold across many ticks; written once while it repeats. */
        void transition(String line);

        void event(String line);

        void endRun(String why);
    }

    /** A change to report: what the slot showed before, and the reading that is now the confirmed state. */
    record Outcome(Result change, Reading previous, Reading confirmed) {}

    private final Tracer tracer;
    private Reading baseline;

    // The amount the slot will show once the placement in flight is applied, or -1 when none is.
    private int placementTarget = -1;

    // Whether the server has taken the placement, shown by the cursor having been emptied. A refused
    // placement hands the fuel back to the cursor instead, so it never gets here.
    private boolean placementAccepted = false;

    private Reading settling = null;
    private int settlingTicks = 0;

    FuelSlotWatcher(Reading baseline, Tracer tracer) {
        this.baseline = baseline;
        this.tracer = tracer == null ? Tracer.NONE : tracer;
    }

    Reading baseline() {
        return baseline;
    }

    /**
     * The client's answer to a click on the slot that is still in flight: the placed fuel sits in the slot
     * and the display item it replaced sits on the cursor.
     */
    private static boolean isPlacementPrediction(Reading slot, Reading cursor) {
        return !slot.empty() && !slot.timed() && !cursor.empty() && cursor.timed() && slot.name().equals(cursor.name());
    }

    private void notePlacement(Reading slot, Reading cursor) {
        // The same unanswered click is seen on every tick it stays in flight.
        if (placementTarget >= 0 && !placementAccepted) return;

        // A click made while the previous one is accepted but not yet shown stacks onto it; the display item
        // on the cursor is still the stale one from before the first.
        int target = (placementTarget >= 0 ? placementTarget : cursor.units()) + slot.units();
        placementTarget = target;
        placementAccepted = false;
        if (tracer.enabled()) {
            tracer.event("placement in flight: slot=" + slot + " cursor=" + cursor
                    + " -> the slot should end at " + target + " (baseline " + baseline + ")");
        }
    }

    private void dropPlacement(String why) {
        if (placementTarget < 0) return;
        if (tracer.enabled()) tracer.event("placement no longer in flight: " + why);
        placementTarget = -1;
        placementAccepted = false;
    }

    /** Follows what the cursor does to a placement that is not (or is no longer) shown as a prediction. */
    private void followPlacement(Reading slot, Reading cursor, boolean cursorHeld) {
        if (placementTarget < 0) return;
        if (!cursorHeld) {
            placementAccepted = true;
        } else if (!placementAccepted) {
            dropPlacement("the cursor holds " + cursor + " with slot=" + slot);
        }
    }

    /**
     * One client tick with the GUI open.
     *
     * @param cursorHeld   whether anything at all is on the cursor
     * @param unitsExpired whole fuel units that ran out since the baseline was confirmed
     * @return the counted change, or null when there is nothing to report yet
     */
    Outcome tick(Reading slot, Reading cursor, boolean cursorHeld, int unitsExpired) {
        if (isPlacementPrediction(slot, cursor)) {
            notePlacement(slot, cursor);
            settling = null;
            settlingTicks = 0;
            if (tracer.enabled()) {
                tracer.transition("cursor guard: the client is predicting a placement, slot=" + slot + " cursor=" + cursor
                        + "; waiting for the server's answer");
            }
            return null;
        }
        followPlacement(slot, cursor, cursorHeld);

        if (slot.sameAs(baseline)) {
            settling = null;
            settlingTicks = 0;
            if (tracer.enabled()) tracer.endRun("slot equals the baseline again " + slot);
            return null;
        }

        // While the cursor still holds something the click is unanswered and the slot is the client's guess.
        if (cursorHeld) {
            settling = null;
            settlingTicks = 0;
            if (tracer.enabled()) {
                tracer.transition("cursor guard: slot=" + slot + " differs from baseline " + baseline
                        + " but the cursor holds " + cursor + "; waiting for it to clear");
            }
            return null;
        }

        if (!slot.sameAs(settling)) {
            settling = slot;
            settlingTicks = 1;
            if (tracer.enabled()) {
                tracer.transition("settling 1/" + SETTLING_TICKS + " slot=" + slot + " baseline=" + baseline);
            }
            return null;
        }
        settlingTicks++;
        if (settlingTicks < SETTLING_TICKS) {
            if (tracer.enabled()) tracer.transition("settling " + settlingTicks + "/" + SETTLING_TICKS + " slot=" + slot);
            return null;
        }
        settling = null;
        settlingTicks = 0;
        if (tracer.enabled()) tracer.transition("settled slot=" + slot + " baseline=" + baseline);

        return confirm(slot, unitsExpired);
    }

    /**
     * The GUI is being left (or another minion selected). Counts a placement that never got to show up in
     * the slot reading, from the menu's last state, which can include a click made in the very tick the GUI
     * closed. Only additions are counted; anything else is left for the next time the minion is opened,
     * which compares its slot with the last confirmed one.
     */
    Outcome flush(Reading slot, Reading cursor, boolean cursorHeld, int unitsExpired) {
        if (isPlacementPrediction(slot, cursor)) {
            notePlacement(slot, cursor);
        } else {
            followPlacement(slot, cursor, cursorHeld);
        }

        // The slot never shows more than the amount the placement was heading for, so a reading below it
        // is the stale pre-placement display (or the client's guess) and the placement counts in full.
        Reading effective = slot;
        if (placementTarget >= 0 && !slot.empty() && slot.units() < placementTarget) {
            effective = new Reading(false, slot.name(), placementTarget, slot.timed());
            if (tracer.enabled()) {
                tracer.event("flush: slot=" + slot + " is behind the placement in flight; using " + effective);
            }
        }

        if (effective.sameAs(baseline)) {
            if (tracer.enabled()) tracer.event("flush: slot equals the baseline, nothing to count");
            return null;
        }

        Result change = FuelSlotChange.evaluate(baseline, effective, unitsExpired);
        if (change.newFuelUnits() <= 0) {
            if (tracer.enabled()) {
                tracer.event("flush: slot=" + effective + " is not an addition to " + baseline + "; nothing counted");
            }
            return null;
        }
        if (tracer.enabled()) {
            tracer.event("flush: counting a placement seen on close, slot=" + slot + " cursor=" + cursor
                    + " counted as " + effective);
        }
        return confirm(effective, change);
    }

    private Outcome confirm(Reading reading, int unitsExpired) {
        return confirm(reading, FuelSlotChange.evaluate(baseline, reading, unitsExpired));
    }

    private Outcome confirm(Reading reading, Result change) {
        Reading previous = baseline;
        baseline = reading;
        placementTarget = -1;
        placementAccepted = false;
        return new Outcome(change, previous, reading);
    }
}
