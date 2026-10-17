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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a greenhouse blueprint over the greenhouse plot the player is standing in, in the style of
 * Litematica: a translucent coloured ghost for every crop that still has to be planted, labelled with
 * the crop's name when you are close. Crops that are already planted correctly disappear from the
 * overlay; a different crop in the wrong place is flagged in red. A small legend shows how many of
 * each crop are still to go.
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

    // Ghost box geometry, in blocks
    private static final float BOX_INSET = 0.12f;
    private static final float BOX_HEIGHT = 0.6f;

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

    private static final int HUD_PADDING = 4;
    private static final int HUD_LINE_HEIGHT = 10;
    private static final int HUD_SWATCH = 8;
    private static final int HUD_BG_COLOR = 0xC0000000;
    private static final int HUD_BORDER_COLOR = 0xFF2E7D32;

    /** The plot the player is standing in, in garden plot coordinates (0-4 on each axis). */
    record PlotRef(int x, int z) {}

    /** One placement together with how it stands. */
    record Cell(GreenhouseBlueprint.Placement placement, GreenhouseEvaluator.Status status) {}

    /** One line of the legend: how many of a crop are planted out of how many the board needs. */
    record LegendRow(GreenhouseCrops.Crop crop, int done, int total) {}

    /**
     * Everything the renderer needs for the current plot; built by {@link #tick(Minecraft)}. For a copy
     * preview ({@code copy}) the cells are the crops that would be saved and the board fields are unused.
     */
    record Snapshot(String name, PlotRef plot, int boardNumber, int boardCount,
                    int rotation, boolean mirrored, int areaX, int areaZ,
                    List<Cell> cells, List<LegendRow> legend, int done, int total, boolean copy) {}

    /** What {@link #captureNow(Minecraft)} found: a board to save, or why there isn't one. */
    record Capture(PlotRef plot, GreenhouseBlueprint.Board board, String error) {
        boolean ok() {
            return error == null;
        }
    }

    private static volatile Snapshot snapshot = null;
    /** True while the copy preview is on: the area that would be saved is shown instead of a blueprint. */
    private static volatile boolean copyMode = false;
    private static int ticksSinceScan = SCAN_INTERVAL_TICKS;

    private GreenhouseBlueprintRenderer() {}

    public static void register() {
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

    /** Scans the greenhouse area of the plot the player stands in, ready to be saved as a blueprint. */
    static Capture captureNow(Minecraft client) {
        String blocker = copyBlocker(client);
        if (blocker != null) return new Capture(null, null, blocker);

        PlotRef plot = currentPlot(client);
        GreenhouseBlueprint.Board board = GreenhouseCapture.toBoard(detectPlanted(client.level, plot));
        if (board.placements.isEmpty()) {
            return new Capture(plot, null, "Nothing is planted in this plot's greenhouse area yet, so there is "
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
    // Detecting what is planted
    // -------------------------------------------------------------------------

    /**
     * Finds the crops planted in the plot's greenhouse area. Plain crops are blocks; the Garden's
     * special crops are armor stands wearing a player head named after the crop.
     */
    static List<GreenhouseEvaluator.Detected> detectPlanted(ClientLevel level, PlotRef plot) {
        int areaX = GreenhouseGeometry.areaMin(plot.x());
        int areaZ = GreenhouseGeometry.areaMin(plot.z());
        int size = GreenhouseGeometry.AREA_SIZE;
        List<GreenhouseEvaluator.Detected> found = new ArrayList<>();

        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                BlockPos pos = new BlockPos(areaX + dx, GreenhouseGeometry.CROP_Y, areaZ + dz);
                String key = cropKeyOfBlock(level.getBlockState(pos).getBlock());
                if (key != null) found.add(new GreenhouseEvaluator.Detected(key, dx + 0.5, dz + 0.5));
            }
        }

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || stand.isRemoved()) continue;
            Vec3 pos = stand.position();
            double dx = pos.x - areaX;
            double dz = pos.z - areaZ;
            if (dx < -1 || dx >= size + 1 || dz < -1 || dz >= size + 1) continue;
            if (pos.y < GreenhouseGeometry.SOIL_Y - 1 || pos.y > GreenhouseGeometry.CROP_Y + 8) continue;

            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (head.isEmpty()) continue;
            GreenhouseCrops.Crop crop = GreenhouseCrops.fromPlantedName(head.getHoverName().getString());
            if (crop != null) found.add(new GreenhouseEvaluator.Detected(crop.key(), dx, dz));
        }
        return found;
    }

    private static String cropKeyOfBlock(Block block) {
        if (block == Blocks.WHEAT) return "WHEAT";
        if (block == Blocks.CARROTS) return "CARROT";
        if (block == Blocks.POTATOES) return "POTATO";
        if (block == Blocks.NETHER_WART) return "NETHER_WART";
        if (block == Blocks.SUGAR_CANE) return "SUGAR_CANE";
        if (block == Blocks.FIRE) return "FIRE";
        if (block == Blocks.DEAD_BUSH) return "DEAD_PLANT";
        if (block == Blocks.CACTUS) return "CACTUS";
        if (block == Blocks.PUMPKIN) return "PUMPKIN";
        if (block == Blocks.MELON) return "MELON";
        if (block == Blocks.COCOA) return "COCOA_BEANS";
        if (block == Blocks.RED_MUSHROOM) return "RED_MUSHROOM";
        if (block == Blocks.BROWN_MUSHROOM) return "BROWN_MUSHROOM";
        return null;
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

        if (copying) return buildCopySnapshot(level, plot);

        GreenhouseBlueprint.Board board = blueprint.boardForPlot(plot.x(), plot.z());
        List<GreenhouseBlueprint.Placement> oriented = blueprint.orientedPlacements(board);
        List<GreenhouseEvaluator.Result> results =
                GreenhouseEvaluator.evaluate(oriented, detectPlanted(level, plot));

        List<Cell> cells = new ArrayList<>(results.size());
        Map<String, int[]> counts = new LinkedHashMap<>();
        int done = 0;
        int total = 0;
        for (GreenhouseEvaluator.Result result : results) {
            GreenhouseBlueprint.Placement p = result.placement();
            cells.add(new Cell(p, result.status()));
            int[] count = counts.computeIfAbsent(p.crop, k -> new int[2]);
            count[1]++;
            total++;
            if (result.status() == GreenhouseEvaluator.Status.DONE) {
                count[0]++;
                done++;
            }
        }

        List<LegendRow> legend = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            GreenhouseCrops.Crop crop = GreenhouseCrops.byKey(entry.getKey());
            if (crop != null) legend.add(new LegendRow(crop, entry.getValue()[0], entry.getValue()[1]));
        }

        return new Snapshot(blueprint.name, plot,
                blueprint.boardIndexForPlot(plot.x(), plot.z()) + 1, blueprint.boards.size(),
                blueprint.rotation, blueprint.mirrored,
                GreenhouseGeometry.areaMin(plot.x()), GreenhouseGeometry.areaMin(plot.z()),
                cells, legend, done, total, false);
    }

    /** What saving right now would copy: every crop planted in the plot's greenhouse area. */
    private static Snapshot buildCopySnapshot(ClientLevel level, PlotRef plot) {
        GreenhouseBlueprint.Board board = GreenhouseCapture.toBoard(detectPlanted(level, plot));

        List<Cell> cells = new ArrayList<>(board.placements.size());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GreenhouseBlueprint.Placement p : board.placements) {
            cells.add(new Cell(p, GreenhouseEvaluator.Status.MISSING));
            counts.merge(p.crop, 1, Integer::sum);
        }

        List<LegendRow> legend = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            GreenhouseCrops.Crop crop = GreenhouseCrops.byKey(entry.getKey());
            if (crop != null) legend.add(new LegendRow(crop, entry.getValue(), entry.getValue()));
        }

        return new Snapshot("Copying greenhouse", plot, 0, 0, 0, false,
                GreenhouseGeometry.areaMin(plot.x()), GreenhouseGeometry.areaMin(plot.z()),
                cells, legend, cells.size(), cells.size(), true);
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
        lines.add("Crops planted in the greenhouse area: " + detectPlanted(client.level, plot).size());

        Snapshot snap = snapshot;
        if (snap != null && snap.copy()) {
            lines.add("Copy preview: " + snap.total() + " crop" + (snap.total() == 1 ? "" : "s") + " would be saved");
        } else if (snap != null) {
            lines.add("Board " + snap.boardNumber() + " of " + snap.boardCount() + ": "
                    + snap.done() + " of " + snap.total() + " crops planted");
        }
        return lines;
    }

    // -------------------------------------------------------------------------
    // World rendering
    // -------------------------------------------------------------------------

    private static float[] rgb(int color) {
        return new float[]{((color >> 16) & 0xFF) / 255.0f, ((color >> 8) & 0xFF) / 255.0f, (color & 0xFF) / 255.0f};
    }

    private static void render(LevelRenderContext context) {
        Snapshot snap = snapshot;
        if (snap == null || snap.copy() != copyMode) return;
        if (snap.cells().isEmpty() && !snap.copy()) return;

        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !(config.greenhouseBlueprint.enabled || snap.copy())) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        boolean hideCompleted = config.greenhouseBlueprint.hideCompleted;
        float opacity = Math.max(0.05f, Math.min(1.0f, config.greenhouseBlueprint.opacity / 100.0f));

        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        Vec3 cameraPos = context.levelState().cameraRenderState.pos;

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4fc pose = poseStack.last().pose();
        VertexConsumer buffer = bufferSource.getBuffer(config.greenhouseBlueprint.throughWalls
                ? BlockHighlightRenderer.NO_DEPTH_FILLED_BOX
                : RenderTypes.debugFilledBox());

        for (Cell cell : snap.cells()) {
            GreenhouseBlueprint.Placement p = cell.placement();
            boolean done = cell.status() == GreenhouseEvaluator.Status.DONE;
            if (done && hideCompleted) continue;

            float[] color = boxColor(cell);
            float alpha = done ? opacity * 0.5f : opacity;
            float height = BOX_HEIGHT + 0.2f * (p.size - 1);

            float x0 = snap.areaX() + p.x + BOX_INSET;
            float x1 = snap.areaX() + p.x + p.size - BOX_INSET;
            float z0 = snap.areaZ() + p.z + BOX_INSET;
            float z1 = snap.areaZ() + p.z + p.size - BOX_INSET;
            float y0 = GreenhouseGeometry.CROP_Y;
            BlockHighlightRenderer.renderFilledBox(pose, buffer, x0, y0, z0, x1, y0 + height, z1,
                    color[0], color[1], color[2], alpha);
        }
        if (snap.copy()) renderCopyFrame(pose, buffer, snap);

        poseStack.popPose();
        bufferSource.endBatch();

        if (config.greenhouseBlueprint.showLabels) {
            renderLabels(context, client, snap, config, hideCompleted, cameraPos);
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

    private static float[] boxColor(Cell cell) {
        if (cell.status() == GreenhouseEvaluator.Status.WRONG) return COLOR_WRONG;
        GreenhouseCrops.Crop crop = GreenhouseCrops.byKey(cell.placement().crop);
        return crop == null ? COLOR_WRONG : rgb(crop.color());
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
            if (done && hideCompleted) continue;

            double cx = snap.areaX() + p.x + p.size / 2.0;
            double cz = snap.areaZ() + p.z + p.size / 2.0;
            double dx = cx - playerPos.x;
            double dz = cz - playerPos.z;
            if (dx * dx + dz * dz > rangeSq) continue;

            GreenhouseCrops.Crop crop = GreenhouseCrops.byKey(p.crop);
            String name = crop == null ? p.crop : crop.name();
            int color = LABEL_COLOR;
            if (cell.status() == GreenhouseEvaluator.Status.WRONG) {
                name = name + " (wrong here)";
                color = LABEL_COLOR_WRONG;
            }

            Component label = Component.literal(name);
            float halfWidth = font.width(label) / 2.0f;
            double top = GreenhouseGeometry.CROP_Y + BOX_HEIGHT + 0.2f * (p.size - 1);

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
                    + " - " + snap.done() + "/" + snap.total() + " planted").withStyle(ChatFormatting.GRAY));
            swatches.add(0);
            addLegendRows(snap, lines, swatches, false);
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
        } else if (snap.cells().isEmpty()) {
            lines.add(Component.literal("Plot " + snap.plot().x() + "," + snap.plot().z()
                    + " - nothing planted here yet").withStyle(ChatFormatting.GRAY));
            swatches.add(0);
        } else {
            lines.add(Component.literal("Plot " + snap.plot().x() + "," + snap.plot().z() + " - "
                    + snap.total() + " crop" + (snap.total() == 1 ? "" : "s") + " will be saved")
                    .withStyle(ChatFormatting.GRAY));
            swatches.add(0);
            addLegendRows(snap, lines, swatches, true);
        }
        lines.add(Component.literal("/it blueprint save <name>").withStyle(ChatFormatting.GREEN));
        swatches.add(0);
        lines.add(Component.literal("/it blueprint cancel").withStyle(ChatFormatting.DARK_GRAY));
        swatches.add(0);
    }

    /** One legend row per crop: "done/total" for a blueprint, or just the count for a copy preview. */
    private static void addLegendRows(Snapshot snap, List<Component> lines, List<Integer> swatches, boolean copy) {
        int rows = 0;
        for (LegendRow row : snap.legend()) {
            if (rows++ >= MAX_LEGEND_ROWS) {
                lines.add(Component.literal("...").withStyle(ChatFormatting.GRAY));
                swatches.add(0);
                break;
            }
            boolean complete = !copy && row.done() >= row.total();
            String count = copy ? "x" + row.total() : row.done() + "/" + row.total();
            lines.add(Component.literal(row.crop().name() + " " + count)
                    .withStyle(complete ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE));
            swatches.add(0xFF000000 | row.crop().color());
        }
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
