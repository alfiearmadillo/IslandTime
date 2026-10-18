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

    /**
     * One thing an armor stand wears or holds.
     *
     * <p>{@link #key} is what two items are compared by; {@link #data} is the whole item (with a skull's
     * texture and everything else), kept so a ghost of the stand can be drawn with it.
     */
    static final class Gear {
        /** One of {@link #SLOTS}. */
        String slot = "";
        /** What identifies the item: its id and its name, for example {@code minecraft:player_head|Veilshroom}. */
        String key = "";
        /** The item's name, for example "Veilshroom". */
        String name = "";
        /** The item as Base64 of its compressed NBT, or "" if it wasn't kept (blueprints from before gear was copied). */
        String data = "";

        Gear() {}

        Gear(String slot, String key, String name, String data) {
            this.slot = slot;
            this.key = key;
            this.name = name;
            this.data = data;
        }

        Gear copy() {
            return new Gear(slot, key, name, data);
        }
    }

    /** The equipment slots of an armor stand, as the game names them. */
    static final String SLOT_HEAD = "head";
    static final String[] SLOTS = {"head", "chest", "legs", "feet", "mainhand", "offhand"};

    /** The flag bits of an armor stand, as the game stores them. */
    static final int FLAG_SMALL = 1;
    static final int FLAG_ARMS = 4;
    static final int FLAG_NO_BASE_PLATE = 8;
    static final int FLAG_MARKER = 16;

    /** How many numbers {@link Stand#pose} holds: x, y and z rotation of head, body, left arm, right arm, left leg, right leg. */
    static final int POSE_VALUES = 18;
    private static final int POSE_LEFT_ARM = 6;
    private static final int POSE_LEFT_LEG = 12;
    private static final int POSE_RIGHT_LEG = 15;

    /** The size of a standing armor stand, in blocks; small ones are half of it. */
    static final double STAND_WIDTH = 0.5;
    static final double STAND_HEIGHT = 1.975;

    /** An armor stand and what it wears, positioned relative to the greenhouse area. */
    static final class Stand {
        /** The name of the head item, for example "Veilshroom". Empty if it wears none. */
        String head = "";
        /** Where the stand is, x and z in cells from the north west corner of the area, y from the top of the soil. */
        double x;
        double y;
        double z;
        /** The stand's hitbox, in blocks (0 for a marker stand, which has none). */
        double width;
        double height;
        /** Everything it wears or holds. Empty in blueprints from before gear was copied, which only check the head. */
        List<Gear> gear = new ArrayList<>();
        /** Which way it faces, in degrees: 0 is south, 90 west, 180 north, 270 east. */
        float yaw;
        /** The armor stand's flag bits: {@link #FLAG_SMALL}, {@link #FLAG_ARMS}, {@link #FLAG_NO_BASE_PLATE}, {@link #FLAG_MARKER}. */
        int flags;
        /** Whether the stand itself is invisible (so only what it wears is seen). */
        boolean invisible;
        /** Its pose: {@link #POSE_VALUES} rotations in degrees, or empty for the default pose. */
        float[] pose = new float[0];

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
            Stand s = new Stand(head, x, y, z, width, height);
            for (Gear g : gear) s.gear.add(g.copy());
            s.yaw = yaw;
            s.flags = flags;
            s.invisible = invisible;
            s.pose = pose == null ? new float[0] : pose.clone();
            return s;
        }

        /** What to call this stand: its head, else the first thing it wears, else "Armor stand". */
        String label() {
            if (head != null && !head.isBlank()) return head;
            for (Gear g : gear) {
                if (g.name != null && !g.name.isBlank()) return g.name;
            }
            return "Armor stand";
        }

        boolean small() {
            return (flags & FLAG_SMALL) != 0;
        }

        /** How wide it looks, which for a marker stand (no hitbox) is still the size of the model. */
        double visualWidth() {
            return width > 0 ? width : STAND_WIDTH * (small() ? 0.5 : 1.0);
        }

        /** How tall it looks, see {@link #visualWidth()}. */
        double visualHeight() {
            return height > 0 ? height : STAND_HEIGHT * (small() ? 0.5 : 1.0);
        }

        /** Whether the item for every piece of gear was kept, so the stand can be drawn as itself. */
        boolean drawable() {
            if (gear.isEmpty()) return false;
            for (Gear g : gear) {
                if (g.data == null || g.data.isEmpty()) return false;
            }
            return true;
        }

        /** The gear in a slot, or null. */
        Gear gearIn(String slot) {
            for (Gear g : gear) {
                if (slot.equals(g.slot)) return g;
            }
            return null;
        }

        /** Repairs anything missing in a stand loaded from an older or hand edited file. */
        void upgrade() {
            if (head == null) head = "";
            if (gear == null) gear = new ArrayList<>();
            gear.removeIf(g -> g == null || g.slot == null || g.slot.isEmpty());
            for (Gear g : gear) {
                if (g.key == null) g.key = "";
                if (g.name == null) g.name = "";
                if (g.data == null) g.data = "";
            }
            if (pose == null || pose.length != POSE_VALUES) pose = new float[0];
            for (float value : pose) {
                if (!Float.isFinite(value)) {
                    pose = new float[0];
                    break;
                }
            }
            yaw = Float.isFinite(yaw) ? normalizeYaw(yaw) : 0.0f;
        }

        /** Mirrors it west to east in a board that is {@code n} cells across. */
        void mirror(int n) {
            x = n - x;
            yaw = normalizeYaw(-yaw);
            if (pose != null && pose.length == POSE_VALUES) {
                // A mirrored rotation turns the other way round the y and z axes, and the left leg becomes the
                // right one. The arms stay as they are: the main hand is always the right arm, so a stand's
                // arms keep the side that holds its item.
                float[] mirrored = pose.clone();
                for (int i = 0; i < POSE_VALUES; i += 3) {
                    mirrored[i + 1] = -mirrored[i + 1];
                    mirrored[i + 2] = -mirrored[i + 2];
                }
                swap(mirrored, POSE_LEFT_LEG, POSE_RIGHT_LEG);
                System.arraycopy(pose, POSE_LEFT_ARM, mirrored, POSE_LEFT_ARM, 6);
                pose = mirrored;
            }
        }

        /** Turns it a quarter clockwise in a board that is {@code n} cells across. */
        void turnClockwise(int n) {
            double sx = x;
            x = n - z;
            z = sx;
            yaw = normalizeYaw(yaw + 90.0f);
        }

        private static void swap(float[] a, int from, int to) {
            for (int i = 0; i < 3; i++) {
                float t = a[from + i];
                a[from + i] = a[to + i];
                a[to + i] = t;
            }
        }
    }

    /** An angle in degrees, brought into [-180, 180). */
    static float normalizeYaw(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
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
            stands.removeIf(s -> s == null);
            for (Stand s : stands) s.upgrade();
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
     * Mirrors a board west to east, then turns it clockwise by quarter turns. Crops, armor stands (and
     * the way they face and are posed) and the floor all move together.
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
                for (Stand s : p.stands) s.mirror(n);
            }
            for (int i = 0; i < turns; i++) {
                int x = p.x;
                int z = p.z;
                p.x = n - z - p.size;
                p.z = x;
                p.block = turnFacing(p.block, false, 1);
                for (Stand s : p.stands) s.turnClockwise(n);
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
