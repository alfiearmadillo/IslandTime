package org.example.islandtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Alerts the player when a SPARKLING critter spawns nearby during a Critter Safari run.
 *
 * <p>Sparkling critters are ordinary critters with a "SPARKLING" prefix on their name tag; catching
 * one awards a bonus Rainbow Feather, so they are worth going out of your way for. When one is first
 * seen an XP pling plays, and a tracer line is drawn from the player's eyes to the critter (through
 * walls) for as long as it is around.
 *
 * <p>Like the Critter Safari widget, this only runs while inside the Safari, reusing the same
 * tab-list area detection as the block highlight feature.
 */
public class SparklingAlert {

    /** Strips Minecraft §-formatting codes before matching the name tag. */
    private static final Pattern FORMAT_STRIP = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    /** Name-tag marker Hypixel puts on sparkling critters (e.g. "SPARKLING Nozzlenose"). */
    private static final String SPARKLING_MARKER = "SPARKLING";

    /** Only consider critters within this many blocks, so far-off spawns don't spam alerts. */
    private static final double SCAN_RANGE = 64.0;
    private static final double SCAN_RANGE_SQ = SCAN_RANGE * SCAN_RANGE;

    /** Client ticks between entity scans; the tracer itself follows the entity every frame. */
    private static final int SCAN_INTERVAL_TICKS = 2;

    private static final float LINE_WIDTH = 2.0f;

    // Name tag styling. Vanilla name tags use a 0.025 scale; this is deliberately larger so a
    // sparkling stands out in a crowd of ordinary critters.
    private static final float NAME_TAG_SCALE = 0.06f;
    private static final double NAME_TAG_OFFSET = 0.6;
    private static final int NAME_TAG_COLOR = 0xFFFFAA00;
    private static final int NAME_TAG_BACKGROUND = 0x40000000;
    /** Full-bright packed light, so the label is legible in dark caves. */
    private static final int FULL_BRIGHT = 0xF000F0;

