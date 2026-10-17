package org.example.islandtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A greenhouse layout copied from the game: for each of up to three boards (one per greenhouse
 * plot), which crop goes in which cell of the 10x10 plot. Plain fields only, so Gson can store it as is.
 *
 * <p>Cells are addressed as (x, z) in "board space": x grows east and z grows south, the same way as
 * in the world. {@link #rotation} and {@link #mirrored} turn the board before it is laid on the plot,
 * for putting a copy over a greenhouse that is built the other way round.
 */
final class GreenhouseBlueprint {

    static final int MAX_BOARDS = 3;

    String id = "";
    String name = "";
    /** Where it came from; {@link GreenhouseCapture#SOURCE} for copies made in game. */
    String source = "";
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

        Placement() {}

        Placement(String crop, int x, int z, int size) {
            this.crop = crop;
            this.x = x;
            this.z = z;
            this.size = size;
        }

        Placement copy() {
            return new Placement(crop, x, z, size);
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
     * Turns a board as it looks in the world into the form this blueprint stores, so that once
     * {@link #rotation} and {@link #mirrored} are applied again it looks the same as it did. A board
     * copied from the game has to go through this before it joins a blueprint that has been turned.
     */
    Board toStoredBoard(Board board) {
        Board turned = new Board();
        turned.size = board.size;
        turned.placements = transform(board, -rotation, false);
        if (!mirrored) return turned;

        Board stored = new Board();
        stored.size = board.size;
        stored.placements = transform(turned, 0, true);
        return stored;
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
}
