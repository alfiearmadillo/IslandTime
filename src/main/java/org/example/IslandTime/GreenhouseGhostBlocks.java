package org.example.islandtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Everything the greenhouse blueprint needs to know about Minecraft's blocks: turning a block state
 * into the text a blueprint stores and back, and drawing a see-through copy of a block's real model
 * (a "ghost") in the world.
 *
 * <p>Ghosts are drawn the way vanilla draws a block that is being moved by a piston: the block's baked
 * model is tessellated into the translucent moving block render type, with each quad's colour
 * multiplied by an alpha. That way they look exactly like the real block (texture, shape, tint), only
 * see-through.
 */
final class GreenhouseGhostBlocks {

    private static final Map<String, Optional<BlockState>> PARSED = new HashMap<>();
    private static final QuadInstance SCRATCH = new QuadInstance();
    private static ModelBlockRenderer blockRenderer;

    private GreenhouseGhostBlocks() {}

    // -------------------------------------------------------------------------
    // Block states as text
    // -------------------------------------------------------------------------

    /** The id of a block, for example {@code minecraft:soul_sand}. */
    static String blockId(Block block) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? "" : key.toString();
    }

    /** A block state as {@code id[property=value,...]}, with the properties in alphabetical order. */
    static String stateString(BlockState state) {
        List<String> properties = new ArrayList<>();
        for (Property<?> property : state.getProperties()) {
            properties.add(property.getName() + "=" + valueName(state, property));
        }
        Collections.sort(properties);
        String id = blockId(state.getBlock());
        return properties.isEmpty() ? id : id + "[" + String.join(",", properties) + "]";
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /**
     * Reads a state string back into a block state, grown to its last growth stage so that a crop's
     * ghost looks ripe whatever stage it was copied at. Returns null for air and for blocks this
     * version of the game doesn't have.
     */
    static BlockState parseState(String text) {
        if (text == null || text.isEmpty()) return null;
        return PARSED.computeIfAbsent(text, key -> Optional.ofNullable(parse(key))).orElse(null);
    }

    private static BlockState parse(String text) {
        Identifier key = Identifier.tryParse(GreenhouseScan.idOf(text));
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) return null;
        BlockState state = BuiltInRegistries.BLOCK.getValue(key).defaultBlockState();
        if (state.isAir()) return null;

        int open = text.indexOf('[');
        if (open >= 0 && text.endsWith("]")) {
            for (String pair : text.substring(open + 1, text.length() - 1).split(",")) {
                int equals = pair.indexOf('=');
                if (equals > 0) state = withProperty(state, pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
        return fullyGrown(state);
    }

    private static BlockState withProperty(BlockState state, String name, String value) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) return withValue(state, property, value);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.isPresent() ? state.setValue(property, parsed.get()) : state;
    }

    private static BlockState fullyGrown(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("age")) return withHighestValue(state, property);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withHighestValue(BlockState state, Property<T> property) {
        T highest = null;
        for (T value : property.getPossibleValues()) {
            if (highest == null || value.compareTo(highest) > 0) highest = value;
        }
        return highest == null ? state : state.setValue(property, highest);
    }

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    /**
     * Draws a translucent copy of a block at a position in the world.
     *
     * @param camX camera position, which the pose stack is not yet translated by
     * @param alpha how solid it is, 0 to 1
     * @param grow  how much to inflate it on every side, in blocks, so that a ghost drawn where a
     *              different block already is doesn't flicker against its faces
     */
    static void render(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, ClientLevel level,
                       BlockState state, BlockPos pos, double camX, double camY, double camZ,
                       float alpha, float grow) {
        Minecraft client = Minecraft.getInstance();
        if (blockRenderer == null) blockRenderer = new ModelBlockRenderer(false, false, client.getBlockColors());
        BlockStateModel model = client.getModelManager().getBlockStateModelSet().get(state);
        VertexConsumer buffer = bufferSource.getBuffer(RenderTypes.translucentMovingBlock());
        int tint = ARGB.color(Math.max(1, Math.min(255, Math.round(alpha * 255.0f))), 0xFFFFFF);

        poseStack.pushPose();
        try {
            poseStack.translate(pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);
            if (grow > 0.0f) {
                float scale = 1.0f + 2.0f * grow;
                poseStack.translate(0.5, 0.5, 0.5);
                poseStack.scale(scale, scale, scale);
                poseStack.translate(-0.5, -0.5, -0.5);
            }

            BlockQuadOutput output = (x, y, z, quad, instance) -> {
                // Copy into our own instance so the colours the renderer prepared are left alone
                for (int vertex = 0; vertex < 4; vertex++) {
                    SCRATCH.setColor(vertex, ARGB.multiply(instance.getColor(vertex), tint));
                    SCRATCH.setLightCoords(vertex, instance.getLightCoords(vertex));
                }
                SCRATCH.setOverlayCoords(instance.overlayCoords());
                poseStack.pushPose();
                poseStack.translate(x, y, z);
                buffer.putBakedQuad(poseStack.last(), quad, SCRATCH);
                poseStack.popPose();
            };
            blockRenderer.tesselateBlock(output, 0.0f, 0.0f, 0.0f, level, pos, state, model, state.getSeed(pos));
        } finally {
            poseStack.popPose();
        }
    }
}
