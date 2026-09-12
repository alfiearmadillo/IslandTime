package org.example.islandtime;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MinionFuelTracker {

    private static ItemStack initialFuelSlotItem = ItemStack.EMPTY;
    private static String currentMinionName = "";

    // The unique key actually passed to FuelCycleTracker for this open GUI. Preferred
    // source is a permanent minion-upgrade uuid read out of the minion's upgrade slots
    // (see resolveMinionIdentity() / MinionIdentityRegistry) — upgrade items such as a
    // Flycatcher keep the exact same custom_data uuid forever, so they identify a
    // physical minion with certainty rather than by inference.
    //
    // When a minion has no usable upgrade uuid we fall back to the older key: the block
    // position and full equipment (see buildArmorStandKey()) of the armor stand entity
    // that was right-clicked to open the GUI (see registerArmorStandInteractionListener()
    // below), and failing even that, the GUI title itself (safety net). Unlike the title
    // (which is identical for every minion of the same type/tier, e.g. 32 "Wheat Minion
    // XI" armor stands on one island), a given physical minion's armor stand normally
    // never moves and never changes its equipped skin/items, so that key distinguishes 32
    // separate minions of the same tier instead of merging them into one. Position+
    // equipment is used instead of the armor stand's entity UUID because entity UUIDs are
    // re-assigned whenever the server unloads and reloads the island.
    //
    // Existing data saved under a position+equipment key is migrated onto the upgrade-uuid
    // key the first time that minion's GUI is opened (see adoptMinionKey()), so the switch
    // is seamless and no in-progress or queued fuel-day is lost.
    private static String currentMinionKey = "";

    // The position+equipment key of the armor stand this GUI was opened from (empty when
    // no right-click was captured). Kept separately from currentMinionKey so that
    // detecting "the player just clicked a different physical minion" keeps working after
    // currentMinionKey has been resolved to an upgrade-uuid key.
    private static String currentArmorStandKey = "";

    // Wall-clock time the currently open minion GUI was detected, used as the reference
    // point for the upgrade-item timestamp freshness check (see readPermanentUuid()).
    private static long minionGuiOpenedAtMs = 0;

    // Whether the identity of the currently open minion has been settled for this GUI.
    private static boolean identityResolved = false;

    private static final int FUEL_SLOT_INDEX = 19; // The fuel slot (row 3, column 2)

    // The minion upgrade slots (row 5 and row 6, column 2). Upgrades placed here — e.g. a
    // Flycatcher — carry a permanent custom_data uuid we can identify the minion by.
    private static final int[] UPGRADE_SLOT_INDEXES = { 37, 46 };

    // Most items in a minion GUI (the minion head, the Enchanted Hopper, decorative
    // panes...) are re-generated with a brand new uuid every single time the menu is
    // opened, making their uuid worthless for identification. Such an item's
    // custom_data timestamp is minted at that same moment, so any upgrade whose timestamp
    // isn't comfortably older than this GUI being opened is treated as self-resetting and
    // ignored. The margin is generous enough to absorb server/network lag.
    private static final long UPGRADE_TIMESTAMP_MIN_AGE_MS = 30_000L;

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
    private static ItemStack pendingChangedItem = ItemStack.EMPTY;
    private static int pendingChangedTickCount = 0;
    private static final int CHANGE_SETTLING_TICKS = 3;

    // Keep the last confirmed slot contents for every physical minion. A single global
    // baseline loses an insertion when the player closes one GUI and later reopens it
    // after adding fuel, especially when cycling quickly through identical minions.
    private static final Map<String, ItemStack> lastObservedFuelSlots = new HashMap<>();
    private static final Map<String, Long> lastObservedFuelSlotTimes = new HashMap<>();

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

    // -------------------------------------------------------------------------
    // Minion identity via permanent upgrade UUIDs
    // -------------------------------------------------------------------------

    /**
     * Works out which physical minion the currently open GUI belongs to and, when that
     * turns out to be a different (more reliable) key than the one we started with,
     * migrates every piece of saved and in-memory state onto it.
     *
     * <p>Minion upgrades such as a Flycatcher keep the exact same {@code custom_data.uuid}
     * for the lifetime of the item, so reading one out of the upgrade slots identifies the
     * minion with certainty — no coordinate guessing, and no dependence on the
     * right-click that opened the GUI having been captured.
     *
     * @param force when true, settle on whatever is currently readable (used once the
     *              container has fully synced); when false, only resolve if a trusted
     *              upgrade uuid is already visible, and retry again next tick otherwise.
     */
    private static void resolveMinionIdentity(ChestMenu menu, boolean force) {
        List<String> upgradeUuids = readUpgradeUuids(menu);
        if (upgradeUuids.isEmpty() && !force) return;

        String resolvedKey = MinionIdentityRegistry.resolve(
                upgradeUuids, currentArmorStandKey, currentMinionKey);

        boolean resolvedSomething = !upgradeUuids.isEmpty();
        if (resolvedKey != null && !resolvedKey.isEmpty() && !resolvedKey.equals(currentMinionKey)) {
            adoptMinionKey(currentMinionKey, resolvedKey);
            resolvedSomething = true;
        }

        // Nothing identifiable yet (a minion with no upgrades at all, or upgrade data that
        // hasn't synced): keep the legacy key for now and keep looking while this GUI is
        // open, since a late-arriving upgrade can still be adopted safely.
        if (resolvedSomething) identityResolved = true;
    }

    /**
     * Switches the currently tracked minion over to {@code newKey}, carrying its existing
     * data across so nothing is lost or double-counted in the transition.
     *
     * <p>The saved fuel state is only ever migrated away from a real position+equipment
     * key: a bare GUI-title fallback key is shared by every minion of that type/tier, so
     * moving its data onto one specific minion could steal another minion's fuel-days.
     */
    private static void adoptMinionKey(String oldKey, String newKey) {
        // Carry the in-memory fuel-slot baseline across, otherwise the very next tick
        // would compare this minion's slot against "no baseline" and could mistake its
        // existing fuel for a brand new placement.
        ItemStack observed = lastObservedFuelSlots.remove(oldKey);
        if (observed != null) lastObservedFuelSlots.putIfAbsent(newKey, observed);
        Long observedAt = lastObservedFuelSlotTimes.remove(oldKey);
        if (observedAt != null) lastObservedFuelSlotTimes.putIfAbsent(newKey, observedAt);

        if (FuelCycleTracker.isArmorStandFormatKey(oldKey)) {
            FuelCycleTracker.renameMinionKey(oldKey, newKey);
        }

        System.out.println("[MinionFuelTracker] Minion \"" + currentMinionName + "\" identified by upgrade uuid: "
                + "\"" + oldKey + "\" -> \"" + newKey + "\"");
        currentMinionKey = newKey;
    }

    /**
     * Reads the permanent uuids of whatever sits in this minion's upgrade slots, in slot
     * order, skipping anything that can't be trusted (see {@link #readPermanentUuid}).
     * Flycatchers are the common case, but any upgrade that keeps its uuid works — hence
     * the slots being scanned for any qualifying item rather than a specific item id.
     */
    private static List<String> readUpgradeUuids(ChestMenu menu) {
        List<String> uuids = new ArrayList<>(UPGRADE_SLOT_INDEXES.length);
        for (int slotIndex : UPGRADE_SLOT_INDEXES) {
            if (slotIndex >= menu.slots.size()) continue;
            String uuid = readPermanentUuid(menu.getSlot(slotIndex).getItem());
            if (uuid != null && !uuids.contains(uuid)) uuids.add(uuid);
        }
        return uuids;
    }

    /**
     * Returns an item's {@code custom_data.uuid} only when that uuid is genuinely
     * permanent, i.e. the item wasn't freshly generated along with the menu we're looking
     * at. Self-resetting GUI items (the minion head, Enchanted Hoppers, ...) get a new
     * uuid <em>and</em> a new timestamp every time the menu opens, so a timestamp that
     * isn't comfortably older than this GUI's opening disqualifies the item. Returns null
     * when the item has no uuid, no timestamp to verify it with, or fails that check.
     */
    private static String readPermanentUuid(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) return null;

        CompoundTag nbt = customData.copyTag();
        String uuid = nbt.getStringOr("uuid", "");
        if (uuid.isEmpty()) return null;

        Long timestamp = readLongTag(nbt, "timestamp");
        if (timestamp == null) return null; // no way to prove the uuid isn't regenerated

        long reference = minionGuiOpenedAtMs > 0 ? minionGuiOpenedAtMs : System.currentTimeMillis();
        if (timestamp > reference - UPGRADE_TIMESTAMP_MIN_AGE_MS) return null;

        return uuid;
    }

    /** Reads a numeric NBT value as a long (tolerating the trailing type suffix), or null. */
    private static Long readLongTag(CompoundTag nbt, String key) {
        Tag tag = nbt.get(key);
        if (tag == null) return null;
        String raw = tag.toString().trim();
        if (raw.endsWith("L") || raw.endsWith("l")) raw = raw.substring(0, raw.length() - 1);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Clears all per-GUI state once we're no longer looking at a minion GUI. */
    private static void resetMinionGuiState() {
        initialFuelSlotItem = ItemStack.EMPTY;
        currentMinionName = "";
        currentMinionKey = "";
        currentArmorStandKey = "";
        minionGuiOpenedAtMs = 0;
        identityResolved = false;
        canTrackChanges = false;
        pendingStableItem = ItemStack.EMPTY;
        stableTickCount = 0;
        pendingChangedItem = ItemStack.EMPTY;
        pendingChangedTickCount = 0;
        wasInMinionGui = false;
    }

    public static void checkFuelSlot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        // Check if we're currently in a GUI
        if (client.screen == null) {
            // We're not in any GUI - reset if we were previously in a minion GUI
            if (wasInMinionGui) {
                resetMinionGuiState();
            }
            return;
        }

        // Check if the current screen is a container screen
        if (!(client.screen instanceof AbstractContainerScreen containerScreen)) {
            // We're in some other GUI - reset if we were in a minion GUI
            if (wasInMinionGui) {
                resetMinionGuiState();
            }
            return;
        }

        AbstractContainerMenu handler = client.player.containerMenu;
        if (!(handler instanceof ChestMenu containerHandler)) return;

        // Get the title from the screen
        String title = containerScreen.getTitle().getString();

        // Only track an actual minion management GUI. The Bazaar's
        // "Inferno Minion Fuel" menu also contains "minion" in its title, but its
        // slot layout is unrelated and must never be treated as a minion fuel slot.
        String normalizedTitle = title.toLowerCase(java.util.Locale.ROOT);
        if (!normalizedTitle.contains("minion") || normalizedTitle.contains("minion fuel")) {
            // We're in a container but not a minion - reset if we were in a minion GUI
            if (wasInMinionGui) {
                resetMinionGuiState();
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
                || (pendingArmorStandKey != null && !pendingArmorStandKey.equals(currentArmorStandKey));
        if (switchedMinion) {
            // RESET EVERYTHING for the new minion
            currentMinionName = title;
            // Prefer the position+equipment key of whichever armor stand was just
            // right-clicked (stable per physical minion, even across server restarts) so
            // identical minion types/tiers aren't merged into one. Falls back to the title
            // itself if no recent armor-stand interaction was captured (e.g. GUI reopened
            // via a favourite/macro instead of a fresh right-click). Once the GUI's
            // contents have synced, this is upgraded to a permanent upgrade-uuid key
            // wherever one is available (see resolveMinionIdentity()).
            String armorStandKey = consumeLastArmorStandKey();
            currentArmorStandKey = armorStandKey != null ? armorStandKey : "";
            currentMinionKey = armorStandKey != null ? armorStandKey : title;
            minionGuiOpenedAtMs = System.currentTimeMillis();
            identityResolved = false;
            canTrackChanges = false; // Disable tracking until we stabilize
            initialFuelSlotItem = ItemStack.EMPTY; // Clear the old state
            pendingStableItem = ItemStack.EMPTY;
            stableTickCount = 0;
            pendingChangedItem = ItemStack.EMPTY;
            pendingChangedTickCount = 0;

            // DON'T store initial state yet - wait for next tick
            return;
        }

        // Upgrade slots sync alongside the rest of the container, so keep trying until a
        // permanent upgrade uuid shows up. Done before any use of currentMinionKey below
        // so the fuel-slot baseline is looked up under the final key.
        if (!identityResolved) resolveMinionIdentity(containerHandler, false);

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
                // The container has fully synced by now, so settle the minion's identity
                // with whatever the upgrade slots actually hold — including the case where
                // there is no usable upgrade uuid at all, which falls back to the legacy
                // position+equipment key (or a previously registered identity for it).
                if (!identityResolved) resolveMinionIdentity(containerHandler, true);

                ItemStack stableItem = pendingStableItem.copy();
                ItemStack lastObserved = lastObservedFuelSlots.get(currentMinionKey);
                if (lastObserved != null && !ItemStack.matches(lastObserved, stableItem)) {
                    // Leave the previous snapshot as the comparison baseline. The next
                    // normal tick will process the net change, including fuel added while
                    // this minion GUI was closed.
                    initialFuelSlotItem = lastObserved.copy();
                } else {
                    initialFuelSlotItem = stableItem;
                    lastObservedFuelSlots.put(currentMinionKey, stableItem.copy());
                    lastObservedFuelSlotTimes.put(currentMinionKey, System.currentTimeMillis());
                }
                canTrackChanges = true; // Enable tracking for next tick
                pendingStableItem = ItemStack.EMPTY;
                stableTickCount = 0;
                pendingChangedItem = ItemStack.EMPTY;
                pendingChangedTickCount = 0;
            }
            return;
        }

        // Now we can safely check for changes
        if (FUEL_SLOT_INDEX >= containerHandler.slots.size()) return;

        Slot fuelSlot = containerHandler.getSlot(FUEL_SLOT_INDEX);
        ItemStack currentItem = fuelSlot.getItem();

        // Check if the slot changed
        if (!ItemStack.matches(currentItem, initialFuelSlotItem)) {
            // Container synchronization can expose an intermediate stack count while a
            // player is inserting a larger stack (for example 2 -> 10 -> 12). Wait for
            // the visible result to settle before comparing it with the last confirmed
            // state, otherwise one insertion is incorrectly reported as two additions.
            if (!ItemStack.matches(currentItem, pendingChangedItem)) {
                pendingChangedItem = currentItem.copy();
                pendingChangedTickCount = 1;
                return;
            }
            pendingChangedTickCount++;
            if (pendingChangedTickCount < CHANGE_SETTLING_TICKS) return;
            pendingChangedItem = ItemStack.EMPTY;
            pendingChangedTickCount = 0;

            // Determine what the initial item was
            boolean initialWasGlassPane = !initialFuelSlotItem.isEmpty() &&
                    initialFuelSlotItem.getItem() == Items.ORANGE_STAINED_GLASS_PANE &&
                    initialFuelSlotItem.getHoverName().getString().equals("Fuel");

            // Determine what the current item is
            boolean currentIsGlassPane = !currentItem.isEmpty() &&
                    currentItem.getItem() == Items.ORANGE_STAINED_GLASS_PANE &&
                    currentItem.getHoverName().getString().equals("Fuel");

            boolean currentIsEmpty = currentItem.isEmpty() || currentIsGlassPane;
            boolean initialWasEmpty = initialFuelSlotItem.isEmpty() || initialWasGlassPane;
            boolean currentIsActualFuel = !currentIsEmpty;
            boolean initialWasActualFuel = !initialWasEmpty;

            // Whether the current fuel item is the same fuel type as whatever was
            // previously in the slot (compared by display name, since fuel items
            // share the same base Item but differ by SkyBlock NBT/hover name).
            boolean sameFuelType = initialWasActualFuel && currentIsActualFuel &&
                    currentItem.getHoverName().getString().equals(initialFuelSlotItem.getHoverName().getString());

            int newFuelUnits = 0;
            if (initialWasEmpty && currentIsActualFuel) {
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
                    } else {
                        // The visible stack count drops as fuel units expire. If the
                        // player added fuel while this GUI was closed, that addition can
                        // be hidden by one or more units expiring in the meantime.
                        long observedAt = lastObservedFuelSlotTimes.getOrDefault(
                                currentMinionKey, System.currentTimeMillis());
                        int unitsExpired = (int) Math.min(Integer.MAX_VALUE,
                                Math.max(0L, System.currentTimeMillis() - observedAt) / FuelCycleTracker.DAY_MS);
                        newFuelUnits = Math.max(0, currentCount - initialCount + unitsExpired);
                    }
                } else {
                    // Different fuel type placed on top of remaining fuel - treat
                    // the whole new stack as freshly added fuel. The previous
                    // cycle info (minions refueled, expiry so far) is preserved while
                    // fuel remains active; an expired minion starts at the refuel time.
                    newFuelUnits = Math.max(1, currentCount);
                }
            } else if (initialWasActualFuel && currentIsEmpty) {
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
                // Uses the per-minion key (currentMinionKey — a permanent upgrade uuid
                // where available, otherwise the armor-stand position+equipment key), not
                // the raw GUI title, so multiple minions of the same type/tier are tracked
                // independently instead of being merged into one.
                // If the slot was empty when the GUI stabilized, discard any stale
                // saved expiry before starting the newly placed fuel.
                if (initialWasEmpty) {
                    FuelCycleTracker.onMinionFuelRemoved(currentMinionKey);
                }
                FuelCycleTracker.onMinionFueled(currentMinionKey, newFuelUnits);
            }

            // Update the initial state to the current state
            // This prevents repeated messages
            initialFuelSlotItem = currentItem.copy();
            lastObservedFuelSlots.put(currentMinionKey, currentItem.copy());
            lastObservedFuelSlotTimes.put(currentMinionKey, System.currentTimeMillis());
        } else {
            pendingChangedItem = ItemStack.EMPTY;
            pendingChangedTickCount = 0;
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