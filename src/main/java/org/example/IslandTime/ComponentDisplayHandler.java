package org.example.islandtime;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

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
    public static void appendComponentLines(ItemStack stack, List<Component> tooltip) {
        if (!isEnabled()) return;

        String[] names = getComponentNames();
        if (names.length == 0) return;

        // Get custom data NBT from the item
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) return;

        CompoundTag nbt = customData.copyTag();

        for (String name : names) {
            if (nbt.contains(name)) {
                Tag element = nbt.get(name);
                if (element == null) {
                    tooltip.add(Component.literal("\u00A78" + name + ": null"));
                } else if (element instanceof ListTag listTag) {
                    tooltip.add(Component.literal("\u00A78" + name + ":"));
                    for (int i = 0; i < listTag.size(); i++) {
                        tooltip.add(Component.literal("\u00A78  " + formatNbtValue(listTag.get(i))));
                    }
                } else if (element instanceof CompoundTag compoundTag) {
                    tooltip.add(Component.literal("\u00A78" + name + ":"));
                    for (String key : compoundTag.getAllKeys()) {
                        Tag child = compoundTag.get(key);
                        String childValue = child != null ? formatNbtValue(child) : "null";
                        tooltip.add(Component.literal("\u00A78  " + key + ": " + childValue));
                    }
                } else {
                    tooltip.add(Component.literal("\u00A78" + name + ": " + formatNbtValue(element)));
                }
            }
        }
    }

    private static String formatNbtValue(Tag element) {
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
