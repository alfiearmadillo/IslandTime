package org.example.islandtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Renders a colored overlay on all blocks matching the configured block IDs
 * within the player's vicinity. Fully client-side.
 */
public class BlockHighlightRenderer {

    private static final String[] COLOR_NAMES = {"YELLOW", "RED", "GREEN", "BLUE", "MAGENTA", "CYAN", "WHITE"};
    private static final float[][] COLORS = {
            {1.0f, 1.0f, 0.2f},  // YELLOW
            {1.0f, 0.2f, 0.2f},  // RED
            {0.2f, 1.0f, 0.2f},  // GREEN
            {0.2f, 0.4f, 1.0f},  // BLUE
            {1.0f, 0.2f, 1.0f},  // MAGENTA
            {0.2f, 1.0f, 1.0f},  // CYAN
            {1.0f, 1.0f, 1.0f},  // WHITE
    };

    // Custom RenderType that disables depth testing so highlights show through walls
    private static final RenderPipeline NO_DEPTH_FILLED_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("islandtime", "pipeline/no_depth_filled_box"))
                    .withDepthStencilState(Optional.empty())
                    .build()
    );

    private static final RenderType NO_DEPTH_FILLED_BOX = RenderType.create(
            "islandtime_no_depth_filled_box",
            RenderSetup.builder(NO_DEPTH_FILLED_PIPELINE)
                    .sortOnUpload()
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .createRenderSetup()
    );

    // Each highlighted position stores its color
    private record ColoredPos(BlockPos pos, float r, float g, float b) {}

    private static List<ColoredPos> highlightedPositions = List.of();
    private static float highlightA;
    private static boolean throughWalls;

    // Caching: avoid rescanning every frame
    private static BlockPos lastScanPos = null;
    private static String lastBlockIdField = "";
    private static int lastRange = 0;
    private static long lastScanTick = -1;
    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final int RESCAN_MOVE_THRESHOLD_SQ = 64;

    // Async scanning
    private static final AtomicReference<List<ColoredPos>> pendingResult = new AtomicReference<>(null);
    private static CompletableFuture<Void> scanFuture = null;

    public static void register() {
        LevelRenderEvents.END_EXTRACTION.register(BlockHighlightRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(BlockHighlightRenderer::render);
    }

    /**
     * Parses a color string into [r, g, b]. Supports named colors and hex codes (#rrggbb).
     * Returns null if unrecognized.
     */
    public static float[] parseColor(String color) {
        if (color == null || color.isEmpty()) return null;
        // Hex color
        if (color.startsWith("#") && color.length() == 7) {
            try {
                int rgb = Integer.parseInt(color.substring(1), 16);
                return new float[]{
                        ((rgb >> 16) & 0xFF) / 255.0f,
                        ((rgb >> 8) & 0xFF) / 255.0f,
                        (rgb & 0xFF) / 255.0f
                };
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // Named color
        String upper = color.toUpperCase();
        for (int i = 0; i < COLOR_NAMES.length; i++) {
            if (COLOR_NAMES[i].equals(upper)) {
                return COLORS[i];
            }
        }
        return null;
    }

    /**
     * Parses the blockId config field. Format: space-separated entries, each is BLOCKID or BLOCKID,COLOR.
     * Returns a map of Block -> color (float[3]).
     */
    private static Map<Block, float[]> parseBlockEntries(String blockIdField, float[] defaultColor) {
        Map<Block, float[]> result = new HashMap<>();
        if (blockIdField == null || blockIdField.isBlank()) return result;

        for (String entry : blockIdField.trim().split("\\s+")) {
            if (entry.isEmpty()) continue;
            String blockPart;
            float[] color = defaultColor;

            int commaIdx = entry.indexOf(',');
            if (commaIdx >= 0) {
                blockPart = entry.substring(0, commaIdx);
                String colorPart = entry.substring(commaIdx + 1);
                float[] parsed = parseColor(colorPart);
                if (parsed != null) color = parsed;
            } else {
                blockPart = entry;
            }

            String normalized = blockPart.trim().toLowerCase();
            if (normalized.isEmpty()) continue;
            if (!normalized.contains(":")) {
                normalized = "minecraft:" + normalized;
            }
            Identifier targetId = Identifier.tryParse(normalized);
            if (targetId == null) continue;
            if (!BuiltInRegistries.BLOCK.containsKey(targetId)) continue;
            result.put(BuiltInRegistries.BLOCK.getValue(targetId), color);
        }
        return result;
    }

    private static void extract(LevelExtractionContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            highlightedPositions = List.of();
            return;
        }

        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !config.blockHighlight.enabled) {
            highlightedPositions = List.of();
            return;
        }

        int intensity = config.blockHighlight.intensity;
        if (intensity <= 0) {
            highlightedPositions = List.of();
            return;
        }

        String blockIdField = config.blockHighlight.blockId;
        if (blockIdField == null || blockIdField.isBlank()) {
            highlightedPositions = List.of();
            return;
        }

        highlightA = intensity / 125.0f;
        throughWalls = config.blockHighlight.throughWalls;

        // Pick up completed async scan results
        List<ColoredPos> completed = pendingResult.getAndSet(null);
        if (completed != null) {
            highlightedPositions = completed;
        }

        // Determine if we need to rescan
        BlockPos playerPos = client.player.blockPosition();
        int range = config.blockHighlight.range;
        long currentTick = client.level.getGameTime();

        boolean configChanged = !blockIdField.equals(lastBlockIdField) || range != lastRange;
        boolean moved = lastScanPos == null || playerPos.distSqr(lastScanPos) >= RESCAN_MOVE_THRESHOLD_SQ;
        boolean timedOut = (currentTick - lastScanTick) >= SCAN_INTERVAL_TICKS;
        boolean scanRunning = scanFuture != null && !scanFuture.isDone();

        if (!scanRunning && (configChanged || moved || timedOut)) {
            lastScanPos = playerPos.immutable();
            lastBlockIdField = blockIdField;
            lastRange = range;
            lastScanTick = currentTick;

            // Parse target blocks with per-block colors on main thread (registry access)
            int colorIdx = Math.max(0, Math.min(config.blockHighlight.color, COLORS.length - 1));
            float[] defaultColor = COLORS[colorIdx];
            Map<Block, float[]> blockColors = parseBlockEntries(blockIdField, defaultColor);

            if (blockColors.isEmpty()) {
                highlightedPositions = List.of();
                return;
            }

            Set<Block> targetBlocks = blockColors.keySet();

            // Capture references for async work
            Level level = client.level;
            BlockPos scanCenter = lastScanPos;
            int scanRadius = range;

            scanFuture = CompletableFuture.runAsync(() -> {
                List<ColoredPos> positions = new ArrayList<>();

                // Convert target blocks to a set of BlockStates for palette checking
                Set<BlockState> targetStates = new HashSet<>();
                for (Block block : targetBlocks) {
                    targetStates.addAll(block.getStateDefinition().getPossibleStates());
                }

                // Determine chunk and section bounds
                int minX = scanCenter.getX() - scanRadius;
                int maxX = scanCenter.getX() + scanRadius;
                int minY = scanCenter.getY() - scanRadius;
                int maxY = scanCenter.getY() + scanRadius;
                int minZ = scanCenter.getZ() - scanRadius;
                int maxZ = scanCenter.getZ() + scanRadius;

                int minChunkX = SectionPos.blockToSectionCoord(minX);
                int maxChunkX = SectionPos.blockToSectionCoord(maxX);
                int minChunkZ = SectionPos.blockToSectionCoord(minZ);
                int maxChunkZ = SectionPos.blockToSectionCoord(maxZ);

                for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                    for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                        LevelChunk chunk;
                        try {
                            chunk = level.getChunk(cx, cz);
                        } catch (Exception e) {
                            continue;
                        }
                        if (chunk == null) continue;

                        LevelChunkSection[] sections = chunk.getSections();
                        int minSectionY = chunk.getMinSectionY();

                        for (int si = 0; si < sections.length; si++) {
                            LevelChunkSection section = sections[si];
                            if (section == null || section.hasOnlyAir()) continue;

                            int sectionWorldY = (minSectionY + si) << 4;
                            if (sectionWorldY + 15 < minY || sectionWorldY > maxY) continue;

                            boolean hasTarget = section.getStates().maybeHas(targetStates::contains);
                            if (!hasTarget) continue;

                            int secBaseX = cx << 4;
                            int secBaseZ = cz << 4;
                            int startX = Math.max(secBaseX, minX);
                            int endX = Math.min(secBaseX + 15, maxX);
                            int startY = Math.max(sectionWorldY, minY);
                            int endY = Math.min(sectionWorldY + 15, maxY);
                            int startZ = Math.max(secBaseZ, minZ);
                            int endZ = Math.min(secBaseZ + 15, maxZ);

                            for (int bx = startX; bx <= endX; bx++) {
                                for (int by = startY; by <= endY; by++) {
                                    for (int bz = startZ; bz <= endZ; bz++) {
                                        try {
                                            BlockState state = level.getBlockState(new BlockPos(bx, by, bz));
                                            Block block = state.getBlock();
                                            float[] color = blockColors.get(block);
                                            if (color != null) {
                                                positions.add(new ColoredPos(new BlockPos(bx, by, bz), color[0], color[1], color[2]));
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                pendingResult.set(positions);
            });
        }
    }

    private static void render(LevelRenderContext context) {
        if (highlightedPositions.isEmpty()) return;

        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        VertexConsumer buffer = bufferSource.getBuffer(
                throughWalls ? NO_DEPTH_FILLED_BOX : RenderTypes.debugFilledBox()
        );

        var cameraState = context.levelState().cameraRenderState;
        double camX = cameraState.pos.x;
        double camY = cameraState.pos.y;
        double camZ = cameraState.pos.z;

        poseStack.pushPose();
        poseStack.translate(-camX, -camY, -camZ);

        Matrix4fc pose = poseStack.last().pose();

        if (throughWalls) {
            for (ColoredPos cp : highlightedPositions) {
                BlockPos pos = cp.pos;
                renderFilledBox(pose, buffer,
                        pos.getX() - 0.001f, pos.getY() - 0.001f, pos.getZ() - 0.001f,
                        pos.getX() + 1.001f, pos.getY() + 1.001f, pos.getZ() + 1.001f,
                        cp.r, cp.g, cp.b, highlightA);
            }
        } else {
            Minecraft client = Minecraft.getInstance();
            Level level = client.level;
            if (level != null) {
                for (ColoredPos cp : highlightedPositions) {
                    renderExposedFaces(pose, buffer, cp.pos, level,
                            cp.r, cp.g, cp.b, highlightA);
                }
            }
        }

        poseStack.popPose();
        bufferSource.endBatch();
    }

    private static void renderExposedFaces(Matrix4fc pose, VertexConsumer buffer,
                                            BlockPos pos, Level level,
                                            float r, float g, float b, float a) {
        float x0 = pos.getX() - 0.001f;
        float y0 = pos.getY() - 0.001f;
        float z0 = pos.getZ() - 0.001f;
        float x1 = pos.getX() + 1.001f;
        float y1 = pos.getY() + 1.001f;
        float z1 = pos.getZ() + 1.001f;

        if (!level.getBlockState(pos.south()).isSolidRender()) {
            buffer.addVertex(pose, x0, y0, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y0, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y1, z1).setColor(r, g, b, a);
        }
        if (!level.getBlockState(pos.north()).isSolidRender()) {
            buffer.addVertex(pose, x1, y0, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y0, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y1, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z0).setColor(r, g, b, a);
        }
        if (!level.getBlockState(pos.west()).isSolidRender()) {
            buffer.addVertex(pose, x0, y0, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y0, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y1, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y1, z0).setColor(r, g, b, a);
        }
        if (!level.getBlockState(pos.east()).isSolidRender()) {
            buffer.addVertex(pose, x1, y0, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y0, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        }
        if (!level.getBlockState(pos.above()).isSolidRender()) {
            buffer.addVertex(pose, x0, y1, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y1, z0).setColor(r, g, b, a);
        }
        if (!level.getBlockState(pos.below()).isSolidRender()) {
            buffer.addVertex(pose, x0, y0, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y0, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y0, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y0, z1).setColor(r, g, b, a);
        }
    }

    private static void renderFilledBox(Matrix4fc positionMatrix, VertexConsumer buffer,
                                         float minX, float minY, float minZ,
                                         float maxX, float maxY, float maxZ,
                                         float r, float g, float b, float a) {
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(r, g, b, a);

        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(r, g, b, a);

        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(r, g, b, a);

        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(r, g, b, a);

        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(r, g, b, a);

        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(r, g, b, a);
    }
}
