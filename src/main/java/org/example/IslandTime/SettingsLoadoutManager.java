package org.example.islandtime;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages named settings loadouts — full copies of the MoulConfig JSON file
 * that can be saved and restored by name.
 *
 * Loadouts are stored as {@code config/IslandTime/loadouts/<name>.json}.
 */
public class SettingsLoadoutManager {

    private static final File LOADOUTS_DIR = new File("config/IslandTime/loadouts");
    private static final File CONFIG_FILE = new File("config/IslandTime/moulconfig.json");

    /**
     * Save the current settings as a named loadout.
     */
    public static void save(Minecraft client, String name) {
        // Ensure current config is persisted
        ConfigManager.syncFromExistingConfigs();
        ConfigManager.saveToFile();

        LOADOUTS_DIR.mkdirs();
        File dest = new File(LOADOUTS_DIR, name + ".json");
        try {
            Files.copy(CONFIG_FILE.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            sendMessage(client, Component.literal("[IslandTime] Saved settings loadout: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(name).withStyle(ChatFormatting.GREEN)));
        } catch (IOException e) {
            sendMessage(client, Component.literal("[IslandTime] Failed to save loadout: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Load a named loadout, replacing the current settings.
     */
    public static void load(Minecraft client, String name) {
        File src = new File(LOADOUTS_DIR, name + ".json");
        if (!src.exists()) {
            sendMessage(client, Component.literal("[IslandTime] No loadout found with name: ").withStyle(ChatFormatting.RED)
                    .append(Component.literal(name).withStyle(ChatFormatting.YELLOW)));
            return;
        }

        try {
            Files.copy(src.toPath(), CONFIG_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
            // Re-initialize config from the new file
            ConfigManager.initialize();
            ConfigManager.syncToExistingConfigs();
            sendMessage(client, Component.literal("[IslandTime] Loaded settings loadout: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(name).withStyle(ChatFormatting.GREEN)));
        } catch (IOException e) {
            sendMessage(client, Component.literal("[IslandTime] Failed to load loadout: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Delete a named loadout.
     */
    public static void delete(Minecraft client, String name) {
        File file = new File(LOADOUTS_DIR, name + ".json");
        if (!file.exists()) {
            sendMessage(client, Component.literal("[IslandTime] No loadout found with name: ").withStyle(ChatFormatting.RED)
                    .append(Component.literal(name).withStyle(ChatFormatting.YELLOW)));
            return;
        }

        if (file.delete()) {
            sendMessage(client, Component.literal("[IslandTime] Deleted settings loadout: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(name).withStyle(ChatFormatting.GREEN)));
        } else {
            sendMessage(client, Component.literal("[IslandTime] Failed to delete loadout: " + name).withStyle(ChatFormatting.RED));
        }
    }

    /**
     * List all saved loadouts.
     */
    public static void list(Minecraft client) {
        List<String> names = getLoadoutNames();
        if (names.isEmpty()) {
            sendMessage(client, Component.literal("[IslandTime] No saved settings loadouts.").withStyle(ChatFormatting.YELLOW));
            return;
        }

        sendMessage(client, Component.literal("[IslandTime] Saved loadouts (" + names.size() + "):").withStyle(ChatFormatting.GOLD));
        for (String n : names) {
            sendMessage(client, Component.literal("  - ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(n).withStyle(ChatFormatting.GREEN)));
        }
    }

    private static List<String> getLoadoutNames() {
        if (!LOADOUTS_DIR.exists()) return Collections.emptyList();
        File[] files = LOADOUTS_DIR.listFiles((dir, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) return Collections.emptyList();
        return Arrays.stream(files)
                .map(f -> f.getName().replace(".json", ""))
                .sorted()
                .collect(Collectors.toList());
    }

    private static void sendMessage(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendSystemMessage(message);
        }
    }
}
