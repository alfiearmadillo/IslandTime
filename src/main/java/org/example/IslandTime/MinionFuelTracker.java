package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

public class MinionFuelTracker {

    private static ItemStack initialFuelSlotItem = ItemStack.EMPTY;
    private static String currentMinionName = "";
    private static final int FUEL_SLOT_INDEX = 19; // The fuel slot (row 3, column 2)
    private static boolean canTrackChanges = false;
    private static boolean wasInMinionGui = false; // Track if we were in a minion GUI last tick

    private static boolean showFuelMessages = true;

    public static boolean isShowFuelMessages() { return showFuelMessages; }
    public static void setShowFuelMessages(boolean value) { showFuelMessages = value; }

    public static void checkFuelSlot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        // Check if we're currently in a GUI
        if (client.screen == null) {
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
        if (!(client.screen instanceof AbstractContainerScreen containerScreen)) {
            // We're in some other GUI - reset if we were in a minion GUI
            if (wasInMinionGui) {
                initialFuelSlotItem = ItemStack.EMPTY;
                currentMinionName = "";
                canTrackChanges = false;
                wasInMinionGui = false;
            }
            return;
        }

        AbstractContainerMenu handler = client.player.containerMenu;
        if (!(handler instanceof ChestMenu containerHandler)) return;

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
                initialFuelSlotItem = fuelSlot.getItem().copy();
                canTrackChanges = true; // Enable tracking for next tick
            }
            return;
        }

        // Now we can safely check for changes
        if (FUEL_SLOT_INDEX >= containerHandler.slots.size()) return;

        Slot fuelSlot = containerHandler.getSlot(FUEL_SLOT_INDEX);
        ItemStack currentItem = fuelSlot.getItem();

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
                // The fuel was placed! Send the message (if enabled)
                if (showFuelMessages) {
                    String fuelName = currentItem.getName().getString();
                    ChatFormatting fuelColor = getFuelColor(currentItem);

                    Component message = Component.empty()
                            .append(Component.literal(currentMinionName).withStyle(ChatFormatting.BLUE))
                            .append(Component.literal(" fueled with ").withStyle(ChatFormatting.GREEN))
                            .append(Component.literal(fuelName).withStyle(fuelColor));

                    client.player.sendSystemMessage(message);
                }

                // Notify FuelCycleTracker
                FuelCycleTracker.onMinionFueled();
            }

            // Update the initial state to the current state
            // This prevents repeated messages
            initialFuelSlotItem = currentItem.copy();
        }
    }

    private static ChatFormatting getFuelColor(ItemStack item) {
        // Get the formatted name text
        Component nameText = item.getName();

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

        return ChatFormatting.WHITE;
    }

    private static ChatFormatting getFormattingFromRgb(int rgb) {
        // Match Minecraft's standard formatting colors
        if (rgb == 0xFFFFFF) return ChatFormatting.WHITE;
        if (rgb == 0xFFAA00) return ChatFormatting.GOLD;
        if (rgb == 0xFF55FF) return ChatFormatting.LIGHT_PURPLE;
        if (rgb == 0x5555FF) return ChatFormatting.BLUE;
        if (rgb == 0x55FF55) return ChatFormatting.GREEN;
        if (rgb == 0x55FFFF) return ChatFormatting.AQUA;
        if (rgb == 0xFF5555) return ChatFormatting.RED;
        if (rgb == 0xAAAAAA) return ChatFormatting.GRAY;
        if (rgb == 0x555555) return ChatFormatting.DARK_GRAY;
        if (rgb == 0xFFFF55) return ChatFormatting.YELLOW;
        if (rgb == 0x00AA00) return ChatFormatting.DARK_GREEN;
        if (rgb == 0x00AAAA) return ChatFormatting.DARK_AQUA;
        if (rgb == 0x0000AA) return ChatFormatting.DARK_BLUE;
        if (rgb == 0xAA0000) return ChatFormatting.DARK_RED;
        if (rgb == 0xAA00AA) return ChatFormatting.DARK_PURPLE;

        return ChatFormatting.WHITE;
    }
}