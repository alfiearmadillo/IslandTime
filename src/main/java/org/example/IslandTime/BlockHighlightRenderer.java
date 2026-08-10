package org.example.islandtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Renders a colored overlay on all blocks matching the configured block ID
 * within the player's vicinity. Fully client-side.
 */
public class BlockHighlightRenderer {

    private static final int SCAN_RADIUS = 48;

    private static final RenderPipeline HIGHLIGHT_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("islandtime", "pipeline/block_highlight"))
                    .withDepthStencilState(Optional.empty()) // Render through walls
                    .build()
    );

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer stagedBuffer = new StagedVertexBuffer(() -> "Block Highlight Buffer", RenderType.SMALL_BUFFER_SIZE);

    private static List<BlockPos> highlightedPositions = List.of();
    private static float highlightR, highlightG, highlightB, highlightA;

    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(BlockHighlightRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(BlockHighlightRenderer::renderAndDraw);
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

    private static void renderAndDraw(LevelRenderContext context) {
        if (highlightedPositions.isEmpty()) return;

        RenderPipeline renderPipeline = HIGHLIGHT_PIPELINE;
        VertexFormat formatBinding = renderPipeline.getVertexFormatBinding(0);
        if (formatBinding == null) return;

        PrimitiveTopology primitive = renderPipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(formatBinding, primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

        renderHighlights(context, draw);

        stagedBuffer.upload();

        StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);
        if (info != null) {
            drawPass(Minecraft.getInstance(), info, renderPipeline);
        }

        stagedBuffer.endFrame();
    }

    private static void renderHighlights(LevelRenderContext context, StagedVertexBuffer.Draw draw) {
        PoseStack matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;

        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        final VertexConsumer builder = stagedBuffer.getVertexBuilder(draw);
        Matrix4fc pose = matrices.last().pose();

        for (BlockPos pos : highlightedPositions) {
            renderFilledBox(pose, builder,
                    pos.getX() - 0.001f, pos.getY() - 0.001f, pos.getZ() - 0.001f,
                    pos.getX() + 1.001f, pos.getY() + 1.001f, pos.getZ() + 1.001f,
                    highlightR, highlightG, highlightB, highlightA);
        }

        matrices.popPose();
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

    private static void drawPass(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();
        if (colorTexture == null) return;

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "islandtime block highlight", colorTexture, Optional.empty(), mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);

            renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
            renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());

            renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    public static void close() {
        stagedBuffer.close();
    }
}
