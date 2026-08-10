package org.example.islandtime;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
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
 * Renders a colored overlay on all blocks matching the configured block ID
 * within the player's vicinity. Fully client-side.
 */
public class BlockHighlightRenderer {

    private static final int SCAN_RADIUS = 48;

    private static List<BlockPos> highlightedPositions = List.of();
    private static float highlightR, highlightG, highlightB, highlightA;

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

        String blockId = config.blockHighlight.blockId;
        if (blockId == null || blockId.isBlank()) {
            highlightedPositions = List.of();
            return;
        }

        // Normalize block ID
        String normalized = blockId.trim().toLowerCase();
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }

        Identifier targetId = Identifier.tryParse(normalized);
        if (targetId == null) {
            highlightedPositions = List.of();
            return;
        }

        if (!BuiltInRegistries.BLOCK.containsKey(targetId)) {
            highlightedPositions = List.of();
            return;
        }
        Block targetBlock = BuiltInRegistries.BLOCK.getValue(targetId);

        Level level = client.level;
        BlockPos playerPos = client.player.blockPosition();

        // Alpha scales from 0 at intensity=1 to 0.8 at intensity=100
        highlightA = intensity / 125.0f;
        highlightR = 1.0f;
        highlightG = 1.0f;
        highlightB = 0.2f;

        int radius = Math.min(SCAN_RADIUS, 16 + (intensity * 32 / 100));
        List<BlockPos> positions = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() == targetBlock) {
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
        VertexConsumer buffer = bufferSource.getBuffer(RenderTypes.debugFilledBox());

        var cameraState = context.levelState().cameraRenderState;
        double camX = cameraState.pos.x;
        double camY = cameraState.pos.y;
        double camZ = cameraState.pos.z;

        poseStack.pushPose();
        poseStack.translate(-camX, -camY, -camZ);

        Matrix4fc pose = poseStack.last().pose();

        for (BlockPos pos : highlightedPositions) {
            renderFilledBox(pose, buffer,
                    pos.getX() - 0.001f, pos.getY() - 0.001f, pos.getZ() - 0.001f,
                    pos.getX() + 1.001f, pos.getY() + 1.001f, pos.getZ() + 1.001f,
                    highlightR, highlightG, highlightB, highlightA);
        }

        poseStack.popPose();
        bufferSource.endBatch();
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
