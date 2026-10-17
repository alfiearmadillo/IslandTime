package org.example.islandtime;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What is physically in a greenhouse's soil area right now, in "plot space": cell (0, 0) is the north
 * west block of the soil, x grows east and z south. The world is read into one of these (see
 * {@link GreenhouseBlueprintRenderer#scan}) and everything else - copying a layout, checking it against
 * a blueprint - works on it without touching Minecraft.
 */
final class GreenhouseScan {

    /** A non-air block in the layer the crops grow in. {@code state} is like {@code minecraft:wheat[age=7]}. */
    record BlockCell(int x, int z, String state) {}

    /**
     * @param floor  the block id under each cell (z * size + x), "" where there is none
     * @param blocks every non-air block in the crop layer
     * @param stands every armor stand that wears or holds something (stands with nothing on are holograms)
     */
    record Scan(List<String> floor, List<BlockCell> blocks, List<GreenhouseBlueprint.Stand> stands) {

        String floorAt(int x, int z) {
            int n = GreenhouseGeometry.AREA_SIZE;
            if (x < 0 || z < 0 || x >= n || z >= n) return "";
            int index = z * n + x;
            return index < floor.size() ? floor.get(index) : "";
        }

        boolean hasCrops() {
            return !blocks.isEmpty() || !stands.isEmpty();
        }
    }

    private GreenhouseScan() {}

    /** The block id of a state string: {@code minecraft:wheat[age=7]} gives {@code minecraft:wheat}. */
    static String idOf(String state) {
        if (state == null) return "";
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    /**
     * Drops the growth stage from a state string, so a crop copied while it was still growing is stored
     * (and drawn) fully grown, and the same layout copied at different times is the same layout.
     */
    static String stripAge(String state) {
        if (state == null) return "";
        int bracket = state.indexOf('[');
        if (bracket < 0 || !state.endsWith("]")) return state;
        StringBuilder kept = new StringBuilder();
        for (String property : state.substring(bracket + 1, state.length() - 1).split(",")) {
            if (property.isEmpty() || property.startsWith("age=")) continue;
            if (kept.length() > 0) kept.append(',');
            kept.append(property);
        }
        return kept.length() == 0 ? state.substring(0, bracket) : state.substring(0, bracket) + "[" + kept + "]";
    }

    /**
     * Whether two blocks count as the same plant. Only the block itself matters, not its growth stage or
     * other properties. The two mushrooms are interchangeable, as they are for the armor stand names.
     */
    static boolean sameBlock(String a, String b) {
        String idA = idOf(a);
        String idB = idOf(b);
        if (idA.isEmpty() || idB.isEmpty()) return false;
        if (idA.equals(idB)) return true;
        return isMushroom(idA) && isMushroom(idB);
    }

    private static boolean isMushroom(String id) {
        return id.equals("minecraft:red_mushroom") || id.equals("minecraft:brown_mushroom");
    }

    /**
     * What identifies an armor stand: the crop it is, when its head is named after one ("Veilshroom",
     * however it is worded), otherwise its head's name with everything but letters and digits dropped.
     */
    static String standKey(String head) {
        if (head == null) return "";
        GreenhouseCrops.Crop crop = GreenhouseCrops.fromPlantedName(head);
        return crop != null ? crop.key() : GreenhouseCrops.normalize(head);
    }

    static boolean sameStand(String headA, String headB) {
        String a = standKey(headA);
        return !a.isEmpty() && a.equals(standKey(headB));
    }

    /**
     * Whether an armor stand found in the greenhouse is the one the layout wants: the same head (by
     * name, so a skull whose texture changed as the crop grew still counts), and the same armor and
     * held items. A layout from before gear was copied only has its head to go by.
     */
    static boolean sameStand(GreenhouseBlueprint.Stand wanted, GreenhouseBlueprint.Stand actual) {
        boolean wantedHead = wanted.head != null && !wanted.head.isBlank();
        boolean actualHead = actual.head != null && !actual.head.isBlank();
        if (wantedHead != actualHead) return false;
        if (wantedHead && !sameStand(wanted.head, actual.head)) return false;
        return wanted.gear.isEmpty() || differingSlots(wanted, actual).isEmpty();
    }

    /**
     * The slots (other than the head, which {@link #sameStand(GreenhouseBlueprint.Stand, GreenhouseBlueprint.Stand)}
     * compares by name) where two stands wear or hold different things, including a piece one of them has
     * and the other hasn't.
     */
    static List<String> differingSlots(GreenhouseBlueprint.Stand wanted, GreenhouseBlueprint.Stand actual) {
        List<String> slots = new ArrayList<>();
        for (String slot : GreenhouseBlueprint.SLOTS) {
            if (slot.equals(GreenhouseBlueprint.SLOT_HEAD)) continue;
            GreenhouseBlueprint.Gear a = wanted.gearIn(slot);
            GreenhouseBlueprint.Gear b = actual.gearIn(slot);
            String keyA = a == null ? "" : a.key;
            String keyB = b == null ? "" : b.key;
            if (!keyA.equals(keyB)) slots.add(slot);
        }
        return slots;
    }

    /** {@code minecraft:soul_sand} becomes {@code Soul Sand}. */
    static String prettyName(String id) {
        String path = idOf(id);
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);
        StringBuilder sb = new StringBuilder(path.length());
        boolean startOfWord = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/') {
                sb.append(' ');
                startOfWord = true;
            } else {
                sb.append(startOfWord ? Character.toUpperCase(c) : c);
                startOfWord = false;
            }
        }
        return sb.toString();
    }

    /** A colour (0xRRGGBB) for something that has no colour of its own, the same every time. */
    static int colorOf(String key) {
        int hash = key == null ? 0 : key.toLowerCase(Locale.ROOT).hashCode();
        float hue = (Math.floorMod(hash * 0x9E3779B1, 1000)) / 1000.0f;
        return Color.HSBtoRGB(hue, 0.55f, 1.0f) & 0xFFFFFF;
    }
}
