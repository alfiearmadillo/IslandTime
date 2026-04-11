package org.example.islandtime;

import com.mojang.datafixers.DataFixer;
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
import java.util.concurrent.CompletableFuture;

import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

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

    /** Synchronous parse — for backward compatibility; prefer parseAsync(). */
    public static List<ItemStack> parse(ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();
        byte[] data = extractData(stack);
        if (data == null || data.length < 4) return null;
        return parseBytes(data,
                client.getDataFixer(),
                SharedConstants.getGameVersion().dataVersion().id(),
                resolveRegistries(client));
    }

    /**
     * Starts an async parse.  Must be called from the main thread so that
     * {@link MinecraftClient#getDataFixer()} and the registry manager are
     * captured safely before handing off to a background thread.
     *
     * @return a future that completes with the slot list (or null on failure).
     */
    public static CompletableFuture<List<ItemStack>> parseAsync(ItemStack stack) {
        // ── Capture everything we need on the main thread ──────────────────
        MinecraftClient    client     = MinecraftClient.getInstance();
        DataFixer          fixer      = client.getDataFixer();
        int                currentVer = SharedConstants.getGameVersion().dataVersion().id();
        DynamicRegistryManager reg    = resolveRegistries(client);
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
        var customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return null;
        NbtCompound nbt = customData.copyNbt();
        for (String key : nbt.getKeys()) {
            if (key.endsWith("_backpack_data") && nbt.get(key) instanceof NbtByteArray arr) {
                return arr.getByteArray();
            }
        }
        return null;
    }

    private static DynamicRegistryManager resolveRegistries(MinecraftClient client) {
        if (client.world != null)             return client.world.getRegistryManager();
        if (client.getNetworkHandler() != null) return client.getNetworkHandler().getRegistryManager();
        return DynamicRegistryManager.EMPTY;
    }

    private static List<ItemStack> parseBytes(byte[] data,
                                              DataFixer fixer,
                                              int currentVer,
                                              DynamicRegistryManager reg) {
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

    private static ItemStack parseItem(NbtCompound legacyNbt,
                                       DataFixer fixer,
                                       int currentVer,
                                       DynamicRegistryManager reg) {
        try {
            short numId = legacyNbt.getShort("id", (short) 0);

            NbtCompound dfuInput = legacyNbt.copy();
            Dynamic<NbtElement> dynamic = new Dynamic<>(NbtOps.INSTANCE, dfuInput);
            Dynamic<NbtElement> updated = fixer.update(
                    TypeReferences.ITEM_STACK, dynamic, LEGACY_DATA_VERSION, currentVer);
            NbtCompound modernNbt = (NbtCompound) updated.getValue();

            // resultOrPartial returns the partial result AND logs; suppress the log —
            // the "Item must not be minecraft:air" message is a DFU validation artefact
            // (every legacy item triggers it) but the partial ItemStack is correct.
            ItemStack stack = ItemStack.CODEC
                    .parse(reg.getOps(NbtOps.INSTANCE), modernNbt)
                    .resultOrPartial(err -> { /* suppress — partial result is usable */ })
                    .orElse(ItemStack.EMPTY);

            // ── Manual overrides for items DFU doesn't know ─────────────────
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

            // ── Spawn-egg overrides for post-1.8 entity types ───────────────
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

            // ── Layer Hypixel-specific data ──────────────────────────────────
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

            if (numId == 397 && tag.get("SkullOwner") instanceof NbtCompound skullOwner) {
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
                if (fmt == Formatting.RESET)       style = Style.EMPTY.withItalic(false);
                else if (fmt.isColor())            style = Style.EMPTY.withItalic(false).withColor(fmt);
                else                               style = style.withFormatting(fmt);
            }
            if (!text.isEmpty()) root.append(Text.literal(text).setStyle(style));
        }
        return root;
    }
}
