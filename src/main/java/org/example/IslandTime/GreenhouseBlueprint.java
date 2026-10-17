package org.example.islandtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A greenhouse layout copied from the game: for each of up to three boards (one per greenhouse
 * plot), what stands in which cell of the 10x10 plot, and what the soil under it is. Plain fields
 * only, so Gson can store it as is.
 *
 * <p>Cells are addressed as (x, z) in "board space": x grows east and z grows south, the same way as
 * in the world. {@link #rotation} and {@link #mirrored} turn the board before it is laid on the plot,
 * for putting a copy over a greenhouse that is built the other way round.
 */
final class GreenhouseBlueprint {

    static final int MAX_BOARDS = 3;

    /** How many different floor blocks a board can hold; one character of {@link #PALETTE_CHARS} each. */
    static final String PALETTE_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final char NO_FLOOR = '.';

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
        /** The block ids the floor is made of; {@link #floorRows} refer to them by position. */
        List<String> floorPalette = new ArrayList<>();
        /**
         * The soil under the crops, one string per row of cells from north to south. Each character is
         * the position of the cell's block in {@link #floorPalette} (see {@link #PALETTE_CHARS}), or '.'
         * where nothing is recorded. Empty for blueprints made before the floor was copied.
         */
        List<String> floorRows = new ArrayList<>();

        /** The block id of the floor at a cell, or "" if none is recorded. */
        String floorAt(int x, int z) {
            if (x < 0 || z < 0 || x >= size || z >= size || z >= floorRows.size()) return "";
            String row = floorRows.get(z);
            if (row == null || x >= row.length()) return "";
            int index = PALETTE_CHARS.indexOf(row.charAt(x));
            return index < 0 || index >= floorPalette.size() ? "" : floorPalette.get(index);
        }

        boolean hasFloor() {
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    if (!floorAt(x, z).isEmpty()) return true;
                }
            }
            return false;
        }

        /** The floor as block ids, indexed [z][x]. */
        String[][] floorGrid() {
            String[][] grid = new String[size][size];
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) grid[z][x] = floorAt(x, z);
            }
            return grid;
        }

        /** Stores a floor given as block ids indexed [z][x] ("" for none). Past the palette's size, blocks are dropped. */
        void setFloor(String[][] grid) {
            floorPalette = new ArrayList<>();
            floorRows = new ArrayList<>();
            for (int z = 0; z < size; z++) {
                StringBuilder row = new StringBuilder(size);
                for (int x = 0; x < size; x++) {
                    String block = grid[z][x];
                    int index = block == null || block.isEmpty() ? -1 : floorPalette.indexOf(block);
                    if (index < 0 && block != null && !block.isEmpty() && floorPalette.size() < PALETTE_CHARS.length()) {
                        floorPalette.add(block);
                        index = floorPalette.size() - 1;
                    }
                    row.append(index < 0 ? NO_FLOOR : PALETTE_CHARS.charAt(index));
                }
                floorRows.add(row.toString());
            }
            if (!hasFloor()) {
                floorPalette = new ArrayList<>();
                floorRows = new ArrayList<>();
            }
        }
    }

    /** An armor stand that wears a head, positioned relative to the greenhouse area. */
    static final class Stand {
        /** The name of the head item, for example "Veilshroom". */
        String head = "";
        /** Where the stand is, x and z in cells from the north west corner of the area, y from the top of the soil. */
        double x;
        double y;
        double z;
        /** The stand's hitbox, in blocks (0 for a marker stand, which has none). */
        double width;
        double height;

        Stand() {}

        Stand(String head, double x, double y, double z, double width, double height) {
            this.head = head;
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
        }

        Stand copy() {
            return new Stand(head, x, y, z, width, height);
        }
    }

    /**
     * Something that stands on one cell of the soil (or on a block of cells, for the big crops): the
     * block in the crop layer and/or the armor stands that make up a crop. A real nether wart is just
     * the block; a custom crop that borrows nether wart is the block plus its armor stand.
     */
    static final class Placement {
        /** A {@link GreenhouseCrops.Crop#key()}, or "" for a custom crop the mod doesn't know by name. */
        String crop = "";
        /** What to call it when {@link #crop} is empty. */
        String name = "";
        /**
         * The block in the crop layer, as a state string like {@code minecraft:nether_wart}, or "" for
         * none. Only matters for single cell placements.
         */
        String block = "";
        List<Stand> stands = new ArrayList<>();
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
            Placement p = new Placement(crop, x, z, size);
            p.name = name;
            p.block = block;
            for (Stand stand : stands) p.stands.add(stand.copy());
            return p;
        }

        /** What this is called in the overlay. */
        String displayName() {
            GreenhouseCrops.Crop known = GreenhouseCrops.byKey(crop);
            if (known != null) return known.name();
            if (!name.isEmpty()) return name;
            if (!block.isEmpty()) return GreenhouseScan.prettyName(block);
            return crop.isEmpty() ? "Unknown crop" : crop;
        }

        /** The colour (0xRRGGBB) of its ghost box and legend swatch. */
        int color() {
            GreenhouseCrops.Crop known = GreenhouseCrops.byKey(crop);
            return known != null ? known.color() : GreenhouseScan.colorOf(displayName());
        }

        /**
         * Fills in what blueprints made before blocks and armor stands were copied don't have: a plain
         * crop is its block, and a special crop is an armor stand wearing its head.
         */
        void upgrade() {
            if (crop == null) crop = "";
            if (name == null) name = "";
            if (block == null) block = "";
            if (stands == null) stands = new ArrayList<>();
            stands.removeIf(s -> s == null || s.head == null);
            if (size < 1) size = 1;
            if (!block.isEmpty() || !stands.isEmpty()) return;

            GreenhouseCrops.Crop known = GreenhouseCrops.byKey(crop);
            if (known == null) return;
            if (!known.block().isEmpty()) {
                block = known.block();
            } else {
                stands.add(new Stand(known.name(), x + size / 2.0, 0, z + size / 2.0, 0, 0));
            }
        }
    }

    /** Repairs anything missing in a blueprint loaded from an older or hand edited file. */
    void upgrade() {
        if (id == null) id = "";
        if (name == null) name = "";
        if (source == null) source = "";
        if (plotBoards == null) plotBoards = new LinkedHashMap<>();
        if (boards == null) boards = new ArrayList<>();
        boards.removeIf(b -> b == null);
        for (Board board : boards) {
            if (board.size < 1) board.size = GreenhouseGeometry.AREA_SIZE;
            if (board.placements == null) board.placements = new ArrayList<>();
            if (board.floorPalette == null) board.floorPalette = new ArrayList<>();
            if (board.floorRows == null) board.floorRows = new ArrayList<>();
            board.placements.removeIf(p -> p == null);
            for (Placement p : board.placements) p.upgrade();
            // Anything still without a block or an armor stand is a crop this version has never heard of
            board.placements.removeIf(p -> p.block.isEmpty() && p.stands.isEmpty());
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

    /** A board after this blueprint's mirror and rotation have been applied. */
    Board orientedBoard(Board board) {
        return transform(board, rotation, mirrored);
    }

    /**
     * Turns a board as it looks in the world into the form this blueprint stores, so that once
     * {@link #rotation} and {@link #mirrored} are applied again it looks the same as it did. A board
     * copied from the game has to go through this before it joins a blueprint that has been turned.
     */
    Board toStoredBoard(Board board) {
        Board turned = transform(board, -rotation, false);
        return mirrored ? transform(turned, 0, true) : turned;
    }

    /**
     * Moves the {@code facing} of a block state (a cocoa pod's, say) the way a board is moved: mirrored
     * west to east first if asked to, then turned clockwise by quarter turns. Anything else is left alone.
     */
    static String turnFacing(String state, boolean mirror, int quarterTurns) {
        if (state == null) return "";
        int at = state.indexOf("facing=");
        if (at < 0) return state;
        int start = at + "facing=".length();
        int end = start;
        while (end < state.length() && Character.isLetter(state.charAt(end))) end++;
        String[] clockwise = {"north", "east", "south", "west"};
        int index = -1;
        for (int i = 0; i < clockwise.length; i++) {
            if (clockwise[i].equals(state.substring(start, end))) index = i;
        }
        if (index < 0) return state;
        if (mirror && index % 2 == 1) index = (index + 2) % 4;
        index = Math.floorMod(index + quarterTurns, 4);
        return state.substring(0, start) + clockwise[index] + state.substring(end);
    }

    /**
     * Mirrors a board west to east, then turns it clockwise by quarter turns. Crops, armor stands and
     * the floor all move together.
     *
     * @return a new board; the one passed in is not changed
     */
    static Board transform(Board board, int quarterTurns, boolean mirror) {
        int n = board.size;
        int turns = Math.floorMod(quarterTurns, 4);
        Board result = new Board();
        result.size = n;

        for (Placement source : board.placements) {
            Placement p = source.copy();
            if (mirror) {
                p.x = n - p.x - p.size;
                p.block = turnFacing(p.block, true, 0);
                for (Stand s : p.stands) s.x = n - s.x;
            }
            for (int i = 0; i < turns; i++) {
                int x = p.x;
                int z = p.z;
                p.x = n - z - p.size;
                p.z = x;
                p.block = turnFacing(p.block, false, 1);
                for (Stand s : p.stands) {
                    double sx = s.x;
                    s.x = n - s.z;
                    s.z = sx;
                }
            }
            result.placements.add(p);
        }

        if (board.hasFloor()) {
            String[][] from = board.floorGrid();
            String[][] to = new String[n][n];
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    int cx = mirror ? n - x - 1 : x;
                    int cz = z;
                    for (int i = 0; i < turns; i++) {
                        int tx = cx;
                        cx = n - cz - 1;
                        cz = tx;
                    }
                    to[cz][cx] = from[z][x];
                }
            }
            result.setFloor(to);
        }
        return result;
    }
}
