package org.example.islandtime;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.datafixer.TypeReferences;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Parses Hypixel Skyblock backpack contents from the compressed NBT byte array.
 *
 * --- 1.21.11 API fixes applied ---
 *
 * Fix 1: SharedConstants.getGameVersion().getSaveVersion().getId()
 *   SharedConstants.getGameVersion() returns the Mojang bridge GameVersion which
 *   does NOT have getSaveVersion(). Use MinecraftVersion.CURRENT instead.
 *   → MinecraftVersion.CURRENT.getSaveVersion().getId()
 *
 * Fix 2: DataFixTypes.ITEM_STACK was completely removed in 1.21 (confirmed via
 *   the 1.21.11 Yarn API docs — the enum only contains LEVEL, PLAYER, CHUNK, etc).
 *   TypeReferences.ITEM_STACK (the raw DFU DSL TypeReference) still exists.
 *   → Call client.getDataFixer().update(TypeReferences.ITEM_STACK, dynamic, old, new)
 *
 * Fix 3: DynamicRegistryManager.createSerializationContext() renamed to getOps().
 *   → registries.getOps(NbtOps.INSTANCE)
 */
public class BackpackNbtParser {

    private static final int LEGACY_DATA_VERSION = 99;

    /**
     * Spawn egg damage values for entities that did not exist in schema 99 (1.8).
     * DFU has no record of these, so it falls back to a wrong or empty mapping.
     * Values are the legacy numeric entity IDs used as item damage.
     *
     * Populated from the Minecraft 1.9–1.12 entity-ID registry:
     *   https://minecraft.wiki/w/Java_Edition_data_values/Pre-flattening#Entity_IDs
     */
    private static final Map<Integer, String> SPAWN_EGG_OVERRIDES = new HashMap<>();
    static {
        // 1.10 additions
        SPAWN_EGG_OVERRIDES.put(6,   "stray_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(23,  "husk_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(28,  "polar_bear_spawn_egg");
        // 1.11 additions
        SPAWN_EGG_OVERRIDES.put(26,  "donkey_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(29,  "skeleton_horse_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(30,  "zombie_horse_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(34,  "evoker_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(35,  "vex_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(36,  "vindicator_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(103, "llama_spawn_egg");
        // 1.12 additions
        SPAWN_EGG_OVERRIDES.put(105, "parrot_spawn_egg");
        // Hypixel Skyblock custom spawn eggs always store damage=0 and encode
        // the mob type in ExtraAttributes.id instead. DFU maps entity ID 0
        // to wither_skeleton. Hypixel renders all custom eggs as polar_bear_spawn_egg.
        SPAWN_EGG_OVERRIDES.put(0,   "polar_bear_spawn_egg");
    }

    /**
     * Numeric item IDs that DFU does not map correctly at schema 99.
     * These appear as "Item must not be minecraft:air" in the log because DFU
     * produces air for any ID it cannot find in its lookup table.
     * Add entries here whenever a new item surfaces with that error.
     */
    private static final Map<Integer, String> ITEM_ID_OVERRIDES = new HashMap<>();
    static {
        // Prismarine items — added late in 1.8, absent from DFU's V99 table
        ITEM_ID_OVERRIDES.put(409, "prismarine_shard");
        ITEM_ID_OVERRIDES.put(410, "prismarine_crystals");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static boolean hasBackpackData(ItemStack stack) {
        var customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return false;
        NbtCompound nbt = customData.copyNbt();
        for (String key : nbt.getKeys()) {
            if (key.endsWith("_backpack_data") && nbt.get(key) instanceof NbtByteArray) {
                return true;
            }
        }
        return false;
    }

    public static List<ItemStack> parse(ItemStack stack) {
        var customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return null;

        NbtCompound nbt = customData.copyNbt();
        byte[] data = null;

        for (String key : nbt.getKeys()) {
            if (key.endsWith("_backpack_data") && nbt.get(key) instanceof NbtByteArray arr) {
                data = arr.getByteArray();
                break;
            }
        }

        if (data == null || data.length < 4) return null;
        return parseBytes(data);
    }

    // -------------------------------------------------------------------------
    // Internal parsing
    // -------------------------------------------------------------------------

    private static List<ItemStack> parseBytes(byte[] data) {
        List<ItemStack> result = new ArrayList<>();
        try {
            NbtCompound root = NbtIo.readCompressed(
                    new ByteArrayInputStream(data),
                    new NbtSizeTracker(10_000_000L, 512));

            NbtList items = root.getListOrEmpty("i");
            for (int i = 0; i < items.size(); i++) {
                NbtCompound itemNbt = items.getCompoundOrEmpty(i);
                short rawId = itemNbt.getShort("id", (short) 0);
                result.add((itemNbt.isEmpty() || rawId == 0)
                        ? ItemStack.EMPTY
                        : parseItem(itemNbt));
            }
        } catch (IOException e) {
            System.err.println("[IslandTime] Backpack decompression failed: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[IslandTime] Backpack parse error: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    private static ItemStack parseItem(NbtCompound legacyNbt) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();

            // Fix 1 — get current data version from MinecraftVersion.CURRENT
            int currentVersion = SharedConstants.getGameVersion().dataVersion().id();

            // Fix 2 — DataFixTypes.ITEM_STACK is gone; use TypeReferences directly.
            // The id field MUST remain as NbtShort — that is what DFU's schema 99
            // ItemStackFix looks up in its numeric→name table. Converting it to a
            // String causes DFU to skip the lookup and blindly namespace it as
            // "minecraft:319", "minecraft:383", etc., which are not valid items.
            NbtCompound dfuInput = legacyNbt.copy();
            short numId = legacyNbt.getShort("id", (short) 0);
            Dynamic<NbtElement> dynamic = new Dynamic<>(NbtOps.INSTANCE, dfuInput);
            Dynamic<NbtElement> updated = client.getDataFixer().update(
                    TypeReferences.ITEM_STACK,
                    dynamic,
                    LEGACY_DATA_VERSION,
                    currentVersion);
            NbtCompound modernNbt = (NbtCompound) updated.getValue();

            // Fix 3 — createSerializationContext() renamed to getOps()
            DynamicRegistryManager registries =
                    client.world != null
                            ? client.world.getRegistryManager()
                            : client.getNetworkHandler() != null
                                ? client.getNetworkHandler().getRegistryManager()
                                : DynamicRegistryManager.EMPTY;

            ItemStack stack = ItemStack.CODEC
                    .parse(registries.getOps(NbtOps.INSTANCE), modernNbt)
                    .resultOrPartial(err ->
                            System.err.println("[IslandTime] ItemStack decode: " + err))
                    .orElse(ItemStack.EMPTY);

            // ---- Manual overrides (must run BEFORE the isEmpty bail) ----
            // DFU silently outputs air for: items absent from its V99 table, and
            // spawn eggs whose entity type didn't exist in 1.8. We fix both here
            // using the original numeric id/damage before discarding the stack.

            // Items that DFU doesn't know → check ITEM_ID_OVERRIDES
            if (stack.isEmpty() && numId != 0) {
                String override = ITEM_ID_OVERRIDES.get((int) numId);
                if (override != null) {
                    net.minecraft.item.Item item =
                            Registries.ITEM.get(Identifier.of("minecraft", override));
                    if (item != Items.AIR) {
                        int count = legacyNbt.getByte("Count", (byte) 1) & 0xFF;
                        stack = new ItemStack(item, Math.max(1, count));
                    }
                }
            }

            // Spawn eggs for post-1.8 entities → SPAWN_EGG_OVERRIDES by damage value.
            // Runs whether DFU produced air (unknown entity) or the wrong egg (entity
            // ID that happened to collide with a different 1.8 entity).
            if (numId == 383) {
                int damage = legacyNbt.getShort("Damage", (short) 0) & 0xFFFF;
                String override = SPAWN_EGG_OVERRIDES.get(damage);
                if (override != null) {
                    net.minecraft.item.Item eggItem =
                            Registries.ITEM.get(Identifier.of("minecraft", override));
                    if (eggItem != Items.AIR) {
                        int count = stack.isEmpty()
                                ? (legacyNbt.getByte("Count", (byte) 1) & 0xFF)
                                : stack.getCount();
                        stack = new ItemStack(eggItem, Math.max(1, count));
                    }
                }
            }

            if (stack.isEmpty()) return ItemStack.EMPTY;

            // Layer Hypixel-specific data that DFU doesn't know about.
            NbtCompound tag = legacyNbt.getCompoundOrEmpty("tag");

            if (tag.get("ExtraAttributes") instanceof NbtCompound ea) {
                NbtCompound customData = new NbtCompound();
                customData.put("ExtraAttributes", ea);
                stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(customData));
            }

            if (tag.get("display") instanceof NbtCompound display) {
                if (display.get("Name") instanceof NbtString) {
                    stack.set(DataComponentTypes.CUSTOM_NAME,
                            parseLegacy(display.getString("Name", "")));
                }
                if (display.get("Lore") instanceof NbtList loreNbt) {
                    List<Text> lore = new ArrayList<>(loreNbt.size());
                    for (int i = 0; i < loreNbt.size(); i++) {
                        lore.add(parseLegacy(loreNbt.getString(i, "")));
                    }
                    stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
                }
            }

            short numericId = legacyNbt.getShort("id", (short) 0);
            if (numericId == 397 && tag.get("SkullOwner") instanceof NbtCompound skullOwner) {
                applySkullTexture(stack, skullOwner);
            }

            return stack;

        } catch (Exception e) {
            System.err.println("[IslandTime] parseItem failed: " + e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    // -------------------------------------------------------------------------
    // Skull texture
    // -------------------------------------------------------------------------

    private static void applySkullTexture(ItemStack stack, NbtCompound skullOwner) {
        try {
            NbtCompound profileNbt = new NbtCompound();

            String name = skullOwner.getString("Name", "Player");
            if (!name.isBlank()) profileNbt.putString("name", name);

            if (skullOwner.get("Id") instanceof NbtIntArray idArr) {
                profileNbt.put("id", idArr);
            }

            NbtCompound properties = skullOwner.getCompoundOrEmpty("Properties");
            NbtList textures = properties.getListOrEmpty("textures");
            if (!textures.isEmpty()) {
                String value = textures.getCompoundOrEmpty(0).getString("Value", "");
                if (!value.isBlank()) {
                    NbtCompound texEntry = new NbtCompound();
                    texEntry.putString("name", "textures");
                    texEntry.putString("value", value);
                    NbtList propsOut = new NbtList();
                    propsOut.add(texEntry);
                    profileNbt.put("properties", propsOut);
                }
            }

            ProfileComponent.CODEC
                    .parse(NbtOps.INSTANCE, profileNbt)
                    .result()
                    .ifPresent(pc -> stack.set(DataComponentTypes.PROFILE, pc));

        } catch (Exception e) {
            System.err.println("[IslandTime] Skull texture failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // §-code → Text parser
    // -------------------------------------------------------------------------

    public static Text parseLegacy(String s) {
        if (s == null || s.isEmpty()) return Text.empty();

        MutableText root = Text.empty();
        String[] segments = s.split("§", -1);
        Style style = Style.EMPTY.withItalic(false);

        if (!segments[0].isEmpty()) {
            root.append(Text.literal(segments[0]).setStyle(style));
        }

        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.isEmpty()) continue;

            char code = Character.toLowerCase(seg.charAt(0));
            String text = seg.substring(1);

            Formatting fmt = Formatting.byCode(code);
            if (fmt != null) {
                if (fmt == Formatting.RESET) {
                    style = Style.EMPTY.withItalic(false);
                } else if (fmt.isColor()) {
                    style = Style.EMPTY.withItalic(false).withColor(fmt);
                } else {
                    style = style.withFormatting(fmt);
                }
            }

            if (!text.isEmpty()) {
                root.append(Text.literal(text).setStyle(style));
            }
        }

        return root;
    }
}
