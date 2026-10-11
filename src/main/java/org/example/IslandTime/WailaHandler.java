package org.example.islandtime;

import com.mojang.authlib.properties.Property;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Handles the /it waila command — "What Am I Looking At".
 * Inspects any targeted block or entity and displays detailed info in chat.
 * For blocks: block type, state properties, position, tile entity data,
 * and Hypixel SkyBlock custom data (e.g. skull textures/IDs).
 * For entities: entity type, name, IDs, position, health, equipment, held items and full NBT.
 * Both come with buttons to add the target to the highlighter.
 */
public class WailaHandler {

    public static void inspect(Minecraft client) {
        if (client.level == null || client.player == null) {
            client.player.sendSystemMessage(
                    Component.literal("Not in a world.").withStyle(ChatFormatting.RED));
            return;
        }

        if (client.hitResult != null && client.hitResult.getType() == HitResult.Type.ENTITY) {
            inspectEntity(client, ((EntityHitResult) client.hitResult).getEntity());
            return;
        }

        if (client.hitResult == null || client.hitResult.getType() != HitResult.Type.BLOCK) {
            client.player.sendSystemMessage(
                    Component.literal("No block or entity targeted. Look at a block or entity and try again.")
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

    // -------------------------------------------------------------------------
    // Entity inspection
    // -------------------------------------------------------------------------

    /** Longest entity name placed into an "add to highlighter" command. */
    private static final int MAX_NAME_FILTER_LENGTH = 80;

    private static void inspectEntity(Minecraft client, Entity entity) {
        Level level = client.level;

        client.player.sendSystemMessage(
                Component.literal("═══ WAILA (Entity) ═══").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        // Entity type (clickable to copy)
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        sendCopyLine(client, "Entity: ", typeId, ChatFormatting.AQUA, "Click to copy entity ID");

        // Name
        String name = entity.getName().getString();
        sendCopyLine(client, "Name: ", name, ChatFormatting.WHITE, "Click to copy name");
        if (entity.getCustomName() != null) {
            client.player.sendSystemMessage(
                    Component.literal("  Name Tag: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(entity.isCustomNameVisible() ? "visible" : "hidden")
                                    .withStyle(ChatFormatting.YELLOW)));
        }

        // Identifiers
        client.player.sendSystemMessage(
                Component.literal("Class: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(entity.getClass().getSimpleName()).withStyle(ChatFormatting.DARK_AQUA)));
        sendCopyLine(client, "Entity ID: ", String.valueOf(entity.getId()), ChatFormatting.WHITE,
                "Click to copy entity network ID");
        sendCopyLine(client, "UUID: ", entity.getStringUUID(), ChatFormatting.DARK_GRAY, "Click to copy UUID");

        // Position and size
        String posStr = String.format(Locale.ROOT, "%.2f, %.2f, %.2f", entity.getX(), entity.getY(), entity.getZ());
        sendCopyLine(client, "Position: ", posStr, ChatFormatting.WHITE, "Click to copy position");
        BlockPos blockPos = entity.blockPosition();
        String blockPosStr = blockPos.getX() + ", " + blockPos.getY() + ", " + blockPos.getZ();
        sendCopyLine(client, "Block Position: ", blockPosStr, ChatFormatting.WHITE, "Click to copy block position");
        client.player.sendSystemMessage(
                Component.literal("Rotation: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.format(Locale.ROOT, "yaw %.1f, pitch %.1f",
                                entity.getYRot(), entity.getXRot())).withStyle(ChatFormatting.WHITE)));
        client.player.sendSystemMessage(
                Component.literal("Size: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.format(Locale.ROOT, "%.2f wide, %.2f tall",
                                entity.getBbWidth(), entity.getBbHeight())).withStyle(ChatFormatting.WHITE)));
        client.player.sendSystemMessage(
                Component.literal("Distance: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.format(Locale.ROOT, "%.2f blocks",
                                client.player.distanceTo(entity))).withStyle(ChatFormatting.WHITE)));
        client.player.sendSystemMessage(
                Component.literal("Pose: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(entity.getPose().name().toLowerCase(Locale.ROOT))
                                .withStyle(ChatFormatting.WHITE)));

        // Flags
        List<String> flags = new ArrayList<>();
        if (entity.isInvisible()) flags.add("invisible");
        if (entity.isCurrentlyGlowing()) flags.add("glowing");
        if (entity.isOnFire()) flags.add("on fire");
        if (entity.isSilent()) flags.add("silent");
        if (entity.isNoGravity()) flags.add("no gravity");
        if (entity instanceof LivingEntity living && living.isBaby()) flags.add("baby");
        if (!flags.isEmpty()) {
            client.player.sendSystemMessage(
                    Component.literal("Flags: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.join(", ", flags)).withStyle(ChatFormatting.YELLOW)));
        }

        // Scoreboard tags
        if (!entity.entityTags().isEmpty()) {
            client.player.sendSystemMessage(
                    Component.literal("Tags: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.join(", ", entity.entityTags()))
                                    .withStyle(ChatFormatting.YELLOW)));
        }

        // Riding relationships
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            client.player.sendSystemMessage(
                    Component.literal("Riding: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(describeEntity(vehicle)).withStyle(ChatFormatting.WHITE)));
        }
        for (Entity passenger : entity.getPassengers()) {
            client.player.sendSystemMessage(
                    Component.literal("Passenger: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(describeEntity(passenger)).withStyle(ChatFormatting.WHITE)));
        }

        // Health and equipment
        if (entity instanceof LivingEntity living) {
            client.player.sendSystemMessage(
                    Component.literal("Health: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.format(Locale.ROOT, "%.1f / %.1f",
                                    living.getHealth(), living.getMaxHealth())).withStyle(ChatFormatting.RED)));
            if (living.getArmorValue() > 0) {
                client.player.sendSystemMessage(
                        Component.literal("Armor: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.valueOf(living.getArmorValue()))
                                        .withStyle(ChatFormatting.WHITE)));
            }
            if (living.getAbsorptionAmount() > 0) {
                client.player.sendSystemMessage(
                        Component.literal("Absorption: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.format(Locale.ROOT, "%.1f", living.getAbsorptionAmount()))
                                        .withStyle(ChatFormatting.YELLOW)));
            }

            boolean headerShown = false;
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (stack.isEmpty()) continue;
                if (!headerShown) {
                    client.player.sendSystemMessage(Component.literal("Equipment:").withStyle(ChatFormatting.GRAY));
                    headerShown = true;
                }
                sendItemInfo(client, "  " + slot.getName() + ": ", stack);
            }
        }

        // Dropped item / framed item
        if (entity instanceof ItemEntity itemEntity) {
            sendItemInfo(client, "Item: ", itemEntity.getItem());
        } else if (entity instanceof ItemFrame itemFrame && !itemFrame.getItem().isEmpty()) {
            sendItemInfo(client, "Framed Item: ", itemFrame.getItem());
        }

        // Full NBT (clickable to copy)
        try {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
            entity.saveWithoutId(output);
            CompoundTag nbt = output.buildResult();
            if (!nbt.isEmpty()) {
                String nbtStr = nbt.toString();
                String preview = nbtStr.length() > 80 ? nbtStr.substring(0, 80) + "..." : nbtStr;
                client.player.sendSystemMessage(
                        Component.literal("NBT: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(preview).withStyle(ChatFormatting.DARK_GRAY)
                                        .withStyle(style -> style
                                                .withClickEvent(new ClickEvent.CopyToClipboard(nbtStr))
                                                .withHoverEvent(new HoverEvent.ShowText(
                                                        Component.literal("Click to copy full NBT data").withStyle(ChatFormatting.GRAY))))));
            }
        } catch (Exception e) {
            // NBT serialization can fail for some entities
        }

        // Highlight command buttons
        String shortId = typeId.startsWith("minecraft:") ? typeId.substring(10) : typeId;
        String idTarget = BlockHighlightManager.buildEntityTarget(shortId, null);
        client.player.sendSystemMessage(
                Component.literal("[+ Add Entity ID to Highlighter]").withStyle(ChatFormatting.GREEN)
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent.RunCommand("/it highlight add " + idTarget))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Click to highlight all " + shortId + " entities")
                                                .withStyle(ChatFormatting.GRAY)))));

        String filterName = sanitizeNameFilter(name);
        if (!filterName.isEmpty()) {
            String nameTarget = BlockHighlightManager.buildEntityTarget(null, filterName);
            client.player.sendSystemMessage(
                    Component.literal("[+ Add Entity Name to Highlighter]").withStyle(ChatFormatting.GREEN)
                            .withStyle(style -> style
                                    .withClickEvent(new ClickEvent.RunCommand("/it highlight add " + nameTarget))
                                    .withHoverEvent(new HoverEvent.ShowText(
                                            Component.literal("Click to highlight all entities whose name contains \"" + filterName + "\"")
                                                    .withStyle(ChatFormatting.GRAY)))));
        }
    }

    /** Sends a "Label: value" line where the value copies to the clipboard when clicked. */
    private static void sendCopyLine(Minecraft client, String label, String value,
                                     ChatFormatting valueColor, String hover) {
        client.player.sendSystemMessage(
                Component.literal(label).withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(value).withStyle(valueColor)
                                .withStyle(style -> style
                                        .withClickEvent(new ClickEvent.CopyToClipboard(value))
                                        .withHoverEvent(new HoverEvent.ShowText(
                                                Component.literal(hover).withStyle(ChatFormatting.GRAY))))));
    }

    /** Short "type (name)" description of an entity, used for riding relationships. */
    private static String describeEntity(Entity entity) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return id + " (" + entity.getName().getString() + ")";
    }

    /**
     * Sends a line describing an item: name, count, registry ID, plus its SkyBlock ID and skull
     * texture hash (both clickable to copy) when it has them.
     */
    private static void sendItemInfo(Minecraft client, String label, ItemStack stack) {
        if (stack.isEmpty()) {
            client.player.sendSystemMessage(
                    Component.literal(label).withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("(empty)").withStyle(ChatFormatting.DARK_GRAY)));
            return;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String text = stack.getHoverName().getString()
                + (stack.getCount() > 1 ? " x" + stack.getCount() : "");
        client.player.sendSystemMessage(
                Component.literal(label).withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(text).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" (" + itemId + ")").withStyle(ChatFormatting.DARK_AQUA)
                                .withStyle(style -> style
                                        .withClickEvent(new ClickEvent.CopyToClipboard(itemId))
                                        .withHoverEvent(new HoverEvent.ShowText(
                                                Component.literal("Click to copy item ID").withStyle(ChatFormatting.GRAY))))));

        String skyblockId = getItemSkyBlockId(stack);
        if (skyblockId != null) {
            sendCopyLine(client, "    SkyBlock ID: ", skyblockId, ChatFormatting.GOLD, "Click to copy SkyBlock ID");
        }
        String textureHash = getItemSkullTextureHash(stack);
        if (textureHash != null) {
            sendCopyLine(client, "    Texture Hash: ", textureHash, ChatFormatting.LIGHT_PURPLE,
                    "Click to copy texture hash");
        }
    }

    /** Reads the SkyBlock item ID (custom_data.id) from an item stack, or null. */
    private static String getItemSkyBlockId(ItemStack stack) {
        try {
            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
            if (customData == null || customData.isEmpty()) return null;
            String id = customData.copyTag().getString("id").orElse(null);
            return id == null || id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads the skull texture hash from a player head item's profile, or null. */
    private static String getItemSkullTextureHash(ItemStack stack) {
        try {
            var profile = stack.get(DataComponents.PROFILE);
            if (profile == null) return null;
            for (Property prop : profile.partialProfile().properties().get("textures")) {
                String hash = extractTextureHash(new String(Base64.getDecoder().decode(prop.value())));
                if (hash != null) return hash;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Cleans an entity name for use as a highlight name filter: drops formatting codes and
     * control characters (which cannot be sent in a command) and limits the length.
     */
    private static String sanitizeNameFilter(String name) {
        String stripped = ChatFormatting.stripFormatting(name);
        String clean = (stripped != null ? stripped : name).replaceAll("[\\p{Cntrl}§]", "").trim();
        if (clean.length() > MAX_NAME_FILTER_LENGTH) {
            clean = clean.substring(0, MAX_NAME_FILTER_LENGTH).trim();
        }
        return clean;
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

                        // Copy Skin button — reads locally cached texture and copies to clipboard
                        if (extractedHash != null) {
                            String skinHash = extractedHash;
                            client.player.sendSystemMessage(
                                    Component.literal("  ").withStyle(ChatFormatting.GRAY)
                                            .append(Component.literal("[Copy Skin Image]").withStyle(ChatFormatting.DARK_AQUA)
                                                    .withStyle(style -> style
                                                            .withClickEvent(new ClickEvent.RunCommand("/it copyskin " + skinHash))
                                                            .withHoverEvent(new HoverEvent.ShowText(
                                                                    Component.literal("Click to copy skin texture image to clipboard (from local cache)")
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

}
