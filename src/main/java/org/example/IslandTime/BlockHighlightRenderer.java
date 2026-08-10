package org.example.islandtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

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

        // Parse comma-separated block IDs
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

        Level level = client.level;
        BlockPos playerPos = client.player.blockPosition();

        // Color from config
        int colorIdx = Math.max(0, Math.min(config.blockHighlight.color, COLORS.length - 1));
        highlightR = COLORS[colorIdx][0];
        highlightG = COLORS[colorIdx][1];
        highlightB = COLORS[colorIdx][2];
        // Alpha scales from 0 at intensity=1 to 0.8 at intensity=100
        highlightA = intensity / 125.0f;

        throughWalls = config.blockHighlight.throughWalls;

        int radius = Math.min(config.blockHighlight.range, 16 + (intensity * 32 / 100));
        List<BlockPos> positions = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (targetBlocks.contains(state.getBlock())) {
                        positions.add(pos.immutable());
                    }
                }
            }
        }

        highlightedPositions = positions;
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
