package org.example.islandtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out how much fuel was added to (or taken out of) a minion's fuel slot between two
 * readings of it. Kept free of Minecraft types so the counting rules stand on their own.
 */
final class FuelSlotChange {

    private FuelSlotChange() {}

    static final long DAY_MS = 24 * 60 * 60 * 1000L;

    /**
     * What the fuel slot showed: nothing (or the "Fuel" placeholder pane), or one kind of fuel and how many
     * units of it there are.
     *
     * <p>Hypixel's fuel item always has a stack count of 1; how much fuel is queued is written in its
     * "Time Remaining" lore line, which {@link #parseTimeRemainingMs} reads. For such an item the units are
     * the whole days remaining ({@code timed}). Only an item without that line (such as the fuel in the
     * player's inventory) counts its stack size instead.
     */
    record Reading(boolean empty, String name, int units, boolean timed) {
        static final Reading EMPTY = new Reading(true, "", 0, false);

        static Reading of(String name, int count) {
            return new Reading(false, name, count, false);
        }

        static Reading timed(String name, long remainingMs) {
            return new Reading(false, name, (int) Math.min(Integer.MAX_VALUE, Math.max(0L, remainingMs) / DAY_MS), true);
        }

        /** Whether both readings show the same fuel and the same amount of it, however each got that amount. */
        boolean sameAs(Reading other) {
            return other != null && empty == other.empty && units == other.units && name.equals(other.name);
        }

        @Override
        public String toString() {
            return empty ? "empty" : units + (timed ? "d" : "x") + " \"" + name + "\"";
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
     *                     an equal number of additions from the visible amount
     */
    static Result evaluate(Reading initial, Reading current, int unitsExpired) {
        boolean sameFuelType = !initial.empty() && !current.empty() && current.name().equals(initial.name());
        int newFuelUnits = 0;
        boolean fuelRemoved = false;

        if (initial.empty() && !current.empty()) {
            // The whole stack is newly placed fuel.
            newFuelUnits = Math.max(1, current.units());
        } else if (!initial.empty() && !current.empty()) {
            if (sameFuelType) {
                // Only the increase is newly added fuel; a same-or-lower amount just reflects the existing
                // fuel depleting over time. An addition made while the GUI was closed can be hidden by units
                // that expired in the meantime, so those are added back.
                newFuelUnits = current.units() > initial.units()
                        ? current.units() - initial.units()
                        : Math.max(0, current.units() - initial.units() + unitsExpired);
            } else {
                // A different fuel placed on top of what remained: the whole new stack is fresh fuel.
                newFuelUnits = Math.max(1, current.units());
            }
        } else if (!initial.empty()) {
            fuelRemoved = true;
        }

        return new Result(newFuelUnits, sameFuelType, fuelRemoved);
    }

    private static final Pattern TIME_REMAINING = Pattern.compile("time remaining\\s*:?\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_PART = Pattern.compile(
            "(\\d+)\\s*(days?|hours?|hrs?|minutes?|mins?|seconds?|secs?|[dhms])(?![a-z])", Pattern.CASE_INSENSITIVE);

    /**
     * Reads a "Time Remaining: 1 day" lore line.
     *
     * @return the time it states in milliseconds, or -1 when the line is not a time-remaining line
     */
    static long parseTimeRemainingMs(String line) {
        Matcher remaining = TIME_REMAINING.matcher(line.trim());
        if (!remaining.find()) return -1;

        long totalMs = 0;
        boolean any = false;
        Matcher part = TIME_PART.matcher(remaining.group(1));
        while (part.find()) {
            any = true;
            long amount = Long.parseLong(part.group(1));
            totalMs += amount * switch (Character.toLowerCase(part.group(2).charAt(0))) {
                case 'd' -> DAY_MS;
                case 'h' -> 60 * 60 * 1000L;
                case 'm' -> 60 * 1000L;
                default -> 1000L;
            };
        }
        return any ? totalMs : -1;
    }
}
