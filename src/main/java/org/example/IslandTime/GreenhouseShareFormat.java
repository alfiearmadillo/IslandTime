package org.example.islandtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The compact binary form a greenhouse blueprint is shared in (see {@link ShareCodec} for how the bytes
 * become text). A blueprint saved on disk keeps every armor stand's complete item, which is thousands of
 * characters; what a shared layout needs is far less, so this format keeps only that:
 *
 * <ul>
 *   <li>an item is its type, its head skin (as the 32 byte hash of the texture) and its dye colour, plus the
 *       name and key the checks compare it by; lore, enchantments and other item data are left out;</li>
 *   <li>every distinct item and every distinct crop (the armor stands that make it up, relative to where it
 *       is planted) is written once and then referred to by number;</li>
 *   <li>positions are whole numbers of 1/64 of a block, yaws are one byte and a plot's soil is run length
 *       encoded.</li>
 * </ul>
 *
 * <p>Items are turned to and from the game's own representation by an {@link ItemLooks}, so this class has
 * no game code in it. Reading never trusts the data: every count and size is bounded before anything is
 * allocated, and anything out of range is reported as a damaged code.
 *
 * <p>Once released, neither this layout nor {@link #DICTIONARY} may change (a new layout gets a new
 * {@link #VERSION}), or codes already shared would stop working.
 */
final class GreenhouseShareFormat {

    /** The first byte of the data: the version of the layout. */
    static final int VERSION = 1;

    static final String HEAD_ID = "minecraft:player_head";

    /** How an item is kept in the item table. */
    private static final int KIND_HEAD = 0;
    private static final int KIND_OTHER = 1;
    private static final int KIND_NONE = 2;
    private static final int KIND_HEAD_SHORT_HASH = 3;
    private static final int ITEM_HAS_DYE = 4;
    private static final int ITEM_HAS_KEY = 8;

    /** Stand flag bits above the five of {@link GreenhouseBlueprint.Stand#flags}. */
    private static final int STAND_INVISIBLE = 32;
    private static final int STAND_HAS_POSE = 64;
    private static final int STAND_HAS_HEAD = 128;

    private static final String SKIN_URL_PART = "textures.minecraft.net/texture/";
    private static final int HASH_HEX_LENGTH = 64;

    private static final double POSITION_SCALE = 64.0;
    private static final int MAX_POSITION = 64_000;
    private static final double POSE_SCALE = 4.0;
    private static final int MAX_POSE = 1_000_000;
    private static final float YAW_STEP = 360.0f / 256.0f;

    private static final int MAX_ITEMS = 4096;
    private static final int MAX_TEMPLATES = 4096;
    private static final int MAX_STRING_BYTES = 2048;
    private static final int MAX_STANDS = 32;
    private static final int MAX_GEAR = 16;
    private static final int MAX_PLOTS = 64;
    private static final int MAX_ITEM_ID_CHARS = 256;
    /**
     * Limits on a whole layout. A crop is written once and placed many times, so a tiny code could
     * otherwise stand for a huge number of stands; these are far above what a real greenhouse needs.
     */
    private static final int MAX_TOTAL_STANDS = 1500;
    private static final int MAX_TOTAL_GEAR = 6000;
    private static final int MAX_PALETTE = GreenhouseBlueprint.PALETTE_CHARS.length();

    /**
     * Words common in a shared layout. The text compression starts out knowing them, so crop names and
     * block ids cost a few bits each instead of their full length.
     */
    static final byte[] DICTIONARY = dictionary();

    private GreenhouseShareFormat() {}

    /** An item as far as a shared layout is concerned. */
    record ItemLook(String id, String texture, int dye) {}

    /** Turns the game's items to and from what the format keeps of them. */
    interface ItemLooks {
        /** What to keep of a stored item, or null if it can't be read. {@code dye} is -1 for none. */
        ItemLook look(String data);

        /** The stored form of an item rebuilt from its look, or "" if it can't be. */
        String data(ItemLook look);
    }

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    /**
     * Writes a blueprint's layout.
     *
     * @throws IllegalArgumentException with a message fit to show the user if it can't be shared
     */
    static byte[] write(GreenhouseBlueprint blueprint, ItemLooks looks) {
        if (blueprint.boards.isEmpty()) throw new IllegalArgumentException("It has no boards.");
        if (blueprint.boards.size() > GreenhouseBlueprint.MAX_BOARDS) {
            throw new IllegalArgumentException("It has more than " + GreenhouseBlueprint.MAX_BOARDS + " boards.");
        }

        ItemTable items = new ItemTable(looks);
        Map<String, TemplateBuild> templates = new LinkedHashMap<>();
        List<List<Placed>> boards = new ArrayList<>();
        int[] totals = new int[2];
        for (GreenhouseBlueprint.Board board : blueprint.boards) {
            boards.add(collect(board, items, templates, totals));
        }
        if (totals[0] > MAX_TOTAL_STANDS || totals[1] > MAX_TOTAL_GEAR) {
            throw new IllegalArgumentException("It has too many armor stands.");
        }
        if (items.entries.size() > MAX_ITEMS) throw new IllegalArgumentException("It has too many different items.");
        if (templates.size() > MAX_TEMPLATES) throw new IllegalArgumentException("It has too many kinds of crops.");

        Out out = new Out();
        out.u8(VERSION);
        out.string(blueprint.name == null ? "" : blueprint.name);
        out.u8(Math.floorMod(blueprint.rotation, 4) | (blueprint.mirrored ? 4 : 0));
        out.varint(blueprint.boards.size());
        writePlots(out, blueprint);

        items.writeTo(out);
        out.varint(templates.size());
        for (TemplateBuild template : templates.values()) template.writeTo(out);

        for (int i = 0; i < blueprint.boards.size(); i++) {
            writeFloor(out, blueprint.boards.get(i));
            writePlacements(out, boards.get(i), templates);
        }
        return out.toByteArray();
    }

    private static void writePlots(Out out, GreenhouseBlueprint blueprint) {
        List<int[]> plots = new ArrayList<>();
        for (Map.Entry<String, Integer> plot : blueprint.plotBoards.entrySet()) {
            int comma = plot.getKey() == null ? -1 : plot.getKey().indexOf(',');
            Integer board = plot.getValue();
            if (comma < 0 || board == null || board < 0 || board >= blueprint.boards.size()) continue;
            try {
                int x = Integer.parseInt(plot.getKey().substring(0, comma).strip());
                int z = Integer.parseInt(plot.getKey().substring(comma + 1).strip());
                plots.add(new int[] {x, z, board});
            } catch (NumberFormatException e) {
                // Not a plot key: left out
            }
            if (plots.size() >= MAX_PLOTS) break;
        }
        out.varint(plots.size());
        for (int[] plot : plots) {
            out.zigzag(plot[0]);
            out.zigzag(plot[1]);
            out.varint(plot[2]);
        }
    }

    /** A placement on its way out: where it is, which template it is, and which way its stands face. */
    private record Placed(int position, TemplateBuild template, byte[] yaws) {}

    private static List<Placed> collect(GreenhouseBlueprint.Board board, ItemTable items,
                                        Map<String, TemplateBuild> templates, int[] totals) {
        int size = GreenhouseGeometry.AREA_SIZE;
        if (board.size != size) throw new IllegalArgumentException("A board isn't " + size + "x" + size + " cells.");
        if (board.placements.size() > size * size) throw new IllegalArgumentException("A board has too many crops.");

        // Row by row, so the same layout always gives the same code however it was copied
        List<GreenhouseBlueprint.Placement> ordered = new ArrayList<>(board.placements);
        ordered.sort((a, b) -> a.z != b.z ? Integer.compare(a.z, b.z) : Integer.compare(a.x, b.x));

        List<Placed> placed = new ArrayList<>();
        for (GreenhouseBlueprint.Placement p : ordered) {
            if (p.size < 1 || p.size > size || p.x < 0 || p.z < 0 || p.x > size - p.size || p.z > size - p.size) {
                throw new IllegalArgumentException("A crop is outside the greenhouse.");
            }
            if (p.stands.size() > MAX_STANDS) throw new IllegalArgumentException("A crop has too many armor stands.");

            Out shape = new Out();
            shape.string(p.crop);
            shape.string(p.name);
            shape.string(p.block);
            shape.varint(p.size);
            shape.varint(p.stands.size());
            byte[] yaws = new byte[p.stands.size()];
            for (int i = 0; i < p.stands.size(); i++) {
                GreenhouseBlueprint.Stand stand = p.stands.get(i);
                writeStand(shape, stand, p, items);
                yaws[i] = yawByte(stand.yaw);
                totals[1] += stand.gear.size();
            }
            totals[0] += p.stands.size();

            String key = new String(shape.toByteArray(), StandardCharsets.ISO_8859_1);
            TemplateBuild template = templates.computeIfAbsent(key,
                    k -> new TemplateBuild(templates.size(), shape.toByteArray(), yaws));
            if (!Arrays.equals(template.yaws, yaws)) template.yawsVary = true;
            placed.add(new Placed(p.z * size + p.x, template, yaws));
        }
        placed.sort((a, b) -> Integer.compare(a.position, b.position));
        return placed;
    }

    private static void writeStand(Out out, GreenhouseBlueprint.Stand stand, GreenhouseBlueprint.Placement p,
                                   ItemTable items) {
        if (!Double.isFinite(stand.x) || !Double.isFinite(stand.y) || !Double.isFinite(stand.z)
                || !Double.isFinite(stand.width) || !Double.isFinite(stand.height)) {
            throw new IllegalArgumentException("An armor stand is somewhere impossible.");
        }
        out.zigzag(position(stand.x - p.x));
        out.zigzag(position(stand.y));
        out.zigzag(position(stand.z - p.z));
        out.varint(Math.max(0, position(stand.width)));
        out.varint(Math.max(0, position(stand.height)));

        List<int[]> gear = new ArrayList<>();
        GreenhouseBlueprint.Gear headGear = null;
        for (GreenhouseBlueprint.Gear g : stand.gear) {
            int slot = Arrays.asList(GreenhouseBlueprint.SLOTS).indexOf(g.slot);
            if (slot < 0) continue;
            if (gear.size() >= MAX_GEAR) throw new IllegalArgumentException("An armor stand wears too much.");
            gear.add(new int[] {slot, items.indexOf(g)});
            if (GreenhouseBlueprint.SLOT_HEAD.equals(g.slot)) headGear = g;
        }

        String head = stand.head == null ? "" : stand.head;
        boolean hasHead = !head.equals(headGear == null ? "" : headGear.name);
        boolean hasPose = stand.pose != null && stand.pose.length == GreenhouseBlueprint.POSE_VALUES;
        out.u8((stand.flags & 31) | (stand.invisible ? STAND_INVISIBLE : 0)
                | (hasPose ? STAND_HAS_POSE : 0) | (hasHead ? STAND_HAS_HEAD : 0));
        if (hasPose) {
            for (float angle : stand.pose) {
                long q = Math.round(angle * POSE_SCALE);
                out.zigzag((int) Math.max(-MAX_POSE, Math.min(MAX_POSE, q)));
            }
        }
        if (hasHead) out.string(head);
        out.varint(gear.size());
        for (int[] g : gear) {
            out.u8(g[0]);
            out.varint(g[1]);
        }
    }

    private static int position(double value) {
        long q = Math.round(value * POSITION_SCALE);
        if (Math.abs(q) > MAX_POSITION) throw new IllegalArgumentException("An armor stand is somewhere impossible.");
        return (int) q;
    }

    private static byte yawByte(float yaw) {
        return (byte) Math.round(yaw / YAW_STEP);
    }

    private static float yawOf(int b) {
        return (byte) b * YAW_STEP;
    }

    private static void writeFloor(Out out, GreenhouseBlueprint.Board board) {
        int size = board.size;
        String[][] grid = board.floorGrid();
        List<String> palette = new ArrayList<>();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (!grid[z][x].isEmpty() && !palette.contains(grid[z][x])) palette.add(grid[z][x]);
            }
        }
        if (palette.size() > MAX_PALETTE) throw new IllegalArgumentException("A board has too many kinds of soil.");

        out.varint(palette.size());
        if (palette.isEmpty()) return;
        for (String block : palette) out.string(block);

        // Runs of the same soil, as (run - 1) * (palette + 1) + value, where value 0 is no soil
        int run = 0;
        int previous = -1;
        for (int i = 0; i <= size * size; i++) {
            int value = i == size * size ? -2 : palette.indexOf(grid[i / size][i % size]) + 1;
            if (value == previous) {
                run++;
                continue;
            }
            if (run > 0) out.varint((long) (run - 1) * (palette.size() + 1) + previous);
            previous = value;
            run = 1;
        }
    }

    private static void writePlacements(Out out, List<Placed> placed, Map<String, TemplateBuild> templates) {
        // Placements of the same crop go together, in the order the crops were first met
        Map<TemplateBuild, List<Placed>> groups = new LinkedHashMap<>();
        for (Placed p : placed) groups.computeIfAbsent(p.template, k -> new ArrayList<>()).add(p);

        out.varint(groups.size());
        for (Map.Entry<TemplateBuild, List<Placed>> group : groups.entrySet()) {
            out.varint(group.getKey().index);
            out.varint(group.getValue().size());
            int previous = 0;
            for (Placed p : group.getValue()) {
                out.varint(p.position - previous);
                previous = p.position;
                if (group.getKey().yawsVary) out.bytes(p.yaws);
            }
        }
    }

    /** What a crop is made of, apart from where it is and which way its stands face. */
    private static final class TemplateBuild {
        final int index;
        final byte[] shape;
        final byte[] yaws;
        boolean yawsVary;

        TemplateBuild(int index, byte[] shape, byte[] yaws) {
            this.index = index;
            this.shape = shape;
            this.yaws = yaws;
        }

        void writeTo(Out out) {
            out.u8(yawsVary ? 1 : 0);
            out.bytes(shape);
            if (!yawsVary) out.bytes(yaws);
        }
    }

    /** The distinct items of a layout, in the order they are first used. */
    private static final class ItemTable {
        private record Item(int kind, String id, String hash, int dye, String name, String key) {}

        final ItemLooks looks;
        final List<Item> entries = new ArrayList<>();
        final Map<Item, Integer> indexes = new HashMap<>();

        ItemTable(ItemLooks looks) {
            this.looks = looks;
        }

        int indexOf(GreenhouseBlueprint.Gear gear) {
            Item item = itemOf(gear);
            Integer known = indexes.get(item);
            if (known != null) return known;
            entries.add(item);
            indexes.put(item, entries.size() - 1);
            return entries.size() - 1;
        }

        private Item itemOf(GreenhouseBlueprint.Gear gear) {
            String name = gear.name == null ? "" : gear.name;
            String key = gear.key == null ? "" : gear.key;
            ItemLook look = gear.data == null || gear.data.isEmpty() ? null : looks.look(gear.data);
            if (look == null || look.id() == null || look.id().isEmpty() || look.id().length() > MAX_ITEM_ID_CHARS) {
                return new Item(KIND_NONE, "", "", -1, name, key);
            }
            int dye = look.dye() >= 0 && look.dye() <= 0xFFFFFF ? look.dye() : -1;
            String hash = HEAD_ID.equals(look.id()) ? skinHash(look.texture()) : null;
            if (hash != null) {
                return new Item(hash.length() == HASH_HEX_LENGTH ? KIND_HEAD : KIND_HEAD_SHORT_HASH, "", hash, dye,
                        name, key);
            }
            return new Item(KIND_OTHER, look.id(), "", dye, name, key);
        }

        void writeTo(Out out) {
            out.varint(entries.size());
            for (Item item : entries) {
                boolean hasKey = item.kind == KIND_NONE || !item.key.equals(expectedKey(item.kind, item.id, item.name));
                out.u8(item.kind | (item.dye >= 0 ? ITEM_HAS_DYE : 0) | (hasKey ? ITEM_HAS_KEY : 0));
                switch (item.kind) {
                    case KIND_HEAD -> out.bytes(hexToBytes(item.hash));
                    case KIND_HEAD_SHORT_HASH -> {
                        out.u8(HASH_HEX_LENGTH - item.hash.length());
                        out.bytes(hexToBytes("0".repeat(HASH_HEX_LENGTH - item.hash.length()) + item.hash));
                    }
                    case KIND_OTHER -> out.string(item.id);
                    default -> { }
                }
                if (item.dye >= 0) {
                    out.u8(item.dye >>> 16);
                    out.u8(item.dye >>> 8);
                    out.u8(item.dye);
                }
                out.string(item.name);
                if (hasKey) out.string(item.key);
            }
        }
    }

    private static String expectedKey(int kind, String id, String name) {
        boolean head = kind == KIND_HEAD || kind == KIND_HEAD_SHORT_HASH;
        return (head ? HEAD_ID : id) + "|" + name;
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    /**
     * Reads a layout written by {@link #write}. Nothing is checked beyond that the data is well formed
     * and in range; {@link GreenhouseBlueprintShare} does the rest.
     *
     * @throws IllegalArgumentException with a message fit to show the user if the data can't be read
     */
    static GreenhouseBlueprint read(byte[] data, ItemLooks looks) {
        In in = new In(data);
        int version = in.u8();
        if (version != VERSION) {
            throw new IllegalArgumentException(version > VERSION
                    ? "That code was made by a newer version of IslandTime. Update the mod to import it."
                    : "That code is not an IslandTime greenhouse code.");
        }

        GreenhouseBlueprint blueprint = new GreenhouseBlueprint();
        blueprint.name = in.string();
        int orientation = in.u8();
        blueprint.rotation = orientation & 3;
        blueprint.mirrored = (orientation & 4) != 0;
        if ((orientation & ~7) != 0) throw ShareCodec.damaged();

        int boardCount = in.varint(GreenhouseBlueprint.MAX_BOARDS);
        if (boardCount < 1) throw ShareCodec.damaged();
        int plots = in.varint(MAX_PLOTS);
        for (int i = 0; i < plots; i++) {
            int x = in.zigzag(Integer.MAX_VALUE / 2);
            int z = in.zigzag(Integer.MAX_VALUE / 2);
            int board = in.varint(boardCount - 1);
            blueprint.plotBoards.put(GreenhouseGeometry.plotKey(x, z), board);
        }

        List<ItemEntry> items = readItems(in, looks);
        List<Template> templates = readTemplates(in, items.size());

        int[] totals = new int[2];
        for (int b = 0; b < boardCount; b++) {
            GreenhouseBlueprint.Board board = new GreenhouseBlueprint.Board();
            readFloor(in, board);
            readPlacements(in, board, templates, items, totals);
            blueprint.boards.add(board);
        }
        if (!in.atEnd()) throw ShareCodec.damaged();
        return blueprint;
    }

    /** An item of the item table, with its stored form made the first time it is needed. */
    private static final class ItemEntry {
        final ItemLook look;
        final String key;
        final String name;
        private final ItemLooks looks;
        private String data;

        ItemEntry(ItemLook look, String key, String name, ItemLooks looks) {
            this.look = look;
            this.key = key;
            this.name = name;
            this.looks = looks;
        }

        String data() {
            if (data == null) data = look == null ? "" : looks.data(look);
            return data == null ? "" : data;
        }
    }

    private static List<ItemEntry> readItems(In in, ItemLooks looks) {
        int count = in.varint(MAX_ITEMS);
        List<ItemEntry> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int flags = in.u8();
            int kind = flags & 3;
            if ((flags & ~15) != 0) throw ShareCodec.damaged();

            String id = "";
            String texture = "";
            switch (kind) {
                case KIND_HEAD -> texture = skinValue(bytesToHex(in.bytes(HASH_HEX_LENGTH / 2)));
                case KIND_HEAD_SHORT_HASH -> {
                    // A hash whose leading zeros were left off, which Mojang's do now and then
                    int zeros = in.u8();
                    String hash = bytesToHex(in.bytes(HASH_HEX_LENGTH / 2));
                    if (zeros < 1 || zeros >= HASH_HEX_LENGTH || !hash.startsWith("0".repeat(zeros))) {
                        throw ShareCodec.damaged();
                    }
                    texture = skinValue(hash.substring(zeros));
                }
                case KIND_OTHER -> {
                    id = in.string();
                    if (id.isEmpty() || id.length() > MAX_ITEM_ID_CHARS) throw ShareCodec.damaged();
                }
                default -> { }
            }
            int dye = -1;
            if ((flags & ITEM_HAS_DYE) != 0) dye = (in.u8() << 16) | (in.u8() << 8) | in.u8();
            String name = in.string();
            boolean head = kind == KIND_HEAD || kind == KIND_HEAD_SHORT_HASH;
            String key = (flags & ITEM_HAS_KEY) != 0 || kind == KIND_NONE
                    ? in.string() : expectedKey(kind, id, name);

            ItemLook look = kind == KIND_NONE ? null : new ItemLook(head ? HEAD_ID : id, texture, dye);
            items.add(new ItemEntry(look, key, name, looks));
        }
        return items;
    }

    /** A crop as read: its shape, and the facing of its stands unless that is different for each placement. */
    private record Template(String crop, String name, String block, int size, List<StandShape> stands,
                           int gear, boolean yawsVary, byte[] yaws) {}

    private record StandShape(int dx, int dy, int dz, int width, int height, int flags, float[] pose,
                              String head, int[] slots, int[] items) {}

    private static List<Template> readTemplates(In in, int itemCount) {
        int count = in.varint(MAX_TEMPLATES);
        List<Template> templates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int flags = in.u8();
            if ((flags & ~1) != 0) throw ShareCodec.damaged();
            boolean yawsVary = (flags & 1) != 0;

            String crop = in.string();
            String name = in.string();
            String block = in.string();
            int size = in.varint(GreenhouseGeometry.AREA_SIZE);
            if (size < 1) throw ShareCodec.damaged();
            int standCount = in.varint(MAX_STANDS);
            List<StandShape> stands = new ArrayList<>(standCount);
            int gear = 0;
            for (int s = 0; s < standCount; s++) {
                StandShape stand = readStand(in, itemCount);
                gear += stand.slots().length;
                stands.add(stand);
            }
            byte[] yaws = yawsVary ? null : in.bytes(standCount);
            templates.add(new Template(crop, name, block, size, stands, gear, yawsVary, yaws));
        }
        return templates;
    }

    private static StandShape readStand(In in, int itemCount) {
        int dx = in.zigzag(MAX_POSITION);
        int dy = in.zigzag(MAX_POSITION);
        int dz = in.zigzag(MAX_POSITION);
        int width = in.varint(MAX_POSITION);
        int height = in.varint(MAX_POSITION);
        int flags = in.u8();

        float[] pose = new float[0];
        if ((flags & STAND_HAS_POSE) != 0) {
            pose = new float[GreenhouseBlueprint.POSE_VALUES];
            for (int i = 0; i < pose.length; i++) pose[i] = (float) (in.zigzag(MAX_POSE) / POSE_SCALE);
        }
        String head = (flags & STAND_HAS_HEAD) != 0 ? in.string() : null;

        int gearCount = in.varint(MAX_GEAR);
        int[] slots = new int[gearCount];
        int[] items = new int[gearCount];
        for (int i = 0; i < gearCount; i++) {
            slots[i] = in.varint(GreenhouseBlueprint.SLOTS.length - 1);
            items[i] = in.varint(itemCount - 1);
        }
        return new StandShape(dx, dy, dz, width, height, flags, pose, head, slots, items);
    }

    private static void readFloor(In in, GreenhouseBlueprint.Board board) {
        int size = board.size;
        int paletteCount = in.varint(MAX_PALETTE);
        if (paletteCount == 0) return;

        List<String> palette = new ArrayList<>(paletteCount);
        for (int i = 0; i < paletteCount; i++) palette.add(in.string());

        String[][] grid = new String[size][size];
        int cell = 0;
        while (cell < size * size) {
            long token = in.varlong();
            long length = token / (paletteCount + 1) + 1;
            int value = (int) (token % (paletteCount + 1));
            if (length > size * size - cell) throw ShareCodec.damaged();
            int run = (int) length;
            for (int i = 0; i < run; i++, cell++) grid[cell / size][cell % size] = value == 0 ? "" : palette.get(value - 1);
        }
        board.setFloor(grid);
    }

    private static void readPlacements(In in, GreenhouseBlueprint.Board board, List<Template> templates,
                                       List<ItemEntry> items, int[] totals) {
        int size = board.size;
        int groups = in.varint(size * size);
        int total = 0;
        for (int g = 0; g < groups; g++) {
            int templateIndex = in.varint(templates.size() - 1);
            Template template = templates.get(templateIndex);
            int count = in.varint(size * size);
            if (count < 1 || total + count > size * size) throw ShareCodec.damaged();
            total += count;

            int position = 0;
            for (int i = 0; i < count; i++) {
                position += in.varint(size * size);
                if (position >= size * size) throw ShareCodec.damaged();
                int x = position % size;
                int z = position / size;
                if (x > size - template.size || z > size - template.size) throw ShareCodec.damaged();
                byte[] yaws = template.yawsVary ? in.bytes(template.stands.size()) : template.yaws;
                totals[0] += template.stands.size();
                totals[1] += template.gear;
                if (totals[0] > MAX_TOTAL_STANDS || totals[1] > MAX_TOTAL_GEAR) {
                    throw new IllegalArgumentException("That layout has too many armor stands.");
                }
                board.placements.add(place(template, x, z, yaws, items));
            }
        }
        board.placements.sort((a, b) -> a.z != b.z ? Integer.compare(a.z, b.z) : Integer.compare(a.x, b.x));
    }

    private static GreenhouseBlueprint.Placement place(Template template, int x, int z, byte[] yaws,
                                                       List<ItemEntry> items) {
        GreenhouseBlueprint.Placement p = new GreenhouseBlueprint.Placement(template.crop, x, z, template.size);
        p.name = template.name;
        p.block = template.block;
        for (int i = 0; i < template.stands.size(); i++) {
            StandShape shape = template.stands.get(i);
            GreenhouseBlueprint.Stand stand = new GreenhouseBlueprint.Stand("", x + shape.dx / POSITION_SCALE,
                    shape.dy / POSITION_SCALE, z + shape.dz / POSITION_SCALE, shape.width / POSITION_SCALE,
                    shape.height / POSITION_SCALE);
            String headGearName = "";
            for (int g = 0; g < shape.slots.length; g++) {
                ItemEntry item = items.get(shape.items[g]);
                String slot = GreenhouseBlueprint.SLOTS[shape.slots[g]];
                stand.gear.add(new GreenhouseBlueprint.Gear(slot, item.key, item.name, item.data()));
                if (GreenhouseBlueprint.SLOT_HEAD.equals(slot)) headGearName = item.name;
            }
            stand.head = shape.head != null ? shape.head : headGearName;
            stand.flags = shape.flags & 31;
            stand.invisible = (shape.flags & STAND_INVISIBLE) != 0;
            stand.pose = shape.pose.clone();
            stand.yaw = yawOf(yaws[i]);
            p.stands.add(stand);
        }
        return p;
    }

    // -------------------------------------------------------------------------
    // Head skins
    // -------------------------------------------------------------------------

    /**
     * The hash that names a skin on Mojang's texture server, in lower case hex (up to 64 digits), from the
     * Base64 texture value of a head. Null if the value isn't one that points at that server.
     */
    static String skinHash(String textureValue) {
        if (textureValue == null || textureValue.isEmpty()) return null;
        String json;
        try {
            json = new String(Base64.getMimeDecoder().decode(textureValue), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int at = json.indexOf(SKIN_URL_PART);
        if (at < 0) return null;
        int start = at + SKIN_URL_PART.length();
        int end = start;
        while (end < json.length() && Character.digit(json.charAt(end), 16) >= 0 && json.charAt(end) < 128) end++;
        String hash = json.substring(start, end).toLowerCase(Locale.ROOT);
        return isHex(hash, 1, HASH_HEX_LENGTH) ? hash : null;
    }

    /** A texture value that points at a skin, from its hash: what a head needs to show it. */
    static String skinValue(String hash) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://" + SKIN_URL_PART + hash + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isHex(String text, int min, int max) {
        if (text.length() < min || text.length() > max) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4) | Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return bytes;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Bytes
    // -------------------------------------------------------------------------

    private static final class Out {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        void u8(int value) {
            buffer.write(value & 0xFF);
        }

        /** An unsigned number, 7 bits to a byte, lowest bits first. */
        void varint(long value) {
            if (value < 0) throw new IllegalArgumentException("A number is out of range.");
            while (value >= 0x80) {
                buffer.write((int) (value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buffer.write((int) value);
        }

        /** A signed number, so that small ones of either sign are short. */
        void zigzag(long value) {
            varint((value << 1) ^ (value >> 63));
        }

        void string(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("A name is too long.");
            varint(bytes.length);
            buffer.write(bytes, 0, bytes.length);
        }

        void bytes(byte[] value) {
            buffer.write(value, 0, value.length);
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }

    private static final class In {
        private final byte[] data;
        private int position;

        In(byte[] data) {
            this.data = data;
        }

        boolean atEnd() {
            return position == data.length;
        }

        int u8() {
            if (position >= data.length) throw ShareCodec.damaged();
            return data[position++] & 0xFF;
        }

        byte[] bytes(int count) {
            if (count < 0 || count > data.length - position) throw ShareCodec.damaged();
            byte[] result = Arrays.copyOfRange(data, position, position + count);
            position += count;
            return result;
        }

        long varlong() {
            long result = 0;
            for (int shift = 0; shift < 63; shift += 7) {
                int b = u8();
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw ShareCodec.damaged();
        }

        int varint(int max) {
            long value = varlong();
            if (value < 0 || value > max) throw ShareCodec.damaged();
            return (int) value;
        }

        int zigzag(int max) {
            long raw = varlong();
            long value = (raw >>> 1) ^ -(raw & 1);
            if (value < -max || value > max) throw ShareCodec.damaged();
            return (int) value;
        }

        String string() {
            int length = varint(MAX_STRING_BYTES);
            if (length > data.length - position) throw ShareCodec.damaged();
            String value = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }
    }

    // -------------------------------------------------------------------------
    // The dictionary
    // -------------------------------------------------------------------------

    private static byte[] dictionary() {
        String[] words = {
                // Soil
                "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:podzol",
                "minecraft:mycelium", "minecraft:sand", "minecraft:red_sand", "minecraft:mud",
                "minecraft:moss_block", "minecraft:soul_soil", "minecraft:netherrack", "minecraft:end_stone",
                "minecraft:soul_sand", "minecraft:farmland",
                // Items
                "minecraft:leather_boots", "minecraft:leather_leggings", "minecraft:leather_chestplate",
                "minecraft:leather_helmet", "minecraft:diamond_hoe", "minecraft:iron_hoe", "minecraft:stick",
                // Mutations
                "ALL_IN_ALOE", "All-in Aloe", "ASHWREATH", "Ashwreath", "BLASTBERRY", "Blastberry", "CHEESEBITE",
                "Cheesebite", "CHLORONITE", "Chloronite", "CHOCOBERRY", "Chocoberry", "CHOCONUT", "Choconut",
                "CHORUS_FRUIT", "Chorus Fruit", "CINDERSHADE", "Cindershade", "COALROOT", "Coalroot",
                "CREAMBLOOM", "Creambloom", "DEVOURER", "Devourer", "DO_NOT_EAT_SHROOM", "Do-not-eat-shroom",
                "DUSKBLOOM", "Duskbloom", "DUSTGRAIN", "Dustgrain", "FLESHTRAP", "Fleshtrap", "GLASSCORN",
                "Glasscorn", "GLOOMGOURD", "Gloomgourd", "GODSEED", "Godseed", "JERRYFLOWER", "Jerryflower",
                "LONELILY", "Lonelily", "MAGIC_JELLYBEAN", "Magic Jellybean", "NOCTILUME", "Noctilume",
                "PHANTOMLEAF", "Phantomleaf", "PLANTBOY_ADVANCE", "PlantBoy Advance", "PUFFERCLOUD",
                "Puffercloud", "SCOURROOT", "Scourroot", "SHADEVINE", "Shadevine", "SHELLFRUIT", "Shellfruit",
                "SNOOZLING", "Snoozling", "SOGGYBUD", "Soggybud", "STARTLEVINE", "Startlevine",
                "STOPLIGHT_PETAL", "Stoplight Petal", "THORNSHADE", "Thornshade", "THUNDERLING", "Thunderling",
                "TIMESTALK", "Timestalk", "TURTLELLINI", "Turtlellini", "VEILSHROOM", "Veilshroom",
                "WITHERBLOOM", "Witherbloom", "ZOMBUD", "Zombud",
                // Crops
                "FERMENTO", "Fermento", "HELIANTHUS", "Helianthus", "SQUASH", "Squash", "CROPIE", "Cropie",
                "DEAD_PLANT", "Dead Plant", "FIRE", "Fire", "WILD_ROSE", "Wild Rose", "SUNFLOWER", "Sunflower",
                "MOONFLOWER", "Moonflower", "BROWN_MUSHROOM", "Brown Mushroom", "RED_MUSHROOM", "Red Mushroom",
                "NETHER_WART", "Nether Wart", "CACTUS", "Cactus", "SUGAR_CANE", "Sugar Cane", "COCOA_BEANS",
                "Cocoa Beans", "MELON", "Melon", "PUMPKIN", "Pumpkin", "CARROT", "Carrot", "POTATO", "Potato",
                "WHEAT", "Wheat",
                // Blocks
                "minecraft:attached_melon_stem", "minecraft:attached_pumpkin_stem", "minecraft:melon_stem",
                "minecraft:pumpkin_stem", "minecraft:dead_bush", "minecraft:fire", "minecraft:rose_bush",
                "minecraft:sunflower", "minecraft:blue_orchid", "minecraft:red_mushroom",
                "minecraft:brown_mushroom", "minecraft:nether_wart", "minecraft:cactus", "minecraft:sugar_cane",
                "minecraft:cocoa", "minecraft:melon", "minecraft:pumpkin", "minecraft:carrots",
                "minecraft:potatoes", "minecraft:wheat", "[facing=north]", "[facing=east]", "[facing=south]",
                "[facing=west]", "[half=lower]", "[half=upper]", "[facing=north,half=lower]", "Planted ",
                HEAD_ID,
        };
        StringBuilder sb = new StringBuilder();
        for (String word : words) sb.append(word);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