    // Line pipeline with depth testing disabled so the tracer is visible through terrain,
    // mirroring the through-walls pipeline used by BlockHighlightRenderer.
    private static final RenderPipeline NO_DEPTH_LINES_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("islandtime", "pipeline/no_depth_lines"))
                    .withDepthStencilState(Optional.empty())
                    .build()
    );

    private static final RenderType NO_DEPTH_LINES = RenderType.create(
            "islandtime_no_depth_lines",
            RenderSetup.builder(NO_DEPTH_LINES_PIPELINE)
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .createRenderSetup()
    );

    /** Sparkling critters currently in range, refreshed every tick and read while rendering. */
    private static volatile List<Entity> trackedSparklings = List.of();

    /** Entity IDs already alerted for, so each sparkling only plays the sound once. */
    private static final Set<Integer> alertedIds = new HashSet<>();

    private static int ticksSinceScan = SCAN_INTERVAL_TICKS;

    private SparklingAlert() {}

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(SparklingAlert::render);
    }

    // -------------------------------------------------------------------------
    // Detection
    // -------------------------------------------------------------------------

    /**
     * Returns true if the given name tag belongs to a sparkling critter.
     */
    public static boolean isSparklingName(String name) {
        if (name == null) return false;
        return FORMAT_STRIP.matcher(name).replaceAll("").contains(SPARKLING_MARKER);
    }

    /**
     * Scans nearby entities for sparkling critters, pings once for each newly seen one and keeps
     * the rest for the tracer. Called every client tick.
     */
    public static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            clear();
            return;
        }

        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !config.critterSafari.sparklingAlert || !CritterSafariHud.isInSafariArea()) {
            clear();
            return;
        }

        // Scanning every entity every tick is wasteful in an area full of armour stands.
        if (++ticksSinceScan < SCAN_INTERVAL_TICKS) return;
        ticksSinceScan = 0;

        Vec3 playerPos = client.player.position();
        List<Entity> found = new ArrayList<>();

        for (Entity entity : client.level.entitiesForRendering()) {
            Component customName = entity.getCustomName();
            if (customName == null) continue;
            if (!isSparklingName(customName.getString())) continue;
            if (entity.position().distanceToSqr(playerPos) > SCAN_RANGE_SQ) continue;

            found.add(entity);
        }

        // Forget sparklings that no longer exist, so one that respawns later alerts again.
        // Entities are kept while they are merely out of scan range, to avoid re-pinging when
        // the player walks back towards one that was already announced.
        alertedIds.removeIf(id -> client.level.getEntity(id) == null);

        boolean playSound = config.critterSafari.sparklingSound;
        for (Entity entity : found) {
            if (alertedIds.add(entity.getId()) && playSound) {
                client.getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5f));
            }
        }

        trackedSparklings = found;
    }

    private static void clear() {
        ticksSinceScan = SCAN_INTERVAL_TICKS;
        if (!trackedSparklings.isEmpty()) trackedSparklings = List.of();
        if (!alertedIds.isEmpty()) alertedIds.clear();
    }

    // -------------------------------------------------------------------------
    // Tracer and name tag rendering
    // -------------------------------------------------------------------------

    private static void render(LevelRenderContext context) {
        List<Entity> sparklings = trackedSparklings;
        if (sparklings.isEmpty()) return;

        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !config.critterSafari.sparklingAlert) return;

        boolean tracer = config.critterSafari.sparklingTracer;
        boolean nameTag = config.critterSafari.sparklingNameTag;
        if (!tracer && !nameTag) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        // Positions are interpolated with the frame's partial tick, otherwise the tracer would only
        // move 20 times a second and visibly stutter behind the critter.
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cameraPos = context.levelState().cameraRenderState.pos;

        if (tracer) renderTracers(context, client, sparklings, config, partialTick, cameraPos);
        if (nameTag) renderNameTags(context, client, sparklings, partialTick, cameraPos);
    }

    private static void renderTracers(LevelRenderContext context, Minecraft client, List<Entity> sparklings,
                                      IslandTimeConfig config, float partialTick, Vec3 cameraPos) {
        int colorIdx = Math.max(0, Math.min(config.critterSafari.sparklingTracerColor,
                BlockHighlightRenderer.COLORS.length - 1));
        float[] color = BlockHighlightRenderer.COLORS[colorIdx];

        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4fc pose = poseStack.last().pose();

        VertexConsumer buffer = bufferSource.getBuffer(NO_DEPTH_LINES);
        Vec3 start = client.player.getEyePosition(partialTick);

        for (Entity entity : sparklings) {
            if (entity.isRemoved()) continue;
            Vec3 pos = entity.getPosition(partialTick);
            Vec3 end = new Vec3(pos.x, pos.y + entity.getBbHeight() / 2.0, pos.z);
            drawLine(pose, buffer, start, end, color[0], color[1], color[2]);
        }

        poseStack.popPose();
        bufferSource.endBatch(NO_DEPTH_LINES);
    }

    /**
     * Draws a large "SPARKLING" label above each tracked critter, billboarded towards the camera and
     * visible through walls, mirroring how vanilla renders name tags.
     */
    private static void renderNameTags(LevelRenderContext context, Minecraft client, List<Entity> sparklings,
                                       float partialTick, Vec3 cameraPos) {
        Font font = client.font;
        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        Component label = Component.literal(SPARKLING_MARKER);
        float halfWidth = font.width(label) / 2.0f;

        for (Entity entity : sparklings) {
            if (entity.isRemoved()) continue;
            Vec3 pos = entity.getPosition(partialTick);

            poseStack.pushPose();
            poseStack.translate(pos.x - cameraPos.x,
                    pos.y + entity.getBbHeight() + NAME_TAG_OFFSET - cameraPos.y,
                    pos.z - cameraPos.z);
            poseStack.mulPose(context.levelState().cameraRenderState.orientation);
            poseStack.scale(NAME_TAG_SCALE, -NAME_TAG_SCALE, NAME_TAG_SCALE);

            font.drawInBatch(label, -halfWidth, 0.0f, NAME_TAG_COLOR, false,
                    poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH,
                    NAME_TAG_BACKGROUND, FULL_BRIGHT);

            poseStack.popPose();
        }

        bufferSource.endBatch();
    }

    private static void drawLine(Matrix4fc pose, VertexConsumer buffer, Vec3 start, Vec3 end,
                                 float r, float g, float b) {
        Vec3 direction = end.subtract(start);
        double length = direction.length();
        if (length < 1.0e-4) return;
        Vec3 normal = direction.scale(1.0 / length);

        buffer.addVertex(pose, (float) start.x, (float) start.y, (float) start.z)
                .setColor(r, g, b, 1.0f)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z)
                .setLineWidth(LINE_WIDTH);
        buffer.addVertex(pose, (float) end.x, (float) end.y, (float) end.z)
                .setColor(r, g, b, 1.0f)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z)
                .setLineWidth(LINE_WIDTH);
    }
}
