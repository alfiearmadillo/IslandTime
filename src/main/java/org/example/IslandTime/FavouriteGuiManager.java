package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class FavouriteGuiManager {

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/favourite_guis.txt");
    private static final Path CONFIG_TMP = Paths.get("config/IslandTime/favourite_guis.tmp");

    // Prefixes used in the on-disk format to distinguish data lines from sentinel markers
    private static final String PREFIX_NAME  = "NAME:";
    private static final String PREFIX_TITLE = "TITLE:";
    private static final String PREFIX_SIZE  = "SIZE:";
    private static final String PREFIX_ITEM  = "ITEM:";

    // Map of saved container name -> SavedContainer
    private static final Map<String, SavedContainer> savedContainers = new HashMap<>();

    public static class SavedContainer {
        public String name;
        public String title;
        public int size;
        public List<SavedItem> items;

        public SavedContainer(String name, String title, int size, List<SavedItem> items) {
            this.name = name;
            this.title = title;
            this.size = size;
            this.items = items;
        }
    }

    public static class SavedItem {
        public int slotIndex;
        public String nbtData;

        public SavedItem(int slotIndex, String nbtData) {
            this.slotIndex = slotIndex;
            this.nbtData = nbtData;
        }
    }

    /**
     * Save the currently open container with the given name
     * @return true if saved successfully, false if no container is open or save failed
     */
    public static boolean saveCurrentContainer(String name) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.currentScreen == null) {
            return false;
        }

        // Check if it's a container screen (chest, barrel, etc.)
        if (!(client.currentScreen instanceof GenericContainerScreen containerScreen)) {
            return false;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        if (!(handler instanceof GenericContainerScreenHandler containerHandler)) {
            return false;
        }

        // Get container info
        String title = containerScreen.getTitle().getString();
        int totalSlots = containerHandler.slots.size();

        // Calculate actual container size (exclude player inventory)
        // Player inventory is always last 36 slots (27 main + 9 hotbar)
        int containerSize = totalSlots - 36;

        if (containerSize <= 0) {
            return false; // Invalid container
        }

        // Save all items in the container (not player inventory)
        List<SavedItem> items = new ArrayList<>();
        for (int i = 0; i < containerSize; i++) {
            Slot slot = containerHandler.getSlot(i);
            ItemStack stack = slot.getStack();

            if (!stack.isEmpty()) {
                // Serialize item to NBT using CODEC
                DynamicRegistryManager registryManager = client.getNetworkHandler() != null
                        ? client.getNetworkHandler().getRegistryManager()
                        : DynamicRegistryManager.EMPTY;

                String nbtString = ItemStack.CODEC
                        .encode(stack, registryManager.getOps(NbtOps.INSTANCE), new NbtCompound())
                        .result()
                        .map(NbtElement::toString)
                        .orElse("");

                if (!nbtString.isEmpty()) {
                    items.add(new SavedItem(i, nbtString));
                }
            }
        }

        SavedContainer newContainer = new SavedContainer(name, title, containerSize, items);
        SavedContainer previousContainer = savedContainers.get(name);
        byte[] beforeSave = readConfigSnapshot();

        // Save to memory first so save() persists the intended state.
        savedContainers.put(name, newContainer);

        // Persist to disk.
        save();

        if (didPersistSucceed(previousContainer, newContainer, beforeSave, readConfigSnapshot())) {
            return true;
        }

        // Roll back the in-memory change when persistence did not happen.
        if (previousContainer == null) {
            savedContainers.remove(name);
        } else {
            savedContainers.put(name, previousContainer);
        }

        return false;
    }

    private static byte[] readConfigSnapshot() {
        try {
            if (!Files.exists(CONFIG_FILE)) {
                return null;
            }
            return Files.readAllBytes(CONFIG_FILE);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean didPersistSucceed(SavedContainer previousContainer, SavedContainer newContainer, byte[] beforeSave, byte[] afterSave) {
        if (afterSave == null) {
            return false;
        }

        if (!Arrays.equals(beforeSave, afterSave)) {
            return true;
        }

        return sameContainer(previousContainer, newContainer);
    }

    private static boolean sameContainer(SavedContainer first, SavedContainer second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        if (!Objects.equals(first.name, second.name)
                || !Objects.equals(first.title, second.title)
                || first.size != second.size
                || first.items == null
                || second.items == null
                || first.items.size() != second.items.size()) {
            return false;
        }

        for (int i = 0; i < first.items.size(); i++) {
            SavedItem firstItem = first.items.get(i);
            SavedItem secondItem = second.items.get(i);
            if (firstItem == null || secondItem == null) {
                if (firstItem != secondItem) {
                    return false;
                }
                continue;
            }
            if (firstItem.slotIndex != secondItem.slotIndex || !Objects.equals(firstItem.nbtData, secondItem.nbtData)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get a saved container by name
     */
    public static SavedContainer getSavedContainer(String name) {
        return savedContainers.get(name);
    }

    /**
     * Get all saved container names
     */
    public static Set<String> getSavedNames() {
        return new TreeSet<>(savedContainers.keySet()); // Sorted
    }

    /**
     * Delete a saved container
     */
    public static boolean deleteSavedContainer(String name) {
        if (savedContainers.remove(name) != null) {
            save();
            return true;
        }
        return false;
    }

    /**
     * Check if a container with the given name exists
     */
    public static boolean hasSavedContainer(String name) {
        return savedContainers.containsKey(name);
    }

    /**
     * Save all data to disk using atomic write pattern
     */
    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_TMP)) {
                for (SavedContainer container : savedContainers.values()) {
                    writer.write("CONTAINER\n");
                    writer.write(PREFIX_NAME  + container.name  + "\n");
                    writer.write(PREFIX_TITLE + container.title + "\n");
                    writer.write(PREFIX_SIZE  + container.size  + "\n");

                    // Write items
                    writer.write("ITEMS\n");
                    for (SavedItem item : container.items) {
                        writer.write(PREFIX_ITEM + item.slotIndex + "|" + item.nbtData + "\n");
                    }
                    writer.write("END_CONTAINER\n");
                }
            }

            Files.move(CONFIG_TMP, CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            System.err.println("[FavouriteGuiManager] Save failed: " + e.getMessage());
            e.printStackTrace();
            try {
                Files.deleteIfExists(CONFIG_TMP);
            } catch (IOException ignored) {}
        }
    }

    /**
     * Load all data from disk with crash recovery
     */
    public static void load() {
        // Recover from crash - promote .tmp if main file is missing
        if (!Files.exists(CONFIG_FILE) && Files.exists(CONFIG_TMP)) {
            try {
                Files.move(CONFIG_TMP, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[FavouriteGuiManager] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[FavouriteGuiManager] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(CONFIG_FILE)) {
            return;
        }

        savedContainers.clear();

        try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE)) {
            String line;
            String section = "";

            String currentName = null;
            String currentTitle = null;
            int currentSize = 0;
            List<SavedItem> currentItems = null;

            while ((line = reader.readLine()) != null) {
                if (line.equals("CONTAINER")) {
                    section = "CONTAINER";
                    currentItems = new ArrayList<>();
                    continue;
                } else if (line.equals("ITEMS")) {
                    section = "ITEMS";
                    continue;
                } else if (line.equals("END_CONTAINER")) {
                    // Save the container
                    if (currentName != null && currentTitle != null && currentItems != null) {
                        savedContainers.put(currentName,
                            new SavedContainer(currentName, currentTitle, currentSize, currentItems));
                    }
                    section = "";
                    currentName = null;
                    currentTitle = null;
                    currentSize = 0;
                    currentItems = null;
                    continue;
                }

                if (section.equals("CONTAINER")) {
                    if (line.startsWith(PREFIX_NAME)) {
                        currentName = line.substring(PREFIX_NAME.length());
                    } else if (line.startsWith(PREFIX_TITLE)) {
                        currentTitle = line.substring(PREFIX_TITLE.length());
                    } else if (line.startsWith(PREFIX_SIZE)) {
                        try {
                            currentSize = Integer.parseInt(line.substring(PREFIX_SIZE.length()));
                        } catch (NumberFormatException e) {
                            System.err.println("[FavouriteGuiManager] Invalid size: " + line);
                        }
                    }
                } else if (section.equals("ITEMS")) {
                    // Parse item: ITEM:slotIndex|nbtData
                    if (line.startsWith(PREFIX_ITEM)) {
                        String[] parts = line.substring(PREFIX_ITEM.length()).split("\\|", 2);
                        if (parts.length == 2) {
                            try {
                                int slotIndex = Integer.parseInt(parts[0]);
                                String nbtData = parts[1];
                                currentItems.add(new SavedItem(slotIndex, nbtData));
                            } catch (NumberFormatException e) {
                                System.err.println("[FavouriteGuiManager] Invalid item data: " + line);
                            }
                        }
                    }
                }
            }

            System.out.println("[FavouriteGuiManager] Loaded " + savedContainers.size() + " saved containers.");

        } catch (IOException e) {
            System.err.println("[FavouriteGuiManager] Load failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
