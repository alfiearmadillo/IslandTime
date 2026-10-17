package org.example.islandtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
     * @param standOk  for each of the placement's armor stands, in order, whether a matching one is there
     * @param unwanted the armor stands in the way: there when none (or a different one) is wanted
     */
    record Result(GreenhouseBlueprint.Placement placement, Status status, boolean blockOk, boolean standsOk,
                  boolean[] standOk, List<GreenhouseBlueprint.Stand> unwanted) {}

    /**
     * Something in the greenhouse area that no placement accounts for: a block in the crop layer
     * ({@code stand} is null) or an armor stand. {@code x} and {@code z} are the cell it is in.
     */
    record Extra(int x, int z, GreenhouseBlueprint.Stand stand) {}

    /** How one cell of the soil stands, for cells the blueprint has a floor block for. */
    record FloorResult(int x, int z, String block, Status status) {}

    /**
     * Everything about a greenhouse measured against one oriented board.
     *
     * @param extras     how many cells outside every placement have a block or an armor stand on them
     * @param extraSpots what is on them (a cell with a block and a stand is listed twice)
     */
    record Evaluation(List<Result> cells, List<FloorResult> floor, int extras, List<Extra> extraSpots) {
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

        /**
         * Whether the soil under every cell of a placement is what the layout wants. Cells the layout has
         * no soil for aren't checked, and neither is anything when the floor wasn't evaluated.
         */
        boolean floorRight(GreenhouseBlueprint.Placement p) {
            for (FloorResult f : floor) {
                if (f.status() != Status.DONE && f.x() >= p.x && f.x() < p.x + p.size
                        && f.z() >= p.z && f.z() < p.z + p.size) return false;
            }
            return true;
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
        Map<GreenhouseBlueprint.Placement, List<GreenhouseBlueprint.Stand>> owned = new IdentityHashMap<>();
        List<GreenhouseBlueprint.Stand> unowned = new ArrayList<>();
        for (GreenhouseBlueprint.Stand stand : scan.stands()) {
            GreenhouseBlueprint.Placement owner = ownerOf(board.placements, stand);
            if (owner == null) unowned.add(stand);
            else owned.computeIfAbsent(owner, k -> new ArrayList<>()).add(stand);
        }

        List<Result> cells = new ArrayList<>(board.placements.size());
        for (GreenhouseBlueprint.Placement p : board.placements) {
            cells.add(evaluatePlacement(p, scan, owned.getOrDefault(p, List.of())));
        }

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
        List<Extra> extraSpots = new ArrayList<>();
        int extras = findExtras(board, scan, unowned, extraSpots);
        return new Evaluation(cells, floor, extras, extraSpots);
    }

    private static Result evaluatePlacement(GreenhouseBlueprint.Placement p, GreenhouseScan.Scan scan,
                                            List<GreenhouseBlueprint.Stand> near) {
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

        // The armor stands that belong to this placement (see ownerOf)
        boolean standsOk;
        boolean standsWrong;
        boolean[] standOk = new boolean[p.stands.size()];
        List<GreenhouseBlueprint.Stand> unwanted = new ArrayList<>();
        if (p.stands.isEmpty()) {
            standsOk = near.isEmpty();
            standsWrong = !standsOk;
            unwanted.addAll(near);
        } else {
            boolean[] used = new boolean[near.size()];
            int unmatched = 0;
            for (int w = 0; w < p.stands.size(); w++) {
                GreenhouseBlueprint.Stand wanted = p.stands.get(w);
                int hit = -1;
                for (int i = 0; i < near.size() && hit < 0; i++) {
                    if (!used[i] && GreenhouseScan.sameStand(wanted, near.get(i))) hit = i;
                }
                if (hit < 0) unmatched++;
                else used[hit] = true;
                standOk[w] = hit >= 0;
            }
            int leftover = 0;
            for (boolean u : used) if (!u) leftover++;
            standsOk = unmatched == 0;
            // Missing one isn't wrong, but having a different one instead is. Spare ones are put up with.
            standsWrong = unmatched > 0 && leftover > 0;
            if (standsWrong) {
                for (int i = 0; i < near.size(); i++) {
                    if (!used[i]) unwanted.add(near.get(i));
                }
            }
        }

        Status status = blockOk && standsOk ? Status.DONE : blockWrong || standsWrong ? Status.WRONG : Status.MISSING;
        return new Result(p, status, blockOk, standsOk, standOk, unwanted);
    }

    /**
     * The placement an armor stand belongs to, or null if it belongs to none. A stand belongs to the
     * placement whose footprint it stands in; failing that, to the big crop it stands right next to
     * (Godseed's pillar and PlantBoy's roots sit a little outside their footprint), the nearest one if
     * several are. Capture uses the same rule, so a copy always evaluates the way it was copied.
     */
    static GreenhouseBlueprint.Placement ownerOf(List<GreenhouseBlueprint.Placement> placements,
                                                 GreenhouseBlueprint.Stand stand) {
        for (GreenhouseBlueprint.Placement p : placements) {
            if (stand.x >= p.x && stand.x < p.x + p.size && stand.z >= p.z && stand.z < p.z + p.size) return p;
        }
        return nextToBig(placements, stand);
    }

    /**
     * The big crop an armor stand is within {@link GreenhouseCapture#BIG_CROP_MARGIN} of, if it isn't in
     * a footprint. The margin is exclusive, so the centre of the cell next to a big crop is never in it
     * and the test comes out the same for a mirrored or turned board.
     */
    static GreenhouseBlueprint.Placement nextToBig(List<GreenhouseBlueprint.Placement> placements,
                                                   GreenhouseBlueprint.Stand stand) {
        double m = GreenhouseCapture.BIG_CROP_MARGIN;
        GreenhouseBlueprint.Placement best = null;
        double bestDistance = Double.MAX_VALUE;
        for (GreenhouseBlueprint.Placement p : placements) {
            if (p.size <= 1) continue;
            if (stand.x <= p.x - m || stand.x >= p.x + p.size + m || stand.z <= p.z - m || stand.z >= p.z + p.size + m) continue;
            double dx = stand.x - (p.x + p.size / 2.0);
            double dz = stand.z - (p.z + p.size / 2.0);
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                best = p;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Finds the cells no placement covers that have a block or an armor stand of nobody's on them anyway.
     *
     * @param spots receives what was found
     * @return how many cells that is
     */
    private static int findExtras(GreenhouseBlueprint.Board board, GreenhouseScan.Scan scan,
                                  List<GreenhouseBlueprint.Stand> unowned, List<Extra> spots) {
        int n = board.size;
        boolean[][] busy = new boolean[n][n];
        for (GreenhouseScan.BlockCell cell : scan.blocks()) {
            if (cell.x() >= 0 && cell.x() < n && cell.z() >= 0 && cell.z() < n && !covers(board, cell.x() + 0.5, cell.z() + 0.5)) {
                busy[cell.z()][cell.x()] = true;
                spots.add(new Extra(cell.x(), cell.z(), null));
            }
        }
        for (GreenhouseBlueprint.Stand stand : unowned) {
            int x = (int) Math.floor(stand.x);
            int z = (int) Math.floor(stand.z);
            if (x >= 0 && x < n && z >= 0 && z < n) {
                busy[z][x] = true;
                spots.add(new Extra(x, z, stand));
            }
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
