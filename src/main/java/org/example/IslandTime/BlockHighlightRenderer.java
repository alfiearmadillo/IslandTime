package org.example.islandtime;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.authlib.properties.Property;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

import net.minecraft.nbt.CompoundTag;

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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Renders colored overlays on blocks matching configured targets.
 * Supports regular block IDs and skull texture hashes (skull:HASH).
 * Highlights use the block's actual collision/visual shape for accurate sizing.
 */
public class BlockHighlightRenderer {

    public static final String[] COLOR_NAMES = {"YELLOW", "RED", "GREEN", "BLUE", "MAGENTA", "CYAN", "WHITE"};
    public static final float[][] COLORS = {
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

    // Each highlighted position stores its color, alpha, bounds, and through-walls flag
    record HighlightBox(BlockPos pos, float r, float g, float b, float a,
                               float minX, float minY, float minZ,
                               float maxX, float maxY, float maxZ,
                               boolean throughWalls) {}

    private static List<HighlightBox> highlightedBoxes = List.of();

    // Caching
    private static BlockPos lastScanPos = null;
    private static String lastConfigFingerprint = "";
    private static long lastScanTick = -1;
    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final int RESCAN_MOVE_THRESHOLD_SQ = 64;

    // Async scanning
    private static final AtomicReference<List<HighlightBox>> pendingResult = new AtomicReference<>(null);
    private static CompletableFuture<Void> scanFuture = null;

    public static void register() {
        LevelRenderEvents.END_EXTRACTION.register(BlockHighlightRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(BlockHighlightRenderer::render);
    }

    /**
     * Parses a color string into [r, g, b]. Supports named colors and hex codes (#rrggbb).
     */
    public static float[] parseColor(String color) {
        if (color == null || color.isEmpty()) return null;
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
        String upper = color.toUpperCase();
        for (int i = 0; i < COLOR_NAMES.length; i++) {
            if (COLOR_NAMES[i].equals(upper)) {
                return COLORS[i];
            }
        }
        return null;
    }

    /**
     * Parsed scan target: either a set of block IDs or skull texture hashes with per-target settings.
     */
    private static class ScanTarget {
        final Map<Block, float[]> blockColors = new HashMap<>();
        final Map<Block, Float> blockAlpha = new HashMap<>();
        final Map<Block, Boolean> blockThroughWalls = new HashMap<>();
        final Map<Block, Integer> blockRange = new HashMap<>();
        final Map<String, float[]> skullHashColors = new HashMap<>();
        final Map<String, Float> skullHashAlpha = new HashMap<>();
        final Map<String, Boolean> skullHashThroughWalls = new HashMap<>();
        final Map<String, Integer> skullHashRange = new HashMap<>();
        final Map<String, float[]> skyblockIdColors = new HashMap<>();
        final Map<String, Float> skyblockIdAlpha = new HashMap<>();
        final Map<String, Boolean> skyblockIdThroughWalls = new HashMap<>();
        final Map<String, Integer> skyblockIdRange = new HashMap<>();
    }

    /**
     * Builds scan targets from both the config text field and BlockHighlightManager entries.
     */
    private static ScanTarget buildScanTargets(IslandTimeConfig config) {
        ScanTarget targets = new ScanTarget();

        // 1) Parse from config text field (legacy format)
        int colorIdx = Math.max(0, Math.min(config.blockHighlight.color, COLORS.length - 1));
        float[] defaultColor = COLORS[colorIdx];
        float defaultAlpha = config.blockHighlight.intensity / 125.0f;
        boolean defaultThroughWalls = config.blockHighlight.throughWalls;
        int defaultRange = config.blockHighlight.range;

        String blockIdField = config.blockHighlight.blockId;
        if (blockIdField != null && !blockIdField.isBlank()) {
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

                // Check if it's a skull target
                if (blockPart.startsWith("skull:")) {
                    String hash = blockPart.substring(6);
                    if (!hash.isEmpty()) {
                        targets.skullHashColors.put(hash, color);
                        targets.skullHashAlpha.put(hash, defaultAlpha);
                        targets.skullHashThroughWalls.put(hash, defaultThroughWalls);
                        targets.skullHashRange.put(hash, defaultRange);
                    }
                    continue;
                }

                // Check if it's a SkyBlock custom ID target
                if (blockPart.startsWith("skyblock:")) {
                    String sbId = blockPart.substring(9);
                    if (!sbId.isEmpty()) {
                        targets.skyblockIdColors.put(sbId, color);
                        targets.skyblockIdAlpha.put(sbId, defaultAlpha);
                        targets.skyblockIdThroughWalls.put(sbId, defaultThroughWalls);
                        targets.skyblockIdRange.put(sbId, defaultRange);
                    }
                    continue;
                }

                String normalized = blockPart.trim().toLowerCase();
                if (normalized.isEmpty()) continue;
                if (!normalized.contains(":")) {
                    normalized = "minecraft:" + normalized;
                }
                Identifier targetId = Identifier.tryParse(normalized);
                if (targetId == null) continue;
                if (!BuiltInRegistries.BLOCK.containsKey(targetId)) continue;
                Block block = BuiltInRegistries.BLOCK.getValue(targetId);
                targets.blockColors.put(block, color);
                targets.blockAlpha.put(block, defaultAlpha);
                targets.blockThroughWalls.put(block, defaultThroughWalls);
                targets.blockRange.put(block, defaultRange);
            }
        }

        // 2) Parse from BlockHighlightManager entries
        for (BlockHighlightManager.HighlightEntry he : BlockHighlightManager.getEntries()) {
            if (!he.enabled || he.intensity <= 0) continue;

            float[] color = parseColor(he.color);
            if (color == null) color = defaultColor;
            float alpha = he.intensity / 125.0f;

            if (he.isSkullTarget()) {
                String hash = he.getSkullHash();
                if (hash != null && !hash.isEmpty()) {
                    targets.skullHashColors.put(hash, color);
                    targets.skullHashAlpha.put(hash, alpha);
                    targets.skullHashThroughWalls.put(hash, he.throughWalls);
                    targets.skullHashRange.put(hash, he.range);
                }
            } else if (he.isSkyBlockTarget()) {
                String sbId = he.getSkyBlockId();
                if (sbId != null && !sbId.isEmpty()) {
                    targets.skyblockIdColors.put(sbId, color);
                    targets.skyblockIdAlpha.put(sbId, alpha);
                    targets.skyblockIdThroughWalls.put(sbId, he.throughWalls);
                    targets.skyblockIdRange.put(sbId, he.range);
                }
            } else {
                String blockId = he.getBlockId();
                if (blockId == null) continue;
                Identifier targetId = Identifier.tryParse(blockId);
                if (targetId == null) continue;
                if (!BuiltInRegistries.BLOCK.containsKey(targetId)) continue;
                Block block = BuiltInRegistries.BLOCK.getValue(targetId);
                targets.blockColors.put(block, color);
                targets.blockAlpha.put(block, alpha);
                targets.blockThroughWalls.put(block, he.throughWalls);
                targets.blockRange.put(block, he.range);
            }
        }

        return targets;
    }

    /**
     * Build a config fingerprint for cache invalidation.
     */
    private static String buildFingerprint(IslandTimeConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append(config.blockHighlight.blockId).append("|");
        sb.append(config.blockHighlight.intensity).append("|");
        sb.append(config.blockHighlight.color).append("|");
        sb.append(config.blockHighlight.throughWalls).append("|");
        sb.append(config.blockHighlight.range).append("|");
        // Include manager entries
        for (BlockHighlightManager.HighlightEntry e : BlockHighlightManager.getEntries()) {
            sb.append(e.target).append(",").append(e.color).append(",")
              .append(e.range).append(",").append(e.throughWalls).append(",")
              .append(e.enabled).append(",").append(e.intensity).append(";");
        }
        return sb.toString();
    }

    /**
     * Get the AABB of a block's visual/collision shape, used for hitbox-matched highlighting.
     */
    private static AABB getBlockBounds(BlockState state, Level level, BlockPos pos) {
        try {
            VoxelShape shape = state.getShape(level, pos);
            if (!shape.isEmpty()) {
                AABB bounds = shape.bounds();
                return bounds;
            }
        } catch (Exception ignored) {}
        // Fallback to full block
        return new AABB(0, 0, 0, 1, 1, 1);
    }

    /**
     * Extract the texture hash from a skull block entity.
     */
    private static String getSkullTextureHash(SkullBlockEntity skull) {
        try {
            var profile = skull.getOwnerProfile();
            if (profile != null) {
                for (Property prop : profile.properties()) {
                    if (!"textures".equals(prop.name())) continue;
                    String decoded = new String(Base64.getDecoder().decode(prop.value()));
                    int urlIdx = decoded.indexOf("textures.minecraft.net/texture/");
                    if (urlIdx >= 0) {
                        int hashStart = urlIdx + "textures.minecraft.net/texture/".length();
                        int hashEnd = decoded.indexOf('"', hashStart);
                        if (hashEnd < 0) hashEnd = decoded.indexOf('}', hashStart);
                        if (hashEnd > hashStart) {
                            return decoded.substring(hashStart, hashEnd);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Extract a SkyBlock custom item ID from a block entity's NBT data.
     * Checks custom_data.id and PublicBukkitValues."hypixel:id".
     */
    private static String extractSkyBlockId(BlockEntity be, Level level) {
        try {
            CompoundTag nbt = be.saveWithoutMetadata(level.registryAccess());
            if (nbt.contains("custom_data")) {
                CompoundTag customData = nbt.getCompound("custom_data").orElse(null);
                if (customData != null && customData.contains("id")) {
                    return customData.getString("id").orElse(null);
                }
            }
            if (nbt.contains("PublicBukkitValues")) {
                CompoundTag bukkit = nbt.getCompound("PublicBukkitValues").orElse(null);
                if (bukkit != null && bukkit.contains("hypixel:id")) {
                    return bukkit.getString("hypixel:id").orElse(null);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void extract(LevelExtractionContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            highlightedBoxes = List.of();
            return;
        }

        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null) {
            highlightedBoxes = List.of();
            return;
        }

        // Check if anything is configured at all
        boolean hasConfigEntries = config.blockHighlight.enabled
                && config.blockHighlight.intensity > 0
                && config.blockHighlight.blockId != null
                && !config.blockHighlight.blockId.isBlank();
        boolean hasManagerEntries = BlockHighlightManager.getEntries().stream()
                .anyMatch(e -> e.enabled && e.intensity > 0);

        if (!hasConfigEntries && !hasManagerEntries) {
            highlightedBoxes = List.of();
            return;
        }

        // Pick up completed async scan results
        List<HighlightBox> completed = pendingResult.getAndSet(null);
        if (completed != null) {
            highlightedBoxes = completed;
        }

        // Determine if we need to rescan
        BlockPos playerPos = client.player.blockPosition();
        long currentTick = client.level.getGameTime();
        String fingerprint = buildFingerprint(config);

        boolean configChanged = !fingerprint.equals(lastConfigFingerprint);
        boolean moved = lastScanPos == null || playerPos.distSqr(lastScanPos) >= RESCAN_MOVE_THRESHOLD_SQ;
        boolean timedOut = (currentTick - lastScanTick) >= SCAN_INTERVAL_TICKS;
        boolean scanRunning = scanFuture != null && !scanFuture.isDone();

        if (!scanRunning && (configChanged || moved || timedOut)) {
            lastScanPos = playerPos.immutable();
            lastConfigFingerprint = fingerprint;
            lastScanTick = currentTick;

            ScanTarget targets = buildScanTargets(config);

            if (targets.blockColors.isEmpty() && targets.skullHashColors.isEmpty() && targets.skyblockIdColors.isEmpty()) {
                highlightedBoxes = List.of();
                return;
            }

            // Determine max range
            int maxRange = 0;
            for (int r : targets.blockRange.values()) maxRange = Math.max(maxRange, r);
            for (int r : targets.skullHashRange.values()) maxRange = Math.max(maxRange, r);
            for (int r : targets.skyblockIdRange.values()) maxRange = Math.max(maxRange, r);

            Set<Block> targetBlocks = targets.blockColors.keySet();
            boolean hasSkullTargets = !targets.skullHashColors.isEmpty();
            boolean hasSkyBlockTargets = !targets.skyblockIdColors.isEmpty();

            // If we have skull targets, also scan for player_head/player_wall_head blocks
            Set<Block> allScanBlocks = new HashSet<>(targetBlocks);
            if (hasSkullTargets) {
                Identifier headId = Identifier.tryParse("minecraft:player_head");
                Identifier wallHeadId = Identifier.tryParse("minecraft:player_wall_head");
                if (headId != null && BuiltInRegistries.BLOCK.containsKey(headId)) {
                    allScanBlocks.add(BuiltInRegistries.BLOCK.getValue(headId));
                }
                if (wallHeadId != null && BuiltInRegistries.BLOCK.containsKey(wallHeadId)) {
                    allScanBlocks.add(BuiltInRegistries.BLOCK.getValue(wallHeadId));
                }
            }

            Level level = client.level;
            BlockPos scanCenter = lastScanPos;
            int scanRadius = maxRange;

            // Capture skull hashes for async use
            Map<String, float[]> skullColors = new HashMap<>(targets.skullHashColors);
            Map<String, Float> skullAlphas = new HashMap<>(targets.skullHashAlpha);
            Map<String, Boolean> skullWalls = new HashMap<>(targets.skullHashThroughWalls);
            Map<String, Integer> skullRanges = new HashMap<>(targets.skullHashRange);
            Map<String, float[]> sbIdColors = new HashMap<>(targets.skyblockIdColors);
            Map<String, Float> sbIdAlphas = new HashMap<>(targets.skyblockIdAlpha);
            Map<String, Boolean> sbIdWalls = new HashMap<>(targets.skyblockIdThroughWalls);
            Map<String, Integer> sbIdRanges = new HashMap<>(targets.skyblockIdRange);
            Map<Block, float[]> bColors = new HashMap<>(targets.blockColors);
            Map<Block, Float> bAlphas = new HashMap<>(targets.blockAlpha);
            Map<Block, Boolean> bWalls = new HashMap<>(targets.blockThroughWalls);
            Map<Block, Integer> bRanges = new HashMap<>(targets.blockRange);

            final boolean capturedHasSkyBlockTargets = hasSkyBlockTargets;

            scanFuture = CompletableFuture.runAsync(() -> {
                List<HighlightBox> boxes = new ArrayList<>();

                Set<BlockState> targetStates = new HashSet<>();
                for (Block block : allScanBlocks) {
                    targetStates.addAll(block.getStateDefinition().getPossibleStates());
                }

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
                                            BlockPos bpos = new BlockPos(bx, by, bz);
                                            BlockState state = level.getBlockState(bpos);
                                            Block block = state.getBlock();

                                            // Check direct block match
                                            float[] color = bColors.get(block);
                                            if (color != null) {
                                                int range = bRanges.getOrDefault(block, scanRadius);
                                                if (bpos.distSqr(scanCenter) <= (long) range * range) {
                                                    float alpha = bAlphas.getOrDefault(block, 0.4f);
                                                    boolean tw = bWalls.getOrDefault(block, true);
                                                    AABB bounds = getBlockBounds(state, level, bpos);
                                                    boxes.add(new HighlightBox(bpos,
                                                            color[0], color[1], color[2], alpha,
                                                            (float) bounds.minX, (float) bounds.minY, (float) bounds.minZ,
                                                            (float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ,
                                                            tw));
                                                }
                                                continue; // Don't also check skull
                                            }

                                            // Check skull texture hash match
                                            if (!skullColors.isEmpty()) {
                                                BlockEntity be = level.getBlockEntity(bpos);
                                                if (be instanceof SkullBlockEntity skull) {
                                                    String hash = getSkullTextureHash(skull);
                                                    if (hash != null) {
                                                        float[] skullColor = skullColors.get(hash);
                                                        if (skullColor != null) {
                                                            int range = skullRanges.getOrDefault(hash, scanRadius);
                                                            if (bpos.distSqr(scanCenter) <= (long) range * range) {
                                                                float alpha = skullAlphas.getOrDefault(hash, 0.4f);
                                                                boolean tw = skullWalls.getOrDefault(hash, true);
                                                                AABB bounds = getBlockBounds(state, level, bpos);
                                                                boxes.add(new HighlightBox(bpos,
                                                                        skullColor[0], skullColor[1], skullColor[2], alpha,
                                                                        (float) bounds.minX, (float) bounds.minY, (float) bounds.minZ,
                                                                        (float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ,
                                                                        tw));
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // SkyBlock ID matching: iterate chunk block entities directly
                if (capturedHasSkyBlockTargets) {
                    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                            LevelChunk chunk;
                            try {
                                chunk = level.getChunk(cx, cz);
                            } catch (Exception e) {
                                continue;
                            }
                            if (chunk == null) continue;

                            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                                try {
                                    BlockPos bpos = entry.getKey();
                                    if (bpos.getX() < minX || bpos.getX() > maxX
                                            || bpos.getY() < minY || bpos.getY() > maxY
                                            || bpos.getZ() < minZ || bpos.getZ() > maxZ) continue;

                                    BlockEntity be = entry.getValue();
                                    String sbId = extractSkyBlockId(be, level);
                                    if (sbId == null) continue;

                                    float[] sbColor = sbIdColors.get(sbId);
                                    if (sbColor == null) {
                                        // Try case-insensitive match
                                        for (Map.Entry<String, float[]> e2 : sbIdColors.entrySet()) {
                                            if (e2.getKey().equalsIgnoreCase(sbId)) {
                                                sbColor = e2.getValue();
                                                sbId = e2.getKey(); // use the key for lookups below
                                                break;
                                            }
                                        }
                                    }
                                    if (sbColor == null) continue;

                                    int range = sbIdRanges.getOrDefault(sbId, scanRadius);
                                    if (bpos.distSqr(scanCenter) > (long) range * range) continue;

                                    float alpha = sbIdAlphas.getOrDefault(sbId, 0.4f);
                                    boolean tw = sbIdWalls.getOrDefault(sbId, true);
                                    BlockState state = level.getBlockState(bpos);
                                    AABB bounds = getBlockBounds(state, level, bpos);
                                    boxes.add(new HighlightBox(bpos,
                                            sbColor[0], sbColor[1], sbColor[2], alpha,
                                            (float) bounds.minX, (float) bounds.minY, (float) bounds.minZ,
                                            (float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ,
                                            tw));
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                }

                pendingResult.set(boxes);
            });
        }
    }

    private static void render(LevelRenderContext context) {
        if (highlightedBoxes.isEmpty()) return;

        PoseStack poseStack = context.poseStack();
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();

        var cameraState = context.levelState().cameraRenderState;
        double camX = cameraState.pos.x;
        double camY = cameraState.pos.y;
        double camZ = cameraState.pos.z;

        poseStack.pushPose();
        poseStack.translate(-camX, -camY, -camZ);

        Matrix4fc pose = poseStack.last().pose();

        // Render through-walls boxes
        VertexConsumer noDepthBuffer = bufferSource.getBuffer(NO_DEPTH_FILLED_BOX);
        VertexConsumer depthBuffer = bufferSource.getBuffer(RenderTypes.debugFilledBox());

        Minecraft client = Minecraft.getInstance();
        Level level = client.level;

        for (HighlightBox box : highlightedBoxes) {
            BlockPos pos = box.pos;
            float pad = 0.001f;
            float x0 = pos.getX() + box.minX - pad;
            float y0 = pos.getY() + box.minY - pad;
            float z0 = pos.getZ() + box.minZ - pad;
            float x1 = pos.getX() + box.maxX + pad;
            float y1 = pos.getY() + box.maxY + pad;
            float z1 = pos.getZ() + box.maxZ + pad;

            VertexConsumer buffer = box.throughWalls ? noDepthBuffer : depthBuffer;

            if (box.throughWalls) {
                renderFilledBox(pose, buffer, x0, y0, z0, x1, y1, z1,
                        box.r, box.g, box.b, box.a);
            } else if (level != null) {
                // For non-through-walls, only render exposed faces
                renderExposedFaces(pose, buffer, pos, level,
                        x0, y0, z0, x1, y1, z1,
                        box.r, box.g, box.b, box.a);
            }
        }

        poseStack.popPose();
        bufferSource.endBatch();
    }

    private static void renderExposedFaces(Matrix4fc pose, VertexConsumer buffer,
                                            BlockPos pos, Level level,
                                            float x0, float y0, float z0,
                                            float x1, float y1, float z1,
                                            float r, float g, float b, float a) {
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

    static void renderFilledBox(Matrix4fc positionMatrix, VertexConsumer buffer,
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
