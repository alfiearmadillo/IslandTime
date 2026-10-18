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

    private GreenhouseGeometry() {}

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
