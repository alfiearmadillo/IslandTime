package org.example.islandtime;

/**
 * Where the greenhouses are in the Garden.
 *
 * <p>The Garden is a 5x5 grid of 96x96 plots centred on the world origin. A greenhouse plot has a
 * 10x10 area of soil in it, starting 43 blocks in from the plot's north west corner. The crops grow
 * in the block layer on top of the soil.
 */
final class GreenhouseGeometry {

    static final int PLOT_SIZE = 96;
    static final int PLOTS_PER_SIDE = 5;
    /** The north west corner of the whole garden, in both X and Z. */
    static final int GARDEN_MIN = -240;
    /** How far into a plot the soil area starts. */
    static final int AREA_OFFSET = 43;
    static final int AREA_SIZE = 10;
    /** Y of the soil layer; crops stand in the layer above it. */
    static final int SOIL_Y = 73;
    static final int CROP_Y = SOIL_Y + 1;

    /** How high above a full size armor stand's feet its head sits, in blocks. */
    static final double STAND_HEAD_BASE = 1.4375;
    /**
     * How big a skull is drawn on a full size armor stand, in blocks: its 8 pixels at the 1.1875 scale
     * the game draws skulls at, plus its hat layer. That is wider than the stand's 0.5 wide hitbox, and
     * it sticks out of the top of it.
     */
    static final double STAND_HEAD_SIZE = 0.64;

    private GreenhouseGeometry() {}

    /**
     * The box around the head an armor stand wears, as the head is drawn rather than as the stand's
     * hitbox is, so that something drawn on it isn't hidden inside the head.
     *
     * @param x          the stand's x, which is also the middle of its head
     * @param y          the stand's y, the height of its feet
     * @param z          the stand's z
     * @param scale      how big the stand is compared to a full size one: 0.5 for a small stand
     * @param yawDegrees which way the head is turned. A turned head's corners reach further than its sides
     * @param margin     how far to keep outside the head, in blocks
     * @return minX, minY, minZ, maxX, maxY and maxZ
     */
    static double[] headBox(double x, double y, double z, double scale, double yawDegrees, double margin) {
        double yaw = Math.toRadians(yawDegrees);
        double reach = STAND_HEAD_SIZE * scale * (Math.abs(Math.cos(yaw)) + Math.abs(Math.sin(yaw))) / 2.0 + margin;
        double bottom = y + STAND_HEAD_BASE * scale - margin;
        double top = y + (STAND_HEAD_BASE + STAND_HEAD_SIZE) * scale + margin;
        return new double[]{x - reach, bottom, z - reach, x + reach, top, z + reach};
    }

    /** The plot index (0-4 for a coordinate inside the garden) a world X or Z coordinate falls in. */
    static int plotIndex(double coord) {
        return Math.floorDiv((int) Math.floor(coord) - GARDEN_MIN, PLOT_SIZE);
    }

    static boolean isValidPlotIndex(int index) {
        return index >= 0 && index < PLOTS_PER_SIDE;
    }

    /** World X or Z of the first block of a plot. */
    static int plotMin(int index) {
        return GARDEN_MIN + index * PLOT_SIZE;
    }

    /** World X or Z of the first soil block of a plot's greenhouse area. */
    static int areaMin(int index) {
        return plotMin(index) + AREA_OFFSET;
    }

    /** The key a plot is stored under in a blueprint's board assignments. */
    static String plotKey(int plotX, int plotZ) {
        return plotX + "," + plotZ;
    }
}
