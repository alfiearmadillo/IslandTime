package org.example.islandtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the crops found in a greenhouse's soil area into a {@link GreenhouseBlueprint}, so a layout
 * can be copied straight from a greenhouse you have planted. The result is in the world's own
 * orientation, so it needs no rotating when it is laid back over a greenhouse.
 */
final class GreenhouseCapture {

    /** Name given to a copy when none is typed. */
    static final String DEFAULT_NAME = "Greenhouse copy";
    static final String SOURCE = "Copied";

    private GreenhouseCapture() {}

    /**
     * Builds a board from the crops detected in a plot (positions in plot space, see
     * {@link GreenhouseEvaluator.Detected}). Big crops are placed first and keep their whole
     * footprint to themselves; anything else found inside a footprint, or twice in the same cell, is
     * left out. Crops that are not in the area, or that are not known crops, are ignored.
     */
    static GreenhouseBlueprint.Board toBoard(List<GreenhouseEvaluator.Detected> detected) {
        int n = GreenhouseGeometry.AREA_SIZE;
        GreenhouseBlueprint.Board board = new GreenhouseBlueprint.Board();
        board.size = n;

        List<GreenhouseEvaluator.Detected> ordered = new ArrayList<>(detected);
        ordered.sort(Comparator
                .comparingInt((GreenhouseEvaluator.Detected d) -> -sizeOf(d))
                .thenComparingDouble(GreenhouseEvaluator.Detected::z)
                .thenComparingDouble(GreenhouseEvaluator.Detected::x));

        boolean[][] taken = new boolean[n][n];
        for (GreenhouseEvaluator.Detected d : ordered) {
            GreenhouseCrops.Crop crop = GreenhouseCrops.byKey(d.crop());
            if (crop == null) continue;
            int size = crop.size();
            int x = origin(d.x(), size, n);
            int z = origin(d.z(), size, n);
            if (x < 0 || z < 0) continue;
            if (overlaps(taken, x, z, size)) continue;

            for (int dz = 0; dz < size; dz++) {
                for (int dx = 0; dx < size; dx++) taken[z + dz][x + dx] = true;
            }
            board.placements.add(new GreenhouseBlueprint.Placement(crop.key(), x, z, size));
        }

        board.placements.sort(Comparator
                .comparingInt((GreenhouseBlueprint.Placement p) -> p.z)
                .thenComparingInt(p -> p.x));
        return board;
    }

    /** A new single-board blueprint for a copy of a plot's greenhouse. */
    static GreenhouseBlueprint newBlueprint(String name, GreenhouseBlueprint.Board board, int plotX, int plotZ) {
        GreenhouseBlueprint blueprint = new GreenhouseBlueprint();
        blueprint.name = name == null || name.isBlank() ? DEFAULT_NAME : name.strip();
        blueprint.source = SOURCE;
        blueprint.boards.add(board);
        blueprint.plotBoards.put(GreenhouseGeometry.plotKey(plotX, plotZ), 0);
        return blueprint;
    }

    private static int sizeOf(GreenhouseEvaluator.Detected d) {
        GreenhouseCrops.Crop crop = GreenhouseCrops.byKey(d.crop());
        return crop == null ? 0 : crop.size();
    }

    /**
     * The first cell (x or z) a crop at this position covers, or -1 if it is outside the area. A
     * single cell crop sits in the cell it stands in; a bigger crop is centred on its position.
     */
    private static int origin(double position, int size, int areaSize) {
        if (size <= 1) {
            int cell = (int) Math.floor(position);
            return cell >= 0 && cell < areaSize ? cell : -1;
        }
        if (position < -1 || position > areaSize + 1) return -1;
        int start = (int) Math.round(position - size / 2.0);
        return Math.max(0, Math.min(areaSize - size, start));
    }

    private static boolean overlaps(boolean[][] taken, int x, int z, int size) {
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                if (taken[z + dz][x + dx]) return true;
            }
        }
        return false;
    }
}
