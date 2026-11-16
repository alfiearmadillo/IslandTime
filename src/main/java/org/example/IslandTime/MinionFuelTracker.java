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
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MinionFuelTracker {

    // Decides when fuel was added to the open minion's fuel slot, starting from the slot's confirmed state.
    // Null until that baseline has been locked in (see the stabilization loop in checkFuelSlot()).
    private static FuelSlotWatcher fuelWatcher = null;
    private static String currentMinionName = "";

    // The unique key actually passed to FuelCycleTracker for this open GUI. Preferred
    // source is a permanent minion-upgrade uuid read out of the minion's upgrade slots
    // (see resolveMinionIdentity() / MinionIdentityRegistry) — upgrade items such as a
    // Flycatcher keep the exact same custom_data uuid forever, so they identify a
    // physical minion with certainty rather than by inference.
    //
    // When a minion has no usable upgrade uuid we fall back to the older key: the block
    // position and worn armor (see buildArmorStandKey()) of the armor stand entity that
    // was right-clicked to open the GUI (see registerArmorStandInteractionListener()
    // below), and failing even that, the GUI title itself (safety net). Unlike the title
    // (which is identical for every minion of the same type/tier, e.g. 32 "Wheat Minion
    // XI" armor stands on one island), a given physical minion's armor stand normally
    // never moves and never changes its worn skin, so that key distinguishes 32 separate
    // minions of the same tier instead of merging them into one. Position+armor is used
    // instead of the armor stand's entity UUID because entity UUIDs are re-assigned
    // whenever the server unloads and reloads the island. What the stand is *holding* is
    // excluded, because Hypixel re-randomizes it on every load — see buildArmorStandKey().
    //
    // Existing data saved under an armor-stand key is migrated onto the upgrade-uuid key
    // the first time that minion's GUI is opened (see adoptMinionKey()), so the switch is
    // seamless and no in-progress or queued fuel-day is lost.
    private static String currentMinionKey = "";

    // The position+equipment key of the armor stand this GUI was opened from (empty when
    // no right-click was captured). Kept separately from currentMinionKey so that
    // detecting "the player just clicked a different physical minion" keeps working after
    // currentMinionKey has been resolved to an upgrade-uuid key.
    private static String currentArmorStandKey = "";

    // The container menu we last saw and the wall-clock time it appeared. Every menu the
    // server opens is a brand new object, so this is both the reference point for the
    // upgrade-item timestamp freshness check (see inspectUpgradeSlot()) and how we tell
    // whether the menu currently open is still the previously selected minion's (see
    // checkFuelSlot()). Held weakly so a closed menu is never kept alive by this tracker.
    private static java.lang.ref.WeakReference<AbstractContainerMenu> lastSeenMenu =
            new java.lang.ref.WeakReference<>(null);
    private static long menuOpenedAtMs = 0;

    // Wall-clock time of the armor-stand right-click that selected the minion we're
    // currently tracking (0 when the GUI was opened without one being captured).
    private static long currentMinionClickMs = 0;

    // Whether the identity of the currently open minion has been settled for this GUI.
    private static boolean identityResolved = false;

    static final int FUEL_SLOT_INDEX = 19; // The fuel slot (row 3, column 2)

    // The minion upgrade slots — row 5 and row 6, column 2 of the 6x9 minion GUI, i.e.
    // slots 38 and 47 counting the container's slots from 1. Minecraft's own slot indexes
    // count from 0, so these are 37 and 46; they're derived here rather than hard-coded so
    // the 1-based/0-based distinction can't drift. (Cross-check: FUEL_SLOT_INDEX above is
    // row 3 column 2 == 20 counting from 1 == 19 counting from 0, the same arithmetic.)
    // Upgrades placed here — e.g. a Flycatcher — carry a permanent custom_data uuid we can
    // identify the minion by.
    private static final int GUI_COLUMNS = 9;
    private static final int[] UPGRADE_SLOT_INDEXES = {
            slotIndex(5, 2),
            slotIndex(6, 2),
    };

    /** Converts a 1-based (row, column) GUI position into Minecraft's 0-based slot index. */
    private static int slotIndex(int rowFromOne, int columnFromOne) {
        return (rowFromOne - 1) * GUI_COLUMNS + (columnFromOne - 1);
    }

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
    // the baseline (see the stabilization loop in checkFuelSlot()). Slot contents are compared as
    // readings (fuel name and amount) rather than as item stacks: Hypixel re-issues the fuel item
    // with a fresh uuid every second even when nothing about it changed.
    private static FuelSlotChange.Reading pendingStableReading = null;
    private static int stableTickCount = 0;
    private static final int STABILIZATION_TICKS = 3;

    // The minion menu currently on screen. The menu object keeps its last slot contents after
    // it is closed, which is what lets a click made just before closing still be read (see
    // flushUnsettledFuelChange()).
    private static AbstractContainerMenu lastMinionMenu = null;

    // Keep the last confirmed slot reading for every physical minion. A single global
    // baseline loses an insertion when the player closes one GUI and later reopens it
    // after adding fuel, especially when cycling quickly through identical minions.
    private static final Map<String, FuelSlotChange.Reading> lastObservedFuelSlots = new HashMap<>();
    private static final Map<String, Long> lastObservedFuelSlotTimes = new HashMap<>();

    private static boolean showFuelMessages = true;

    public static boolean isShowFuelMessages() { return showFuelMessages; }
    public static void setShowFuelMessages(boolean value) { showFuelMessages = value; }

    // -------------------------------------------------------------------------
    // GUI debug log trace points (see GuiDebugLog). Observation only: nothing here
    // changes what gets counted, and everything is skipped while the log is off.
    // -------------------------------------------------------------------------

    // The last line written by traceTransition(), so a state that holds across many ticks
    // (waiting for the cursor to clear, a slot that is still settling) is written once.
    private static String lastTraceLine = "";

    /** The tracker's settings, for the header of a GUI debug log. */
    static String debugSettings() {
        return "fuelSlot=" + FUEL_SLOT_INDEX + " stabilizationTicks=" + STABILIZATION_TICKS
                + " changeSettlingTicks=" + FuelSlotWatcher.SETTLING_TICKS + " showFuelMessages=" + showFuelMessages;
    }

    private static void trace(String message) {
        GuiDebugLog.tracker(message);
    }

    // Lets the fuel slot watcher write its decisions into the same log.
    private static final FuelSlotWatcher.Tracer watcherTracer = new FuelSlotWatcher.Tracer() {
        @Override
        public boolean enabled() {
            return GuiDebugLog.isActive();
        }

        @Override
        public void transition(String line) {
            traceTransition(line);
        }

        @Override
        public void event(String line) {
            trace(line);
        }

        @Override
        public void endRun(String why) {
            endTraceRun(why);
        }
    };

    /** Like {@link #trace} but writes a line identical to the previous transition only once. */
    private static void traceTransition(String line) {
        if (line.equals(lastTraceLine)) return;
        lastTraceLine = line;
        GuiDebugLog.tracker(line);
    }

    /** Ends a run of transition lines, so the next one is written even if it repeats an earlier line. */
    private static void endTraceRun(String why) {
        if (lastTraceLine.isEmpty()) return;
        lastTraceLine = "";
        GuiDebugLog.tracker(why);
    }

    private static String traceKey() {
        return "minion=\"" + currentMinionName + "\" key=\"" + currentMinionKey + "\"";
    }

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

    // The real position of each right-clicked armor stand, by its key, so a minion can be listed
    // where it actually stands rather than by its block position.
    private static final java.util.Map<String, String> exactPositionByStandKey = new java.util.HashMap<>();

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
                Vec3 exact = armorStand.position();
                exactPositionByStandKey.put(lastArmorStandKey,
                        MinionIdentityRegistry.formatExactPosition(exact.x, exact.y, exact.z));
                if (GuiDebugLog.isActive()) {
                    GuiDebugLog.event("MINION_CLICK", "entityId=" + entity.getId() + " hand=" + hand
                            + " key=\"" + lastArmorStandKey + "\"");
                }
            }
            return InteractionResult.PASS;
        });
    }

    /**
     * Builds a key identifying a specific physical minion armor stand from its block
     * position and its worn armor — stable across server restarts/rejoins (unlike the
     * entity's UUID, which gets reassigned whenever the server unloads and reloads the
     * island), as long as the minion isn't physically moved or its skin changed. Two
     * minions of the exact same type/tier standing at different spots still get different
     * keys since their positions differ.
     *
     * <p>The hand slots are deliberately excluded. Hypixel hands a minion a <em>randomly
     * chosen</em> weapon (stone/iron/gold/diamond sword) every time it loads in, so
     * anything held is re-rolled on each rejoin and cannot form part of an identity — a
     * key including it silently becomes a different key after a reload, which is the
     * root cause of the long-standing "which minion is which" mis-tracking. Worn armor
     * and the head skin are not randomized, so they are kept: they encode the minion's
     * type/tier, which lets a minion replaced by a different one at the same position be
     * told apart rather than inheriting its predecessor's fuel-days.
     */
    private static String buildArmorStandKey(ArmorStand armorStand) {
        BlockPos pos = armorStand.blockPosition();
        StringBuilder key = new StringBuilder();
        key.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) continue;
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
     * @param force when true, settle the identity for good with whatever is currently
     *              readable (used once the container has fully synced); when false, adopt
     *              an identity as soon as a trusted upgrade uuid is visible but keep
     *              re-checking on later ticks, so a second upgrade that syncs a tick later
     *              is registered too.
     */
    private static void resolveMinionIdentity(ChestMenu menu, boolean force) {
        List<String> upgradeUuids = readUpgradeUuids(menu);
        if (upgradeUuids.isEmpty() && !force) return;

        String resolvedKey = MinionIdentityRegistry.resolve(
                upgradeUuids, currentArmorStandKey, currentMinionKey, currentMinionName,
                exactPositionByStandKey.get(currentArmorStandKey));

        if (resolvedKey != null && !resolvedKey.isEmpty() && !resolvedKey.equals(currentMinionKey)) {
            adoptMinionKey(currentMinionKey, resolvedKey);
        }

        // Only the forced pass (after the container has settled) ends the search — until
        // then a minion with no readable upgrade yet is simply re-checked next tick.
        if (force) {
            identityResolved = true;

            // A minion with no usable upgrade uuid stays on its armor-stand key. Because
            // that key used to include the randomly re-rolled held weapon, this same
            // minion may have saved fuel state under several different old keys — one per
            // sword it happened to be holding. Consolidate them onto the current key so
            // those minions recover too, not just the ones with an upgrade uuid.
            if (FuelCycleTracker.isArmorStandFormatKey(currentMinionKey)) {
                migrateStrandedKeysAtSamePosition(currentMinionKey);
            }

            // Always report the outcome: when a minion can't be identified by an upgrade
            // uuid, the per-slot reasons are the only way to tell why.
            logIdentityOutcome(menu);
        }
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
        if (GuiDebugLog.isActive()) {
            FuelSlotChange.Reading baseline = lastObservedFuelSlots.get(oldKey);
            trace("key adopted \"" + oldKey + "\" -> \"" + newKey + "\" baselineCarriedOver="
                    + (baseline == null ? "none" : baseline));
        }
        // Carry the in-memory fuel-slot baseline across, otherwise the very next tick
        // would compare this minion's slot against "no baseline" and could mistake its
        // existing fuel for a brand new placement.
        FuelSlotChange.Reading observed = lastObservedFuelSlots.remove(oldKey);
        if (observed != null) lastObservedFuelSlots.putIfAbsent(newKey, observed);
        Long observedAt = lastObservedFuelSlotTimes.remove(oldKey);
        if (observedAt != null) lastObservedFuelSlotTimes.putIfAbsent(newKey, observedAt);

        if (FuelCycleTracker.isArmorStandFormatKey(oldKey)) {
            FuelCycleTracker.renameMinionKey(oldKey, newKey);
        }

        migrateStrandedKeysAtSamePosition(newKey);

        System.out.println("[MinionFuelTracker] Minion \"" + currentMinionName + "\" identified by upgrade uuid: "
                + "\"" + oldKey + "\" -> \"" + newKey + "\"");
        currentMinionKey = newKey;
    }

    /**
     * Re-attaches fuel state that was saved under a <em>stale</em> armor-stand key for this
     * same minion.
     *
     * <p>Armor-stand keys used to include the stand's hand slots, and Hypixel gives every
     * minion a randomly chosen sword each time it loads in. The same physical minion
     * therefore saved its fuel state under a different key after almost every rejoin, so
     * the plain rename above can't find that data: today's key no longer equals the one on
     * disk, and a live fuel-day is left stranded under a key nothing will ever match again
     * — while the next refuel counts the minion again under its new key. (This re-rolled
     * sword is the original cause of the "which minion is which" mis-tracking that
     * motivated identifying minions by a permanent upgrade uuid.) Newly built keys no
     * longer include the hands, but keys saved before that change still have to be found.
     *
     * <p>The block position is the stable half of the key and holds exactly one minion, so
     * any stored key at this minion's position belongs to this minion and is migrated onto
     * {@code newKey}. {@code renameMinionKey} merges rather than overwrites, so recovering
     * several stale keys at once — one per sword this minion has been seen holding — can't
     * lose or double-count anything.
     */
    private static void migrateStrandedKeysAtSamePosition(String newKey) {
        if (currentArmorStandKey.isEmpty()) return;

        int positionEnd = currentArmorStandKey.indexOf('|');
        if (positionEnd <= 0) return;
        String position = currentArmorStandKey.substring(0, positionEnd);

        for (String strandedKey : FuelCycleTracker.findMinionKeysAtPosition(position, currentArmorStandKey)) {
            System.out.println("[MinionFuelTracker] Recovering fuel state saved under a stale armor-stand key "
                    + "at " + position + " (recorded while holding a different randomly assigned item): \""
                    + strandedKey + "\" -> \"" + newKey + "\"");
            FuelCycleTracker.renameMinionKey(strandedKey, newKey);
        }
    }

    /**
     * The outcome of inspecting one upgrade slot — the accepted uuid when there is one,
     * plus a human-readable reason either way so the whole decision can be logged and
     * shown by {@code /it minionid}. Without this, a minion that fails to be identified
     * gives no clue whether the slot was empty, the item had no uuid, or its uuid was
     * rejected as self-resetting.
     */
    private record UpgradeSlotRead(int slotFromOne, String itemName, String uuid, String reason) {
        boolean accepted() { return uuid != null; }
    }

    /**
     * Reads the permanent uuids of whatever sits in this minion's upgrade slots, in slot
     * order, skipping anything that can't be trusted (see {@link #inspectUpgradeSlot}).
     * Flycatchers are the common case, but any upgrade that keeps its uuid works — hence
     * the slots being scanned for any qualifying item rather than a specific item id.
     */
    private static List<String> readUpgradeUuids(ChestMenu menu) {
        List<String> uuids = new ArrayList<>(UPGRADE_SLOT_INDEXES.length);
        for (UpgradeSlotRead read : inspectUpgradeSlots(menu)) {
            if (read.accepted() && !uuids.contains(read.uuid())) uuids.add(read.uuid());
        }
        return uuids;
    }

    /** Inspects every upgrade slot, returning one diagnostic result per slot. */
    private static List<UpgradeSlotRead> inspectUpgradeSlots(ChestMenu menu) {
        List<UpgradeSlotRead> reads = new ArrayList<>(UPGRADE_SLOT_INDEXES.length);
        for (int slotIndex : UPGRADE_SLOT_INDEXES) {
            reads.add(inspectUpgradeSlot(menu, slotIndex));
        }
        return reads;
    }

    /**
     * Decides whether the item in {@code slotIndex} carries a genuinely permanent
     * {@code custom_data.uuid}, i.e. whether it was freshly generated along with the menu
     * we're looking at. Self-resetting GUI items (the minion head, Enchanted Hoppers, ...)
     * get a new uuid <em>and</em> a new timestamp every time the menu opens, so a
     * timestamp that isn't comfortably older than this GUI's opening disqualifies the
     * item. Every rejection path records why, for the diagnostics.
     */
    private static UpgradeSlotRead inspectUpgradeSlot(ChestMenu menu, int slotIndex) {
        int slotFromOne = slotIndex + 1;

        if (slotIndex >= menu.slots.size()) {
            return new UpgradeSlotRead(slotFromOne, "-", null,
                    "slot does not exist (menu only has " + menu.slots.size() + " slots)");
        }

        ItemStack stack = menu.getSlot(slotIndex).getItem();
        if (stack == null || stack.isEmpty()) {
            return new UpgradeSlotRead(slotFromOne, "empty", null, "slot is empty");
        }

        String itemName = stack.getHoverName().getString();

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return new UpgradeSlotRead(slotFromOne, itemName, null, "item has no custom_data");
        }

        CompoundTag nbt = customData.copyTag();
        String uuid = nbt.getStringOr("uuid", "");
        if (uuid.isEmpty()) {
            return new UpgradeSlotRead(slotFromOne, itemName, null,
                    "item has no custom_data.uuid (keys: " + nbt.keySet() + ")");
        }

        Tag timestampTag = nbt.get("timestamp");
        if (timestampTag == null) {
            // Without a timestamp there is no way to prove the uuid isn't regenerated
            // every time the menu opens, so the item can't be trusted as an identity.
            return new UpgradeSlotRead(slotFromOne, itemName, null,
                    "item has a uuid but no custom_data.timestamp to verify it is permanent");
        }

        Long timestamp = parseTimestamp(timestampTag);
        if (timestamp == null) {
            return new UpgradeSlotRead(slotFromOne, itemName, null,
                    "could not parse custom_data.timestamp (raw: " + timestampTag + ")");
        }

        long reference = menuOpenedAtMs > 0 ? menuOpenedAtMs : System.currentTimeMillis();
        long ageMs = reference - timestamp;
        if (ageMs < UPGRADE_TIMESTAMP_MIN_AGE_MS) {
            return new UpgradeSlotRead(slotFromOne, itemName, null,
                    "timestamp is only " + ageMs + "ms old, so this item is regenerated "
                            + "with the menu and its uuid is not permanent");
        }

        return new UpgradeSlotRead(slotFromOne, itemName, uuid,
                "uuid " + uuid + " accepted (item is " + (ageMs / 86_400_000L) + " days old)");
    }

    /**
     * Reads a SkyBlock {@code timestamp} tag as epoch milliseconds. Hypixel has used
     * several encodings over the years and an item's age is the only thing separating a
     * permanent uuid from a regenerated one, so all of them are accepted: a numeric tag
     * (epoch millis, the modern form), a numeric string, and the legacy display-date
     * strings such as {@code "4/22/22 4:31 PM"} that older items still carry. Returns null
     * if the value is in none of those forms.
     */
    private static Long parseTimestamp(Tag tag) {
        String raw = tag.toString().trim();

        // Strip the SNBT type suffix (1650642660000L) and/or surrounding quotes.
        if (raw.length() > 1 && (raw.endsWith("L") || raw.endsWith("l"))) {
            raw = raw.substring(0, raw.length() - 1);
        }
        if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        raw = raw.trim();
        if (raw.isEmpty()) return null;

        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            // Not numeric — fall through to the legacy date formats below.
        }

        for (java.time.format.DateTimeFormatter formatter : LEGACY_TIMESTAMP_FORMATS) {
            try {
                return java.time.LocalDateTime.parse(raw, formatter)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            } catch (java.time.format.DateTimeParseException ignored) {
                // Try the next known format.
            }
        }
        return null;
    }

    /** Legacy Hypixel {@code timestamp} display formats, e.g. {@code "4/22/22 4:31 PM"}. */
    private static final java.time.format.DateTimeFormatter[] LEGACY_TIMESTAMP_FORMATS = {
            java.time.format.DateTimeFormatter.ofPattern("M/d/yy h:mm a", java.util.Locale.US),
            java.time.format.DateTimeFormatter.ofPattern("d/M/yy h:mm a", java.util.Locale.US),
            java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy h:mm a", java.util.Locale.US),
    };

    /**
     * Builds a human-readable report of what the currently open minion GUI's upgrade slots
     * contain and how each was judged — the diagnostic behind {@code /it minionid}.
     * Returns null when no minion GUI is currently open.
     */
    public static List<String> describeOpenMinionGui() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || currentMinionName.isEmpty()) return null;
        if (!(client.player.containerMenu instanceof ChestMenu menu)) return null;

        List<String> lines = new ArrayList<>();
        lines.add("Minion: " + currentMinionName);
        lines.add("Tracking key: " + currentMinionKey);
        lines.add("Key type: " + (MinionIdentityRegistry.isUuidKey(currentMinionKey)
                ? "permanent upgrade uuid"
                : FuelCycleTracker.isArmorStandFormatKey(currentMinionKey)
                        ? "legacy position+equipment"
                        : "GUI title fallback"));
        lines.add("Identity settled: " + identityResolved);
        for (UpgradeSlotRead read : inspectUpgradeSlots(menu)) {
            lines.add("Upgrade slot " + read.slotFromOne() + " [" + read.itemName() + "]: " + read.reason());
        }
        return lines;
    }

    /** Logs the final identity decision for a minion GUI, including every rejection reason. */
    private static void logIdentityOutcome(ChestMenu menu) {
        StringBuilder message = new StringBuilder("[MinionFuelTracker] Identity for \"")
                .append(currentMinionName).append("\" -> \"").append(currentMinionKey).append("\"");
        for (UpgradeSlotRead read : inspectUpgradeSlots(menu)) {
            message.append("\n    upgrade slot ").append(read.slotFromOne())
                    .append(" [").append(read.itemName()).append("]: ").append(read.reason());
        }
        System.out.println(message);
        if (GuiDebugLog.isActive()) trace(message.toString().replace("[MinionFuelTracker] ", ""));
    }

    /** Counts any fuel placed just before the minion GUI closed, then clears all per-GUI state. */
    private static void leaveMinionGui(Minecraft client) {
        if (GuiDebugLog.isActive()) {
            AbstractContainerMenu menu = lastMinionMenu;
            trace("leaving minion GUI " + traceKey() + " canTrackChanges=" + canTrackChanges
                    + " baseline=" + (fuelWatcher == null ? "none" : fuelWatcher.baseline())
                    + (menu != null && FUEL_SLOT_INDEX < menu.slots.size()
                            ? " slot" + FUEL_SLOT_INDEX + "=" + GuiDebugLog.describe(menu.getSlot(FUEL_SLOT_INDEX).getItem())
                                    + " carried=" + GuiDebugLog.describe(menu.getCarried())
                            : " menu=none"));
        }
        flushUnsettledFuelChange(client);
        resetMinionGuiState();
    }

    /** Clears all per-GUI state once we're no longer looking at a minion GUI. */
    private static void resetMinionGuiState() {
        lastTraceLine = "";
        lastMinionMenu = null;
        fuelWatcher = null;
        currentMinionName = "";
        currentMinionKey = "";
        currentArmorStandKey = "";
        currentMinionClickMs = 0;
        identityResolved = false;
        canTrackChanges = false;
        pendingStableReading = null;
        stableTickCount = 0;
        wasInMinionGui = false;
    }

    public static void checkFuelSlot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            // Whatever menu we were watching belongs to a session that is gone.
            lastMinionMenu = null;
            return;
        }

        // Check if we're currently in a GUI
        if (client.screen == null) {
            // We're not in any GUI - reset if we were previously in a minion GUI
            if (wasInMinionGui) {
                leaveMinionGui(client);
            }
            return;
        }

        // Check if the current screen is a container screen
        if (!(client.screen instanceof AbstractContainerScreen containerScreen)) {
            // We're in some other GUI - reset if we were in a minion GUI
            if (wasInMinionGui) {
                leaveMinionGui(client);
            }
            return;
        }

        AbstractContainerMenu handler = client.player.containerMenu;
        if (!(handler instanceof ChestMenu containerHandler)) return;

        // Note when this particular container appeared. Every menu the server opens is a
        // new object, so a change here means a genuinely new GUI (and therefore newly
        // generated GUI items, see inspectUpgradeSlot()).
        boolean newMenu = lastSeenMenu.get() != handler;
        if (newMenu) {
            lastSeenMenu = new java.lang.ref.WeakReference<>(handler);
            menuOpenedAtMs = System.currentTimeMillis();
        }

        // Get the title from the screen
        String title = containerScreen.getTitle().getString();

        // Only track an actual minion management GUI. The Bazaar's
        // "Inferno Minion Fuel" menu also contains "minion" in its title, but its
        // slot layout is unrelated and must never be treated as a minion fuel slot.
        // Bazaar item pages ("Inferno Minion ➜ Reaper Pepper") are titled "<category> ➜ <item>".
        String normalizedTitle = title.toLowerCase(java.util.Locale.ROOT);
        if (!normalizedTitle.contains("minion") || normalizedTitle.contains("minion fuel")
                || normalizedTitle.contains("\u279C")) {
            // We're in a container but not a minion - reset if we were in a minion GUI
            if (wasInMinionGui) {
                leaveMinionGui(client);
            }
            return;
        }

        // We're in a minion GUI
        wasInMinionGui = true;
        if (newMenu) FuelExpiredAlertTracker.onMinionGuiOpened();

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
            if (GuiDebugLog.isActive()) {
                trace("switched minion: title \"" + currentMinionName + "\" -> \"" + title + "\" previousStand=\""
                        + currentArmorStandKey + "\" pendingStand=" + (pendingArmorStandKey == null
                                ? "none" : "\"" + pendingArmorStandKey + "\"")
                        + " clickAgeMs=" + (lastArmorStandInteractMs == 0 ? "n/a"
                                : String.valueOf(System.currentTimeMillis() - lastArmorStandInteractMs)));
            }
            // Fuel placed in the previous minion just before switching must still count.
            flushUnsettledFuelChange(client);
            lastTraceLine = "";

            // RESET EVERYTHING for the new minion
            currentMinionName = title;
            // Prefer the position+equipment key of whichever armor stand was just
            // right-clicked (stable per physical minion, even across server restarts) so
            // identical minion types/tiers aren't merged into one. Falls back to the title
            // itself if no recent armor-stand interaction was captured (e.g. GUI reopened
            // via a favourite/macro instead of a fresh right-click). Once the GUI's
            // contents have synced, this is upgraded to a permanent upgrade-uuid key
            // wherever one is available (see resolveMinionIdentity()).
            long armorStandClickMs = lastArmorStandInteractMs;
            String armorStandKey = consumeLastArmorStandKey();
            currentArmorStandKey = armorStandKey != null ? armorStandKey : "";
            currentMinionKey = armorStandKey != null ? armorStandKey : title;
            currentMinionClickMs = armorStandKey != null ? armorStandClickMs : 0;
            identityResolved = false;
            canTrackChanges = false; // Disable tracking until we stabilize
            fuelWatcher = null; // Clear the old state
            pendingStableReading = null;
            stableTickCount = 0;

            // DON'T store initial state yet - wait for next tick
            lastMinionMenu = containerHandler;
            if (GuiDebugLog.isActive()) {
                trace("now tracking " + traceKey() + " clickMsAgo="
                        + (currentMinionClickMs == 0 ? "n/a" : String.valueOf(System.currentTimeMillis() - currentMinionClickMs))
                        + " menu=" + containerHandler.containerId);
            }
            return;
        }
        lastMinionMenu = containerHandler;

        // The right-click that selected this minion can land a tick or two before the
        // server actually swaps in its GUI, leaving the PREVIOUS minion's menu on screen.
        // Reading that menu would attribute another minion's fuel slot and — far worse —
        // another minion's upgrade uuids to this one, so wait for the new menu to arrive.
        // The wait is bounded by the same freshness window used for the click itself, so a
        // click that never resulted in a new menu can't stall tracking indefinitely.
        if (currentMinionClickMs > 0 && menuOpenedAtMs < currentMinionClickMs
                && System.currentTimeMillis() - currentMinionClickMs <= ARMOR_STAND_INTERACT_MAX_AGE_MS) {
            if (GuiDebugLog.isActive()) {
                traceTransition("waiting for the clicked minion's own menu; still showing menu#" + containerHandler.containerId);
            }
            return;
        }

        // Upgrade slots sync alongside the rest of the container, so keep looking until a
        // permanent upgrade uuid shows up (and to pick up a second upgrade that syncs a
        // tick later than the first). Done before any use of currentMinionKey below so the
        // fuel-slot baseline is looked up under the final key.
        if (!identityResolved) resolveMinionIdentity(containerHandler, false);

        // If we can't track changes yet, we're in the stabilization phase. The slot's
        // contents must read back identically for STABILIZATION_TICKS consecutive ticks
        // before we trust it as the baseline — a single-tick read risks catching the
        // container mid-sync (e.g. still showing the "empty" glass-pane placeholder
        // before the server's real fuel data has arrived), which would otherwise get
        // misread as a brand new fuel placement the moment the real data shows up.
        if (!canTrackChanges) {
            if (FUEL_SLOT_INDEX >= containerHandler.slots.size()) return;
            ItemStack current = containerHandler.getSlot(FUEL_SLOT_INDEX).getItem();
            FuelSlotChange.Reading currentReading = readingOf(current);

            if (currentReading.sameAs(pendingStableReading)) {
                stableTickCount++;
            } else {
                pendingStableReading = currentReading;
                stableTickCount = 1;
            }
            if (GuiDebugLog.isActive() && stableTickCount < STABILIZATION_TICKS) {
                traceTransition("stabilizing " + stableTickCount + "/" + STABILIZATION_TICKS + " slot" + FUEL_SLOT_INDEX
                        + "=" + GuiDebugLog.describe(current) + " " + traceKey());
            }

            if (stableTickCount >= STABILIZATION_TICKS) {
                // The container has fully synced by now, so settle the minion's identity
                // with whatever the upgrade slots actually hold — including the case where
                // there is no usable upgrade uuid at all, which falls back to the legacy
                // position+equipment key (or a previously registered identity for it).
                if (!identityResolved) resolveMinionIdentity(containerHandler, true);

                FuelSlotChange.Reading stable = pendingStableReading;
                FuelSlotChange.Reading lastObserved = lastObservedFuelSlots.get(currentMinionKey);
                FuelSlotChange.Reading baseline;
                if (lastObserved != null && !lastObserved.sameAs(stable)) {
                    // Leave the previous snapshot as the comparison baseline. The next
                    // normal tick will process the net change, including fuel added while
                    // this minion GUI was closed.
                    baseline = lastObserved;
                    if (GuiDebugLog.isActive()) {
                        trace("baseline locked " + traceKey() + " stable=" + stable
                                + " DIFFERS from the last confirmed " + lastObserved
                                + "; keeping the last confirmed one as baseline so the net change is processed next");
                    }
                } else {
                    baseline = stable;
                    lastObservedFuelSlots.put(currentMinionKey, stable);
                    lastObservedFuelSlotTimes.put(currentMinionKey, System.currentTimeMillis());
                    if (GuiDebugLog.isActive()) {
                        trace("baseline locked " + traceKey() + " baseline=" + stable
                                + (lastObserved == null ? " (first time seen)" : " (same as last confirmed)"));
                    }
                }
                fuelWatcher = new FuelSlotWatcher(baseline, watcherTracer);
                canTrackChanges = true; // Enable tracking for next tick
                lastTraceLine = "";
                pendingStableReading = null;
                stableTickCount = 0;
            }
            return;
        }

        // Now we can safely check for changes
        if (FUEL_SLOT_INDEX >= containerHandler.slots.size() || fuelWatcher == null) return;

        ItemStack currentItem = containerHandler.getSlot(FUEL_SLOT_INDEX).getItem();
        ItemStack carried = containerHandler.getCarried();

        FuelSlotWatcher.Outcome outcome = fuelWatcher.tick(
                readingOf(currentItem), readingOf(carried), !carried.isEmpty(), unitsExpiredSinceConfirmed());
        if (outcome != null) {
            applyFuelChange(client, currentItem, outcome);
            lastTraceLine = "";
        }
    }

    /**
     * Counts fuel that was placed so shortly before the GUI closed (or before another minion
     * was selected) that the slot's own reading never showed it. The state is read from the
     * menu itself, which keeps what it last showed after closing — so even a click made in the
     * same tick as the close is seen. See {@link FuelSlotWatcher#flush}.
     */
    private static void flushUnsettledFuelChange(Minecraft client) {
        AbstractContainerMenu menu = lastMinionMenu;
        if (!canTrackChanges || menu == null || currentMinionKey.isEmpty() || fuelWatcher == null) {
            if (GuiDebugLog.isActive() && (menu != null || canTrackChanges)) {
                trace("flush: nothing counted, " + (!canTrackChanges
                        ? "the GUI closed before the baseline locked (canTrackChanges=false)"
                        : menu == null ? "no menu" : "no minion key"));
            }
            return;
        }
        if (FUEL_SLOT_INDEX >= menu.slots.size()) return;

        ItemStack slotItem = menu.getSlot(FUEL_SLOT_INDEX).getItem();
        ItemStack carried = menu.getCarried();
        FuelSlotWatcher.Outcome outcome = fuelWatcher.flush(
                readingOf(slotItem), readingOf(carried), !carried.isEmpty(), unitsExpiredSinceConfirmed());
        if (outcome == null) return;

        System.out.println("[MinionFuelTracker] Minion GUI left before the fuel slot settled; counting the "
                + "placement seen on close for \"" + currentMinionKey + "\"");
        applyFuelChange(client, slotItem, outcome);
    }

    /**
     * Reads what a stack in the fuel slot (or on the cursor) amounts to. The fuel's own "Time Remaining"
     * lore line is what says how much fuel is queued: Hypixel keeps the stack count at 1 and, around a
     * placement, shows a count that has nothing to do with the amount (see {@link FuelSlotWatcher}).
     */
    private static FuelSlotChange.Reading readingOf(ItemStack stack) {
        boolean isPlaceholder = stack.getItem() == Items.ORANGE_STAINED_GLASS_PANE
                && stack.getHoverName().getString().equals("Fuel");
        if (stack.isEmpty() || isPlaceholder) return FuelSlotChange.Reading.EMPTY;
        // Fuel items share the same base Item but differ by SkyBlock NBT/hover name, so the
        // display name is what tells one fuel type from another.
        String name = stack.getHoverName().getString();
        long remainingMs = timeRemainingMs(stack);
        return remainingMs >= 0
                ? FuelSlotChange.Reading.timed(name, remainingMs)
                : FuelSlotChange.Reading.of(name, stack.getCount());
    }

    /** The time stated by the stack's "Time Remaining" lore line in milliseconds, or -1 when it has none. */
    private static long timeRemainingMs(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return -1;
        for (Component line : lore.lines()) {
            long remainingMs = FuelSlotChange.parseTimeRemainingMs(line.getString());
            if (remainingMs >= 0) return remainingMs;
        }
        return -1;
    }

    /**
     * Fuel units that have run out since this minion's slot was last confirmed. If the player added fuel
     * while this GUI was closed, that addition can be hidden by one or more units running out in the
     * meantime, so those are added back when comparing readings.
     */
    private static int unitsExpiredSinceConfirmed() {
        long observedAt = lastObservedFuelSlotTimes.getOrDefault(currentMinionKey, System.currentTimeMillis());
        // Enchanted Clock time warps made the fuel run out sooner than the wall clock alone
        // says, so count that time as elapsed too.
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - observedAt)
                + FuelCycleTracker.warpedMsSince(observedAt);
        return (int) Math.min(Integer.MAX_VALUE, elapsedMs / FuelCycleTracker.DAY_MS);
    }

    /** Reports a change in the fuel slot and makes it the new confirmed state. */
    private static void applyFuelChange(Minecraft client, ItemStack currentItem, FuelSlotWatcher.Outcome outcome) {
        FuelSlotChange.Result change = outcome.change();
        boolean initialWasEmpty = outcome.previous().empty();
        int newFuelUnits = change.newFuelUnits();

        if (change.fuelRemoved()) {
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
                + "initial=[" + outcome.previous() + "] -> current=[" + outcome.confirmed() + "]"
                + " sameFuelType=" + change.sameFuelType() + " newFuelUnits=" + newFuelUnits);
        if (GuiDebugLog.isActive()) {
            trace("apply " + traceKey() + " initial=" + outcome.previous() + " -> current=" + outcome.confirmed()
                    + " slotItem=" + GuiDebugLog.describe(currentItem) + " unitsExpired=" + unitsExpiredSinceConfirmed()
                    + " newFuelUnits=" + newFuelUnits + " fuelRemoved=" + change.fuelRemoved()
                    + " initialWasEmpty=" + initialWasEmpty
                    + (newFuelUnits > 0 ? "" : " (nothing to count; baseline updated)"));
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
                if (GuiDebugLog.isActive()) trace("COUNTED, chat message sent: \"" + message.getString() + "\"");
            } else if (GuiDebugLog.isActive()) {
                trace("COUNTED " + newFuelUnits + " fuel unit(s); chat message is turned off");
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
            FuelCycleTracker.onMinionFueled(currentMinionKey, newFuelUnits, fuelGradeOf(currentItem));
        }

        // The watcher already treats the change as its new baseline, which prevents repeated
        // messages; remember it for when this minion's GUI is opened again.
        lastObservedFuelSlots.put(currentMinionKey, outcome.confirmed());
        lastObservedFuelSlotTimes.put(currentMinionKey, System.currentTimeMillis());
    }

    /**
     * The Inferno fuel grade of a fuel item, from the colour of its name (its rarity): Rare is blue, Epic is
     * dark purple and Legendary is gold. Any other colour is not one of the grades, which gives -1.
     */
    private static int fuelGradeOf(ItemStack item) {
        ChatFormatting color = getFuelColor(item);
        if (color == ChatFormatting.BLUE) return InfernoReturnCalculator.GRADE_FUEL;
        if (color == ChatFormatting.DARK_PURPLE) return InfernoReturnCalculator.GRADE_HEAVY;
        if (color == ChatFormatting.GOLD) return InfernoReturnCalculator.GRADE_HYPERGOLIC;
        return -1;
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