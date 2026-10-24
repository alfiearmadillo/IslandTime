package org.example.islandtime;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Parses Hypixel Skyblock backpack contents from the compressed NBT byte array.
 *
 * parseAsync() captures main-thread data (DataFixer, registry manager) immediately,
 * then does the heavy work (GZip decompression + per-item DFU update) on a
 * background thread so it never stalls the render thread.
 */
public class BackpackNbtParser {

    private static final int LEGACY_DATA_VERSION = 99;

    private static final Map<Integer, String> SPAWN_EGG_OVERRIDES = new HashMap<>();
    static {
        SPAWN_EGG_OVERRIDES.put(6,   "stray_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(23,  "husk_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(28,  "polar_bear_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(26,  "donkey_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(29,  "skeleton_horse_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(30,  "zombie_horse_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(34,  "evoker_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(35,  "vex_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(36,  "vindicator_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(103, "llama_spawn_egg");
        SPAWN_EGG_OVERRIDES.put(105, "parrot_spawn_egg");
        // Hypixel custom eggs: entity id 0 maps to wrong egg in DFU; use polar bear as fallback.
        SPAWN_EGG_OVERRIDES.put(0,   "polar_bear_spawn_egg");
    }

    private static final Map<Integer, String> ITEM_ID_OVERRIDES = new HashMap<>();
    static {
        ITEM_ID_OVERRIDES.put(409, "prismarine_shard");
        ITEM_ID_OVERRIDES.put(410, "prismarine_crystals");
    }

    /**
     * One slot of a backpack. {@code count} is what the backpack says and can be any int, negative or 0 too. The
     * {@code stack} has that count when it is 1 to 99 (so the game draws it as usual) and is a single item
     * otherwise, as an {@code ItemStack} can't hold, and an empty or negative one wouldn't even be drawn.
     */
    public record BackpackItem(ItemStack stack, int count) {

        public static final BackpackItem EMPTY = new BackpackItem(ItemStack.EMPTY, 0);

        public boolean isEmpty() {
            return stack.isEmpty();
        }

        /** True when the count is one the stack can't show, so it has to be drawn separately. */
        public boolean hasOwnCountText() {
            return !stack.isEmpty() && !BackpackCounts.fitsStack(count);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static boolean hasBackpackData(ItemStack stack) {
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return false;
        CompoundTag nbt = customData.copyTag();
        for (String key : nbt.keySet()) {
            if (key.endsWith("_backpack_data") && nbt.get(key) instanceof ByteArrayTag) {
                return true;
            }
        }
        return false;
    }

    /** Synchronous parse — for backward compatibility; prefer parseAsync(). */
    public static List<BackpackItem> parse(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        byte[] data = extractData(stack);
        if (data == null || data.length < 4) return null;
        return parseBytes(data,
                DataFixers.getDataFixer(),
                SharedConstants.getCurrentVersion().dataVersion().version(),
                resolveRegistries(client));
    }

    /**
     * Starts an async parse.  Must be called from the main thread so that
     * {@link Minecraft#getDataFixer()} and the registry manager are
     * captured safely before handing off to a background thread.
     *
     * @return a future that completes with the slot list (or null on failure).
     */
    public static CompletableFuture<List<BackpackItem>> parseAsync(ItemStack stack) {
        // ── Capture everything we need on the main thread ──────────────────
        Minecraft    client     = Minecraft.getInstance();
        DataFixer          fixer      = DataFixers.getDataFixer();
        int                currentVer = SharedConstants.getCurrentVersion().dataVersion().version();
        RegistryAccess reg    = resolveRegistries(client);
        byte[]             data       = extractData(stack);

        if (data == null || data.length < 4) {
            return CompletableFuture.completedFuture(null);
        }

        // ── Heavy work on a background thread ──────────────────────────────
        return CompletableFuture.supplyAsync(() ->
                parseBytes(data, fixer, currentVer, reg));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static byte[] extractData(ItemStack stack) {
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return null;
        CompoundTag nbt = customData.copyTag();
        for (String key : nbt.keySet()) {
            if (key.endsWith("_backpack_data") && nbt.get(key) instanceof ByteArrayTag arr) {
                return arr.getAsByteArray();
            }
        }
        return null;
    }

    private static RegistryAccess resolveRegistries(Minecraft client) {
        if (client.level != null)             return client.level.registryAccess();
        if (client.getConnection() != null) return client.getConnection().registryAccess();
        return RegistryAccess.EMPTY;
    }

    private static List<BackpackItem> parseBytes(byte[] data,
                                                 DataFixer fixer,
                                                 int currentVer,
                                                 RegistryAccess reg) {
        List<BackpackItem> result = new ArrayList<>();
        try {
            CompoundTag root = NbtIo.readCompressed(
                    new ByteArrayInputStream(data),
                    new NbtAccounter(10_000_000L, 512));

            ListTag items = root.getListOrEmpty("i");
            for (int i = 0; i < items.size(); i++) {
                CompoundTag itemNbt = items.getCompoundOrEmpty(i);
                short rawId = itemNbt.getShortOr("id", (short) 0);
                result.add((itemNbt.isEmpty() || rawId == 0)
                        ? BackpackItem.EMPTY
                        : parseItem(itemNbt, fixer, currentVer, reg));
            }
        } catch (IOException e) {
            System.err.println("[IslandTime] Backpack decompression failed: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[IslandTime] Backpack parse error: " + e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    /**
     * The count the backpack stores for an item: any int, so negative and 0 included. A byte, short or int
     * count is read as it is signed, and a missing one means a single item.
     */
    private static int readCount(CompoundTag itemNbt) {
        return itemNbt.getInt("Count").orElseGet(() -> itemNbt.getIntOr("count", 1));
    }

    private static BackpackItem parseItem(CompoundTag legacyNbt,
                                          DataFixer fixer,
                                          int currentVer,
                                          RegistryAccess reg) {
        try {
            short numId = legacyNbt.getShortOr("id", (short) 0);
            int count = readCount(legacyNbt);

            // The fixer and the stack codec only take a count of 1 to 99 and drop the item otherwise, so the
            // item is made as a single one and the real count is kept next to it.
            CompoundTag dfuInput = legacyNbt.copy();
            dfuInput.putByte("Count", (byte) 1);
            Dynamic<Tag> dynamic = new Dynamic<>(NbtOps.INSTANCE, dfuInput);
            Dynamic<Tag> updated = fixer.update(
                    References.ITEM_STACK, dynamic, LEGACY_DATA_VERSION, currentVer);
            CompoundTag modernNbt = (CompoundTag) updated.getValue();

            // resultOrPartial returns the partial result AND logs; suppress the log —
            // the "Item must not be minecraft:air" message is a DFU validation artefact
            // (every legacy item triggers it) but the partial ItemStack is correct.
            ItemStack stack = ItemStack.CODEC
                    .parse(RegistryOps.create(NbtOps.INSTANCE, reg), modernNbt)
                    .resultOrPartial(err -> { /* suppress — partial result is usable */ })
                    .orElse(ItemStack.EMPTY);

            // ── Manual overrides for items DFU doesn't know ─────────────────
            if (stack.isEmpty() && numId != 0) {
                String override = ITEM_ID_OVERRIDES.get((int) numId);
                if (override != null) {
                    net.minecraft.world.item.Item item =
                            BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath("minecraft", override)).orElse(null);
                    if (item != null && item != Items.AIR) {
                        stack = new ItemStack(item);
                    }
                }
            }

            // ── Spawn-egg overrides for post-1.8 entity types ───────────────
            if (numId == 383) {
                int damage = legacyNbt.getShortOr("Damage", (short) 0) & 0xFFFF;
                String override = SPAWN_EGG_OVERRIDES.get(damage);
                if (override != null) {
                    net.minecraft.world.item.Item eggItem =
                            BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath("minecraft", override)).orElse(null);
                    if (eggItem != null && eggItem != Items.AIR) {
                        stack = new ItemStack(eggItem);
                    }
                }
            }

            if (stack.isEmpty()) return BackpackItem.EMPTY;

            if (BackpackCounts.fitsStack(count)) stack.setCount(count);

            // ── Layer Hypixel-specific data ──────────────────────────────────
            CompoundTag tag = legacyNbt.getCompoundOrEmpty("tag");

            if (tag.get("ExtraAttributes") instanceof CompoundTag ea) {
                CompoundTag customData = new CompoundTag();
                customData.put("ExtraAttributes", ea);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData));
            }

            if (tag.get("display") instanceof CompoundTag display) {
                if (display.get("Name") instanceof StringTag) {
                    stack.set(DataComponents.CUSTOM_NAME,
                            parseLegacy(display.getStringOr("Name", "")));
                }
                if (display.get("Lore") instanceof ListTag loreNbt) {
                    List<Component> lore = new ArrayList<>(loreNbt.size());
                    for (int i = 0; i < loreNbt.size(); i++) {
                        lore.add(parseLegacy(loreNbt.getString(i).orElse("")));
                    }
                    stack.set(DataComponents.LORE, new ItemLore(lore));
                }
            }

            if (numId == 397 && tag.get("SkullOwner") instanceof CompoundTag skullOwner) {
                applySkullTexture(stack, skullOwner);
            }

            return new BackpackItem(stack, count);

        } catch (Exception e) {
            System.err.println("[IslandTime] parseItem failed: " + e.getMessage());
            return BackpackItem.EMPTY;
        }
    }

    // -------------------------------------------------------------------------
    // Skull texture
    // -------------------------------------------------------------------------

    private static void applySkullTexture(ItemStack stack, CompoundTag skullOwner) {
        try {
            CompoundTag profileNbt = new CompoundTag();
            String name = skullOwner.getStringOr("Name", "Player");
            if (!name.isBlank()) profileNbt.putString("name", name);
            if (skullOwner.get("Id") instanceof IntArrayTag idArr) {
                profileNbt.put("id", idArr);
            }
            CompoundTag properties = skullOwner.getCompoundOrEmpty("Properties");
            ListTag textures = properties.getListOrEmpty("textures");
            if (!textures.isEmpty()) {
                String value = textures.getCompoundOrEmpty(0).getStringOr("Value", "");
                if (!value.isBlank()) {
                    CompoundTag texEntry = new CompoundTag();
                    texEntry.putString("name", "textures");
                    texEntry.putString("value", value);
                    ListTag propsOut = new ListTag();
                    propsOut.add(texEntry);
                    profileNbt.put("properties", propsOut);
                }
            }
            ResolvableProfile.CODEC
                    .parse(NbtOps.INSTANCE, profileNbt)
                    .result()
                    .ifPresent(pc -> stack.set(DataComponents.PROFILE, pc));
        } catch (Exception e) {
            System.err.println("[IslandTime] Skull texture failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // §-code → Text parser
    // -------------------------------------------------------------------------

    public static Component parseLegacy(String s) {
        if (s == null || s.isEmpty()) return Component.empty();
        MutableComponent root = Component.empty();
        String[] segments = s.split("§", -1);
        Style style = Style.EMPTY.withItalic(false);
        if (!segments[0].isEmpty()) {
            root.append(Component.literal(segments[0]).setStyle(style));
        }
        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.isEmpty()) continue;
            char code = Character.toLowerCase(seg.charAt(0));
            String text = seg.substring(1);
            ChatFormatting fmt = ChatFormatting.getByCode(code);
            if (fmt != null) {
                if (fmt == ChatFormatting.RESET)       style = Style.EMPTY.withItalic(false);
                else if (fmt.isColor())            style = Style.EMPTY.withItalic(false).withColor(fmt);
                else                               style = style.applyFormat(fmt);
            }
            if (!text.isEmpty()) root.append(Component.literal(text).setStyle(style));
        }
        return root;
    }
}
