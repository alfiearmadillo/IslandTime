package org.example.islandtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a greenhouse blueprint over the greenhouse plot the player is standing in, in the style of
 * Litematica: a see-through ghost of every block that still has to be placed.
 *
 * <ul>
 *   <li>Crops that are blocks are drawn as translucent copies of the real block model (see
 *       {@link GreenhouseGhostBlocks}), and so is any soil block that is missing or wrong.</li>
 *   <li>Armor stands (custom crops, and big crops like Godseed) are drawn as translucent copies of
 *       the stand, wearing and holding what the copied one did, skull textures included (see
 *       {@link GreenhouseGhostStands}). A custom crop that borrows a plain crop's block shows the
 *       block's ghost and the stand's ghost, so you can tell a real nether wart from a nether wart
 *       with an armor stand on it. Where a stand can't be drawn that way it is a box the size of the
 *       stand instead.</li>
 *   <li>Things that are already right disappear from the overlay. Where a block, an armor stand or a
 *       soil block that the layout wants isn't right (air included), or where something is in the way
 *       that the layout doesn't have, a red outline is drawn around it, over the ghost if there is one.
 *       A small legend (the guide) shows what is left, and can hide itself once everything is right.</li>
 * </ul>
 *
 * <p>Everything is gated to the Garden using the same tab-list area detection as the block
 * highlights ({@link BlockHighlightRenderer#detectCurrentArea()}). The world is only scanned a couple
 * of times a second from {@link #tick(Minecraft)}; rendering just reads the snapshot that produced.
 */
public class GreenhouseBlueprintRenderer {

    private static final Pattern GARDEN_AREA = Pattern.compile("\\bgarden\\b", Pattern.CASE_INSENSITIVE);

    /** Client ticks between scans of the greenhouse. Rendering reads the last result every frame. */
    private static final int SCAN_INTERVAL_TICKS = 10;

    /** The Carpenter NPC stands in every greenhouse plot, which is how a greenhouse plot is told apart. */
    private static final String GREENHOUSE_MARKER = "Carpenter";

    private static final int MAX_LEGEND_ROWS = 14;
    private static final int MAX_FLOOR_LEGEND_ROWS = 6;

    // Ghost box geometry, in blocks
    private static final float BOX_INSET = 0.12f;
    private static final float BOX_HEIGHT = 0.6f;
    /** Armor stands with no hitbox (marker stands) are drawn at least this big, and no taller than the max. */
    private static final float STAND_MIN_SIZE = 0.25f;
    private static final float STAND_MAX_HEIGHT = 2.5f;
    /** The thin red slab that marks a cell where a different block than the planned one is. */
    private static final float WRONG_SLAB_HEIGHT = 0.08f;
    /** The flat tile that marks soil that is missing or wrong when ghost blocks aren't drawn. */
    private static final float FLOOR_TILE_HEIGHT = 0.05f;

    /** How far (in blocks) a ghost block is inflated so it doesn't flicker against a real block in its place. */
    private static final float GHOST_GROW = 0.004f;

    // The frame drawn around the greenhouse area while copying
    private static final float FRAME_WIDTH = 0.1f;
    private static final float FRAME_EDGE_HEIGHT = 0.06f;
    private static final float FRAME_POST_HEIGHT = 1.5f;
    private static final float[] COLOR_FRAME = {1.0f, 0.75f, 0.1f};
    private static final float FRAME_ALPHA = 0.85f;

    private static final float LABEL_SCALE = 0.03f;
    private static final double LABEL_OFFSET = 0.3;
    private static final int LABEL_BACKGROUND = 0x40000000;
    private static final int FULL_BRIGHT = 0xF000F0;

    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int LABEL_COLOR_WRONG = 0xFFFF5555;

    private static final float[] COLOR_WRONG = {1.0f, 0.2f, 0.2f};

    // The red outline around what isn't right. Its beams stick out of the cell by up to OUTLINE_GROW,
    // so they show over a ghost block, and over a real block that is in the way. How thick and how
    // solid they are comes from the settings.
    private static final float[] COLOR_OUTLINE = {1.0f, 0.15f, 0.15f};
    private static final float OUTLINE_GROW = 0.03f;
    /** Armor stand outlines are at least this wide in total (held items stick out of a small stand's box). */
    private static final float OUTLINE_STAND_MIN_WIDTH = 0.5f;
    private static final float OUTLINE_STAND_GROW = 0.1f;
    /** Outlines further than this from the camera (in blocks) aren't drawn. */
    private static final double OUTLINE_RANGE = 64.0;

    private static final int HUD_PADDING = 4;
    private static final int HUD_LINE_HEIGHT = 10;
    private static final int HUD_SWATCH = 8;
    private static final int HUD_BG_COLOR = 0xC0000000;
    private static final int HUD_BORDER_COLOR = 0xFF2E7D32;

    /** The plot the player is standing in, in garden plot coordinates (0-4 on each axis). */
    record PlotRef(int x, int z) {}

    /**
     * One placement together with how it stands: whether its block and its armor stands are right.
     *
     * @param standOk  for each of the placement's armor stands, whether a matching one is there
     * @param unwanted the armor stands that are in the way, which get a red outline
     * @param hidden   not drawn at all because the soil under it isn't right yet (see
     *                 {@code cropsOnlyOnCorrectFloor})
     */
    record Cell(GreenhouseBlueprint.Placement placement, GreenhouseEvaluator.Status status,
                boolean blockOk, boolean standsOk, boolean[] standOk,
                List<GreenhouseBlueprint.Stand> unwanted, boolean hidden) {}

    /** One cell of the soil that the blueprint has a block for. */
    record FloorCell(int x, int z, String block, GreenhouseEvaluator.Status status) {}

    /**
     * How the red outline is drawn.
     *
     * @param beam  how thick its lines are, in blocks
     * @param grow  how far the outline sticks out of the space it marks, in blocks
     * @param alpha how solid it is, 0 to 1
     */
    private record OutlineStyle(float beam, float grow, float alpha) {}

    /** One line of the legend: how many of something are right out of how many the board needs. */
    record LegendRow(String name, int color, int done, int total) {}

    /**
     * Everything the renderer needs for the current plot; built by {@link #tick(Minecraft)}. For a copy
     * preview ({@code copy}) the cells are what would be saved and the board fields are unused.
     *
     * @param ghostStands the armor stands to draw as ghosts: the ones still to put up, and the ones that
     *                    are right already (drawn fainter, unless completed things are hidden)
     * @param extraSpots  the blocks and armor stands in the area that the layout has no place for
     * @param waiting     how many crops still to do are hidden until the soil under them is right
     * @param perfect everything matches the blueprint and nothing else is in the way
     */
    record Snapshot(String name, PlotRef plot, int boardNumber, int boardCount,
                    int rotation, boolean mirrored, int areaX, int areaZ,
                    List<Cell> cells, List<FloorCell> floor,
                    List<LegendRow> legend, List<LegendRow> floorLegend,
                    List<GreenhouseGhostStands.Wanted> ghostStands, List<GreenhouseEvaluator.Extra> extraSpots,
                    int done, int total, int floorDone, int floorTotal, int extras, int waiting,
                    boolean perfect, boolean copy) {}

    /** What {@link #captureNow(Minecraft)} found: a board to save, or why there isn't one. */
    record Capture(PlotRef plot, GreenhouseBlueprint.Board board, String error) {
        boolean ok() {
            return error == null;
        }
    }

    private static volatile Snapshot snapshot = null;
    /** True while the copy preview is on: the area that would be saved is shown instead of a blueprint. */
    private static volatile boolean copyMode = false;
    /** Set if drawing real block models ever throws, so the overlay falls back to coloured boxes for the session. */
    private static volatile boolean modelRenderingFailed = false;
    private static int ticksSinceScan = SCAN_INTERVAL_TICKS;

    private GreenhouseBlueprintRenderer() {}

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(GreenhouseBlueprintRenderer::collectSubmits);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(GreenhouseBlueprintRenderer::render);
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("islandtime", "greenhouse_blueprint"),
                (graphics, deltaTracker) -> renderHud(graphics));
    }

    /** Makes the next tick rescan, so a command's effect shows up straight away. */
    static void requestRefresh() {
        ticksSinceScan = SCAN_INTERVAL_TICKS;
    }

    // -------------------------------------------------------------------------
    // Copying a greenhouse
    // -------------------------------------------------------------------------

    /**
     * Turns the copy preview on for the plot the player stands in.
     *
     * @return why it can't be, or null if the preview is now on
     */
    static String startCopy(Minecraft client) {
        String blocker = copyBlocker(client);
        if (blocker != null) return blocker;
        copyMode = true;
        requestRefresh();
        return null;
    }

    static void stopCopy() {
        copyMode = false;
        requestRefresh();
    }

    static boolean isCopying() {
        return copyMode;
    }

    /**
     * Scans the greenhouse area of the plot the player stands in, ready to be saved as a blueprint:
     * the crops (blocks and armor stands) and the soil under them.
     */
    static Capture captureNow(Minecraft client) {
        String blocker = copyBlocker(client);
        if (blocker != null) return new Capture(null, null, blocker);

        PlotRef plot = currentPlot(client);
        if (!areaLoaded(client.level, plot)) {
            return new Capture(plot, null, "Part of this plot's greenhouse area isn't loaded. Move closer to it "
                    + "and try again.");
        }
        GreenhouseBlueprint.Board board = GreenhouseCapture.toBoard(scan(client.level, plot, true));
        if (board.placements.isEmpty() && !board.hasFloor()) {
            return new Capture(plot, null, "There is nothing in this plot's greenhouse area yet, so there is "
                    + "nothing to copy.");
        }
        return new Capture(plot, board, null);
    }

    /** Why the plot the player stands in can't be copied right now, or null if it can. */
    private static String copyBlocker(Minecraft client) {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || client.level == null || client.player == null) {
            return "You need to be in a world to copy a greenhouse.";
        }
        if (config.greenhouseBlueprint.onlyInGarden && !isInGarden()) {
            return "Greenhouse blueprints only work in the Garden.";
        }
        PlotRef plot = currentPlot(client);
        if (plot == null) return "Stand inside the garden plot whose greenhouse you want to copy.";
        if (!config.greenhouseBlueprint.ignoreGreenhouseCheck && !hasGreenhouse(client.level, plot)) {
            return "No greenhouse found in this plot. Stand in a greenhouse plot, or turn on \"Show In Any Plot\" "
                    + "in the Greenhouse Blueprint settings.";
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Gating
    // -------------------------------------------------------------------------

    /** Returns true when the tab list says the player is in the Garden. */
    static boolean isInGarden() {
        String area = BlockHighlightRenderer.detectCurrentArea();
        return area != null && GARDEN_AREA.matcher(area).find();
    }

    /** The plot the player is standing in, or null when not inside the garden's 5x5 plots. */
    static PlotRef currentPlot(Minecraft client) {
        if (client.player == null) return null;
        Vec3 pos = client.player.position();
        int plotX = GreenhouseGeometry.plotIndex(pos.x);
        int plotZ = GreenhouseGeometry.plotIndex(pos.z);
        if (!GreenhouseGeometry.isValidPlotIndex(plotX) || !GreenhouseGeometry.isValidPlotIndex(plotZ)) return null;
        return new PlotRef(plotX, plotZ);
    }

    /** Returns true if the plot has a greenhouse: a Carpenter's name tag stands somewhere in it. */
    static boolean hasGreenhouse(ClientLevel level, PlotRef plot) {
        double minX = GreenhouseGeometry.plotMin(plot.x());
        double minZ = GreenhouseGeometry.plotMin(plot.z());
        for (Entity entity : level.entitiesForRendering()) {
            Component customName = entity.getCustomName();
            if (customName == null) continue;
            Vec3 pos = entity.position();
            if (pos.x < minX || pos.x >= minX + GreenhouseGeometry.PLOT_SIZE
                    || pos.z < minZ || pos.z >= minZ + GreenhouseGeometry.PLOT_SIZE
                    || pos.y < 55 || pos.y > 100) continue;
            if (customName.getString().contains(GREENHOUSE_MARKER)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Reading the greenhouse
    // -------------------------------------------------------------------------

    /** Whether all four corners of the plot's greenhouse area are in loaded chunks. */
    static boolean areaLoaded(ClientLevel level, PlotRef plot) {
        int last = GreenhouseGeometry.AREA_SIZE - 1;
        int areaX = GreenhouseGeometry.areaMin(plot.x());
        int areaZ = GreenhouseGeometry.areaMin(plot.z());
        int y = GreenhouseGeometry.CROP_Y;
        return level.hasChunkAt(new BlockPos(areaX, y, areaZ))
                && level.hasChunkAt(new BlockPos(areaX + last, y, areaZ))
                && level.hasChunkAt(new BlockPos(areaX, y, areaZ + last))
                && level.hasChunkAt(new BlockPos(areaX + last, y, areaZ + last));
    }

    /**
     * Reads everything in the plot's greenhouse area: the soil block under each cell, every block in
     * the layer the crops grow in, and every armor stand wearing or holding something (the Garden's
     * special crops are armor stands, and many custom crops put one on top of a plain crop's block).
     * Reading the items themselves is only needed to copy them, so it is left out otherwise.
     */
    static GreenhouseScan.Scan scan(ClientLevel level, PlotRef plot) {
        return scan(level, plot, false);
    }

    /** @param keepItems whether to keep what the armor stands wear and hold, which a copy needs to draw them */
    static GreenhouseScan.Scan scan(ClientLevel level, PlotRef plot, boolean keepItems) {
        int areaX = GreenhouseGeometry.areaMin(plot.x());
        int areaZ = GreenhouseGeometry.areaMin(plot.z());
        int size = GreenhouseGeometry.AREA_SIZE;
        List<String> floor = new ArrayList<>(size * size);
        List<GreenhouseScan.BlockCell> blocks = new ArrayList<>();

        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                BlockState soil = level.getBlockState(new BlockPos(areaX + dx, GreenhouseGeometry.SOIL_Y, areaZ + dz));
                floor.add(soil.isAir() ? "" : GreenhouseGhostBlocks.blockId(soil.getBlock()));

                BlockState crop = level.getBlockState(new BlockPos(areaX + dx, GreenhouseGeometry.CROP_Y, areaZ + dz));
                if (!crop.isAir()) blocks.add(new GreenhouseScan.BlockCell(dx, dz, GreenhouseGhostBlocks.stateString(crop)));
            }
        }

        List<GreenhouseBlueprint.Stand> stands = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || stand.isRemoved()) continue;
            Vec3 pos = stand.position();
            double dx = pos.x - areaX;
            double dz = pos.z - areaZ;
            if (dx < -1 || dx >= size + 1 || dz < -1 || dz >= size + 1) continue;
            if (pos.y < GreenhouseGeometry.SOIL_Y - 1 || pos.y > GreenhouseGeometry.CROP_Y + 8) continue;

            GreenhouseBlueprint.Stand read =
                    GreenhouseStandItems.read(stand, areaX, areaZ, level.registryAccess(), keepItems);
            if (read != null) stands.add(read);
        }
        return new GreenhouseScan.Scan(floor, blocks, stands);
    }

    // -------------------------------------------------------------------------
    // Tick: build the snapshot
    // -------------------------------------------------------------------------

    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null) copyMode = false;
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !(config.greenhouseBlueprint.enabled || copyMode)
                || client.level == null || client.player == null) {
            snapshot = null;
            ticksSinceScan = SCAN_INTERVAL_TICKS;
            GreenhouseGhostStands.reset();
            return;
        }
        if (++ticksSinceScan < SCAN_INTERVAL_TICKS) return;
        ticksSinceScan = 0;

        try {
            snapshot = buildSnapshot(client, config);
        } catch (RuntimeException e) {
            snapshot = null;
            System.err.println("[IslandTime] Greenhouse blueprint scan failed: " + e);
        }
        if (snapshot == null) GreenhouseGhostStands.reset();
    }

    private static Snapshot buildSnapshot(Minecraft client, IslandTimeConfig config) {
        boolean copying = copyMode;
        GreenhouseBlueprint blueprint = copying ? null : GreenhouseBlueprintManager.getActive();
        if (!copying && (blueprint == null || blueprint.boards.isEmpty())) return null;
        if (config.greenhouseBlueprint.onlyInGarden && !isInGarden()) return null;

        PlotRef plot = currentPlot(client);
        if (plot == null) return null;
        ClientLevel level = client.level;
        if (!config.greenhouseBlueprint.ignoreGreenhouseCheck && !hasGreenhouse(level, plot)) return null;
        // An area that isn't loaded reads as empty, which would show everything as still to do
        if (!areaLoaded(level, plot)) return null;

        GreenhouseScan.Scan scan = scan(level, plot);
        if (copying) return buildCopySnapshot(plot, scan);

        GreenhouseBlueprint.Board board = blueprint.orientedBoard(blueprint.boardForPlot(plot.x(), plot.z()));
        GreenhouseEvaluator.Evaluation evaluation =
                GreenhouseEvaluator.evaluate(board, scan, config.greenhouseBlueprint.showFloor);

        boolean floorFirst = config.greenhouseBlueprint.cropsOnlyOnCorrectFloor;
        int waiting = 0;
        List<Cell> cells = new ArrayList<>(evaluation.cells().size());
        List<GreenhouseGhostStands.Wanted> ghostStands = new ArrayList<>();
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Integer> colors = new LinkedHashMap<>();
        for (GreenhouseEvaluator.Result result : evaluation.cells()) {
            GreenhouseBlueprint.Placement p = result.placement();
            boolean cellDone = result.status() == GreenhouseEvaluator.Status.DONE;
            boolean hidden = floorFirst && !evaluation.floorRight(p);
            cells.add(new Cell(p, result.status(), result.blockOk(), result.standsOk(), result.standOk(),
                    result.unwanted(), hidden));
            if (hidden && !cellDone) waiting++;
            for (int i = 0; i < p.stands.size() && !hidden; i++) {
                // A stand that is there already is only drawn (fainter) when the whole placement is right
                if (p.stands.get(i).drawable() && (cellDone || !result.standOk()[i])) {
                    ghostStands.add(new GreenhouseGhostStands.Wanted(p.stands.get(i), cellDone));
                }
            }
            int[] count = counts.computeIfAbsent(p.displayName(), k -> new int[2]);
            colors.putIfAbsent(p.displayName(), p.color());
            count[1]++;
            if (result.status() == GreenhouseEvaluator.Status.DONE) count[0]++;
        }

        List<FloorCell> floor = new ArrayList<>(evaluation.floor().size());
        Map<String, int[]> floorCounts = new LinkedHashMap<>();
        for (GreenhouseEvaluator.FloorResult result : evaluation.floor()) {
            floor.add(new FloorCell(result.x(), result.z(), result.block(), result.status()));
            int[] count = floorCounts.computeIfAbsent(result.block(), k -> new int[2]);
            count[1]++;
            if (result.status() == GreenhouseEvaluator.Status.DONE) count[0]++;
        }

        return new Snapshot(blueprint.name, plot,
                blueprint.boardIndexForPlot(plot.x(), plot.z()) + 1, blueprint.boards.size(),
                blueprint.rotation, blueprint.mirrored,
                GreenhouseGeometry.areaMin(plot.x()), GreenhouseGeometry.areaMin(plot.z()),
                cells, floor, legendRows(counts, colors), floorLegendRows(floorCounts),
                ghostStands, evaluation.extraSpots(),
                evaluation.done(), evaluation.cells().size(), evaluation.floorDone(), evaluation.floor().size(),
                evaluation.extras(), waiting, evaluation.perfect(), false);
    }

    /** What saving right now would copy: every crop in the plot's greenhouse area, and the soil under it. */
    private static Snapshot buildCopySnapshot(PlotRef plot, GreenhouseScan.Scan scan) {
        GreenhouseBlueprint.Board board = GreenhouseCapture.toBoard(scan);

        List<Cell> cells = new ArrayList<>(board.placements.size());
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Integer> colors = new LinkedHashMap<>();
        for (GreenhouseBlueprint.Placement p : board.placements) {
            cells.add(new Cell(p, GreenhouseEvaluator.Status.MISSING, false, false,
                    new boolean[p.stands.size()], List.of(), false));
            int[] count = counts.computeIfAbsent(p.displayName(), k -> new int[2]);
            colors.putIfAbsent(p.displayName(), p.color());
            count[0]++;
            count[1]++;
        }

        Map<String, int[]> floorCounts = new LinkedHashMap<>();
        int floorTotal = 0;
        for (int z = 0; z < board.size; z++) {
            for (int x = 0; x < board.size; x++) {
                String block = board.floorAt(x, z);
                if (block.isEmpty()) continue;
                int[] count = floorCounts.computeIfAbsent(block, k -> new int[2]);
                count[0]++;
                count[1]++;
                floorTotal++;
            }
        }

        return new Snapshot("Copying greenhouse", plot, 0, 0, 0, false,
                GreenhouseGeometry.areaMin(plot.x()), GreenhouseGeometry.areaMin(plot.z()),
                cells, List.of(), legendRows(counts, colors), floorLegendRows(floorCounts),
                List.of(), List.of(),
                cells.size(), cells.size(), floorTotal, floorTotal, 0, 0, false, true);
    }

    private static List<LegendRow> legendRows(Map<String, int[]> counts, Map<String, Integer> colors) {
        List<LegendRow> rows = new ArrayList<>(counts.size());
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            rows.add(new LegendRow(entry.getKey(), colors.get(entry.getKey()),
                    entry.getValue()[0], entry.getValue()[1]));
        }
        return rows;
    }

    /** The floor legend, most used block first. */
    private static List<LegendRow> floorLegendRows(Map<String, int[]> counts) {
        List<LegendRow> rows = new ArrayList<>(counts.size());
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            rows.add(new LegendRow(GreenhouseScan.prettyName(entry.getKey()), GreenhouseScan.colorOf(entry.getKey()),
                    entry.getValue()[0], entry.getValue()[1]));
        }
        rows.sort((a, b) -> Integer.compare(b.total(), a.total()));
        return rows;
    }

    /** Lines describing what the overlay currently sees, for {@code /it blueprint status}. */
    static List<String> describe(Minecraft client) {
        List<String> lines = new ArrayList<>();
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null) return lines;

        GreenhouseBlueprint active = GreenhouseBlueprintManager.getActive();
        lines.add("Overlay: " + (config.greenhouseBlueprint.enabled ? "on" : "off"));
        lines.add("Active blueprint: " + (active == null ? "none (copy one with /it blueprint copy)"
                : active.name + " (" + active.source + ", " + active.boards.size() + " board"
                + (active.boards.size() == 1 ? "" : "s") + ", rotated " + active.rotation * 90 + "°"
                + (active.mirrored ? ", mirrored" : "") + ")"));
        if (copyMode) lines.add("Copy preview: on (/it blueprint save <name> to keep it, /it blueprint cancel to stop)");
        boolean garden = isInGarden();
        lines.add("In the Garden: " + (garden ? "yes" : "no")
                + (!garden && config.greenhouseBlueprint.onlyInGarden ? " (the overlay only shows in the Garden)" : ""));

        PlotRef plot = currentPlot(client);
        if (plot == null || client.level == null) {
            lines.add("Plot: you are not standing in a garden plot");
            return lines;
        }
        boolean greenhouse = hasGreenhouse(client.level, plot);
        lines.add("Plot: " + plot.x() + "," + plot.z() + " - greenhouse "
                + (greenhouse ? "found" : "not found"
                + (config.greenhouseBlueprint.ignoreGreenhouseCheck ? " (ignored by your settings)" : "")));
        if (!areaLoaded(client.level, plot)) {
            lines.add("The greenhouse area isn't fully loaded - move closer to it");
            return lines;
        }

        GreenhouseScan.Scan scan = scan(client.level, plot);
        long floorTypes = scan.floor().stream().filter(id -> !id.isEmpty()).distinct().count();
        lines.add("In the greenhouse area: " + scan.blocks().size() + " block" + (scan.blocks().size() == 1 ? "" : "s")
                + " in the crop layer, " + scan.stands().size() + " armor stand" + (scan.stands().size() == 1 ? "" : "s")
                + ", " + floorTypes + " kind" + (floorTypes == 1 ? "" : "s") + " of soil");

        Snapshot snap = snapshot;
        if (snap != null && snap.copy()) {
            lines.add("Copy preview: " + snap.total() + " crop" + (snap.total() == 1 ? "" : "s") + " and "
                    + snap.floorTotal() + " soil block" + (snap.floorTotal() == 1 ? "" : "s") + " would be saved");
        } else if (snap != null) {
            lines.add("Board " + snap.boardNumber() + " of " + snap.boardCount() + ": "
                    + snap.done() + " of " + snap.total() + " crops right"
                    + (snap.floorTotal() > 0 ? ", " + snap.floorDone() + " of " + snap.floorTotal() + " soil blocks right" : "")
                    + (snap.extras() > 0 ? ", " + snap.extras() + " extra not in the layout" : "")
                    + (snap.waiting() > 0 ? ", " + snap.waiting() + " hidden until their soil is right" : "")
                    + (snap.perfect() ? " - the greenhouse matches the layout" : ""));
        }
        return lines;
    }

    // -------------------------------------------------------------------------
    // World rendering
    // -------------------------------------------------------------------------

    private static float[] rgb(int color) {
        return new float[]{((color >> 16) & 0xFF) / 255.0f, ((color >> 8) & 0xFF) / 255.0f, (color & 0xFF) / 255.0f};
    }

    /** Whether armor stands that have all their items are drawn as ghosts, rather than as boxes. */
    private static boolean standGhostsOn(IslandTimeConfig config, Snapshot snap) {
        return !snap.copy() && config.greenhouseBlueprint.ghostStands && !GreenhouseGhostStands.hasFailed();
    }

    /**
     * Hands the ghost armor stands to the game's renderer. This has to happen at this point of the
     * frame, where the game collects what entities draw, rather than with the boxes in {@link #render}.
     */
    private static void collectSubmits(LevelRenderContext context) {
        Snapshot snap = snapshot;
        if (snap == null || snap.copy() || copyMode || snap.ghostStands().isEmpty()) return;
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !config.greenhouseBlueprint.enabled || !standGhostsOn(config, snap)) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        float opacity = Math.max(0.05f, Math.min(1.0f, config.greenhouseBlueprint.opacity / 100.0f));
        GreenhouseGhostStands.submit(context, client.level, snap.ghostStands(), snap.areaX(), snap.areaZ(),
                opacity, config.greenhouseBlueprint.hideCompleted);
    }

    private static void render(LevelRenderContext context) {
        Snapshot snap = snapshot;
        if (snap == null || snap.copy() != copyMode) return;
        if (snap.cells().isEmpty() && snap.floor().isEmpty() && !snap.copy()) return;

        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !(config.greenhouseBlueprint.enabled || snap.copy())) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        boolean hideCompleted = config.greenhouseBlueprint.hideCompleted;
        float opacity = Math.max(0.05f, Math.min(1.0f, config.greenhouseBlueprint.opacity / 100.0f));
        boolean models = !snap.copy() && config.greenhouseBlueprint.ghostBlocks && !modelRenderingFailed;
        boolean standGhosts = standGhostsOn(config, snap);

        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        Vec3 cameraPos = context.levelState().cameraRenderState.pos;

        // Boxes first: they stand in for whatever can't be drawn as a block model
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4fc pose = poseStack.last().pose();
        VertexConsumer buffer = bufferSource.getBuffer(config.greenhouseBlueprint.throughWalls
                ? BlockHighlightRenderer.NO_DEPTH_FILLED_BOX
                : RenderTypes.debugFilledBox());

        for (Cell cell : snap.cells()) {
            boolean done = cell.status() == GreenhouseEvaluator.Status.DONE;
            if (cell.hidden() || done && hideCompleted) continue;
            renderCellBoxes(pose, buffer, snap, cell, done ? opacity * 0.5f : opacity,
                    models && ghostModelOf(cell.placement()) != null, standGhosts);
        }
        if (!models && config.greenhouseBlueprint.showFloor) renderFloorBoxes(pose, buffer, snap, opacity);
        if (snap.copy()) renderCopyFrame(pose, buffer, snap);

        poseStack.popPose();
        bufferSource.endBatch();

        if (models) {
            try {
                renderGhostBlocks(poseStack, bufferSource, client.level, snap, config, opacity, cameraPos);
                bufferSource.endBatch();
            } catch (RuntimeException e) {
                modelRenderingFailed = true;
                System.err.println("[IslandTime] Drawing greenhouse ghost blocks failed, using boxes instead: " + e);
            }
        }

        // The outlines come last of the ghosts, in a batch of their own, so a ghost can't tint them
        if (!snap.copy() && config.greenhouseBlueprint.incorrectOutline) {
            renderOutlines(poseStack, bufferSource, snap, config, cameraPos);
        }

        if (config.greenhouseBlueprint.showLabels) {
            renderLabels(context, client, snap, config, hideCompleted, cameraPos);
        }
    }

    /** The block a single cell placement is drawn as a ghost of, or null if it has none to draw. */
    private static BlockState ghostModelOf(GreenhouseBlueprint.Placement p) {
        return p.size == 1 ? GreenhouseGhostBlocks.parseState(p.block) : null;
    }

    /**
     * Draws the ghost blocks: crops that still have to be planted, and soil that is missing or wrong.
     * Anything that is already right is skipped, since a copy of a block drawn over itself is pointless.
     */
    private static void renderGhostBlocks(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                          ClientLevel level, Snapshot snap, IslandTimeConfig config,
                                          float opacity, Vec3 cameraPos) {
        for (Cell cell : snap.cells()) {
            GreenhouseBlueprint.Placement p = cell.placement();
            if (cell.hidden() || cell.status() == GreenhouseEvaluator.Status.DONE || cell.blockOk()) continue;
            BlockState state = ghostModelOf(p);
            if (state == null) continue;
            GreenhouseGhostBlocks.render(poseStack, bufferSource, level, state,
                    new BlockPos(snap.areaX() + p.x, GreenhouseGeometry.CROP_Y, snap.areaZ() + p.z),
                    cameraPos.x, cameraPos.y, cameraPos.z, opacity, GHOST_GROW);
        }

        if (!config.greenhouseBlueprint.showFloor) return;
        for (FloorCell cell : snap.floor()) {
            if (cell.status() == GreenhouseEvaluator.Status.DONE) continue;
            BlockState state = GreenhouseGhostBlocks.parseState(cell.block());
            if (state == null) continue;
            GreenhouseGhostBlocks.render(poseStack, bufferSource, level, state,
                    new BlockPos(snap.areaX() + cell.x(), GreenhouseGeometry.SOIL_Y, snap.areaZ() + cell.z()),
                    cameraPos.x, cameraPos.y, cameraPos.z, opacity, GHOST_GROW);
        }
    }

    /**
     * Draws the boxes for one placement: a box over the whole footprint of a big crop, a box in the
     * cell of a block that has no model to show (or that is being previewed for a copy), a thin red
     * slab where a different block than planned is, and a box the size of each armor stand that is
     * still to be put there and isn't drawn as a ghost.
     *
     * @param blockIsModel the block part is drawn as a block model elsewhere
     * @param standGhosts  armor stands that have all their items are drawn as ghosts elsewhere
     */
    private static void renderCellBoxes(Matrix4fc pose, VertexConsumer buffer, Snapshot snap, Cell cell,
                                        float alpha, boolean blockIsModel, boolean standGhosts) {
        GreenhouseBlueprint.Placement p = cell.placement();
        boolean done = cell.status() == GreenhouseEvaluator.Status.DONE;
        boolean wrong = cell.status() == GreenhouseEvaluator.Status.WRONG;
        float[] color = wrong ? COLOR_WRONG : rgb(p.color());
        float y0 = GreenhouseGeometry.CROP_Y;

        if (p.size > 1) {
            float x0 = snap.areaX() + p.x + BOX_INSET;
            float x1 = snap.areaX() + p.x + p.size - BOX_INSET;
            float z0 = snap.areaZ() + p.z + BOX_INSET;
            float z1 = snap.areaZ() + p.z + p.size - BOX_INSET;
            float height = BOX_HEIGHT + 0.2f * (p.size - 1);
            BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y0, z0, x1, y0 + height, z1,
                    color[0], color[1], color[2], alpha);
            return;
        }

        float x0 = snap.areaX() + p.x + BOX_INSET;
        float x1 = snap.areaX() + p.x + 1 - BOX_INSET;
        float z0 = snap.areaZ() + p.z + BOX_INSET;
        float z1 = snap.areaZ() + p.z + 1 - BOX_INSET;
        boolean blockPending = !p.block.isEmpty() && (done || !cell.blockOk());
        if (blockPending && (!blockIsModel || done)) {
            BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y0, z0, x1, y0 + BOX_HEIGHT, z1,
                    color[0], color[1], color[2], alpha);
        } else if (wrong && !cell.blockOk()) {
            BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y0, z0, x1, y0 + WRONG_SLAB_HEIGHT, z1,
                    COLOR_WRONG[0], COLOR_WRONG[1], COLOR_WRONG[2], alpha);
        }

        if (p.stands.isEmpty() || (!done && cell.standsOk())) return;
        boolean known = GreenhouseCrops.byKey(p.crop) != null;
        for (int i = 0; i < p.stands.size(); i++) {
            GreenhouseBlueprint.Stand stand = p.stands.get(i);
            boolean standThere = i < cell.standOk().length && cell.standOk()[i];
            if (!done && standThere) continue;
            if (standGhosts && stand.drawable() && GreenhouseGhostStands.isDrawn(stand)) continue;
            float[] standColor = wrong && !standThere ? COLOR_WRONG
                    : known ? rgb(p.color()) : rgb(GreenhouseScan.colorOf(stand.head));
            float half = Math.max((float) stand.visualWidth(), STAND_MIN_SIZE) / 2.0f;
            float height = Math.max(STAND_MIN_SIZE, Math.min(STAND_MAX_HEIGHT, (float) stand.visualHeight()));
            float cx = snap.areaX() + (float) stand.x;
            float cz = snap.areaZ() + (float) stand.z;
            float sy = y0 + (float) stand.y;
            BlockHighlightRenderer.renderFilledBox(pose, buffer, cx - half, sy, cz - half, cx + half, sy + height,
                    cz + half, standColor[0], standColor[1], standColor[2], alpha);
        }
    }

    /**
     * Draws a red outline around everything that isn't right: a block, an armor stand or a soil block
     * that the layout wants and that isn't there (or is something else), and anything in the way that
     * the layout doesn't have, such as a real armor stand where there should be none.
     */
    private static void renderOutlines(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                       Snapshot snap, IslandTimeConfig config, Vec3 cameraPos) {
        float beam = Math.max(1, Math.min(25, config.greenhouseBlueprint.outlineThickness)) / 100.0f;
        float alpha = Math.max(10, Math.min(100, config.greenhouseBlueprint.outlineOpacity)) / 100.0f;
        OutlineStyle style = new OutlineStyle(beam, Math.min(OUTLINE_GROW, beam / 2.0f), alpha);

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4fc pose = poseStack.last().pose();
        VertexConsumer buffer = bufferSource.getBuffer(config.greenhouseBlueprint.throughWalls
                ? BlockHighlightRenderer.NO_DEPTH_FILLED_BOX
                : RenderTypes.debugFilledBox());

        for (Cell cell : snap.cells()) {
            if (cell.hidden() || cell.status() == GreenhouseEvaluator.Status.DONE) continue;
            GreenhouseBlueprint.Placement p = cell.placement();
            if (p.size == 1 && !p.block.isEmpty() && !cell.blockOk()) {
                outlineCell(pose, buffer, style, snap, p.x, p.z, GreenhouseGeometry.CROP_Y, cameraPos);
            }
            for (int i = 0; i < p.stands.size(); i++) {
                if (i < cell.standOk().length && cell.standOk()[i]) continue;
                outlineStand(pose, buffer, style, snap, p.stands.get(i), cameraPos);
            }
            for (GreenhouseBlueprint.Stand stand : cell.unwanted()) {
                outlineStand(pose, buffer, style, snap, stand, cameraPos);
            }
        }
        if (config.greenhouseBlueprint.showFloor) {
            for (FloorCell cell : snap.floor()) {
                if (cell.status() == GreenhouseEvaluator.Status.DONE) continue;
                outlineCell(pose, buffer, style, snap, cell.x(), cell.z(), GreenhouseGeometry.SOIL_Y, cameraPos);
            }
        }
        for (GreenhouseEvaluator.Extra extra : snap.extraSpots()) {
            if (extra.stand() == null) outlineCell(pose, buffer, style, snap, extra.x(), extra.z(), GreenhouseGeometry.CROP_Y, cameraPos);
            else outlineStand(pose, buffer, style, snap, extra.stand(), cameraPos);
        }

        poseStack.popPose();
        bufferSource.endBatch();
    }

    /** Outlines the block space at a cell of the area, {@code y} being the block's lowest y. */
    private static void outlineCell(Matrix4fc pose, VertexConsumer buffer, OutlineStyle style, Snapshot snap,
                                    int x, int z, int y, Vec3 cameraPos) {
        float x0 = snap.areaX() + x;
        float z0 = snap.areaZ() + z;
        if (!inOutlineRange(x0 + 0.5, y + 0.5, z0 + 0.5, cameraPos)) return;
        float g = style.grow();
        outlineBox(pose, buffer, style, x0 - g, y - g, z0 - g, x0 + 1 + g, y + 1 + g, z0 + 1 + g);
    }

    /** Outlines the space an armor stand takes up, with room for what it holds. */
    private static void outlineStand(Matrix4fc pose, VertexConsumer buffer, OutlineStyle style, Snapshot snap,
                                     GreenhouseBlueprint.Stand stand, Vec3 cameraPos) {
        float cx = snap.areaX() + (float) stand.x;
        float cz = snap.areaZ() + (float) stand.z;
        float y0 = GreenhouseGeometry.CROP_Y + (float) stand.y;
        float half = Math.max((float) stand.visualWidth(), OUTLINE_STAND_MIN_WIDTH) / 2.0f + OUTLINE_STAND_GROW;
        float height = (float) stand.visualHeight() + OUTLINE_STAND_GROW;
        if (!inOutlineRange(cx, y0 + height / 2.0, cz, cameraPos)) return;
        outlineBox(pose, buffer, style, cx - half, y0 - style.grow(), cz - half, cx + half, y0 + height, cz + half);
    }

    private static boolean inOutlineRange(double x, double y, double z, Vec3 cameraPos) {
        double dx = x - cameraPos.x;
        double dy = y - cameraPos.y;
        double dz = z - cameraPos.z;
        return dx * dx + dy * dy + dz * dz <= OUTLINE_RANGE * OUTLINE_RANGE;
    }

    /**
     * The twelve edges of a box, as beams whose outer faces are the box's own. A beam is never thicker
     * than a third of the box's smallest side, so the beams of a thick outline don't swallow a small box.
     */
    private static void outlineBox(Matrix4fc pose, VertexConsumer buffer, OutlineStyle style,
                                   float x0, float y0, float z0, float x1, float y1, float z1) {
        float t = Math.min(style.beam(), Math.min(x1 - x0, Math.min(y1 - y0, z1 - z0)) / 3.0f);
        float a = style.alpha();
        float[] c = COLOR_OUTLINE;
        for (int i = 0; i < 4; i++) {
            float y = (i & 1) == 0 ? y0 : y1 - t;
            float z = (i & 2) == 0 ? z0 : z1 - t;
            BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y, z, x1, y + t, z + t, c[0], c[1], c[2], a);
        }
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? x0 : x1 - t;
            float z = (i & 2) == 0 ? z0 : z1 - t;
            BlockHighlightRenderer.renderFilledBox(pose, buffer, x, y0 + t, z, x + t, y1 - t, z + t, c[0], c[1], c[2], a);
        }
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? x0 : x1 - t;
            float y = (i & 2) == 0 ? y0 : y1 - t;
            BlockHighlightRenderer.renderFilledBox(pose, buffer, x, y, z0 + t, x + t, y + t, z1 - t, c[0], c[1], c[2], a);
        }
    }

    /** Stands in for the soil ghost blocks when those aren't drawn: a flat tile where soil is missing or wrong. */
    private static void renderFloorBoxes(Matrix4fc pose, VertexConsumer buffer, Snapshot snap, float alpha) {
        float y0 = GreenhouseGeometry.CROP_Y;
        for (FloorCell cell : snap.floor()) {
            if (cell.status() == GreenhouseEvaluator.Status.DONE) continue;
            float[] color = cell.status() == GreenhouseEvaluator.Status.WRONG
                    ? COLOR_WRONG : rgb(GreenhouseScan.colorOf(cell.block()));
            float x0 = snap.areaX() + cell.x() + BOX_INSET;
            float z0 = snap.areaZ() + cell.z() + BOX_INSET;
            BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y0, z0, x0 + 1 - 2 * BOX_INSET,
                    y0 + FLOOR_TILE_HEIGHT, z0 + 1 - 2 * BOX_INSET, color[0], color[1], color[2], alpha);
        }
    }

    /** Outlines the greenhouse area that a copy would cover: a thin border on the soil and a post at each corner. */
    private static void renderCopyFrame(Matrix4fc pose, VertexConsumer buffer, Snapshot snap) {
        float x0 = snap.areaX();
        float z0 = snap.areaZ();
        float x1 = x0 + GreenhouseGeometry.AREA_SIZE;
        float z1 = z0 + GreenhouseGeometry.AREA_SIZE;
        float y0 = GreenhouseGeometry.CROP_Y;
        float edgeTop = y0 + FRAME_EDGE_HEIGHT;
        float postTop = y0 + FRAME_POST_HEIGHT;
        float w = FRAME_WIDTH;
        float[] c = COLOR_FRAME;

        BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y0, z0, x1, edgeTop, z0 + w, c[0], c[1], c[2], FRAME_ALPHA);
        BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y0, z1 - w, x1, edgeTop, z1, c[0], c[1], c[2], FRAME_ALPHA);
        BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y0, z0 + w, x0 + w, edgeTop, z1 - w, c[0], c[1], c[2], FRAME_ALPHA);
        BlockHighlightRenderer.renderFilledBox(pose, buffer, x1 - w, y0, z0 + w, x1, edgeTop, z1 - w, c[0], c[1], c[2], FRAME_ALPHA);

        BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y0, z0, x0 + w, postTop, z0 + w, c[0], c[1], c[2], FRAME_ALPHA);
        BlockHighlightRenderer.renderFilledBox(pose, buffer, x1 - w, y0, z0, x1, postTop, z0 + w, c[0], c[1], c[2], FRAME_ALPHA);
        BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y0, z1 - w, x0 + w, postTop, z1, c[0], c[1], c[2], FRAME_ALPHA);
        BlockHighlightRenderer.renderFilledBox(pose, buffer, x1 - w, y0, z1 - w, x1, postTop, z1, c[0], c[1], c[2], FRAME_ALPHA);
    }

    /** Billboarded crop names above the ghosts near the player, in the way vanilla draws name tags. */
    private static void renderLabels(LevelRenderContext context, Minecraft client, Snapshot snap,
                                     IslandTimeConfig config, boolean hideCompleted, Vec3 cameraPos) {
        Font font = client.font;
        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        Vec3 playerPos = client.player.position();
        double range = Math.max(1, config.greenhouseBlueprint.labelRange);
        double rangeSq = range * range;

        boolean any = false;
        for (Cell cell : snap.cells()) {
            GreenhouseBlueprint.Placement p = cell.placement();
            boolean done = cell.status() == GreenhouseEvaluator.Status.DONE;
            if (cell.hidden() || done && hideCompleted) continue;

            double cx = snap.areaX() + p.x + p.size / 2.0;
            double cz = snap.areaZ() + p.z + p.size / 2.0;
            double dx = cx - playerPos.x;
            double dz = cz - playerPos.z;
            if (dx * dx + dz * dz > rangeSq) continue;

            String name = p.displayName();
            int color = LABEL_COLOR;
            if (cell.status() == GreenhouseEvaluator.Status.WRONG) {
                name = name + " (wrong here)";
                color = LABEL_COLOR_WRONG;
            }

            Component label = Component.literal(name);
            float halfWidth = font.width(label) / 2.0f;
            double top = GreenhouseGeometry.CROP_Y + labelHeight(p);

            poseStack.pushPose();
            poseStack.translate(cx - cameraPos.x, top + LABEL_OFFSET - cameraPos.y, cz - cameraPos.z);
            poseStack.mulPose(context.levelState().cameraRenderState.orientation);
            poseStack.scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);

            font.drawInBatch(label, -halfWidth, 0.0f, color, false,
                    poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH,
                    LABEL_BACKGROUND, FULL_BRIGHT);

            poseStack.popPose();
            any = true;
        }

        if (any) bufferSource.endBatch();
    }

    /** How far above the soil a placement's label floats: above its ghost box, or its tallest armor stand. */
    private static double labelHeight(GreenhouseBlueprint.Placement p) {
        double height = BOX_HEIGHT + 0.2f * (p.size - 1);
        if (p.size > 1) return height;
        for (GreenhouseBlueprint.Stand stand : p.stands) {
            double top = stand.y + Math.max(STAND_MIN_SIZE, Math.min(STAND_MAX_HEIGHT, stand.height));
            height = Math.max(height, top);
        }
        return height;
    }

    // -------------------------------------------------------------------------
    // HUD legend
    // -------------------------------------------------------------------------

    private static void renderHud(GuiGraphicsExtractor context) {
        Snapshot snap = snapshot;
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null) return;
        boolean copying = copyMode;
        if (snap == null ? !copying : snap.copy() != copying) return;
        if (!copying && !(config.greenhouseBlueprint.enabled && config.greenhouseBlueprint.showLegend)) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        if (client.options != null && client.options.hideGui) return;
        // Nothing left to guide you through once the greenhouse is exactly as the layout says
        if (!copying && snap.perfect() && config.greenhouseBlueprint.hideLegendWhenComplete) return;

        Font font = client.font;
        List<Component> lines = new ArrayList<>();
        List<Integer> swatches = new ArrayList<>();

        if (copying) {
            copyHudLines(snap, lines, swatches);
        } else {
            lines.add(Component.literal(snap.name()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            swatches.add(0);
            lines.add(Component.literal("Plot " + snap.plot().x() + "," + snap.plot().z()
                    + " - board " + snap.boardNumber() + "/" + snap.boardCount()
                    + " - " + snap.done() + "/" + snap.total() + " crops"
                    + (snap.floorTotal() > 0 ? ", " + snap.floorDone() + "/" + snap.floorTotal() + " soil" : ""))
                    .withStyle(ChatFormatting.GRAY));
            swatches.add(0);
            if (snap.extras() > 0) {
                lines.add(Component.literal(snap.extras() + " extra block" + (snap.extras() == 1 ? "" : "s")
                        + " or armor stand" + (snap.extras() == 1 ? "" : "s") + " not in the layout")
                        .withStyle(ChatFormatting.RED));
                swatches.add(0);
            }
            if (snap.waiting() > 0) {
                lines.add(Component.literal(snap.waiting() + " crop" + (snap.waiting() == 1 ? "" : "s")
                        + " hidden until the soil under " + (snap.waiting() == 1 ? "it is" : "them is") + " right")
                        .withStyle(ChatFormatting.YELLOW));
                swatches.add(0);
            }
            addLegendRows(snap, lines, swatches, false);
            addFloorRows(snap, lines, swatches, false);
        }

        int textWidth = 0;
        for (int i = 0; i < lines.size(); i++) {
            int w = font.width(lines.get(i)) + (swatches.get(i) != 0 ? HUD_SWATCH + 3 : 0);
            textWidth = Math.max(textWidth, w);
        }
        int width = textWidth + HUD_PADDING * 2;
        int height = lines.size() * HUD_LINE_HEIGHT + HUD_PADDING * 2 - 1;

        int freeX = Math.max(0, client.getWindow().getGuiScaledWidth() - width);
        int freeY = Math.max(0, client.getWindow().getGuiScaledHeight() - height);
        int x = Math.round(freeX * clampPercent(config.greenhouseBlueprint.legendX) / 100.0f);
        int y = Math.round(freeY * clampPercent(config.greenhouseBlueprint.legendY) / 100.0f);

        context.fill(x, y, x + width, y + height, HUD_BG_COLOR);
        context.fill(x, y, x + width, y + 1, HUD_BORDER_COLOR);
        context.fill(x, y + height - 1, x + width, y + height, HUD_BORDER_COLOR);
        context.fill(x, y, x + 1, y + height, HUD_BORDER_COLOR);
        context.fill(x + width - 1, y, x + width, y + height, HUD_BORDER_COLOR);

        int textY = y + HUD_PADDING;
        for (int i = 0; i < lines.size(); i++) {
            int textX = x + HUD_PADDING;
            int swatch = swatches.get(i);
            if (swatch != 0) {
                context.fill(textX, textY, textX + HUD_SWATCH, textY + HUD_SWATCH, swatch);
                textX += HUD_SWATCH + 3;
            }
            context.text(font, lines.get(i), textX, textY, -1, true);
            textY += HUD_LINE_HEIGHT;
        }
    }

    /** The legend while copying: what would be saved, and how to save or stop. */
    private static void copyHudLines(Snapshot snap, List<Component> lines, List<Integer> swatches) {
        lines.add(Component.literal("Copying greenhouse").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        swatches.add(0);
        if (snap == null) {
            lines.add(Component.literal("Stand in a greenhouse plot in the Garden").withStyle(ChatFormatting.GRAY));
            swatches.add(0);
        } else if (snap.cells().isEmpty() && snap.floorTotal() == 0) {
            lines.add(Component.literal("Plot " + snap.plot().x() + "," + snap.plot().z()
                    + " - nothing here yet").withStyle(ChatFormatting.GRAY));
            swatches.add(0);
        } else {
            int withStands = 0;
            for (Cell cell : snap.cells()) {
                if (!cell.placement().stands.isEmpty()) withStands++;
            }
            lines.add(Component.literal("Plot " + snap.plot().x() + "," + snap.plot().z() + " - "
                    + snap.total() + " crop" + (snap.total() == 1 ? "" : "s")
                    + (withStands > 0 ? " (" + withStands + " with armor stands)" : "")
                    + ", " + snap.floorTotal() + " soil block" + (snap.floorTotal() == 1 ? "" : "s")
                    + " will be saved").withStyle(ChatFormatting.GRAY));
            swatches.add(0);
            addLegendRows(snap, lines, swatches, true);
            addFloorRows(snap, lines, swatches, true);
        }
        lines.add(Component.literal("/it blueprint save <name>").withStyle(ChatFormatting.GREEN));
        swatches.add(0);
        lines.add(Component.literal("/it blueprint cancel").withStyle(ChatFormatting.DARK_GRAY));
        swatches.add(0);
    }

    /** One legend row per crop: "done/total" for a blueprint, or just the count for a copy preview. */
    private static void addLegendRows(Snapshot snap, List<Component> lines, List<Integer> swatches, boolean copy) {
        addRows(snap.legend(), MAX_LEGEND_ROWS, lines, swatches, copy);
    }

    /** The soil blocks the layout needs, under a "Floor" heading. Only drawn when the floor is shown. */
    private static void addFloorRows(Snapshot snap, List<Component> lines, List<Integer> swatches, boolean copy) {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (snap.floorLegend().isEmpty() || (!copy && config != null && !config.greenhouseBlueprint.showFloor)) return;
        lines.add(Component.literal("Floor").withStyle(ChatFormatting.GRAY, ChatFormatting.UNDERLINE));
        swatches.add(0);
        addRows(snap.floorLegend(), MAX_FLOOR_LEGEND_ROWS, lines, swatches, copy);
    }

    private static void addRows(List<LegendRow> legend, int maxRows, List<Component> lines,
                                List<Integer> swatches, boolean copy) {
        int rows = 0;
        for (LegendRow row : legend) {
            if (rows++ >= maxRows) {
                lines.add(Component.literal("...").withStyle(ChatFormatting.GRAY));
                swatches.add(0);
                break;
            }
            boolean complete = !copy && row.done() >= row.total();
            String count = copy ? "x" + row.total() : row.done() + "/" + row.total();
            lines.add(Component.literal(row.name() + " " + count)
                    .withStyle(complete ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE));
            swatches.add(0xFF000000 | row.color());
        }
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
