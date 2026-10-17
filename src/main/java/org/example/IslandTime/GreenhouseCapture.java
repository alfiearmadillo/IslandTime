package org.example.islandtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns what is found in a greenhouse's soil area (see {@link GreenhouseScan.Scan}) into a
 * {@link GreenhouseBlueprint}, so a layout can be copied straight from a greenhouse you have built:
 * the crops (as the blocks and armor stands they really are), and the soil under them. The result is
 * in the world's own orientation, so it needs no rotating when it is laid back over a greenhouse.
 */
final class GreenhouseCapture {

    /** Name given to a copy when none is typed. */
    static final String DEFAULT_NAME = "Greenhouse copy";
    static final String SOURCE = "Copied";

    /** How far outside a big crop's footprint (in cells) one of its armor stands can sit and still belong to it. */
    static final double BIG_CROP_MARGIN = 0.5;

    private GreenhouseCapture() {}

    /**
     * Builds a board from a scan of a plot.
     *
     * <p>Armor stands named after a big crop are placed first and keep their whole footprint to
     * themselves, together with the other stands around them (Godseed's pillar, PlantBoy's roots).
     * What is left is grouped by cell: the block in the crop layer, plus any armor stands there. So a
     * custom crop that borrows nether wart is stored as a nether wart <em>with</em> its armor stand,
     * and a real nether wart as the block alone. Stands outside the area are ignored. Armor stands
     * without a head are not scanned at all, which keeps holograms out.
     */
    static GreenhouseBlueprint.Board toBoard(GreenhouseScan.Scan scan) {
        int n = GreenhouseGeometry.AREA_SIZE;
        GreenhouseBlueprint.Board board = new GreenhouseBlueprint.Board();
        board.size = n;

        List<GreenhouseBlueprint.Stand> ordered = new ArrayList<>(scan.stands());
        ordered.sort(Comparator
                .comparingInt((GreenhouseBlueprint.Stand s) -> -sizeOf(s))
                .thenComparingDouble((GreenhouseBlueprint.Stand s) -> s.z)
                .thenComparingDouble(s -> s.x)
                .thenComparing(s -> s.head));

        boolean[][] taken = new boolean[n][n];
        List<GreenhouseBlueprint.Placement> bigs = new ArrayList<>();
        List<GreenhouseBlueprint.Stand> loose = new ArrayList<>();
        for (GreenhouseBlueprint.Stand stand : ordered) {
            GreenhouseCrops.Crop crop = GreenhouseCrops.fromPlantedName(stand.head);
            if (crop == null || crop.size() <= 1) {
                loose.add(stand);
                continue;
            }
            int x = origin(stand.x, crop.size(), n);
            int z = origin(stand.z, crop.size(), n);
            if (x < 0 || z < 0 || overlaps(taken, x, z, crop.size())) {
                loose.add(stand);
                continue;
            }
            for (int dz = 0; dz < crop.size(); dz++) {
                for (int dx = 0; dx < crop.size(); dx++) taken[z + dz][x + dx] = true;
            }
            GreenhouseBlueprint.Placement p = new GreenhouseBlueprint.Placement(crop.key(), x, z, crop.size());
            p.stands.add(stand.copy());
            bigs.add(p);
        }

        // Single cells: every block in the crop layer, and every stand that is a crop of its own
        // (or that isn't next to a big crop, so can't be one of its decorations)
        Map<Integer, GreenhouseBlueprint.Placement> singles = new TreeMap<>();
        for (GreenhouseScan.BlockCell cell : scan.blocks()) {
            if (cell.x() < 0 || cell.z() < 0 || cell.x() >= n || cell.z() >= n || taken[cell.z()][cell.x()]) continue;
            singles.computeIfAbsent(cell.z() * n + cell.x(),
                    k -> new GreenhouseBlueprint.Placement("", cell.x(), cell.z(), 1)).block =
                    GreenhouseScan.stripAge(cell.state());
        }
        for (GreenhouseBlueprint.Stand stand : loose) {
            int x = (int) Math.floor(stand.x);
            int z = (int) Math.floor(stand.z);
            if (x < 0 || z < 0 || x >= n || z >= n || taken[z][x]) continue;
            GreenhouseCrops.Crop crop = GreenhouseCrops.fromPlantedName(stand.head);
            boolean ownCrop = crop != null && crop.size() <= 1;
            if (ownCrop || GreenhouseEvaluator.nextToBig(bigs, stand) == null) {
                singles.computeIfAbsent(z * n + x, k -> new GreenhouseBlueprint.Placement("", x, z, 1));
            }
        }

        // Then every stand goes to whoever owns it by the rule the evaluator uses, so what is copied
        // is what gets compared
        List<GreenhouseBlueprint.Placement> all = new ArrayList<>(bigs);
        all.addAll(singles.values());
        for (GreenhouseBlueprint.Stand stand : loose) {
            GreenhouseBlueprint.Placement owner = GreenhouseEvaluator.ownerOf(all, stand);
            if (owner != null) owner.stands.add(stand.copy());
        }
        for (GreenhouseBlueprint.Placement p : singles.values()) classify(p);

        board.placements.addAll(bigs);
        board.placements.addAll(singles.values());
        board.placements.sort(Comparator
                .comparingInt((GreenhouseBlueprint.Placement p) -> p.z)
                .thenComparingInt(p -> p.x));

        String[][] floor = new String[n][n];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) floor[z][x] = scan.floorAt(x, z);
        }
        board.setFloor(floor);
        return board;
    }

    /**
     * Works out what a single cell placement is. It is a known crop if one of its stands is named
     * after one, or, with no stands at all, if its block is a plain crop's. With stands the mod doesn't
     * know it is a custom crop, named after its block and stands ("Nether Wart + Moon Lily").
     */
    private static void classify(GreenhouseBlueprint.Placement p) {
        p.stands.sort(Comparator
                .comparingDouble((GreenhouseBlueprint.Stand s) -> s.y)
                .thenComparingDouble(s -> s.x)
                .thenComparingDouble(s -> s.z)
                .thenComparing(s -> s.head));

        GreenhouseCrops.Crop crop = null;
        for (GreenhouseBlueprint.Stand stand : p.stands) {
            GreenhouseCrops.Crop named = GreenhouseCrops.fromPlantedName(stand.head);
            if (named != null) {
                crop = named;
                break;
            }
        }
        if (crop == null && p.stands.isEmpty()) crop = GreenhouseCrops.byBlockId(GreenhouseScan.idOf(p.block));

        if (crop != null) {
            p.crop = crop.key();
            return;
        }
        p.crop = "";
        List<String> parts = new ArrayList<>();
        if (!p.block.isEmpty()) parts.add(GreenhouseScan.prettyName(p.block));
        for (GreenhouseBlueprint.Stand stand : p.stands) {
            if (!parts.contains(stand.head)) parts.add(stand.head);
        }
        p.name = String.join(" + ", parts);
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

    private static int sizeOf(GreenhouseBlueprint.Stand stand) {
        GreenhouseCrops.Crop crop = GreenhouseCrops.fromPlantedName(stand.head);
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
