package org.example.islandtime;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Highlights, through walls, the minions whose fuel is out of sync with the rest of the cohort (see
 * {@link FuelCycleTracker#getOutOfSyncMinions(long)}), when the "Highlight Out-of-Sync Minions" setting is on.
 * A minion's armor stand is found by the block position it was registered under, so only minions that have been
 * opened since they were fueled can be highlighted.
 */
public class MinionSyncHighlight {

    private static final float RED = 1.0f;
    private static final float GREEN = 0.25f;
    private static final float BLUE = 0.25f;
    private static final float ALPHA = 0.45f;
    private static final long REFRESH_MS = 250L;

    private static List<AABB> boxes = List.of();
    private static long lastRefreshMs = 0;

    public static void register() {
        LevelRenderEvents.END_EXTRACTION.register(MinionSyncHighlight::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(MinionSyncHighlight::render);
    }

    private static void extract(LevelExtractionContext context) {
        Minecraft client = Minecraft.getInstance();
        IslandTimeConfig config = ConfigManager.getConfig();
        if (client.player == null || client.level == null
                || config == null || !config.fuelCycles.highlightOutOfSyncMinions) {
            boxes = List.of();
            return;
        }

        // The armor stands barely move and the fuel only changes every so often, so there's no need to redo this every frame
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < REFRESH_MS) return;
        lastRefreshMs = now;

        Set<String> positions = new HashSet<>();
        for (String key : FuelCycleTracker.getOutOfSyncMinions(now).keySet()) {
            String position = MinionIdentityRegistry.blockPositionFor(key);
            if (position != null) positions.add(position);
        }
        if (positions.isEmpty()) {
            boxes = List.of();
            return;
        }

        List<AABB> found = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || stand.isRemoved()) continue;
            BlockPos pos = stand.blockPosition();
            if (positions.contains(pos.getX() + "," + pos.getY() + "," + pos.getZ())) {
                found.add(stand.getBoundingBox().inflate(0.05));
            }
        }
        boxes = found;
    }

    private static void render(LevelRenderContext context) {
        List<AABB> toDraw = boxes;
        if (toDraw.isEmpty()) return;

        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        var camera = context.levelState().cameraRenderState.pos;

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc pose = poseStack.last().pose();
        VertexConsumer buffer = bufferSource.getBuffer(BlockHighlightRenderer.NO_DEPTH_FILLED_BOX);
        for (AABB box : toDraw) {
            BlockHighlightRenderer.renderFilledBox(pose, buffer,
                    (float) box.minX, (float) box.minY, (float) box.minZ,
                    (float) box.maxX, (float) box.maxY, (float) box.maxZ,
                    RED, GREEN, BLUE, ALPHA);
        }
        poseStack.popPose();
        bufferSource.endBatch();
    }
}
