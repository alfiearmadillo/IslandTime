package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
public class MinionIdentityRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/minion_identities.json");
    private static final Path CONFIG_TMP  = Paths.get("config/IslandTime/minion_identities.tmp");

    /** Alias ("uuid:..." / "stand:...") -> canonical minion key. */
    private static final Map<String, String> aliases = new ConcurrentHashMap<>();

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
     * @return the canonical key, or {@code fallbackKey} when this minion has no usable
     *         upgrade uuid and has never been registered before.
     */
    public static String resolve(List<String> upgradeUuids, String armorStandKey, String fallbackKey) {
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

        if (changed || saveFailed) save();

        return resolved;
    }

    /** Stores an alias, returning true when that actually changed anything. */
    private static boolean put(String alias, String canonical) {
        String previous = aliases.put(alias, canonical);
        return !canonical.equals(previous);
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

        try {
            String json = Files.readString(CONFIG_FILE);
            Map<String, String> stored = GSON.fromJson(json,
                    new TypeToken<LinkedHashMap<String, String>>() {}.getType());
            if (stored != null) {
                for (Map.Entry<String, String> entry : stored.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    if (entry.getKey().isEmpty() || entry.getValue().isEmpty()) continue;
                    aliases.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            System.err.println("[MinionIdentityRegistry] Load failed: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_TMP, GSON.toJson(new LinkedHashMap<>(aliases)));
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
