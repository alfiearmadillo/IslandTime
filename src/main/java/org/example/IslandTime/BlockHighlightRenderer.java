package org.example.islandtime;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

import net.minecraft.network.chat.Component;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Renders colored overlays on blocks and entities matching configured targets.
 * Supports regular block IDs, entity IDs (entity:TYPE) with optional name filters
 * (entity:TYPE[name=TEXT]), and skull texture hashes (skull:HASH). Entries can match IDs and
 * names partially ("contains") instead of exactly.
 * Highlights use the block's actual collision/visual shape or an entity's bounding box; entries can
 * give hitbox-less entities (marker armor stands, ...) a 1 block box so they are visible.
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

    record EntityHighlightBox(double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ,
                              float r, float g, float b, float a,
                              boolean throughWalls) {}

    /**
     * An entity highlight rule. A rule matches an entity when its type matches (or the rule has no
     * type) and its name matches the filter (or the rule has no name filter).
     *
     * @param typeId      registry ID such as "minecraft:armor_stand" (or, when partialType, the text the
     *                    ID must contain), or null for any entity type
     * @param nameFilter  lower-case text to match the entity's name against, or null for any name
     * @param partialType match the type ID by "contains" instead of exactly
     * @param partialName match the name by "contains" instead of exactly
     * @param minSize     highlight entities that have no hitbox with a 1 block box
     */
    record EntityRule(String typeId, String nameFilter, boolean partialType, boolean partialName,
                      boolean minSize, float[] color, float alpha, boolean throughWalls, int range) {
        /**
         * Higher scores are more specific; a more specific rule wins when several match. A name
         * beats a type, and an exact match beats a partial one.
         */
        int specificity() {
            int type = typeId == null ? 0 : (partialType ? 1 : 2);
            int name = nameFilter == null ? 0 : (partialName ? 3 : 4);
            return type + name;
        }
    }

    /**
     * A skull hash or SkyBlock ID rule that matches by "contains" instead of exactly.
     *
     * @param needle lower-case text the ID must contain
     */
    record IdRule(String needle, float[] color, float alpha, boolean throughWalls, int range) {}

    /** Entity box edges shorter than this count as "no hitbox" (marker armor stands, markers, displays). */
    static final double MIN_HITBOX_EDGE = 0.01;

    /** Edge length given to the missing sides of a hitbox-less entity when the min-size toggle is on. */
    private static final double MIN_SIZE_EDGE = 1.0;

    /** Most block highlight boxes a scan keeps, so very broad partial matches can't freeze the game. */
    private static final int MAX_BLOCK_BOXES = 20000;

    private static List<HighlightBox> highlightedBoxes = List.of();
    private static List<EntityHighlightBox> highlightedEntityBoxes = List.of();

    /**
     * An entity a highlight rule matched. Only the entity and its rule are kept; the box is rebuilt
     * from the entity's position every frame so the highlight follows it smoothly.
     */
    record TrackedEntity(Entity entity, EntityRule rule) {}

    // Entity tracking: rules come from the periodic rescan, matching runs on its own shorter cadence
    // and the boxes are rebuilt every frame
    private static List<EntityRule> entityRules = List.of();
    private static List<TrackedEntity> trackedEntities = List.of();
    private static ClientLevel trackedLevel = null;
    private static long lastEntityMatchTick = -1;
    private static final int ENTITY_MATCH_INTERVAL_TICKS = 1;
    /** Most entities tracked at once, so a very broad name or type match can't slow every frame down. */
    private static final int MAX_TRACKED_ENTITIES = 2000;

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
     * Parsed scan target: block IDs, entity IDs, or skull texture hashes with per-target settings.
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
        final List<IdRule> skullPartialRules = new ArrayList<>();
        final List<IdRule> skyblockPartialRules = new ArrayList<>();
        final List<EntityRule> entityRules = new ArrayList<>();
    }

    /**
     * Detects the current SkyBlock area from the tab menu.
     * Looks for a line containing "Area: " or "⏣ " pattern in tab list display names.
     */
    static String detectCurrentArea() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) return null;
        for (PlayerInfo entry : client.getConnection().getOnlinePlayers()) {
            Component displayName = entry.getTabListDisplayName();
            if (displayName == null) continue;
            String text = displayName.getString();
            if (text == null) continue;
            // SkyBlock tab menu has "Area: <name>" or " ⏣ <name>" lines
            int areaIdx = text.indexOf("Area: ");
            if (areaIdx >= 0) {
                return text.substring(areaIdx + 6).trim();
            }
            // Also check for the ⏣ symbol used by SkyBlock for area
            int symbolIdx = text.indexOf("⏣ ");
            if (symbolIdx >= 0) {
                return text.substring(symbolIdx + 2).trim();
            }
        }
        return null;
    }

    /**
     * Builds scan targets from BlockHighlightManager entries.
     */
    private static ScanTarget buildScanTargets(IslandTimeConfig config) {
        ScanTarget targets = new ScanTarget();

        int colorIdx = Math.max(0, Math.min(config.blockHighlight.color, COLORS.length - 1));
        float[] defaultColor = COLORS[colorIdx];

        // Detect current area from tab menu
        String currentArea = detectCurrentArea();

        // Partial entries go first so an exact entry for the same block overrides them
        List<BlockHighlightManager.HighlightEntry> ordered = new ArrayList<>();
        for (BlockHighlightManager.HighlightEntry he : BlockHighlightManager.getEntries()) {
            if (he.isPartialIds()) ordered.add(he);
        }
        for (BlockHighlightManager.HighlightEntry he : BlockHighlightManager.getEntries()) {
            if (!he.isPartialIds()) ordered.add(he);
        }

        // Parse from BlockHighlightManager entries
        for (BlockHighlightManager.HighlightEntry he : ordered) {
            if (!he.enabled || he.intensity <= 0) continue;

            // Area filtering: if entry has an area set, only include if it matches current area
            if (he.area != null && !he.area.isBlank()) {
                if (currentArea == null || !he.area.equalsIgnoreCase(currentArea)) continue;
            }

            float[] color = parseColor(he.color);
            if (color == null) color = defaultColor;
            float alpha = he.intensity / 125.0f;
            boolean partial = he.isPartialIds();

            if (he.isSkullTarget()) {
                String hash = he.getSkullHash();
                if (hash != null && !hash.isEmpty()) {
                    if (partial) {
                        targets.skullPartialRules.add(new IdRule(hash.toLowerCase(Locale.ROOT),
                                color, alpha, he.throughWalls, he.range));
                    } else {
                        targets.skullHashColors.put(hash, color);
                        targets.skullHashAlpha.put(hash, alpha);
                        targets.skullHashThroughWalls.put(hash, he.throughWalls);
                        targets.skullHashRange.put(hash, he.range);
                    }
                }
            } else if (he.isSkyBlockTarget()) {
                String sbId = he.getSkyBlockId();
                if (sbId != null && !sbId.isEmpty()) {
                    if (partial) {
                        targets.skyblockPartialRules.add(new IdRule(sbId.toLowerCase(Locale.ROOT),
                                color, alpha, he.throughWalls, he.range));
                    } else {
                        targets.skyblockIdColors.put(sbId, color);
                        targets.skyblockIdAlpha.put(sbId, alpha);
                        targets.skyblockIdThroughWalls.put(sbId, he.throughWalls);
                        targets.skyblockIdRange.put(sbId, he.range);
                    }
                }
            } else if (he.isEntityTarget()) {
                addEntityTarget(targets, he.getEntityId(), color, alpha, he.throughWalls, he.range,
                        partial, he.isPartialNames(), he.minSizeBox);
            } else if (partial) {
                String text = he.target == null ? "" : he.target.trim();
                if (text.isEmpty()) continue;
                for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
                    if (!BlockHighlightManager.idMatches(text, id.toString(), true)) continue;
                    Block block = BuiltInRegistries.BLOCK.getValue(id);
                    // Air is everywhere, so a broad text like "air" would otherwise highlight the whole world
                    if (block.defaultBlockState().isAir()) continue;
                    targets.blockColors.put(block, color);
                    targets.blockAlpha.put(block, alpha);
                    targets.blockThroughWalls.put(block, he.throughWalls);
                    targets.blockRange.put(block, he.range);
                }
            } else {
                String blockId = he.getBlockId();
                if (blockId == null) continue;
                Identifier targetId = Identifier.tryParse(blockId);
                if (targetId == null) continue;
                if (BuiltInRegistries.BLOCK.containsKey(targetId)) {
                    Block block = BuiltInRegistries.BLOCK.getValue(targetId);
                    targets.blockColors.put(block, color);
                    targets.blockAlpha.put(block, alpha);
                    targets.blockThroughWalls.put(block, he.throughWalls);
                    targets.blockRange.put(block, he.range);
                } else {
                    // Allow entity IDs without the optional entity: prefix as well.
                    addEntityTarget(targets, targetId.toString(), color, alpha, he.throughWalls, he.range,
                            false, false, he.minSizeBox);
                }
            }
        }

        return targets;
    }

    /**
     * Adds an entity rule from an entity spec: "TYPE", "TYPE[name=TEXT]" or "*[name=TEXT]".
     * With partialType the type is any text contained in an entity type ID instead of a registered ID.
     */
    private static void addEntityTarget(ScanTarget targets, String entitySpec, float[] color,
                                        float alpha, boolean throughWalls, int range,
                                        boolean partialType, boolean partialName, boolean minSize) {
        if (!BlockHighlightManager.isValidEntitySpec(entitySpec)) return;

        String typeKey = null;
        String typeId = BlockHighlightManager.parseEntityTypeId(entitySpec);
        if (typeId != null) {
            typeId = typeId.toLowerCase(Locale.ROOT);
            if (partialType) {
                typeKey = typeId;
            } else {
                if (!typeId.contains(":")) {
                    typeId = "minecraft:" + typeId;
                }
                Identifier id = Identifier.tryParse(typeId);
                if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return;
                typeKey = id.toString();
            }
        }

        String nameFilter = BlockHighlightManager.parseEntityNameFilter(entitySpec);
        if (nameFilter != null) {
            nameFilter = normalizeEntityName(nameFilter);
        }
        targets.entityRules.add(new EntityRule(typeKey, nameFilter, partialType, partialName, minSize,
                color, alpha, throughWalls, range));
    }

    /**
     * Normalizes an entity name for case-insensitive matching: drops formatting codes and lower-cases.
     */
    private static String normalizeEntityName(String name) {
        String stripped = ChatFormatting.stripFormatting(name);
        return (stripped != null ? stripped : name).toLowerCase(Locale.ROOT);
    }

    /**
     * Finds the most specific rule matching the entity, or null if none match.
     * When several rules are equally specific, the one added last wins.
     */
    private static EntityRule findEntityRule(List<EntityRule> rules, Entity entity, String entityTypeId,
                                             BlockPos scanCenter) {
        EntityRule best = null;
        int bestSpecificity = 0;
        String name = null;
        for (EntityRule rule : rules) {
            if (rule.typeId() != null
                    && !BlockHighlightManager.idMatches(rule.typeId(), entityTypeId, rule.partialType())) continue;
            int specificity = rule.specificity();
            if (specificity < bestSpecificity) continue;
            if (entity.blockPosition().distSqr(scanCenter) > (long) rule.range() * rule.range()) continue;
            if (rule.nameFilter() != null) {
                if (name == null) {
                    name = normalizeEntityName(entity.getName().getString());
                }
                if (!BlockHighlightManager.nameMatches(rule.nameFilter(), name, rule.partialName())) continue;
            }
            best = rule;
            bestSpecificity = specificity;
        }
        return best;
    }

    /**
     * Build a config fingerprint for cache invalidation.
     */
    private static String buildFingerprint(IslandTimeConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append(config.blockHighlight.intensity).append("|");
        sb.append(config.blockHighlight.color).append("|");
        sb.append(config.blockHighlight.throughWalls).append("|");
        sb.append(config.blockHighlight.range).append("|");
        // Include current area
        String area = detectCurrentArea();
        sb.append(area != null ? area : "").append("|");
        // Include manager entries
        for (BlockHighlightManager.HighlightEntry e : BlockHighlightManager.getEntries()) {
            sb.append(e.target).append(",").append(e.color).append(",")
              .append(e.range).append(",").append(e.throughWalls).append(",")
              .append(e.enabled).append(",").append(e.intensity).append(",")
              .append(e.area != null ? e.area : "").append(",")
              .append(e.partialMatch).append(",").append(e.minSizeBox).append(";");
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
                for (Property prop : profile.partialProfile().properties().get("textures")) {
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
        // Check skull profile signature for SkyBlock item ID (e.g. "DITTO_BLOB")
        if (be instanceof SkullBlockEntity skull) {
            try {
                var profile = skull.getOwnerProfile();
                if (profile != null) {
                    var gp = profile.partialProfile();
                    for (com.mojang.authlib.properties.Property prop : gp.properties().get("textures")) {
                        String sig = prop.signature();
                        if (sig != null && !sig.isEmpty() && sig.matches("[A-Z0-9_]+")) {
                            return sig;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void extract(LevelExtractionContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            highlightedBoxes = List.of();
            clearEntityHighlights();
            return;
        }

        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null) {
            highlightedBoxes = List.of();
            clearEntityHighlights();
            return;
        }

        // Check if anything is configured at all
        boolean hasManagerEntries = config.blockHighlight.enabled
                && BlockHighlightManager.getEntries().stream()
                .anyMatch(e -> e.enabled && e.intensity > 0);

        if (!hasManagerEntries) {
            highlightedBoxes = List.of();
            clearEntityHighlights();
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

            // Entities are matched against these rules separately and more often, see updateEntityHighlights
            entityRules = List.copyOf(targets.entityRules);
            lastEntityMatchTick = -1;

            if (targets.blockColors.isEmpty() && targets.skullHashColors.isEmpty()
                    && targets.skyblockIdColors.isEmpty() && targets.skullPartialRules.isEmpty()
                    && targets.skyblockPartialRules.isEmpty() && targets.entityRules.isEmpty()) {
                highlightedBoxes = List.of();
                clearEntityHighlights();
                return;
            }

            // Determine max range
            int maxRange = 0;
            for (int r : targets.blockRange.values()) maxRange = Math.max(maxRange, r);
            for (int r : targets.skullHashRange.values()) maxRange = Math.max(maxRange, r);
            for (int r : targets.skyblockIdRange.values()) maxRange = Math.max(maxRange, r);
            for (IdRule rule : targets.skullPartialRules) maxRange = Math.max(maxRange, rule.range());
            for (IdRule rule : targets.skyblockPartialRules) maxRange = Math.max(maxRange, rule.range());
            for (EntityRule rule : targets.entityRules) maxRange = Math.max(maxRange, rule.range());

            Set<Block> targetBlocks = targets.blockColors.keySet();
            boolean hasSkullTargets = !targets.skullHashColors.isEmpty() || !targets.skullPartialRules.isEmpty();
            boolean hasSkyBlockTargets = !targets.skyblockIdColors.isEmpty() || !targets.skyblockPartialRules.isEmpty();

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

            ClientLevel level = client.level;
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
            List<IdRule> skullPartial = new ArrayList<>(targets.skullPartialRules);
            List<IdRule> sbIdPartial = new ArrayList<>(targets.skyblockPartialRules);
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
                                                    addBox(boxes, new HighlightBox(bpos,
                                                            color[0], color[1], color[2], alpha,
                                                            (float) bounds.minX, (float) bounds.minY, (float) bounds.minZ,
                                                            (float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ,
                                                            tw));
                                                }
                                                continue; // Don't also check skull
                                            }

                                            // Check skull texture hash match
                                            if (!skullColors.isEmpty() || !skullPartial.isEmpty()) {
                                                BlockEntity be = level.getBlockEntity(bpos);
                                                if (be instanceof SkullBlockEntity skull) {
                                                    String hash = getSkullTextureHash(skull);
                                                    if (hash != null) {
                                                        float[] skullColor = skullColors.get(hash);
                                                        int range = skullRanges.getOrDefault(hash, scanRadius);
                                                        float alpha = skullAlphas.getOrDefault(hash, 0.4f);
                                                        boolean tw = skullWalls.getOrDefault(hash, true);
                                                        if (skullColor == null) {
                                                            IdRule rule = findIdRule(skullPartial, hash);
                                                            if (rule != null) {
                                                                skullColor = rule.color();
                                                                range = rule.range();
                                                                alpha = rule.alpha();
                                                                tw = rule.throughWalls();
                                                            }
                                                        }
                                                        if (skullColor != null
                                                                && bpos.distSqr(scanCenter) <= (long) range * range) {
                                                            AABB bounds = getBlockBounds(state, level, bpos);
                                                            addBox(boxes, new HighlightBox(bpos,
                                                                    skullColor[0], skullColor[1], skullColor[2], alpha,
                                                                    (float) bounds.minX, (float) bounds.minY, (float) bounds.minZ,
                                                                    (float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ,
                                                                    tw));
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

                                    int range = sbIdRanges.getOrDefault(sbId, scanRadius);
                                    float alpha = sbIdAlphas.getOrDefault(sbId, 0.4f);
                                    boolean tw = sbIdWalls.getOrDefault(sbId, true);
                                    if (sbColor == null) {
                                        IdRule rule = findIdRule(sbIdPartial, sbId);
                                        if (rule == null) continue;
                                        sbColor = rule.color();
                                        range = rule.range();
                                        alpha = rule.alpha();
                                        tw = rule.throughWalls();
                                    }

                                    if (bpos.distSqr(scanCenter) > (long) range * range) continue;

                                    BlockState state = level.getBlockState(bpos);
                                    AABB bounds = getBlockBounds(state, level, bpos);
                                    addBox(boxes, new HighlightBox(bpos,
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

        updateEntityHighlights(client, client.level, playerPos, currentTick, config);
    }

    /**
     * Finds the partial rule whose text is contained in the ID (case-insensitive), or null.
     * When several match, the one added last wins.
     */
    private static IdRule findIdRule(List<IdRule> rules, String id) {
        if (id == null) return null;
        String lower = id.toLowerCase(Locale.ROOT);
        IdRule match = null;
        for (IdRule rule : rules) {
            if (lower.contains(rule.needle())) match = rule;
        }
        return match;
    }

    /** Adds a block box unless the scan already holds {@link #MAX_BLOCK_BOXES}. */
    private static void addBox(List<HighlightBox> boxes, HighlightBox box) {
        if (boxes.size() < MAX_BLOCK_BOXES) boxes.add(box);
    }

    /**
     * Returns true if the box has no size on at least one axis, like marker armor stands, marker
     * entities and display entities, so the highlight would be an invisible sliver.
     */
    static boolean hasNoHitbox(AABB box) {
        return box.getXsize() < MIN_HITBOX_EDGE
                || box.getYsize() < MIN_HITBOX_EDGE
                || box.getZsize() < MIN_HITBOX_EDGE;
    }

    /**
     * Grows every axis of the box that has no size to {@link #MIN_SIZE_EDGE} around the box centre.
     * Axes that do have a size are left alone.
     */
    private static AABB withMinSize(AABB box) {
        double minX = box.minX, maxX = box.maxX;
        double minY = box.minY, maxY = box.maxY;
        double minZ = box.minZ, maxZ = box.maxZ;
        double half = MIN_SIZE_EDGE / 2.0;
        if (maxX - minX < MIN_HITBOX_EDGE) {
            double c = (minX + maxX) / 2.0;
            minX = c - half;
            maxX = c + half;
        }
        if (maxY - minY < MIN_HITBOX_EDGE) {
            double c = (minY + maxY) / 2.0;
            minY = c - half;
            maxY = c + half;
        }
        if (maxZ - minZ < MIN_HITBOX_EDGE) {
            double c = (minZ + maxZ) / 2.0;
            minZ = c - half;
            maxZ = c + half;
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void clearEntityHighlights() {
        trackedEntities = List.of();
        highlightedEntityBoxes = List.of();
        trackedLevel = null;
        lastEntityMatchTick = -1;
    }

    /**
     * Keeps entity highlights up to date. Which entities match is worked out every game tick (so new,
     * renamed or out-of-range entities are picked up straight away), and the boxes are rebuilt every frame
     * from the matched entities so they follow them smoothly instead of jumping at each rescan.
     * Runs while extracting the frame; the renderer only draws the finished boxes.
     */
    private static void updateEntityHighlights(Minecraft client, ClientLevel level, BlockPos playerPos,
                                               long currentTick, IslandTimeConfig config) {
        if (entityRules.isEmpty()) {
            if (!trackedEntities.isEmpty() || !highlightedEntityBoxes.isEmpty()) clearEntityHighlights();
            return;
        }

        // A new world also means every tracked entity is gone; the game time can jump backwards there too
        boolean rematch = trackedLevel != level
                || lastEntityMatchTick < 0
                || currentTick < lastEntityMatchTick
                || currentTick - lastEntityMatchTick >= ENTITY_MATCH_INTERVAL_TICKS;
        if (rematch) {
            trackedEntities = matchEntities(level, playerPos, entityRules);
            trackedLevel = level;
            lastEntityMatchTick = currentTick;
        }

        if (trackedEntities.isEmpty()) {
            if (!highlightedEntityBoxes.isEmpty()) highlightedEntityBoxes = List.of();
            return;
        }

        boolean smooth = config.blockHighlight.smoothEntities;
        float partialTick = smooth ? client.getDeltaTracker().getGameTimeDeltaPartialTick(false) : 1.0f;

        List<EntityHighlightBox> boxes = new ArrayList<>(trackedEntities.size());
        for (TrackedEntity tracked : trackedEntities) {
            Entity entity = tracked.entity();
            // Entities that died or left the loaded area lose their highlight straight away
            if (entity.isRemoved()) continue;
            try {
                // The bounding box sits at the entity's tick position; shift it to where the entity
                // is drawn this frame, interpolated with the same partial tick the renderer uses
                Vec3 tickPos = entity.position();
                Vec3 drawPos = smooth ? entity.getPosition(partialTick) : tickPos;
                boxes.add(buildEntityBox(entity.getBoundingBox(),
                        drawPos.x - tickPos.x, drawPos.y - tickPos.y, drawPos.z - tickPos.z,
                        tracked.rule()));
            } catch (Exception ignored) {
            }
        }
        highlightedEntityBoxes = boxes;
    }

    /**
     * Returns the entities that match a rule, each with its most specific matching rule.
     */
    private static List<TrackedEntity> matchEntities(ClientLevel level, BlockPos center, List<EntityRule> rules) {
        List<TrackedEntity> matched = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (matched.size() >= MAX_TRACKED_ENTITIES) break;
            try {
                if (entity.isRemoved()) continue;
                String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                EntityRule rule = findEntityRule(rules, entity, entityId, center);
                if (rule != null) matched.add(new TrackedEntity(entity, rule));
            } catch (Exception ignored) {
            }
        }
        return matched;
    }

    /**
     * Builds the highlight box for an entity from its bounding box, shifted by the given offset
     * (the distance between its tick position and its interpolated draw position).
     */
    static EntityHighlightBox buildEntityBox(AABB bounds, double dx, double dy, double dz, EntityRule rule) {
        if (rule.minSize() && hasNoHitbox(bounds)) bounds = withMinSize(bounds);
        bounds = bounds.inflate(0.01);
        float[] color = rule.color();
        return new EntityHighlightBox(bounds.minX + dx, bounds.minY + dy, bounds.minZ + dz,
                bounds.maxX + dx, bounds.maxY + dy, bounds.maxZ + dz,
                color[0], color[1], color[2],
                rule.alpha(),
                rule.throughWalls());
    }

    private static void render(LevelRenderContext context) {
        if (highlightedBoxes.isEmpty() && highlightedEntityBoxes.isEmpty()) return;

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

        for (EntityHighlightBox box : highlightedEntityBoxes) {
            VertexConsumer buffer = box.throughWalls() ? noDepthBuffer : depthBuffer;
            renderFilledBox(pose, buffer,
                    (float) box.minX(), (float) box.minY(), (float) box.minZ(),
                    (float) box.maxX(), (float) box.maxY(), (float) box.maxZ(),
                    box.r(), box.g(), box.b(), box.a());
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
