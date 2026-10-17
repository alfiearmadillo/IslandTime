package org.example.islandtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Draws a see-through copy ("ghost") of the armor stands a greenhouse blueprint still needs, wearing
 * what the real ones wear: armor, held items, and heads with their skull textures.
 *
 * <p>Instead of drawing a stand's parts by hand, each ghost is a real {@link ArmorStand} that only
 * exists on the client (it is never added to the world), dressed and posed like the stand that was
 * copied, and drawn by the game's own entity renderer. So a custom head, armor with a trim, or an item
 * in a hand all look as they do on the real thing. The renderer is given a {@link GhostCollector}
 * instead of the game's own collector, which makes everything it draws translucent and drops what a
 * ghost shouldn't have (shadow, name tag, fire, glint).
 *
 * <p>Making a model translucent means drawing it with the translucent twin of its render type, which is
 * the same texture with blending on. The game keeps a render type's texture private, so it is read
 * with reflection; if that (or anything else here) ever fails, {@link #hasFailed()} turns true and the
 * overlay goes back to boxes for stands.
 */
final class GreenhouseGhostStands {

    /** A stand the blueprint wants, and whether it is already in place (drawn fainter). */
    record Wanted(GreenhouseBlueprint.Stand stand, boolean done) {}

    private record Entry(ArmorStand entity, boolean done, double x, double y, double z) {}

    /** At most this many ghosts are drawn at once, nearest first. */
    private static final int MAX_STANDS = 80;
    private static final double MAX_DISTANCE = 96.0;

    private static final Map<RenderType, Optional<RenderType>> TWINS = new IdentityHashMap<>();
    private static Map<String, Optional<ArmorStand>> cache = new HashMap<>();
    private static List<Entry> entries = List.of();
    private static List<Wanted> builtFor = null;
    private static ClientLevel builtLevel = null;
    private static int builtAreaX;
    private static int builtAreaZ;
    private static boolean builtHideDone;
    private static volatile boolean failed = false;

    private GreenhouseGhostStands() {}

    /** True once drawing a ghost stand has gone wrong; stands are drawn as boxes from then on. */
    static boolean hasFailed() {
        return failed;
    }

    private static void fail(String what, Throwable cause) {
        if (!failed) System.err.println("[IslandTime] Drawing greenhouse ghost armor stands failed (" + what
                + "), using boxes instead: " + cause);
        failed = true;
        cache = new HashMap<>();
        entries = List.of();
        builtFor = null;
    }

    /**
     * Adds the ghosts to what the game draws this frame.
     *
     * @param opacity  how solid a ghost is, 0 to 1 (one that is in place already is drawn at half of it)
     * @param hideDone leave out the ghosts of stands that are in place already
     */
    static void submit(LevelRenderContext context, ClientLevel level, List<Wanted> wanted,
                       int areaX, int areaZ, float opacity, boolean hideDone) {
        if (failed || wanted.isEmpty()) return;
        try {
            if (wanted != builtFor || level != builtLevel || areaX != builtAreaX || areaZ != builtAreaZ
                    || hideDone != builtHideDone) {
                rebuild(level, wanted, areaX, areaZ, hideDone);
            }
            if (entries.isEmpty()) return;

            Minecraft client = Minecraft.getInstance();
            EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
            float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            CameraRenderState camera = context.levelState().cameraRenderState;
            Vec3 cam = camera.pos;
            PoseStack poseStack = context.poseStack();

            List<Entry> visible = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                if (distanceSq(entry, cam) <= MAX_DISTANCE * MAX_DISTANCE) visible.add(entry);
            }
            if (visible.size() > MAX_STANDS) {
                visible.sort(Comparator.comparingDouble(entry -> distanceSq(entry, cam)));
                visible = visible.subList(0, MAX_STANDS);
            }

            GhostCollector full = new GhostCollector(context.submitNodeCollector(), tint(opacity));
            GhostCollector faint = new GhostCollector(context.submitNodeCollector(), tint(opacity * 0.5f));
            for (Entry entry : visible) {
                EntityRenderState state = dispatcher.extractEntity(entry.entity(), partialTick);
                dispatcher.submit(state, camera, state.x - cam.x, state.y - cam.y, state.z - cam.z, poseStack,
                        entry.done() ? faint : full);
            }
        } catch (RuntimeException | LinkageError e) {
            fail("rendering", e);
        }
    }

    private static double distanceSq(Entry entry, Vec3 cam) {
        double dx = entry.x() - cam.x;
        double dy = entry.y() - cam.y;
        double dz = entry.z() - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int tint(float opacity) {
        return ARGB.color(Math.max(1, Math.min(255, Math.round(opacity * 255.0f))), 0xFFFFFF);
    }

    /** Makes the stands for a new list of wanted ones, reusing the ones that are unchanged. */
    private static void rebuild(ClientLevel level, List<Wanted> wanted, int areaX, int areaZ, boolean hideDone) {
        if (level != builtLevel) cache = new HashMap<>();
        Map<String, Optional<ArmorStand>> next = new HashMap<>();
        List<Entry> built = new ArrayList<>(wanted.size());
        for (Wanted w : wanted) {
            if (w.done() && hideDone) continue;
            GreenhouseBlueprint.Stand stand = w.stand();
            double x = areaX + stand.x;
            double y = GreenhouseGeometry.CROP_Y + stand.y;
            double z = areaZ + stand.z;
            String key = signature(stand, x, y, z) + (w.done() ? "d" : "w");
            if (next.containsKey(key)) continue;
            Optional<ArmorStand> entity = cache.get(key);
            if (entity == null) {
                entity = Optional.ofNullable(GreenhouseStandItems.createGhost(level, stand, x, y, z));
            }
            next.put(key, entity);
            entity.ifPresent(e -> built.add(new Entry(e, w.done(), x, y, z)));
        }
        cache = next;
        entries = built;
        builtFor = wanted;
        builtLevel = level;
        builtAreaX = areaX;
        builtAreaZ = areaZ;
        builtHideDone = hideDone;
    }

    /** Identifies a stand by everything that shapes how it is drawn, so an unchanged one keeps its entity. */
    private static String signature(GreenhouseBlueprint.Stand stand, double x, double y, double z) {
        StringBuilder key = new StringBuilder(96);
        key.append(x).append(',').append(y).append(',').append(z).append(',').append(stand.yaw)
                .append(',').append(stand.flags).append(',').append(stand.invisible);
        if (stand.pose != null) key.append(',').append(Arrays.hashCode(stand.pose));
        for (GreenhouseBlueprint.Gear gear : stand.gear) {
            key.append(',').append(gear.slot).append(':').append(gear.data == null ? 0 : gear.data.hashCode())
                    .append('/').append(gear.data == null ? 0 : gear.data.length());
        }
        return key.toString();
    }

    // -------------------------------------------------------------------------
    // Translucent render types
    // -------------------------------------------------------------------------

    /**
     * The render type to draw something in as a ghost: its translucent twin, or itself if it is
     * already translucent. Null for what a ghost shouldn't draw, the enchantment glint.
     */
    private static RenderType ghostType(RenderType type) {
        if (type == null) return null;
        Optional<RenderType> known = TWINS.get(type);
        if (known == null) {
            known = computeTwin(type);
            TWINS.put(type, known);
        }
        return known.orElse(null);
    }

    private static Optional<RenderType> computeTwin(RenderType type) {
        if (type == RenderTypes.armorEntityGlint() || type == RenderTypes.glint()
                || type == RenderTypes.glintTranslucent() || type == RenderTypes.entityGlint()) {
            return Optional.empty();
        }
        if (type.hasBlending()) return Optional.of(type);
        Identifier texture = textureOf(type);
        return texture == null ? Optional.empty() : Optional.of(RenderTypes.entityTranslucent(texture));
    }

    /**
     * The texture a render type draws with. The game doesn't expose it, so this reads
     * {@code RenderType.state.textures["Sampler0"].location} with reflection.
     */
    private static Identifier textureOf(RenderType type) {
        try {
            Field stateField = RenderType.class.getDeclaredField("state");
            stateField.setAccessible(true);
            Object setup = stateField.get(type);
            Field texturesField = setup.getClass().getDeclaredField("textures");
            texturesField.setAccessible(true);
            Map<?, ?> textures = (Map<?, ?>) texturesField.get(setup);
            Object binding = textures.get("Sampler0");
            if (binding == null && !textures.isEmpty()) binding = textures.values().iterator().next();
            if (binding == null) return null;
            Method location = binding.getClass().getDeclaredMethod("location");
            location.setAccessible(true);
            return (Identifier) location.invoke(binding);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail("reading a render type's texture", e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // The collector the ghosts are drawn through
    // -------------------------------------------------------------------------

    /**
     * Passes on what the entity renderer submits, made translucent. Shadows, name tags, text, fire,
     * leashes and everything that isn't an armor stand's own look are left out.
     */
    private static class GhostOrdered implements OrderedSubmitNodeCollector {
        final OrderedSubmitNodeCollector inner;
        /** White with the ghost's alpha, which colours are multiplied by. */
        final int tint;

        GhostOrdered(OrderedSubmitNodeCollector inner, int tint) {
            this.inner = inner;
            this.tint = tint;
        }

        int fade(int color) {
            return ARGB.multiply((color >>> 24) == 0 ? color | 0xFF000000 : color, tint);
        }

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {}

        @Override
        public void submitNameTag(PoseStack poseStack, Vec3 nameTagAttachment, int offset, Component name,
                                  boolean seeThrough, int lightCoords, double distanceToCameraSq, CameraRenderState camera) {}

        @Override
        public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                               Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor, int outlineColor) {}

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {}

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {}

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                    int lightCoords, int overlayCoords, int tintedColor, TextureAtlasSprite sprite,
                                    int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
            RenderType ghost = ghostType(renderType);
            if (ghost == null) return;
            inner.submitModel(model, state, poseStack, ghost, lightCoords, overlayCoords, fade(tintedColor), sprite, 0, null);
        }

        @Override
        public void submitModelPart(ModelPart modelPart, PoseStack poseStack, RenderType renderType, int lightCoords,
                                    int overlayCoords, TextureAtlasSprite sprite, boolean sheeted, boolean hasFoil,
                                    int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
                                    int outlineColor) {
            RenderType ghost = ghostType(renderType);
            if (ghost == null) return;
            inner.submitModelPart(modelPart, poseStack, ghost, lightCoords, overlayCoords, sprite, sheeted, false,
                    fade(tintedColor), null, 0);
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState) {}

        @Override
        public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                     int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {}

        @Override
        public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel model, long seed, int progress) {}

        /**
         * An item's quads, drawn the way the game draws them (each in its own render type) but in the
         * translucent twin of that type, and tinted by the ghost's alpha as well as the item's own tint.
         */
        @Override
        public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                               int overlayCoords, int outlineColor, int[] tintLayers, List<BakedQuad> quads,
                               ItemStackRenderState.FoilType foilType) {
            Map<RenderType, List<BakedQuad>> byType = new LinkedHashMap<>();
            for (BakedQuad quad : quads) {
                RenderType ghost = ghostType(quad.materialInfo().itemRenderType());
                if (ghost != null) byType.computeIfAbsent(ghost, k -> new ArrayList<>()).add(quad);
            }
            for (Map.Entry<RenderType, List<BakedQuad>> group : byType.entrySet()) {
                List<BakedQuad> groupQuads = group.getValue();
                inner.submitCustomGeometry(poseStack, group.getKey(), (pose, buffer) -> {
                    QuadInstance instance = new QuadInstance();
                    instance.setLightCoords(lightCoords);
                    instance.setOverlayCoords(overlayCoords);
                    for (BakedQuad quad : groupQuads) {
                        BakedQuad.MaterialInfo material = quad.materialInfo();
                        int color = material.isTinted() && material.tintIndex() >= 0 && material.tintIndex() < tintLayers.length
                                ? tintLayers[material.tintIndex()] : -1;
                        instance.setColor(fade(color));
                        buffer.putBakedQuad(pose, quad, instance);
                    }
                });
            }
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                         SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {}

        @Override
        public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer particleGroupRenderer) {}
    }

    private static final class GhostCollector extends GhostOrdered implements SubmitNodeCollector {
        private final SubmitNodeCollector base;

        GhostCollector(SubmitNodeCollector base, int tint) {
            super(base, tint);
            this.base = base;
        }

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return new GhostOrdered(base.order(order), tint);
        }
    }
}
