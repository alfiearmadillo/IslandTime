package org.example.islandtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Renders a colored overlay on all blocks matching the configured block ID
 * within the player's vicinity. Fully client-side.
 */
public class BlockHighlightRenderer {

    private static final int SCAN_RADIUS = 48;

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BlockHighlightRenderer::onWorldRender);
    }

    private static void onWorldRender(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null) return;

        if (!config.blockHighlight.enabled) return;

        int intensity = config.blockHighlight.intensity;
        if (intensity <= 0) return;

        String blockId = config.blockHighlight.blockId;
        if (blockId == null || blockId.isBlank()) return;

        // Normalize block ID
        String normalized = blockId.trim().toLowerCase();
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }

        ResourceLocation targetId = ResourceLocation.tryParse(normalized);
        if (targetId == null) return;

        if (!BuiltInRegistries.BLOCK.containsKey(targetId)) return;
        Block targetBlock = BuiltInRegistries.BLOCK.getValue(targetId);

        Level level = client.level;
        BlockPos playerPos = client.player.blockPosition();
        Vec3 cam = context.camera().getPosition();

        // Alpha scales from 0 at intensity=1 to 0.8 at intensity=100
        float alpha = intensity / 125.0f;
        // Color: bright green-yellow highlight
        float r = 1.0f;
        float g = 1.0f;
        float b = 0.2f;

        PoseStack poseStack = context.matrixStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int radius = Math.min(SCAN_RADIUS, 16 + (intensity * 32 / 100));
        boolean hasVertices = false;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() == targetBlock) {
                        renderBlockOverlay(buffer, pos, alpha, r, g, b);
                        hasVertices = true;
                    }
                }
            }
        }

        if (hasVertices) {
            MeshData mesh = buffer.buildOrThrow();
            BufferUploader.drawWithShader(mesh);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

    private static void renderBlockOverlay(BufferBuilder buffer, BlockPos pos, float a, float r, float g, float b) {
        float x0 = pos.getX() - 0.001f;
        float y0 = pos.getY() - 0.001f;
        float z0 = pos.getZ() - 0.001f;
        float x1 = pos.getX() + 1.001f;
        float y1 = pos.getY() + 1.001f;
        float z1 = pos.getZ() + 1.001f;

        // Bottom face
        buffer.addVertex(x0, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(x1, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(x1, y0, z1).setColor(r, g, b, a);
        buffer.addVertex(x0, y0, z1).setColor(r, g, b, a);

        // Top face
        buffer.addVertex(x0, y1, z0).setColor(r, g, b, a);
        buffer.addVertex(x0, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(x1, y1, z0).setColor(r, g, b, a);

        // North face
        buffer.addVertex(x0, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(x0, y1, z0).setColor(r, g, b, a);
        buffer.addVertex(x1, y1, z0).setColor(r, g, b, a);
        buffer.addVertex(x1, y0, z0).setColor(r, g, b, a);

        // South face
        buffer.addVertex(x0, y0, z1).setColor(r, g, b, a);
        buffer.addVertex(x1, y0, z1).setColor(r, g, b, a);
        buffer.addVertex(x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(x0, y1, z1).setColor(r, g, b, a);

        // West face
        buffer.addVertex(x0, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(x0, y0, z1).setColor(r, g, b, a);
        buffer.addVertex(x0, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(x0, y1, z0).setColor(r, g, b, a);

        // East face
        buffer.addVertex(x1, y0, z0).setColor(r, g, b, a);
        buffer.addVertex(x1, y1, z0).setColor(r, g, b, a);
        buffer.addVertex(x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(x1, y0, z1).setColor(r, g, b, a);
    }
}
