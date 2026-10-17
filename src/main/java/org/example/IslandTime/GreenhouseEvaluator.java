package org.example.islandtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares a blueprint with what is actually in the greenhouse. Everything here works in "plot space":
 * cell (0, 0) is the north west block of the greenhouse soil, so the maths doesn't care where in the
 * world the plot is.
 *
 * <p>A placement is made of up to two things: the block in the crop layer and the armor stands. Both
 * have to be right for it to be {@link Status#DONE}. A custom crop is the block <em>and</em> its armor
 * stand, so a plain nether wart where a nether wart with a stand is wanted is only half done, and the
 * other way round it is wrong.
 */
final class GreenhouseEvaluator {

    enum Status {
        /** Something is still missing here. */
        MISSING,
        /** It is as planned. */
        DONE,
        /** Something is there that shouldn't be. */
        WRONG
    }

    /**
     * How one placement stands.
     *
     * @param blockOk  the block part is right (or there is none to check)
     * @param standsOk the armor stand part is right (or there is none to check)
     */
    record Result(GreenhouseBlueprint.Placement placement, Status status, boolean blockOk, boolean standsOk) {}

    /** How one cell of the soil stands, for cells the blueprint has a floor block for. */
    record FloorResult(int x, int z, String block, Status status) {}

    /**
     * Everything about a greenhouse measured against one oriented board.
     *
     * @param extras how many cells outside every placement have a block or an armor stand on them
     */
    record Evaluation(List<Result> cells, List<FloorResult> floor, int extras) {
        int done() {
            return count(Status.DONE);
        }

        int wrong() {
            return count(Status.WRONG);
        }

        int floorDone() {
            int n = 0;
            for (FloorResult f : floor) if (f.status() == Status.DONE) n++;
            return n;
        }

        /** Everything matches, and nothing else is in the way. False for an empty board. */
        boolean perfect() {
            return (!cells.isEmpty() || !floor.isEmpty())
                    && done() == cells.size() && floorDone() == floor.size() && extras == 0;
        }

        private int count(Status status) {
            int n = 0;
            for (Result r : cells) if (r.status() == status) n++;
            return n;
        }
    }

    /** How well an orientation of a board fits what is there. */
    record Orientation(int rotation, boolean mirrored, int done, int wrong, int floorDone) {
        int score() {
            return done - wrong;
        }
    }

    private GreenhouseEvaluator() {}

    /**
     * @param board        a board that has already been turned the way it lies on the plot
     * @param includeFloor false to leave the soil out, for people who don't want it checked
     */
    static Evaluation evaluate(GreenhouseBlueprint.Board board, GreenhouseScan.Scan scan, boolean includeFloor) {
        int n = board.size;
        List<Result> cells = new ArrayList<>(board.placements.size());
        for (GreenhouseBlueprint.Placement p : board.placements) cells.add(evaluatePlacement(p, scan));

        List<FloorResult> floor = new ArrayList<>();
        if (includeFloor) {
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    String wanted = board.floorAt(x, z);
                    if (wanted.isEmpty()) continue;
                    String actual = scan.floorAt(x, z);
                    floor.add(new FloorResult(x, z, wanted, actual.equals(wanted) ? Status.DONE
                            : actual.isEmpty() ? Status.MISSING : Status.WRONG));
                }
            }
        }
        return new Evaluation(cells, floor, countExtras(board, scan));
    }

    private static Result evaluatePlacement(GreenhouseBlueprint.Placement p, GreenhouseScan.Scan scan) {
        // The block. Only single cell placements that name one are checked; a big crop is armor stands only.
        boolean blockOk = true;
        boolean blockWrong = false;
        if (p.size == 1 && !p.block.isEmpty()) {
            boolean anyBlock = false;
            boolean matches = false;
            for (GreenhouseScan.BlockCell cell : scan.blocks()) {
                if (cell.x() != p.x || cell.z() != p.z) continue;
                anyBlock = true;
                if (GreenhouseScan.sameBlock(p.block, cell.state())) matches = true;
            }
            blockOk = matches;
            blockWrong = anyBlock && !matches;
        }

        // The armor stands. A big crop's can sit a little outside its footprint.
        double margin = margin(p);
        List<GreenhouseBlueprint.Stand> near = new ArrayList<>();
        int inside = 0;
        for (GreenhouseBlueprint.Stand stand : scan.stands()) {
            if (within(p, stand, margin)) near.add(stand);
            if (within(p, stand, 0.0)) inside++;
        }

        boolean standsOk;
        boolean standsWrong;
        if (p.stands.isEmpty()) {
            standsOk = inside == 0;
            standsWrong = !standsOk;
        } else {
            boolean[] used = new boolean[near.size()];
            int unmatched = 0;
            for (GreenhouseBlueprint.Stand wanted : p.stands) {
                int hit = -1;
                for (int i = 0; i < near.size() && hit < 0; i++) {
                    if (!used[i] && GreenhouseScan.sameStand(wanted.head, near.get(i).head)) hit = i;
                }
                if (hit < 0) unmatched++;
                else used[hit] = true;
            }
            int leftover = 0;
            for (boolean u : used) if (!u) leftover++;
            standsOk = unmatched == 0;
            // Missing one isn't wrong, but having a different one instead is. Spare ones are put up with.
            standsWrong = unmatched > 0 && leftover > 0;
        }

        Status status = blockOk && standsOk ? Status.DONE : blockWrong || standsWrong ? Status.WRONG : Status.MISSING;
        return new Result(p, status, blockOk, standsOk);
    }

    private static double margin(GreenhouseBlueprint.Placement p) {
        return p.size > 1 ? GreenhouseCapture.BIG_CROP_MARGIN : 0.0;
    }

    private static boolean within(GreenhouseBlueprint.Placement p, GreenhouseBlueprint.Stand s, double margin) {
        return s.x >= p.x - margin && s.x < p.x + p.size + margin
                && s.z >= p.z - margin && s.z < p.z + p.size + margin;
    }

    /** Counts the cells no placement covers that have a block or an armor stand on them anyway. */
    private static int countExtras(GreenhouseBlueprint.Board board, GreenhouseScan.Scan scan) {
        int n = board.size;
        boolean[][] busy = new boolean[n][n];
        for (GreenhouseScan.BlockCell cell : scan.blocks()) {
            if (cell.x() >= 0 && cell.x() < n && cell.z() >= 0 && cell.z() < n && !covers(board, cell.x() + 0.5, cell.z() + 0.5)) {
                busy[cell.z()][cell.x()] = true;
            }
        }
        for (GreenhouseBlueprint.Stand stand : scan.stands()) {
            int x = (int) Math.floor(stand.x);
            int z = (int) Math.floor(stand.z);
            if (x >= 0 && x < n && z >= 0 && z < n && !covers(board, stand)) busy[z][x] = true;
        }
        int extras = 0;
        for (boolean[] row : busy) {
            for (boolean b : row) if (b) extras++;
        }
        return extras;
    }

    /** Whether a block at a cell's centre is inside some placement's footprint. */
    private static boolean covers(GreenhouseBlueprint.Board board, double x, double z) {
        for (GreenhouseBlueprint.Placement p : board.placements) {
            if (x >= p.x && x < p.x + p.size && z >= p.z && z < p.z + p.size) return true;
        }
        return false;
    }

    /** Whether an armor stand belongs to a placement, counting the margin a big crop's stands are allowed. */
    private static boolean covers(GreenhouseBlueprint.Board board, GreenhouseBlueprint.Stand stand) {
        for (GreenhouseBlueprint.Placement p : board.placements) {
            if (within(p, stand, margin(p))) return true;
        }
        return false;
    }

    /**
     * Tries the eight ways of turning and mirroring a board and returns the one that fits what is
     * there best: by crops first, then by floor. The current orientation wins ties, so an empty plot
     * never changes it.
     */
    static Orientation bestOrientation(GreenhouseBlueprint.Board board, GreenhouseScan.Scan scan,
                                       int currentRotation, boolean currentMirrored) {
        Orientation best = measure(board, scan, Math.floorMod(currentRotation, 4), currentMirrored);
        for (int mirrored = 0; mirrored < 2; mirrored++) {
            for (int rotation = 0; rotation < 4; rotation++) {
                Orientation candidate = measure(board, scan, rotation, mirrored == 1);
                if (candidate.score() > best.score()
                        || candidate.score() == best.score() && candidate.floorDone() > best.floorDone()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static Orientation measure(GreenhouseBlueprint.Board board, GreenhouseScan.Scan scan,
                                       int rotation, boolean mirrored) {
        Evaluation evaluation = evaluate(GreenhouseBlueprint.transform(board, rotation, mirrored), scan, true);
        return new Orientation(rotation, mirrored, evaluation.done(), evaluation.wrong(), evaluation.floorDone());
    }
}
