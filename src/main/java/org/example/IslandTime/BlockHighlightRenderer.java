package org.example.islandtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    private static List<BlockPos> highlightedPositions = List.of();
    private static float highlightR, highlightG, highlightB, highlightA;
    private static boolean throughWalls;

    // Caching: avoid rescanning every frame
    private static BlockPos lastScanPos = null;
    private static String lastBlockIdField = "";
    private static int lastRange = 0;
    private static long lastScanTick = -1;
    private static final int SCAN_INTERVAL_TICKS = 20; // rescan every 1 second
    private static final int RESCAN_MOVE_THRESHOLD_SQ = 64; // rescan if moved 8+ blocks

    // Async scanning
    private static final AtomicReference<List<BlockPos>> pendingResult = new AtomicReference<>(null);
    private static CompletableFuture<Void> scanFuture = null;

    public static void register() {
        LevelRenderEvents.END_EXTRACTION.register(BlockHighlightRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(BlockHighlightRenderer::render);
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

        // Color from config
        int colorIdx = Math.max(0, Math.min(config.blockHighlight.color, COLORS.length - 1));
        highlightR = COLORS[colorIdx][0];
        highlightG = COLORS[colorIdx][1];
        highlightB = COLORS[colorIdx][2];
        highlightA = intensity / 125.0f;

        throughWalls = config.blockHighlight.throughWalls;

        // Pick up completed async scan results
        List<BlockPos> completed = pendingResult.getAndSet(null);
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

            // Parse target blocks on main thread (registry access)
            Set<Block> targetBlocks = new HashSet<>();
            for (String entry : blockIdField.split(",")) {
                String normalized = entry.trim().toLowerCase();
                if (normalized.isEmpty()) continue;
                if (!normalized.contains(":")) {
                    normalized = "minecraft:" + normalized;
                }
                Identifier targetId = Identifier.tryParse(normalized);
                if (targetId == null) continue;
                if (!BuiltInRegistries.BLOCK.containsKey(targetId)) continue;
                targetBlocks.add(BuiltInRegistries.BLOCK.getValue(targetId));
            }

            if (targetBlocks.isEmpty()) {
                highlightedPositions = List.of();
                return;
            }

            // Capture references for async work
            Level level = client.level;
            BlockPos scanCenter = lastScanPos;
            int scanRadius = range;

            scanFuture = CompletableFuture.runAsync(() -> {
                List<BlockPos> positions = new ArrayList<>();

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
                            // Skip sections outside Y range
                            if (sectionWorldY + 15 < minY || sectionWorldY > maxY) continue;

                            // Palette check: does this section contain any target state?
                            boolean hasTarget = section.getStates().maybeHas(targetStates::contains);
                            if (!hasTarget) continue;

                            // Scan this 16x16x16 section, clamped to our radius bounds
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
                                            if (targetBlocks.contains(state.getBlock())) {
                                                positions.add(new BlockPos(bx, by, bz));
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
            // Render all faces (visible through walls)
            for (BlockPos pos : highlightedPositions) {
                renderFilledBox(pose, buffer,
                        pos.getX() - 0.001f, pos.getY() - 0.001f, pos.getZ() - 0.001f,
                        pos.getX() + 1.001f, pos.getY() + 1.001f, pos.getZ() + 1.001f,
                        highlightR, highlightG, highlightB, highlightA);
            }
        } else {
            // Only render faces adjacent to non-solid blocks (natural occlusion)
            Minecraft client = Minecraft.getInstance();
            Level level = client.level;
            if (level != null) {
                for (BlockPos pos : highlightedPositions) {
                    renderExposedFaces(pose, buffer, pos, level,
                            highlightR, highlightG, highlightB, highlightA);
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

        // South face (+Z)
        if (!level.getBlockState(pos.south()).isSolidRender()) {
            buffer.addVertex(pose, x0, y0, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y0, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y1, z1).setColor(r, g, b, a);
        }
        // North face (-Z)
        if (!level.getBlockState(pos.north()).isSolidRender()) {
            buffer.addVertex(pose, x1, y0, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y0, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y1, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z0).setColor(r, g, b, a);
        }
        // West face (-X)
        if (!level.getBlockState(pos.west()).isSolidRender()) {
            buffer.addVertex(pose, x0, y0, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y0, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y1, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y1, z0).setColor(r, g, b, a);
        }
        // East face (+X)
        if (!level.getBlockState(pos.east()).isSolidRender()) {
            buffer.addVertex(pose, x1, y0, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y0, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        }
        // Top face (+Y)
        if (!level.getBlockState(pos.above()).isSolidRender()) {
            buffer.addVertex(pose, x0, y1, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
            buffer.addVertex(pose, x1, y1, z0).setColor(r, g, b, a);
            buffer.addVertex(pose, x0, y1, z0).setColor(r, g, b, a);
        }
        // Bottom face (-Y)
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
        // Front face (south)
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(r, g, b, a);

        // Back face (north)
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(r, g, b, a);

        // Left face (west)
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(r, g, b, a);

        // Right face (east)
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(r, g, b, a);

        // Top face
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(r, g, b, a);

        // Bottom face
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(r, g, b, a);
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(r, g, b, a);
    }
}
