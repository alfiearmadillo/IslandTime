package org.example.islandtime;

import com.mojang.authlib.properties.Property;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Base64;

/**
 * Handles the /it waila command — "What Am I Looking At".
 * Inspects any targeted block and displays detailed info in chat,
 * including block type, state properties, position, tile entity data,
 * and Hypixel SkyBlock custom data (e.g. skull textures/IDs).
 */
public class WailaHandler {

    public static void inspect(Minecraft client) {
        if (client.level == null || client.player == null) {
            client.player.sendSystemMessage(
                    Component.literal("Not in a world.").withStyle(ChatFormatting.RED));
            return;
        }

        if (client.hitResult == null || client.hitResult.getType() != HitResult.Type.BLOCK) {
            client.player.sendSystemMessage(
                    Component.literal("No block targeted. Look at a block and try again.")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        BlockHitResult hit = (BlockHitResult) client.hitResult;
        BlockPos pos = hit.getBlockPos();
        Level level = client.level;
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);

        // Header
        client.player.sendSystemMessage(
                Component.literal("═══ WAILA ═══").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        // Block ID (clickable to copy)
        String blockIdStr = blockId.toString();
        client.player.sendSystemMessage(
                Component.literal("Block: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(blockIdStr).withStyle(ChatFormatting.AQUA)
                                .withStyle(style -> style
                                        .withClickEvent(new ClickEvent.CopyToClipboard(blockIdStr))
                                        .withHoverEvent(new HoverEvent.ShowText(
                                                Component.literal("Click to copy block ID").withStyle(ChatFormatting.GRAY))))));

        // Position (clickable to copy)
        String posStr = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        client.player.sendSystemMessage(
                Component.literal("Position: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(posStr).withStyle(ChatFormatting.WHITE)
                                .withStyle(style -> style
                                        .withClickEvent(new ClickEvent.CopyToClipboard(posStr))
                                        .withHoverEvent(new HoverEvent.ShowText(
                                                Component.literal("Click to copy position").withStyle(ChatFormatting.GRAY))))));

        // Block state properties
        var values = state.getValues().toList();
        if (!values.isEmpty()) {
            StringBuilder props = new StringBuilder();
            for (var entry : values) {
                if (props.length() > 0) props.append(", ");
                props.append(entry.property().getName()).append("=").append(entry.value());
            }
            client.player.sendSystemMessage(
                    Component.literal("State: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("[" + props + "]").withStyle(ChatFormatting.YELLOW)));
        }

        // Block entity / tile entity data
        BlockEntity blockEntity = level.getBlockEntity(pos);
        String skyblockId = null;
        String skullTextureHash = null;
        String skullSkyblockId = null;
        if (blockEntity != null) {
            String beType = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString();
            client.player.sendSystemMessage(
                    Component.literal("Block Entity: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(beType).withStyle(ChatFormatting.DARK_AQUA)));

            // Skull-specific handling
            if (blockEntity instanceof SkullBlockEntity skull) {
                SkullInfo skullInfo = handleSkull(client, skull);
                skullTextureHash = skullInfo.textureHash();
                // Use skull signature as SkyBlock ID if no NBT-based ID found later
                skullSkyblockId = skullInfo.skyblockId();
            }

            // Full NBT (clickable to copy)
            try {
                CompoundTag nbt = blockEntity.saveWithoutMetadata(level.registryAccess());
                if (!nbt.isEmpty()) {
                    String nbtStr = nbt.toString();
                    // Show truncated preview
                    String preview = nbtStr.length() > 80 ? nbtStr.substring(0, 80) + "..." : nbtStr;
                    client.player.sendSystemMessage(
                            Component.literal("NBT: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(preview).withStyle(ChatFormatting.DARK_GRAY)
                                            .withStyle(style -> style
                                                    .withClickEvent(new ClickEvent.CopyToClipboard(nbtStr))
                                                    .withHoverEvent(new HoverEvent.ShowText(
                                                            Component.literal("Click to copy full NBT data").withStyle(ChatFormatting.GRAY))))));

                    // Extract SkyBlock custom item ID from NBT
                    skyblockId = extractSkyBlockItemId(nbt);
                    if (skyblockId != null) {
                        String finalSkyblockId = skyblockId;
                        client.player.sendSystemMessage(
                                Component.literal("  SkyBlock ID: ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(skyblockId).withStyle(ChatFormatting.GOLD)
                                                .withStyle(style -> style
                                                        .withClickEvent(new ClickEvent.CopyToClipboard(finalSkyblockId))
                                                        .withHoverEvent(new HoverEvent.ShowText(
                                                                Component.literal("Click to copy SkyBlock ID")
                                                                        .withStyle(ChatFormatting.GRAY))))));
                    }
                }
            } catch (Exception e) {
                // NBT serialization can fail for some block entities
            }
        }

        // Use skull signature-based SkyBlock ID as fallback if no NBT-based ID found
        if (skyblockId == null && skullSkyblockId != null) {
            skyblockId = skullSkyblockId;
            String sbId = skyblockId;
            client.player.sendSystemMessage(
                    Component.literal("SkyBlock ID (from signature): ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(sbId).withStyle(ChatFormatting.GOLD)
                                    .withStyle(style -> style
                                            .withClickEvent(new ClickEvent.CopyToClipboard(sbId))
                                            .withHoverEvent(new HoverEvent.ShowText(
                                                    Component.literal("SkyBlock item ID from skull profile signature\nClick to copy")
                                                            .withStyle(ChatFormatting.GRAY))))));
        }

        // Highlight command buttons
        boolean isSkull = (skullTextureHash != null);
        String shortId = blockIdStr.startsWith("minecraft:") ? blockIdStr.substring(10) : blockIdStr;

        // Always show block ID highlighter button
        client.player.sendSystemMessage(
                Component.literal("[+ Add to Highlighter]").withStyle(ChatFormatting.GREEN)
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent.RunCommand("/it highlight add " + shortId))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Click to highlight all " + shortId + " blocks").withStyle(ChatFormatting.GRAY)))));

        // For heads, show head texture highlighter button
        if (isSkull) {
            String skullTarget = "skull:" + skullTextureHash;
            client.player.sendSystemMessage(
                    Component.literal("[+ Add Head Texture to Highlighter]").withStyle(ChatFormatting.GREEN)
                            .withStyle(style -> style
                                    .withClickEvent(new ClickEvent.RunCommand("/it highlight add " + skullTarget))
                                    .withHoverEvent(new HoverEvent.ShowText(
                                            Component.literal("Click to highlight skulls with this texture").withStyle(ChatFormatting.GRAY)))));
        }

        // If there's a skyblock ID, show skyblock ID highlighter button
        if (skyblockId != null) {
            String sbTarget = "sb:" + skyblockId;
            String finalSbId = skyblockId;
            client.player.sendSystemMessage(
                    Component.literal("[+ Add SkyBlock ID to Highlighter]").withStyle(ChatFormatting.GREEN)
                            .withStyle(style -> style
                                    .withClickEvent(new ClickEvent.RunCommand("/it highlight add " + sbTarget))
                                    .withHoverEvent(new HoverEvent.ShowText(
                                            Component.literal("Click to highlight all blocks with SkyBlock ID " + finalSbId).withStyle(ChatFormatting.GRAY)))));
        }
    }

    /**
     * Extract a SkyBlock custom item ID from block entity NBT.
     * Looks for patterns like custom_data.id, PublicBukkitValues."hypixel:id", etc.
     */
    private static String extractSkyBlockItemId(CompoundTag nbt) {
        // Check custom_data compound for "id" key
        if (nbt.contains("custom_data")) {
            try {
                CompoundTag customData = nbt.getCompound("custom_data").orElse(null);
                if (customData != null && customData.contains("id")) {
                    return customData.getString("id").orElse(null);
                }
            } catch (Exception ignored) {}
        }
        // Check PublicBukkitValues for hypixel:id
        if (nbt.contains("PublicBukkitValues")) {
            try {
                CompoundTag bukkit = nbt.getCompound("PublicBukkitValues").orElse(null);
                if (bukkit != null && bukkit.contains("hypixel:id")) {
                    return bukkit.getString("hypixel:id").orElse(null);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Result from skull inspection: texture hash and optional SkyBlock ID from signature. */
    private record SkullInfo(String textureHash, String skyblockId) {}

    private static SkullInfo handleSkull(Minecraft client, SkullBlockEntity skull) {
        String extractedHash = null;
        String extractedSbId = null;
        try {
            var profile = skull.getOwnerProfile();
            if (profile != null) {
                // Display owner name
                String profileName = profile.name().orElse(null);
                if (profileName != null && !profileName.isEmpty()) {
                    client.player.sendSystemMessage(
                            Component.literal("  Owner: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(profileName).withStyle(ChatFormatting.WHITE)));
                }

                // Display UUID
                var gp = profile.partialProfile();
                if (gp.id() != null) {
                    String uuid = gp.id().toString();
                    client.player.sendSystemMessage(
                            Component.literal("  UUID: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(uuid).withStyle(ChatFormatting.DARK_GRAY)
                                            .withStyle(style -> style
                                                    .withClickEvent(new ClickEvent.CopyToClipboard(uuid))
                                                    .withHoverEvent(new HoverEvent.ShowText(
                                                            Component.literal("Click to copy UUID").withStyle(ChatFormatting.GRAY))))));
                }

                // Extract texture and provide Copy as Skin button
                for (Property prop : gp.properties().get("textures")) {
                    String textureValue = prop.value();

                    // Check signature for SkyBlock ID (e.g. "DITTO_BLOB")
                    String sig = prop.signature();
                    if (sig != null && !sig.isEmpty() && !sig.equals(textureValue)) {
                        // Signatures that look like item IDs (uppercase with underscores, not base64)
                        if (sig.matches("[A-Z0-9_]+")) {
                            extractedSbId = sig;
                        }
                    }

                    // Decode base64 to get texture URL and extract hash
                    try {
                        String decoded = new String(Base64.getDecoder().decode(textureValue));

                        // Extract texture hash
                        String hash = extractTextureHash(decoded);
                        if (hash != null) {
                            extractedHash = hash;

                            // Show texture hash (clickable to copy)
                            client.player.sendSystemMessage(
                                    Component.literal("  Texture Hash: ").withStyle(ChatFormatting.GRAY)
                                            .append(Component.literal(hash).withStyle(ChatFormatting.LIGHT_PURPLE)
                                                    .withStyle(style -> style
                                                            .withClickEvent(new ClickEvent.CopyToClipboard(hash))
                                                            .withHoverEvent(new HoverEvent.ShowText(
                                                                    Component.literal("Click to copy texture hash")
                                                                            .withStyle(ChatFormatting.GRAY))))));
                        }

                        // Copy Skin button — runs command to download and copy image to clipboard
                        String skinUrl = extractSkinUrl(decoded);
                        if (skinUrl != null) {
                            client.player.sendSystemMessage(
                                    Component.literal("  ").withStyle(ChatFormatting.GRAY)
                                            .append(Component.literal("[Copy Skin Image]").withStyle(ChatFormatting.DARK_AQUA)
                                                    .withStyle(style -> style
                                                            .withClickEvent(new ClickEvent.RunCommand("/it copyskin " + skinUrl))
                                                            .withHoverEvent(new HoverEvent.ShowText(
                                                                    Component.literal("Click to download and copy skin texture image to clipboard")
                                                                            .withStyle(ChatFormatting.GRAY))))));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            // getOwnerProfile may not exist in this version
        }
        return new SkullInfo(extractedHash, extractedSbId);
    }

    /**
     * Extract the texture hash from decoded texture JSON.
     */
    private static String extractTextureHash(String decodedJson) {
        int urlIdx = decodedJson.indexOf("textures.minecraft.net/texture/");
        if (urlIdx < 0) return null;
        int hashStart = urlIdx + "textures.minecraft.net/texture/".length();
        int hashEnd = decodedJson.indexOf('"', hashStart);
        if (hashEnd < 0) hashEnd = decodedJson.indexOf('}', hashStart);
        if (hashEnd < 0) return null;
        return decodedJson.substring(hashStart, hashEnd);
    }

    /**
     * Extract the full skin texture URL from decoded texture JSON.
     */
    private static String extractSkinUrl(String decodedJson) {
        int urlIdx = decodedJson.indexOf("http://textures.minecraft.net/texture/");
        if (urlIdx < 0) {
            urlIdx = decodedJson.indexOf("https://textures.minecraft.net/texture/");
        }
        if (urlIdx < 0) return null;
        int urlEnd = decodedJson.indexOf('"', urlIdx);
        if (urlEnd < 0) return null;
        return decodedJson.substring(urlIdx, urlEnd);
    }
}
