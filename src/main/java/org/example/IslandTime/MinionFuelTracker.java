package org.example.islandtime;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
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

    // The unique key actually passed to FuelCycleTracker for this open GUI — built from
    // the block position and full equipment (see buildArmorStandKey()) of the armor stand
    // entity that was right-clicked to open it (see registerArmorStandInteractionListener()
    // below), falling back to the GUI title itself if no armor-stand interaction was
    // captured in time (safety net). Unlike the title (which is identical for every minion
    // of the same type/tier, e.g. 32 "Wheat Minion XI" armor stands on one island), a given
    // physical minion's armor stand never moves and never changes its equipped
    // skin/items, so this key reliably distinguishes 32 separate minions of the same tier
    // instead of merging them into one. Position+equipment is used instead of the armor
    // stand's entity UUID because UUIDs are re-assigned whenever the server unloads and
    // reloads the island (e.g. after being away or switching servers) — position+equipment
    // stays stable across that, as long as the minion itself isn't moved/replaced.
    private static String currentMinionKey = "";

    private static final int FUEL_SLOT_INDEX = 19; // The fuel slot (row 3, column 2)
    private static boolean canTrackChanges = false;
    private static boolean wasInMinionGui = false; // Track if we were in a minion GUI last tick

    // The fuel slot's contents right after opening a minion's GUI can take a tick or two
    // to actually sync from the server — reading it too early risks seeing a stale/empty
    // placeholder even though the minion genuinely already has fuel queued. If we then
    // trusted that stale read as our baseline, the real content arriving a tick later
    // would look like a brand new fuel placement and get miscounted as newly-added fuel
    // units that were never actually placed. To guard against this, the slot's contents
    // must read back identically for several consecutive ticks before we lock it in as
    // the baseline (see the stabilization loop in checkFuelSlot()).
    private static ItemStack pendingStableItem = ItemStack.EMPTY;
    private static int stableTickCount = 0;
    private static final int STABILIZATION_TICKS = 3;

    private static boolean showFuelMessages = true;

    public static boolean isShowFuelMessages() { return showFuelMessages; }
    public static void setShowFuelMessages(boolean value) { showFuelMessages = value; }

    // -------------------------------------------------------------------------
    // Armor stand interaction tracking
    // -------------------------------------------------------------------------

    // SkyBlock minions are armor stand entities you right-click to open their GUI. We
    // record a stable key (block position + all equipped items, see buildArmorStandKey())
    // for whichever armor stand was last right-clicked (and when), so that when a minion
    // GUI subsequently opens we can attribute it to that specific physical minion rather
    // than just its (non-unique) GUI title. Only ever read/written on the client thread
    // (tick + input handling), so no synchronization is needed.
    private static String lastArmorStandKey = null;
    private static long lastArmorStandInteractMs = 0;

    // How long after right-clicking an armor stand we'll still trust it as "the minion
    // whose GUI just opened" — generous enough to cover normal server round-trip latency
    // for the container-open packet, but short enough that a stray earlier click can't
    // get misattributed to an unrelated, much-later GUI open.
    private static final long ARMOR_STAND_INTERACT_MAX_AGE_MS = 3000L;

    /** Registers the client-side entity-interact listener. Call once during mod init. */
    public static void registerArmorStandInteractionListener() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide() && entity instanceof ArmorStand armorStand) {
                lastArmorStandKey = buildArmorStandKey(armorStand);
                lastArmorStandInteractMs = System.currentTimeMillis();
            }
            return InteractionResult.PASS;
        });
    }

    /**
     * Builds a key identifying a specific physical minion armor stand from its block
     * position and everything it has equipped (helmet/chestplate/leggings/boots/hands) —
     * stable across server restarts/rejoins (unlike the entity's UUID, which gets
     * reassigned whenever the server unloads and reloads the island), as long as the
     * minion itself isn't physically moved or its skin changed. Two minions of the exact
     * same type/tier standing at different spots still get different keys since their
     * positions differ.
     */
    private static String buildArmorStandKey(ArmorStand armorStand) {
        BlockPos pos = armorStand.blockPosition();
        StringBuilder key = new StringBuilder();
        key.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack item = armorStand.getItemBySlot(slot);
            key.append('|');
            if (!item.isEmpty()) {
                key.append(item.getItem()).append(':').append(item.getHoverName().getString());
            }
        }
        return key.toString();
    }

    /**
     * Returns (and clears) the most recently right-clicked armor stand's key, if the
     * interaction happened recently enough to plausibly be the one that opened the minion
     * GUI we're currently looking at. Returns null if there's no recent-enough interaction
     * (e.g. the GUI was opened some other way), in which case callers should fall back to
     * the GUI title as the minion key.
     */
    private static String consumeLastArmorStandKey() {
        if (lastArmorStandKey != null &&
                System.currentTimeMillis() - lastArmorStandInteractMs <= ARMOR_STAND_INTERACT_MAX_AGE_MS) {
            String key = lastArmorStandKey;
            lastArmorStandKey = null; // consume so a stale value can't be reused for a later, unrelated open
            return key;
        }
        return null;
    }

    /**
     * Same freshness check as {@link #consumeLastArmorStandKey()} but without consuming
     * it — used to detect "did the player just right-click a DIFFERENT physical minion"
     * even while a minion GUI of the exact same title is already open (see
     * {@link #checkFuelSlot()}), without prematurely throwing away the key before we've
     * decided whether this tick is actually the one that should consume it.
     */
    private static String peekLastArmorStandKey() {
        if (lastArmorStandKey != null &&
                System.currentTimeMillis() - lastArmorStandInteractMs <= ARMOR_STAND_INTERACT_MAX_AGE_MS) {
            return lastArmorStandKey;
        }
        return null;
    }

    public static void checkFuelSlot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        // Check if we're currently in a GUI
        if (client.screen == null) {
            // We're not in any GUI - reset if we were previously in a minion GUI
            if (wasInMinionGui) {
                initialFuelSlotItem = ItemStack.EMPTY;
                currentMinionName = "";
                currentMinionKey = "";
                canTrackChanges = false;
                pendingStableItem = ItemStack.EMPTY;
                stableTickCount = 0;
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
                currentMinionKey = "";
                canTrackChanges = false;
                pendingStableItem = ItemStack.EMPTY;
                stableTickCount = 0;
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
                currentMinionKey = "";
                canTrackChanges = false;
                pendingStableItem = ItemStack.EMPTY;
                stableTickCount = 0;
                wasInMinionGui = false;
            }
            return;
        }

        // We're in a minion GUI
        wasInMinionGui = true;

        // Check if we just opened a new minion or switched to a different one. A plain
        // title comparison alone isn't enough: many physical minions share the exact same
        // GUI title (e.g. 32 identical-tier "Inferno Minion XI" armor stands), so if the
        // player switches from one to another WITHOUT the screen ever going null in
        // between (e.g. quickly right-clicking a neighboring armor stand while a same-tier
        // minion's GUI is still open), the title never changes even though it's now a
        // genuinely different physical minion — so also treat a still-unconsumed, fresh
        // armor-stand right-click whose key differs from the one we're currently tracking
        // as a switch, even when the title matches.
        String pendingArmorStandKey = peekLastArmorStandKey();
        boolean switchedMinion = !title.equals(currentMinionName)
                || (pendingArmorStandKey != null && !pendingArmorStandKey.equals(currentMinionKey));
        if (switchedMinion) {
            // RESET EVERYTHING for the new minion
            currentMinionName = title;
            // Prefer the position+equipment key of whichever armor stand was just
            // right-clicked (stable per physical minion, even across server restarts) so
            // identical minion types/tiers aren't merged into one. Falls back to the title
            // itself if no recent armor-stand interaction was captured (e.g. GUI reopened
            // via a favourite/macro instead of a fresh right-click).
            String armorStandKey = consumeLastArmorStandKey();
            currentMinionKey = armorStandKey != null ? armorStandKey : title;
            canTrackChanges = false; // Disable tracking until we stabilize
            initialFuelSlotItem = ItemStack.EMPTY; // Clear the old state
            pendingStableItem = ItemStack.EMPTY;
            stableTickCount = 0;

            // DON'T store initial state yet - wait for next tick
            return;
        }

        // If we can't track changes yet, we're in the stabilization phase. The slot's
        // contents must read back identically for STABILIZATION_TICKS consecutive ticks
        // before we trust it as the baseline — a single-tick read risks catching the
        // container mid-sync (e.g. still showing the "empty" glass-pane placeholder
        // before the server's real fuel data has arrived), which would otherwise get
        // misread as a brand new fuel placement the moment the real data shows up.
        if (!canTrackChanges) {
            if (FUEL_SLOT_INDEX >= containerHandler.slots.size()) return;
            Slot fuelSlot = containerHandler.getSlot(FUEL_SLOT_INDEX);
            ItemStack current = fuelSlot.getItem();

            if (ItemStack.matches(current, pendingStableItem)) {
                stableTickCount++;
            } else {
                pendingStableItem = current.copy();
                stableTickCount = 1;
            }

            if (stableTickCount >= STABILIZATION_TICKS) {
                initialFuelSlotItem = pendingStableItem.copy();
                canTrackChanges = true; // Enable tracking for next tick
                pendingStableItem = ItemStack.EMPTY;
                stableTickCount = 0;
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
            } else if (initialWasActualFuel && currentIsGlassPane) {
                FuelCycleTracker.onMinionFuelRemoved(currentMinionKey);
            }

            // Diagnostic logging (console only, not chat) for every detected fuel-slot
            // change, regardless of whether it resulted in any counted fuel units — this
            // is intentionally always-on (not gated behind a debug flag) since it only
            // writes to the log file, not chat, and is the only way to retroactively
            // diagnose a placement that silently failed to register (e.g. topping up an
            // already-active minion whose remaining fuel item's count/hover-name
            // comparison didn't behave as expected).
            System.out.println("[MinionFuelTracker] Fuel slot changed for \"" + currentMinionKey + "\": "
                    + "initial=[" + (initialFuelSlotItem.isEmpty() ? "empty" : initialFuelSlotItem.getCount() + "x " + initialFuelSlotItem.getHoverName().getString()) + "]"
                    + " -> current=[" + (currentItem.isEmpty() ? "empty" : currentItem.getCount() + "x " + currentItem.getHoverName().getString()) + "]"
                    + " sameFuelType=" + sameFuelType + " newFuelUnits=" + newFuelUnits);

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

                // Notify FuelCycleTracker - this extends this specific minion's own
                // fuel expiry (preserving prior cycle info) rather than resetting it,
                // so topping up mid-cycle just queues more fuel-days for this minion.
                // Uses the armor-stand-UUID-based key (currentMinionKey), not the raw
                // GUI title, so multiple minions of the same type/tier are tracked
                // independently instead of being merged into one.
                FuelCycleTracker.onMinionFueled(currentMinionKey, newFuelUnits);
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