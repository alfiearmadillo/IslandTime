package org.example.islandtime;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Appends specified component values to item tooltips when enabled.
 * Reads component names from config and displays matching values from the item's custom data.
 */
public class ComponentDisplayHandler {

    public static boolean isEnabled() {
        IslandTimeConfig config = ConfigManager.getConfig();
        return config != null && config.misc.componentDisplayEnabled;
    }

    public static String[] getComponentNames() {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || config.misc.componentNames == null || config.misc.componentNames.trim().isEmpty()) {
            return new String[0];
        }
        return config.misc.componentNames.trim().split("\\s+");
    }

    /**
     * Appends component display lines to the tooltip list.
     * Call this after the tooltip has been built.
     */
    public static void appendComponentLines(ItemStack stack, List<Text> tooltip) {
        if (!isEnabled()) return;

        String[] names = getComponentNames();
        if (names.length == 0) return;

        // Get custom data NBT from the item
        NbtComponent nbtComponent = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (nbtComponent == null || nbtComponent.isEmpty()) return;

        NbtCompound nbt = nbtComponent.copyNbt();

        for (String name : names) {
            if (nbt.contains(name)) {
                NbtElement element = nbt.get(name);
                String value = element != null ? formatNbtValue(element) : "null";
                // Dark gray text (§8)
                tooltip.add(Text.literal("§8" + name + ": " + value));
            }
        }
    }

    private static String formatNbtValue(NbtElement element) {
        String raw = element.toString();
        // Strip surrounding quotes from strings
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        // Strip type suffixes (e.g. 123L, 1.0f)
        if (raw.endsWith("L") || raw.endsWith("l") || raw.endsWith("f") || raw.endsWith("F")
                || raw.endsWith("d") || raw.endsWith("D") || raw.endsWith("b") || raw.endsWith("B")
                || raw.endsWith("s") || raw.endsWith("S")) {
            String prefix = raw.substring(0, raw.length() - 1);
            try {
                Double.parseDouble(prefix);
                raw = prefix;
            } catch (NumberFormatException ignored) {}
        }
        return raw;
    }
}
