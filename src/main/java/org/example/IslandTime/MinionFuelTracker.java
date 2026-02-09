package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class MinionFuelTracker {

    private static ItemStack initialFuelSlotItem = ItemStack.EMPTY;
    private static String currentMinionName = "";
    private static final int FUEL_SLOT_INDEX = 19; // The fuel slot (row 3, column 2)
    private static boolean canTrackChanges = false;
    private static boolean wasInMinionGui = false; // Track if we were in a minion GUI last tick

    public static void checkFuelSlot() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // Check if we're currently in a GUI
        if (client.currentScreen == null) {
            // We're not in any GUI - reset if we were previously in a minion GUI
            if (wasInMinionGui) {
                initialFuelSlotItem = ItemStack.EMPTY;
                currentMinionName = "";
                canTrackChanges = false;
                wasInMinionGui = false;
            }
            return;
        }

        // Check if the current screen is a container screen
        if (!(client.currentScreen instanceof GenericContainerScreen containerScreen)) {
            // We're in some other GUI - reset if we were in a minion GUI
            if (wasInMinionGui) {
                initialFuelSlotItem = ItemStack.EMPTY;
                currentMinionName = "";
                canTrackChanges = false;
                wasInMinionGui = false;
            }
            return;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        if (!(handler instanceof GenericContainerScreenHandler containerHandler)) return;

        // Get the title from the screen
        String title = containerScreen.getTitle().getString();

        // Only track if this is a minion GUI
        if (!title.toLowerCase().contains("minion")) {
            // We're in a container but not a minion - reset if we were in a minion GUI
            if (wasInMinionGui) {
                initialFuelSlotItem = ItemStack.EMPTY;
                currentMinionName = "";
                canTrackChanges = false;
                wasInMinionGui = false;
            }
            return;
        }

        // We're in a minion GUI
        wasInMinionGui = true;

        // Check if we just opened a new minion or switched to a different one
        if (!title.equals(currentMinionName)) {
            // RESET EVERYTHING for the new minion
            currentMinionName = title;
            canTrackChanges = false; // Disable tracking until we stabilize
            initialFuelSlotItem = ItemStack.EMPTY; // Clear the old state

            // DON'T store initial state yet - wait for next tick
            return;
        }

        // If we can't track changes yet, we're in the stabilization phase
        if (!canTrackChanges) {
            // NOW store the initial state and enable tracking
            if (FUEL_SLOT_INDEX < containerHandler.slots.size()) {
                Slot fuelSlot = containerHandler.getSlot(FUEL_SLOT_INDEX);
                initialFuelSlotItem = fuelSlot.getStack().copy();
                canTrackChanges = true; // Enable tracking for next tick
            }
            return;
        }

        // Now we can safely check for changes
        if (FUEL_SLOT_INDEX >= containerHandler.slots.size()) return;

        Slot fuelSlot = containerHandler.getSlot(FUEL_SLOT_INDEX);
        ItemStack currentItem = fuelSlot.getStack();

        // Check if the slot changed
        if (!ItemStack.areEqual(currentItem, initialFuelSlotItem)) {
            // Determine what the initial item was
            boolean initialWasGlassPane = !initialFuelSlotItem.isEmpty() &&
                    initialFuelSlotItem.getItem() == Items.ORANGE_STAINED_GLASS_PANE &&
                    initialFuelSlotItem.getName().getString().equals("Fuel");

            // Determine what the current item is
            boolean currentIsGlassPane = !currentItem.isEmpty() &&
                    currentItem.getItem() == Items.ORANGE_STAINED_GLASS_PANE &&
                    currentItem.getName().getString().equals("Fuel");

            boolean currentIsActualFuel = !currentItem.isEmpty() && !currentIsGlassPane;

            // Only send message if:
            // 1. We started with the glass pane (empty slot indicator)
            // 2. AND now we have actual fuel (not glass pane, not empty)
            if (initialWasGlassPane && currentIsActualFuel) {
                // The fuel was placed! Send the message
                String fuelName = currentItem.getName().getString();
                Formatting fuelColor = getFuelColor(currentItem);

                // Send the message
                Text message = Text.empty()
                        .append(Text.literal(currentMinionName).formatted(Formatting.BLUE))
                        .append(Text.literal(" fueled with ").formatted(Formatting.GREEN))
                        .append(Text.literal(fuelName).formatted(fuelColor));

                client.player.sendMessage(message, false);

                // Notify FuelCycleTracker
                FuelCycleTracker.onMinionFueled();
            }

            // Update the initial state to the current state
            // This prevents repeated messages
            initialFuelSlotItem = currentItem.copy();
        }
    }

    private static Formatting getFuelColor(ItemStack item) {
        // Get the formatted name text
        Text nameText = item.getName();

        // Try to extract formatting from the first sibling/component
        var siblings = nameText.getSiblings();
        if (!siblings.isEmpty()) {
            var firstSibling = siblings.get(0);
            var color = firstSibling.getStyle().getColor();
            if (color != null) {
                return getFormattingFromRgb(color.getRgb());
            }
        }

        // Check the main text's color
        var style = nameText.getStyle();
        var color = style.getColor();
        if (color != null) {
            return getFormattingFromRgb(color.getRgb());
        }

        return Formatting.WHITE;
    }

    private static Formatting getFormattingFromRgb(int rgb) {
        // Match Minecraft's standard formatting colors
        if (rgb == 0xFFFFFF) return Formatting.WHITE;
        if (rgb == 0xFFAA00) return Formatting.GOLD;
        if (rgb == 0xFF55FF) return Formatting.LIGHT_PURPLE;
        if (rgb == 0x5555FF) return Formatting.BLUE;
        if (rgb == 0x55FF55) return Formatting.GREEN;
        if (rgb == 0x55FFFF) return Formatting.AQUA;
        if (rgb == 0xFF5555) return Formatting.RED;
        if (rgb == 0xAAAAAA) return Formatting.GRAY;
        if (rgb == 0x555555) return Formatting.DARK_GRAY;
        if (rgb == 0xFFFF55) return Formatting.YELLOW;
        if (rgb == 0x00AA00) return Formatting.DARK_GREEN;
        if (rgb == 0x00AAAA) return Formatting.DARK_AQUA;
        if (rgb == 0x0000AA) return Formatting.DARK_BLUE;
        if (rgb == 0xAA0000) return Formatting.DARK_RED;
        if (rgb == 0xAA00AA) return Formatting.DARK_PURPLE;

        return Formatting.WHITE;
    }
}