package org.example.islandtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A greenhouse layout: for each of up to three boards, which crop goes in which cell of the 10x10
 * plot. Plain fields only, so Gson can store it as is.
 *
 * <p>Cells are addressed as (x, z) in "board space": x grows east and z grows south with the board
 * unrotated. {@link #rotation} and {@link #mirrored} turn the board before it is laid on the plot,
 * because the layout sites don't agree with each other (or with the game) on which way is up.
 */
final class GreenhouseBlueprint {

    static final int MAX_BOARDS = 3;

    String id = "";
    String name = "";
    /** Where it came from: "SkyLayouts", "SkyMutations" or "Manual". */
    String source = "";
    /** The mutation this layout is for, if the site said so. */
    String target = "";
    /** Clockwise quarter turns, 0-3. */
    int rotation = 0;
    boolean mirrored = false;
    /** Which board is used for which plot, keyed by {@link GreenhouseGeometry#plotKey(int, int)}. */
    Map<String, Integer> plotBoards = new LinkedHashMap<>();
    List<Board> boards = new ArrayList<>();

    static final class Board {
        int size = GreenhouseGeometry.AREA_SIZE;
        List<Placement> placements = new ArrayList<>();
    }

    static final class Placement {
        /** A {@link GreenhouseCrops.Crop#key()}. */
        String crop = "";
        int x;
        int z;
        /** Width and height in cells. */
        int size = 1;
        /** True for a spot where the target mutation is expected to spawn rather than a crop to plant. */
        boolean target = false;

        Placement() {}

        Placement(String crop, int x, int z, int size, boolean target) {
            this.crop = crop;
            this.x = x;
            this.z = z;
            this.size = size;
            this.target = target;
        }

        Placement copy() {
            return new Placement(crop, x, z, size, target);
        }
    }

    /** The board to use for a plot: the assigned one, else the first. Null if there are no boards. */
    Board boardForPlot(int plotX, int plotZ) {
        if (boards.isEmpty()) return null;
        Integer assigned = plotBoards.get(GreenhouseGeometry.plotKey(plotX, plotZ));
        int index = assigned == null ? 0 : assigned;
        if (index < 0 || index >= boards.size()) index = 0;
        return boards.get(index);
    }

    /** The 0 based index of the board used for a plot. */
    int boardIndexForPlot(int plotX, int plotZ) {
        if (boards.isEmpty()) return 0;
        Integer assigned = plotBoards.get(GreenhouseGeometry.plotKey(plotX, plotZ));
        int index = assigned == null ? 0 : assigned;
        return index < 0 || index >= boards.size() ? 0 : index;
    }

    /** The placements of a board after this blueprint's mirror and rotation have been applied. */
    List<Placement> orientedPlacements(Board board) {
        return transform(board, rotation, mirrored);
    }

    /**
     * Mirrors a board west to east, then turns it clockwise by quarter turns.
     *
     * @return new placements; the board itself is not changed
     */
    static List<Placement> transform(Board board, int quarterTurns, boolean mirror) {
        int n = board.size;
        int turns = Math.floorMod(quarterTurns, 4);
        List<Placement> result = new ArrayList<>(board.placements.size());
        for (Placement source : board.placements) {
            Placement p = source.copy();
            if (mirror) {
                p.x = n - p.x - p.size;
            }
            for (int i = 0; i < turns; i++) {
                int x = p.x;
                int z = p.z;
                p.x = n - z - p.size;
                p.z = x;
            }
            result.add(p);
        }
        return result;
    }

    /** All crop keys that appear in the planted (non target) placements of a board, with how many. */
    static Map<String, Integer> countCrops(Board board) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Placement p : board.placements) {
            if (p.target) continue;
            counts.merge(p.crop, 1, Integer::sum);
        }
        return counts;
    }
}
