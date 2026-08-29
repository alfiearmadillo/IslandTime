package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages named settings loadouts and settings groups.
 *
 * Loadouts are stored as {@code config/IslandTime/loadouts/<name>.json}.
 * Groups are stored in {@code config/IslandTime/loadouts/groups.json}.
 *
 * A loadout can belong to at most one group. {@code /it cyclesettings}
 * loads the next loadout in the group that contains the most recently
 * loaded loadout.
 */
public class SettingsLoadoutManager {

    private static final File LOADOUTS_DIR = new File("config/IslandTime/loadouts");
    private static final File CONFIG_FILE = new File("config/IslandTime/moulconfig.json");
    private static final File GROUPS_FILE = new File("config/IslandTime/loadouts/groups.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type GROUPS_TYPE = new TypeToken<Map<String, List<String>>>() {}.getType();

    /** Tracks the most recently loaded loadout name so cyclesettings knows where we are. */
    private static String currentLoadout = null;

    /** Returns Minecraft's options.txt file. */
    private static File getOptionsFile() {
        return new File(Minecraft.getInstance().gameDirectory, "options.txt");
    }

    // ── Loadout CRUD ────────────────────────────────────────────────────────

    public static void save(Minecraft client, String name) {
        // Persist current Minecraft options to disk
        client.options.save();
        ConfigManager.syncFromExistingConfigs();
        ConfigManager.saveToFile();

        LOADOUTS_DIR.mkdirs();
        File destConfig = new File(LOADOUTS_DIR, name + ".json");
        File destOptions = new File(LOADOUTS_DIR, name + ".options.txt");
        try {
            Files.copy(CONFIG_FILE.toPath(), destConfig.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(getOptionsFile().toPath(), destOptions.toPath(), StandardCopyOption.REPLACE_EXISTING);
            currentLoadout = name;
            sendMessage(client, Component.literal("[IslandTime] Saved settings loadout: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(name).withStyle(ChatFormatting.GREEN)));
        } catch (IOException e) {
            sendMessage(client, Component.literal("[IslandTime] Failed to save loadout: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    public static void load(Minecraft client, String name) {
        File srcConfig = new File(LOADOUTS_DIR, name + ".json");
        if (!srcConfig.exists()) {
            sendMessage(client, Component.literal("[IslandTime] No loadout found with name: ").withStyle(ChatFormatting.RED)
                    .append(Component.literal(name).withStyle(ChatFormatting.YELLOW)));
            return;
        }

        try {
            // Restore mod config
            Files.copy(srcConfig.toPath(), CONFIG_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ConfigManager.initialize();
            ConfigManager.syncToExistingConfigs();

            // Restore Minecraft options (render distance, FPS cap, etc.) if saved
            File srcOptions = new File(LOADOUTS_DIR, name + ".options.txt");
            if (srcOptions.exists()) {
                Files.copy(srcOptions.toPath(), getOptionsFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
                client.options.load();
            }

            currentLoadout = name;
            sendMessage(client, Component.literal("[IslandTime] Loaded settings loadout: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(name).withStyle(ChatFormatting.GREEN)));
        } catch (IOException e) {
            sendMessage(client, Component.literal("[IslandTime] Failed to load loadout: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    public static void delete(Minecraft client, String name) {
        File file = new File(LOADOUTS_DIR, name + ".json");
        if (!file.exists()) {
            sendMessage(client, Component.literal("[IslandTime] No loadout found with name: ").withStyle(ChatFormatting.RED)
                    .append(Component.literal(name).withStyle(ChatFormatting.YELLOW)));
            return;
        }

        if (file.delete()) {
            // Also delete the options file if it exists
            new File(LOADOUTS_DIR, name + ".options.txt").delete();
            // Remove from any group it belongs to
            Map<String, List<String>> groups = loadGroups();
            boolean changed = false;
            for (List<String> members : groups.values()) {
                if (members.remove(name)) changed = true;
            }
            // Remove empty groups
            groups.entrySet().removeIf(e -> e.getValue().isEmpty());
            if (changed) saveGroups(groups);

            if (name.equals(currentLoadout)) currentLoadout = null;

            sendMessage(client, Component.literal("[IslandTime] Deleted settings loadout: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(name).withStyle(ChatFormatting.GREEN)));
        } else {
            sendMessage(client, Component.literal("[IslandTime] Failed to delete loadout: " + name).withStyle(ChatFormatting.RED));
        }
    }

    public static void list(Minecraft client) {
        List<String> names = getLoadoutNames();
        Map<String, List<String>> groups = loadGroups();

        if (names.isEmpty() && groups.isEmpty()) {
            sendMessage(client, Component.literal("[IslandTime] No saved settings loadouts.").withStyle(ChatFormatting.YELLOW));
            return;
        }

        // Show individual loadouts
        if (!names.isEmpty()) {
            sendMessage(client, Component.literal("[IslandTime] Saved loadouts (" + names.size() + "):").withStyle(ChatFormatting.GOLD));
            for (String n : names) {
                Component line = Component.literal("  - ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(n).withStyle(ChatFormatting.GREEN));
                if (n.equals(currentLoadout)) {
                    line = line.copy().append(Component.literal(" (active)").withStyle(ChatFormatting.AQUA));
                }
                sendMessage(client, line);
            }
        }

        // Show groups
        if (!groups.isEmpty()) {
            sendMessage(client, Component.literal(""));
            sendMessage(client, Component.literal("[IslandTime] Settings groups:").withStyle(ChatFormatting.GOLD));
            for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
                sendMessage(client, Component.literal("  " + entry.getKey() + ": ").withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal(String.join(", ", entry.getValue())).withStyle(ChatFormatting.GREEN)));
            }
        }
    }

    // ── Groups ──────────────────────────────────────────────────────────────

    /**
     * Create or replace a settings group. Each loadout name must exist and
     * will be removed from any previous group it belonged to.
     */
    public static void setGroup(Minecraft client, String groupName, List<String> loadoutNames) {
        // Validate all loadouts exist
        List<String> missing = new ArrayList<>();
        for (String n : loadoutNames) {
            if (!new File(LOADOUTS_DIR, n + ".json").exists()) {
                missing.add(n);
            }
        }
        if (!missing.isEmpty()) {
            sendMessage(client, Component.literal("[IslandTime] Unknown loadouts: ").withStyle(ChatFormatting.RED)
                    .append(Component.literal(String.join(", ", missing)).withStyle(ChatFormatting.YELLOW)));
            return;
        }

        if (loadoutNames.size() < 2) {
            sendMessage(client, Component.literal("[IslandTime] A group needs at least 2 loadouts.").withStyle(ChatFormatting.RED));
            return;
        }

        Map<String, List<String>> groups = loadGroups();

        // Remove these loadouts from any existing group (a loadout can only be in one group)
        for (String n : loadoutNames) {
            for (List<String> members : groups.values()) {
                members.remove(n);
            }
        }
        // Remove empty groups
        groups.entrySet().removeIf(e -> e.getValue().isEmpty());

        // Set the new group
        groups.put(groupName, new ArrayList<>(loadoutNames));
        saveGroups(groups);

        sendMessage(client, Component.literal("[IslandTime] Created settings group: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(groupName).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" → ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.join(", ", loadoutNames)).withStyle(ChatFormatting.GREEN)));
    }

    /**
     * Cycle to the next loadout in whichever group contains the current loadout.
     */
    public static void cycle(Minecraft client) {
        if (currentLoadout == null) {
            sendMessage(client, Component.literal("[IslandTime] No active loadout. Use /it loadsettings <name> first.").withStyle(ChatFormatting.RED));
            return;
        }

        Map<String, List<String>> groups = loadGroups();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            List<String> members = entry.getValue();
            int idx = members.indexOf(currentLoadout);
            if (idx >= 0) {
                String next = members.get((idx + 1) % members.size());
                load(client, next);
                return;
            }
        }

        sendMessage(client, Component.literal("[IslandTime] Current loadout '").withStyle(ChatFormatting.RED)
                .append(Component.literal(currentLoadout).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("' is not in any settings group.").withStyle(ChatFormatting.RED)));
    }

    // ── Persistence helpers ─────────────────────────────────────────────────

    private static List<String> getLoadoutNames() {
        if (!LOADOUTS_DIR.exists()) return Collections.emptyList();
        File[] files = LOADOUTS_DIR.listFiles((dir, n) -> n.endsWith(".json") && !n.equals("groups.json"));
        if (files == null || files.length == 0) return Collections.emptyList();
        return Arrays.stream(files)
                .map(f -> f.getName().replace(".json", ""))
                .sorted()
                .collect(Collectors.toList());
    }

    private static Map<String, List<String>> loadGroups() {
        if (!GROUPS_FILE.exists()) return new LinkedHashMap<>();
        try (FileReader reader = new FileReader(GROUPS_FILE)) {
            Map<String, List<String>> groups = GSON.fromJson(reader, GROUPS_TYPE);
            return groups != null ? groups : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static void saveGroups(Map<String, List<String>> groups) {
        try {
            LOADOUTS_DIR.mkdirs();
            try (FileWriter writer = new FileWriter(GROUPS_FILE)) {
                GSON.toJson(groups, writer);
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to save settings groups: " + e.getMessage());
        }
    }

    private static void sendMessage(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendSystemMessage(message);
        }
    }
}
