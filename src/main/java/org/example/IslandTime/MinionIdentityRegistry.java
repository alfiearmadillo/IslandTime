package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps the things we can observe about a physical minion onto one stable "canonical"
 * minion key, which is what every tracker ({@link FuelCycleTracker}) stores its
 * per-minion fuel state under.
 *
 * <p>Historically a minion was identified by its armor stand's block position plus its
 * full equipment (see {@code MinionFuelTracker.buildArmorStandKey()}). That works, but it
 * depends on the right-click that opened the GUI being captured at all, and on the
 * coordinates read back matching exactly — both of which occasionally miss.
 *
 * <p>Minion upgrade items (e.g. a Flycatcher) carry a genuinely permanent
 * {@code custom_data.uuid} that survives closing/reopening the menu, server reloads and
 * even moving the minion, so when one is present in a minion's upgrade slots it's by far
 * the most reliable identity available. Not every GUI item is trustworthy though — the
 * minion head, the Enchanted Hopper and most decorative items are re-generated (with a
 * brand new uuid) every time the menu is opened. Those are filtered out by their
 * {@code custom_data.timestamp} being freshly minted, see
 * {@code MinionFuelTracker.readStableUpgradeUuid()}.
 *
 * <p>This registry stores the aliases so the transition is seamless:
 * <ul>
 *   <li>{@code uuid:<upgrade uuid>} → canonical key, for every trusted upgrade uuid seen
 *       in that minion's upgrade slots. Both upgrade slots are registered, so removing or
 *       reordering one upgrade still resolves to the same minion via the other.</li>
 *   <li>{@code stand:<position+equipment key>} → canonical key, so a minion whose
 *       upgrades are later removed entirely (leaving no uuid to read) still resolves to
 *       the identity its data is stored under, instead of silently starting over.</li>
 * </ul>
 *
 * <p>The file is only a memory aid, not the source of truth: because a canonical key is
 * derived deterministically from the upgrade uuid itself, losing it simply means each
 * minion re-registers the same identity the next time its GUI is opened.
 *
 * <p>The canonical key itself is {@code uuid:<first trusted upgrade uuid ever seen>} for
 * minions that have a usable upgrade, and stays that exact string forever afterwards even
 * if that particular upgrade is later removed — renaming it again would orphan the saved
 * data. Minions with no trusted upgrade uuid keep using their legacy armor-stand key
 * exactly as before, so nothing regresses for them.
 *
 * <p><b>On disk</b> the aliases are grouped into one record per minion rather than written
 * out as the flat alias map they are in memory, because the flat form scattered each
 * minion's three entries across the file in hash order and repeated a ~230 character
 * armor-stand key on every one. A record instead reads as a single glanceable block:
 *
 * <pre>
 * {
 *   "armorTypes": { "armor1": "|minecraft:leather_boots:Leather Boots|..." },
 *   "minions": [
 *     {
 *       "id": "uuid:bc5ed5d0-2129-487f-8865-04a2bc6a2a1c",
 *       "name": "Inferno Minion XI",
 *       "position": "-41,216,10",
 *       "armor": "armor1",
 *       "upgrades": [ "bc5ed5d0-...", "b6ec6d5f-..." ]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>The equipment half of every armor-stand key is identical across minions of the same
 * build, so it is deduplicated into the shared {@code armorTypes} table and referenced by
 * name. The stored form is still lossless — {@code position + armorTypes[armor]} rebuilds
 * the original key character for character, which matters because that exact string is
 * what {@code MinionFuelTracker.buildArmorStandKey()} will look up.
 *
 * <p>Files written by older versions used the flat alias map; those are detected and
 * converted on load, so upgrading needs no action and loses nothing.
 */
public class MinionIdentityRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/minion_identities.json");
    private static final Path CONFIG_TMP  = Paths.get("config/IslandTime/minion_identities.tmp");

    /** Alias ("uuid:..." / "stand:...") -> canonical minion key. */
    private static final Map<String, String> aliases = new ConcurrentHashMap<>();

    /**
     * Canonical minion key -> the minion's GUI title, e.g. "Inferno Minion XI".
     *
     * <p>Purely cosmetic: it exists so the saved file can be read at a glance and is never
     * consulted when resolving an identity. Hypixel reuses the same title for every minion
     * of a kind, so it is not remotely unique and must not be treated as an alias.
     */
    private static final Map<String, String> displayNames = new ConcurrentHashMap<>();

    private static volatile boolean loaded = false;

    // Set when a save fails, so the next resolve() retries it instead of leaving the
    // in-memory aliases unwritten until something else happens to change them.
    private static volatile boolean saveFailed = false;

    /** Prefix of every canonical key derived from a permanent minion-upgrade uuid. */
    public static final String UUID_KEY_PREFIX = "uuid:";

    private static final String STAND_PREFIX = "stand:";

    private static String uuidAlias(String upgradeUuid) { return UUID_KEY_PREFIX + upgradeUuid; }
    private static String standAlias(String armorStandKey) { return STAND_PREFIX + armorStandKey; }

    /** True for canonical keys produced by this registry from an upgrade uuid. */
    public static boolean isUuidKey(String minionKey) {
        return minionKey != null && minionKey.startsWith(UUID_KEY_PREFIX);
    }

    /**
     * Resolves the canonical minion key to track a physical minion under.
     *
     * @param upgradeUuids trusted (non self-resetting) upgrade uuids read from the minion's
     *                     upgrade slots, in slot order. May be empty.
     * @param armorStandKey the legacy position+equipment key for the minion, or null/empty
     *                      when no armor-stand right-click was captured.
     * @param fallbackKey   the key to keep using when nothing better can be determined
     *                      (normally the armor-stand key, or the GUI title).
     * @param displayName   the minion's GUI title, recorded alongside the identity purely
     *                      so the saved file is readable. May be null or empty.
     * @return the canonical key, or {@code fallbackKey} when this minion has no usable
     *         upgrade uuid and has never been registered before.
     */
    public static String resolve(List<String> upgradeUuids, String armorStandKey,
                                 String fallbackKey, String displayName) {
        load();

        String canonical = null;

        // A permanent upgrade uuid is the strongest signal: prefer it over everything else.
        if (upgradeUuids != null) {
            for (String uuid : upgradeUuids) {
                String known = aliases.get(uuidAlias(uuid));
                if (known != null && !known.isEmpty()) { canonical = known; break; }
            }
        }

        // Known armor stand, but its upgrade uuids (if any) have never been seen before —
        // e.g. the first time an already-tracked, coordinate-keyed minion is opened after
        // this update, or a minion whose upgrades were swapped out for different ones.
        if (canonical == null && armorStandKey != null && !armorStandKey.isEmpty()) {
            String known = aliases.get(standAlias(armorStandKey));
            if (known != null && !known.isEmpty()) canonical = known;
        }

        // Brand new minion with a usable upgrade: mint a canonical key from its uuid.
        if (canonical == null && upgradeUuids != null && !upgradeUuids.isEmpty()) {
            canonical = uuidAlias(upgradeUuids.get(0));
        }

        // Nothing usable at all — keep the legacy behaviour untouched.
        if (canonical == null) return fallbackKey;

        final String resolved = canonical;
        boolean changed = false;

        if (upgradeUuids != null) {
            for (String uuid : upgradeUuids) {
                String alias = uuidAlias(uuid);
                String existing = aliases.get(alias);
                if (existing != null && !existing.isEmpty() && !existing.equals(resolved)) {
                    // This upgrade is already registered to a different minion — it must
                    // have been physically moved between minions. Leave the older mapping
                    // alone rather than quietly re-pointing it (and with it that minion's
                    // saved fuel data) at this one.
                    System.out.println("[MinionIdentityRegistry] Upgrade uuid " + uuid
                            + " is registered to '" + existing + "'; not re-binding it to '" + resolved + "'.");
                    continue;
                }
                changed |= put(alias, resolved);
            }
        }

        if (armorStandKey != null && !armorStandKey.isEmpty()) {
            // An identity can only live on one armor stand at a time. If it was previously
            // registered to a different stand (i.e. the upgrade was physically moved to
            // another minion), drop that older mapping — otherwise the old minion would
            // keep resolving to this same identity and the two minions' fuel tracking
            // would silently merge into one.
            final String standKey = standAlias(armorStandKey);
            changed |= aliases.entrySet().removeIf(entry ->
                    entry.getKey().startsWith(STAND_PREFIX)
                            && !entry.getKey().equals(standKey)
                            && resolved.equals(entry.getValue()));
            changed |= put(standKey, resolved);
        }

        if (displayName != null && !displayName.isEmpty()
                && !displayName.equals(displayNames.put(resolved, displayName))) {
            changed = true;
        }

        if (changed || saveFailed) save();

        return resolved;
    }

    /** Stores an alias, returning true when that actually changed anything. */
    private static boolean put(String alias, String canonical) {
        String previous = aliases.put(alias, canonical);
        return !canonical.equals(previous);
    }

    // -------------------------------------------------------------------------
    // On-disk model — one record per minion
    // -------------------------------------------------------------------------

    /** Root object of the saved file. */
    private static final class IdentityDocument {
        /** Shared armor-stand equipment signatures, referenced by {@link MinionRecord#armor}. */
        Map<String, String> armorTypes;
        List<MinionRecord> minions;
    }

    /** One physical minion: its canonical key and everything that resolves to it. */
    private static final class MinionRecord {
        /** The canonical minion key every tracker stores this minion's state under. */
        String id;
        /** GUI title, for readability only — never used to resolve an identity. */
        String name;
        /** Block position half of the armor-stand key, e.g. {@code -41,216,10}. */
        String position;
        /** Key into {@link IdentityDocument#armorTypes} for the equipment half. */
        String armor;
        /** Permanent upgrade-item uuids, stored without the {@code uuid:} prefix. */
        List<String> upgrades;
        /**
         * Any alias that doesn't fit the fields above, stored verbatim with its prefix.
         * Normally absent; it exists so a hand-edited or future-format file survives a
         * load/save round trip instead of having entries silently dropped.
         */
        List<String> extraAliases;
    }

    /** Splits an armor-stand key into its position and equipment halves. */
    private static String[] splitStandKey(String standKey) {
        int pipe = standKey.indexOf('|');
        return pipe < 0 ? new String[] { standKey, "" }
                        : new String[] { standKey.substring(0, pipe), standKey.substring(pipe) };
    }

    /** Projects the flat alias map into per-minion records for saving. */
    private static IdentityDocument toDocument() {
        // Canonical key -> its aliases, sorted so the output is byte-for-byte stable
        // between saves (a HashMap's iteration order is not, which made the old file
        // reshuffle itself every time it was written).
        Map<String, List<String>> byCanonical = new TreeMap<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(aliases).entrySet()) {
            byCanonical.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        // A minion whose name we know but whose aliases have all been evicted would
        // otherwise vanish; keep the record so the name isn't lost.
        for (String canonical : displayNames.keySet()) {
            byCanonical.computeIfAbsent(canonical, k -> new ArrayList<>());
        }

        Map<String, String> armorNames = new LinkedHashMap<>(); // signature -> "armorN"
        List<MinionRecord> records = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : byCanonical.entrySet()) {
            MinionRecord record = new MinionRecord();
            record.id = entry.getKey();
            record.name = displayNames.get(entry.getKey());

            for (String alias : entry.getValue()) {
                if (alias.startsWith(UUID_KEY_PREFIX)) {
                    if (record.upgrades == null) record.upgrades = new ArrayList<>();
                    record.upgrades.add(alias.substring(UUID_KEY_PREFIX.length()));
                } else if (alias.startsWith(STAND_PREFIX) && record.position == null
                        && !splitStandKey(alias.substring(STAND_PREFIX.length()))[0].isEmpty()) {
                    String[] halves = splitStandKey(alias.substring(STAND_PREFIX.length()));
                    record.position = halves[0];
                    if (!halves[1].isEmpty()) {
                        String name = armorNames.get(halves[1]);
                        if (name == null) {
                            name = "armor" + (armorNames.size() + 1);
                            armorNames.put(halves[1], name);
                        }
                        record.armor = name;
                    }
                } else {
                    // A second armor stand for one identity shouldn't happen (resolve()
                    // evicts the old one), so anything landing here is preserved as-is
                    // rather than being squeezed into the fields above.
                    if (record.extraAliases == null) record.extraAliases = new ArrayList<>();
                    record.extraAliases.add(alias);
                }
            }
            records.add(record);
        }

        records.sort(Comparator
                .comparing((MinionRecord r) -> r.name == null ? "" : r.name)
                .thenComparing(r -> r.position == null ? "" : r.position)
                .thenComparing(r -> r.id));

        IdentityDocument document = new IdentityDocument();
        document.minions = records;
        document.armorTypes = new LinkedHashMap<>();
        for (Map.Entry<String, String> armor : armorNames.entrySet()) {
            document.armorTypes.put(armor.getValue(), armor.getKey());
        }
        return document;
    }

    /** Rebuilds the flat alias map from per-minion records. */
    private static void fromDocument(IdentityDocument document) {
        if (document == null || document.minions == null) return;
        Map<String, String> armorTypes = document.armorTypes == null
                ? Map.<String, String>of() : document.armorTypes;

        for (MinionRecord record : document.minions) {
            if (record == null || record.id == null || record.id.isEmpty()) continue;

            if (record.name != null && !record.name.isEmpty()) {
                displayNames.put(record.id, record.name);
            }
            if (record.upgrades != null) {
                for (String uuid : record.upgrades) {
                    if (uuid != null && !uuid.isEmpty()) aliases.put(uuidAlias(uuid), record.id);
                }
            }
            if (record.position != null && !record.position.isEmpty()) {
                String armor = record.armor == null ? "" : armorTypes.getOrDefault(record.armor, "");
                aliases.put(standAlias(record.position + armor), record.id);
            }
            if (record.extraAliases != null) {
                for (String alias : record.extraAliases) {
                    if (alias != null && !alias.isEmpty()) aliases.put(alias, record.id);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Persistence — atomic write via tmp → rename
    // -------------------------------------------------------------------------

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        if (!Files.exists(CONFIG_FILE) && Files.exists(CONFIG_TMP)) {
            try {
                Files.move(CONFIG_TMP, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[MinionIdentityRegistry] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[MinionIdentityRegistry] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(CONFIG_FILE)) return;

        boolean converted = false;
        try {
            String json = Files.readString(CONFIG_FILE);
            JsonElement root = JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) return;
            JsonObject object = root.getAsJsonObject();

            if (object.has("minions")) {
                fromDocument(GSON.fromJson(object, IdentityDocument.class));
            } else {
                // Pre-record format: a flat alias -> canonical map. Read it as-is and
                // rewrite it in the new shape, so upgrading needs no action.
                loadLegacyAliasMap(json);
                converted = !aliases.isEmpty();
            }
        } catch (Exception e) {
            System.err.println("[MinionIdentityRegistry] Load failed: " + e.getMessage());
            return;
        }

        if (converted) {
            System.out.println("[MinionIdentityRegistry] Converted " + aliases.size()
                    + " saved aliases to the per-minion format.");
            save();
        }
    }

    /** Reads a file written before identities were grouped into per-minion records. */
    private static void loadLegacyAliasMap(String json) {
        Map<String, String> stored = GSON.fromJson(json,
                new TypeToken<LinkedHashMap<String, String>>() {}.getType());
        if (stored == null) return;
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (entry.getKey().isEmpty() || entry.getValue().isEmpty()) continue;
            aliases.put(entry.getKey(), entry.getValue());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_TMP, GSON.toJson(toDocument()));
            Files.move(CONFIG_TMP, CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            saveFailed = false;
        } catch (IOException e) {
            saveFailed = true;
            System.err.println("[MinionIdentityRegistry] Save failed: " + e.getMessage());
            try { Files.deleteIfExists(CONFIG_TMP); } catch (IOException ignored) {}
        }
    }
}
