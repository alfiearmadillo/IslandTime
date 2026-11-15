package org.example.islandtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/**
 * Appends specified component values to item tooltips when enabled.
 * Reads component names from config and displays matching values from the item's custom data.
 * A name starting with {@code pet_} reads from the pet's {@code petInfo} instead, so
 * {@code pet_candyUsed} shows that value (matched without regard to case).
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
        JsonObject petInfo = null;
        boolean petInfoRead = false;

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
                    for (String key : compoundTag.keySet()) {
                        Tag child = compoundTag.get(key);
                        String childValue = child != null ? formatNbtValue(child) : "null";
                        tooltip.add(Component.literal("\u00A78  " + key + ": " + childValue));
                    }
                } else {
                    tooltip.add(Component.literal("\u00A78" + name + ": " + formatNbtValue(element)));
                }
            } else if (name.regionMatches(true, 0, PET_PREFIX, 0, PET_PREFIX.length())
                    && name.length() > PET_PREFIX.length()) {
                if (!petInfoRead) {
                    petInfo = readPetInfo(nbt);
                    petInfoRead = true;
                }
                appendPetLines(tooltip, name, name.substring(PET_PREFIX.length()), petInfo);
            }
        }
    }

    /** Names starting with this look inside the pet's {@code petInfo} data instead of the item's own. */
    private static final String PET_PREFIX = "pet_";

    /** The pet's {@code petInfo}, which SkyBlock stores as a JSON string, or null when the item has none. */
    private static JsonObject readPetInfo(CompoundTag nbt) {
        String raw = nbt.getStringOr("petInfo", "");
        if (raw.isEmpty()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** Adds the lines for one petInfo value, matching the layout used for values on the item itself. */
    private static void appendPetLines(List<Component> tooltip, String label, String key, JsonObject petInfo) {
        if (petInfo == null) return;

        JsonElement value = null;
        for (Map.Entry<String, JsonElement> entry : petInfo.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                value = entry.getValue();
                break;
            }
        }
        if (value == null) return;

        if (value.isJsonArray()) {
            tooltip.add(Component.literal("\u00A78" + label + ":"));
            for (JsonElement child : value.getAsJsonArray()) {
                tooltip.add(Component.literal("\u00A78  " + formatJsonValue(child)));
            }
        } else if (value.isJsonObject()) {
            tooltip.add(Component.literal("\u00A78" + label + ":"));
            for (Map.Entry<String, JsonElement> child : value.getAsJsonObject().entrySet()) {
                tooltip.add(Component.literal("\u00A78  " + child.getKey() + ": " + formatJsonValue(child.getValue())));
            }
        } else {
            tooltip.add(Component.literal("\u00A78" + label + ": " + formatJsonValue(value)));
        }
    }

    private static String formatJsonValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return "null";
        if (element.isJsonPrimitive()) return element.getAsString();
        return element.toString();
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
