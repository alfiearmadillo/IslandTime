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
        if (!ItemStack.matches(currentItem, initialFuelSlotItem)) {
            // Determine what the initial item was
            boolean initialWasGlassPane = !initialFuelSlotItem.isEmpty() &&
                    initialFuelSlotItem.getItem() == Items.ORANGE_STAINED_GLASS_PANE &&
                    initialFuelSlotItem.getHoverName().getString().equals("Fuel");

            // Determine what the current item is
            boolean currentIsGlassPane = !currentItem.isEmpty() &&
                    currentItem.getItem() == Items.ORANGE_STAINED_GLASS_PANE &&
                    currentItem.getHoverName().getString().equals("Fuel");

            boolean currentIsActualFuel = !currentItem.isEmpty() && !currentIsGlassPane;
            boolean initialWasActualFuel = !initialFuelSlotItem.isEmpty() && !initialWasGlassPane;

            // Whether the current fuel item is the same fuel type as whatever was
            // previously in the slot (compared by display name, since fuel items
            // share the same base Item but differ by SkyBlock NBT/hover name).
            boolean sameFuelType = initialWasActualFuel && currentIsActualFuel &&
                    currentItem.getHoverName().getString().equals(initialFuelSlotItem.getHoverName().getString());

            int newFuelUnits = 0;
            if (initialWasGlassPane && currentIsActualFuel) {
                // Slot went from empty (glass pane placeholder) to holding fuel:
                // the whole stack is newly placed fuel.
                newFuelUnits = Math.max(1, currentItem.getCount());
            } else if (initialWasActualFuel && currentIsActualFuel) {
                // Fuel was topped up while the minion was still actively fueled
                // (placing fuel into a slot that already has fuel, instead of glass).
                // Only the increase in count is newly added fuel; a same-or-lower
                // count just reflects the existing fuel depleting over time and
                // shouldn't be counted again.
                int currentCount = currentItem.getCount();
                int initialCount = initialFuelSlotItem.getCount();
                if (sameFuelType) {
                    if (currentCount > initialCount) {
                        newFuelUnits = currentCount - initialCount;
                    }
                } else {
                    // Different fuel type placed on top of remaining fuel - treat
                    // the whole new stack as freshly added fuel. The previous
                    // cycle info (minions refueled, expiry so far) is preserved
                    // since FuelCycleTracker.onMinionFueled() only ever extends it.
                    newFuelUnits = Math.max(1, currentCount);
                }
            }

            if (newFuelUnits > 0) {
                // The fuel was placed! Send the message (if enabled)
                if (showFuelMessages) {
                    String fuelName = currentItem.getHoverName().getString();
                    ChatFormatting fuelColor = getFuelColor(currentItem);

                    Component message = Component.empty()
                            .append(Component.literal(currentMinionName).withStyle(ChatFormatting.BLUE))
                            .append(Component.literal(" fueled with ").withStyle(ChatFormatting.GREEN))
                            .append(Component.literal(newFuelUnits > 1 ? (newFuelUnits + "x ") : "").withStyle(ChatFormatting.GREEN))
                            .append(Component.literal(fuelName).withStyle(fuelColor));

                    client.player.sendSystemMessage(message);
                }

                // Notify FuelCycleTracker - this additively extends the existing
                // cycle (preserving prior minionsRefueled/expiry) rather than
                // resetting it, so topping up mid-cycle just queues more fuel-days.
                FuelCycleTracker.onMinionFueled(newFuelUnits);
            }

            // Update the initial state to the current state
            // This prevents repeated messages
            initialFuelSlotItem = currentItem.copy();
        }
    }

    private static ChatFormatting getFuelColor(ItemStack item) {
        // Get the formatted name text
        Component nameText = item.getHoverName();

        // Try to extract formatting from the first sibling/component
        var siblings = nameText.getSiblings();
        if (!siblings.isEmpty()) {
            var firstSibling = siblings.get(0);
            var color = firstSibling.getStyle().getColor();
            if (color != null) {
                return getFormattingFromRgb(color.getValue());
            }
        }

        // Check the main text's color
        var style = nameText.getStyle();
        var color = style.getColor();
        if (color != null) {
            return getFormattingFromRgb(color.getValue());
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