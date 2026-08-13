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
        if (blockEntity != null) {
            String beType = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString();
            client.player.sendSystemMessage(
                    Component.literal("Block Entity: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(beType).withStyle(ChatFormatting.DARK_AQUA)));

            // Skull-specific handling
            if (blockEntity instanceof SkullBlockEntity skull) {
                handleSkull(client, skull);
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
                                                                Component.literal("Click to copy SkyBlock ID\nUse with /it highlight add skyblock:" + finalSkyblockId)
                                                                        .withStyle(ChatFormatting.GRAY))))));
                    }
                }
            } catch (Exception e) {
                // NBT serialization can fail for some block entities
            }
        }

        // Highlight command suggestion — prefer SkyBlock ID if available
        String shortId = blockIdStr.startsWith("minecraft:") ? blockIdStr.substring(10) : blockIdStr;
        if (skyblockId != null) {
            String skyblockTarget = "skyblock:" + skyblockId;
            client.player.sendSystemMessage(
                    Component.literal("[+ Add to Highlighter (SkyBlock)]").withStyle(ChatFormatting.GREEN)
                            .withStyle(style -> style
                                    .withClickEvent(new ClickEvent.RunCommand("/it highlight add " + skyblockTarget))
                                    .withHoverEvent(new HoverEvent.ShowText(
                                            Component.literal("Click to highlight all " + skyblockTarget + " blocks").withStyle(ChatFormatting.GRAY)))));
        }
        client.player.sendSystemMessage(
                Component.literal("[+ Add to Highlighter]").withStyle(ChatFormatting.GREEN)
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent.RunCommand("/it highlight add " + shortId))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Click to highlight all " + shortId + " blocks").withStyle(ChatFormatting.GRAY)))));
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

    private static void handleSkull(Minecraft client, SkullBlockEntity skull) {
        // Try to get the skull profile
        // In modern MC, SkullBlockEntity has getOwnerProfile()
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
                var profileId = profile.id().orElse(null);
                if (profileId != null) {
                    String uuid = profileId.toString();
                    client.player.sendSystemMessage(
                            Component.literal("  UUID: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(uuid).withStyle(ChatFormatting.DARK_GRAY)
                                            .withStyle(style -> style
                                                    .withClickEvent(new ClickEvent.CopyToClipboard(uuid))
                                                    .withHoverEvent(new HoverEvent.ShowText(
                                                            Component.literal("Click to copy UUID").withStyle(ChatFormatting.GRAY))))));
                }

                // Extract and display texture URL from properties
                for (Property prop : profile.properties()) {
                    if (!"textures".equals(prop.name())) continue;
                    String textureValue = prop.value();
                    client.player.sendSystemMessage(
                            Component.literal("  Texture: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal("[Copy Base64]").withStyle(ChatFormatting.DARK_AQUA)
                                            .withStyle(style -> style
                                                    .withClickEvent(new ClickEvent.CopyToClipboard(textureValue))
                                                    .withHoverEvent(new HoverEvent.ShowText(
                                                            Component.literal("Click to copy texture base64").withStyle(ChatFormatting.GRAY))))));

                    // Decode base64 to get texture URL
                    try {
                        String decoded = new String(Base64.getDecoder().decode(textureValue));
                        client.player.sendSystemMessage(
                                Component.literal("  Decoded: ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(decoded.length() > 100 ? decoded.substring(0, 100) + "..." : decoded)
                                                .withStyle(ChatFormatting.DARK_GRAY)
                                                .withStyle(style -> style
                                                        .withClickEvent(new ClickEvent.CopyToClipboard(decoded))
                                                        .withHoverEvent(new HoverEvent.ShowText(
                                                                Component.literal("Click to copy decoded texture JSON").withStyle(ChatFormatting.GRAY))))));

                        // Try to extract SkyBlock ID from texture URL
                        extractSkyBlockId(client, decoded);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            // getOwnerProfile may not exist in this version
        }
    }

    /**
     * Attempt to identify a SkyBlock custom item by its texture hash.
     * The decoded JSON typically looks like:
     * {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/HASH"}}}
     * We extract the hash and display it as a potential SkyBlock identifier.
     */
    private static void extractSkyBlockId(Minecraft client, String decodedJson) {
        // Extract texture hash from URL
        int urlIdx = decodedJson.indexOf("textures.minecraft.net/texture/");
        if (urlIdx < 0) return;
        int hashStart = urlIdx + "textures.minecraft.net/texture/".length();
        int hashEnd = decodedJson.indexOf('"', hashStart);
        if (hashEnd < 0) hashEnd = decodedJson.indexOf('}', hashStart);
        if (hashEnd < 0) return;

        String textureHash = decodedJson.substring(hashStart, hashEnd);
        client.player.sendSystemMessage(
                Component.literal("  Texture Hash: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(textureHash).withStyle(ChatFormatting.LIGHT_PURPLE)
                                .withStyle(style -> style
                                        .withClickEvent(new ClickEvent.CopyToClipboard(textureHash))
                                        .withHoverEvent(new HoverEvent.ShowText(
                                                Component.literal("Click to copy texture hash\nUse this with /it highlight add for SkyBlock skull matching")
                                                        .withStyle(ChatFormatting.GRAY))))));
    }
}
