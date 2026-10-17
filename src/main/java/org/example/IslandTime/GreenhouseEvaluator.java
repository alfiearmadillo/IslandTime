package org.example.islandtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares a blueprint with what is actually planted. Everything here works in "plot space": cell
 * (0, 0) is the north west block of the greenhouse soil, so the maths doesn't care where in the
 * world the plot is.
 */
final class GreenhouseEvaluator {

    enum Status {
        /** Nothing has been planted here yet. */
        MISSING,
        /** The planned crop is there. */
        DONE,
        /** A different crop is there. */
        WRONG
    }

    /** A crop found in the world, with its position in plot space (cell centres are at .5). */
    record Detected(String crop, double x, double z) {}

    record Result(GreenhouseBlueprint.Placement placement, Status status) {}

    /** How well an orientation of a board fits what is planted. */
    record Orientation(int rotation, boolean mirrored, int done, int wrong) {
        int score() {
            return done - wrong;
        }
    }

    private GreenhouseEvaluator() {}

    static List<Result> evaluate(List<GreenhouseBlueprint.Placement> placements, List<Detected> detected) {
        List<Result> results = new ArrayList<>(placements.size());
        for (GreenhouseBlueprint.Placement p : placements) {
            boolean matched = false;
            boolean wrong = false;
            for (Detected d : detected) {
                boolean same = GreenhouseCrops.interchangeable(p.crop, d.crop());
                if (inside(p, d, 0.0)) {
                    if (same) matched = true;
                    else wrong = true;
                } else if (same && p.size > 1 && inside(p, d, 0.5)) {
                    // A big crop's armor stand doesn't necessarily sit exactly inside its footprint
                    matched = true;
                }
            }
            results.add(new Result(p, matched ? Status.DONE : wrong ? Status.WRONG : Status.MISSING));
        }
        return results;
    }

    private static boolean inside(GreenhouseBlueprint.Placement p, Detected d, double margin) {
        return d.x() >= p.x - margin && d.x() < p.x + p.size + margin
                && d.z() >= p.z - margin && d.z() < p.z + p.size + margin;
    }

    /**
     * Tries the eight ways of turning and mirroring a board and returns the one that fits the
     * planted crops best. The current orientation wins ties, so an empty plot never changes it.
     */
    static Orientation bestOrientation(GreenhouseBlueprint.Board board, List<Detected> detected,
                                       int currentRotation, boolean currentMirrored) {
        Orientation best = measure(board, detected, Math.floorMod(currentRotation, 4), currentMirrored);
        for (int mirrored = 0; mirrored < 2; mirrored++) {
            for (int rotation = 0; rotation < 4; rotation++) {
                Orientation candidate = measure(board, detected, rotation, mirrored == 1);
                if (candidate.score() > best.score()) best = candidate;
            }
        }
        return best;
    }

    private static Orientation measure(GreenhouseBlueprint.Board board, List<Detected> detected,
                                       int rotation, boolean mirrored) {
        int done = 0;
        int wrong = 0;
        List<Result> results = evaluate(GreenhouseBlueprint.transform(board, rotation, mirrored), detected);
        for (Result r : results) {
            if (r.status() == Status.DONE) done++;
            else if (r.status() == Status.WRONG) wrong++;
        }
        return new Orientation(rotation, mirrored, done, wrong);
    }
}
