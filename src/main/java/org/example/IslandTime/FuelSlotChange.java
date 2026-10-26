package org.example.islandtime;

/**
 * Works out how much fuel was added to (or taken out of) a minion's fuel slot between two
 * readings of it. Kept free of Minecraft types so the counting rules stand on their own.
 */
final class FuelSlotChange {

    private FuelSlotChange() {}

    /** What the fuel slot showed: nothing (or the "Fuel" placeholder pane), or a stack of one kind of fuel. */
    record Reading(boolean empty, String name, int count) {
        static final Reading EMPTY = new Reading(true, "", 0);

        static Reading of(String name, int count) {
            return new Reading(false, name, count);
        }
    }

    /**
     * @param newFuelUnits fuel units that were newly added, 0 when none
     * @param sameFuelType the slot held the same fuel before and after
     * @param fuelRemoved  the slot went from holding fuel to being empty
     */
    record Result(int newFuelUnits, boolean sameFuelType, boolean fuelRemoved) {}

    /**
     * @param initial      the last confirmed reading
     * @param current      the reading to compare against it
     * @param unitsExpired whole fuel units that have run out since {@code initial} was confirmed, which hide
     *                     an equal number of additions from the visible count
     */
    static Result evaluate(Reading initial, Reading current, int unitsExpired) {
        boolean sameFuelType = !initial.empty() && !current.empty() && current.name().equals(initial.name());
        int newFuelUnits = 0;
        boolean fuelRemoved = false;

        if (initial.empty() && !current.empty()) {
            // The whole stack is newly placed fuel.
            newFuelUnits = Math.max(1, current.count());
        } else if (!initial.empty() && !current.empty()) {
            if (sameFuelType) {
                // Only the increase in count is newly added fuel; a same-or-lower count just reflects the
                // existing fuel depleting over time. An addition made while the GUI was closed can be hidden
                // by units that expired in the meantime, so those are added back.
                newFuelUnits = current.count() > initial.count()
                        ? current.count() - initial.count()
                        : Math.max(0, current.count() - initial.count() + unitsExpired);
            } else {
                // A different fuel placed on top of what remained: the whole new stack is fresh fuel.
                newFuelUnits = Math.max(1, current.count());
            }
        } else if (!initial.empty()) {
            fuelRemoved = true;
        }

        return new Result(newFuelUnits, sameFuelType, fuelRemoved);
    }
}
