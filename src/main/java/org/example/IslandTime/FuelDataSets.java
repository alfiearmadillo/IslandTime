package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The named sets of fuel day data, and which one the tracker records into. No Minecraft dependencies.
 *
 * <p>There are always two built-in sets, {@code main} (your real data, {@code fuel_days.json}) and {@code debug}
 * (the old "test data file", {@code fuel_days_test.json}), plus any number of custom ones. Each set is one file next
 * to the others in {@code config/IslandTime}, and the set in use is saved in {@code fuel_datasets.json}, so it is
 * still the one shown after a restart. Which set the GUI is showing at the moment is not stored here: browsing a
 * set does not change the one being recorded into.
 */
final class FuelDataSets {

    static final String MAIN_ID = "main";
    static final String DEBUG_ID = "debug";
    static final int MAX_NAME_LENGTH = 32;
    static final int MAX_CUSTOM_SETS = 50;

    /** Custom ids are limited to these characters, so the file names and backup prefixes built from them can't clash. */
    private static final Pattern CUSTOM_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final int MAX_ID_LENGTH = 24;

    record DataSet(String id, String name, boolean builtIn) {
    }

    /** What is written to the registry file. */
    private static final class Registry {
        String active = MAIN_ID;
        List<Custom> custom = new ArrayList<>();
    }

    private static final class Custom {
        String id;
        String name;

        Custom(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final DataSet MAIN = new DataSet(MAIN_ID, "Main", true);
    private static final DataSet DEBUG = new DataSet(DEBUG_ID, "Debug", true);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path baseDir = Paths.get("config/IslandTime");
    private static Registry registry = new Registry();

    private FuelDataSets() {
    }

    /** Points everything at another directory. Only for tests. */
    static synchronized void useDirectory(Path dir) {
        baseDir = dir;
        registry = new Registry();
    }

    // -------------------------------------------------------------------------
    // Files
    // -------------------------------------------------------------------------

    private static Path registryFile() {
        return baseDir.resolve("fuel_datasets.json");
    }

    /** The old test-mode flag, read once to carry "test data file on" over into the registry. */
    private static Path legacyTestModeFlag() {
        return baseDir.resolve("fuel_test_mode.txt");
    }

    static Path backupDir() {
        return baseDir.resolve("backups");
    }

    static synchronized Path dataFile(String id) {
        return baseDir.resolve(fileStem(id) + ".json");
    }

    static synchronized Path tmpFile(String id) {
        return baseDir.resolve(fileStem(id) + ".tmp");
    }

    /** The start of the file name of every rolling backup of the set. */
    static String backupPrefix(String id) {
        return switch (id) {
            case MAIN_ID -> "fuel_days_backup-";
            case DEBUG_ID -> "fuel_days_test_backup-";
            default -> "fuel_days_set_" + id + "_backup-";
        };
    }

    private static String fileStem(String id) {
        return switch (id) {
            case MAIN_ID -> "fuel_days";
            case DEBUG_ID -> "fuel_days_test";
            default -> "fuel_days_set_" + id;
        };
    }

    // -------------------------------------------------------------------------
    // Registry
    // -------------------------------------------------------------------------

    static synchronized void load() {
        Registry loaded = null;
        boolean existed = Files.exists(registryFile());
        if (existed) {
            try {
                loaded = GSON.fromJson(Files.readString(registryFile()), Registry.class);
            } catch (Exception e) {
                System.err.println("[FuelDataSets] Failed to load data sets: " + e.getMessage());
            }
        }

        Registry fresh = new Registry();
        if (loaded != null) {
            if (loaded.active != null) fresh.active = loaded.active;
            if (loaded.custom != null) {
                for (Custom c : loaded.custom) {
                    if (c == null || c.id == null || c.name == null) continue;
                    if (!isValidCustomId(c.id) || containsCustom(fresh, c.id)) continue;
                    String name = c.name.strip();
                    fresh.custom.add(new Custom(c.id, name.isEmpty() ? c.id : name));
                }
            }
        } else if (!existed) {
            fresh.active = readLegacyTestMode() ? DEBUG_ID : MAIN_ID;
        }
        registry = fresh;
        if (!isKnown(registry.active)) registry.active = MAIN_ID;
        if (!existed) save();
    }

    private static boolean readLegacyTestMode() {
        try {
            return Files.exists(legacyTestModeFlag())
                    && Boolean.parseBoolean(Files.readString(legacyTestModeFlag()).trim());
        } catch (IOException e) {
            return false;
        }
    }

    static synchronized void save() {
        Path file = registryFile();
        Path tmp = baseDir.resolve("fuel_datasets.tmp");
        try {
            Files.createDirectories(baseDir);
            Files.writeString(tmp, GSON.toJson(registry));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[FuelDataSets] Failed to save data sets: " + e.getMessage());
        }
    }

    private static boolean isValidCustomId(String id) {
        return id.length() <= MAX_ID_LENGTH && CUSTOM_ID.matcher(id).matches()
                && !MAIN_ID.equals(id) && !DEBUG_ID.equals(id);
    }

    private static boolean containsCustom(Registry r, String id) {
        for (Custom c : r.custom) {
            if (c.id.equals(id)) return true;
        }
        return false;
    }

    private static boolean isKnown(String id) {
        return MAIN_ID.equals(id) || DEBUG_ID.equals(id) || containsCustom(registry, id);
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /** Every set: Main, Debug, then the custom ones in the order they were made. */
    static synchronized List<DataSet> all() {
        List<DataSet> sets = new ArrayList<>();
        sets.add(MAIN);
        sets.add(DEBUG);
        for (Custom c : registry.custom) sets.add(new DataSet(c.id, c.name, false));
        return sets;
    }

    static synchronized DataSet get(String id) {
        if (MAIN_ID.equals(id)) return MAIN;
        if (DEBUG_ID.equals(id)) return DEBUG;
        for (Custom c : registry.custom) {
            if (c.id.equals(id)) return new DataSet(c.id, c.name, false);
        }
        return null;
    }

    static synchronized String activeId() {
        return registry.active;
    }

    static synchronized DataSet active() {
        DataSet set = get(registry.active);
        return set != null ? set : MAIN;
    }

    static synchronized boolean isActive(String id) {
        return registry.active.equals(id);
    }

    // -------------------------------------------------------------------------
    // Changes
    // -------------------------------------------------------------------------

    /** Why {@code name} can't be used for a new set, or null if it can. */
    static synchronized String validateName(String name) {
        if (name == null || name.strip().isEmpty()) return "Enter a name for the data set.";
        String stripped = name.strip();
        if (stripped.length() > MAX_NAME_LENGTH) return "Names can be at most " + MAX_NAME_LENGTH + " characters.";
        if (slug(stripped).isEmpty()) return "The name needs at least one letter or number.";
        for (DataSet set : all()) {
            if (set.name().equalsIgnoreCase(stripped)) return "A data set called \"" + set.name() + "\" already exists.";
        }
        if (registry.custom.size() >= MAX_CUSTOM_SETS) return "You can have at most " + MAX_CUSTOM_SETS + " custom data sets.";
        return null;
    }

    /**
     * Makes an empty custom set. Nothing is written for it until it is used.
     *
     * @throws IllegalArgumentException with a message that can be shown, if the name can't be used
     */
    static synchronized DataSet create(String name) {
        String problem = validateName(name);
        if (problem != null) throw new IllegalArgumentException(problem);

        String stripped = name.strip();
        String base = slug(stripped);
        String id = base;
        for (int suffix = 2; !isFreeId(id); suffix++) {
            String tail = "-" + suffix;
            id = base.substring(0, Math.min(base.length(), MAX_ID_LENGTH - tail.length())) + tail;
        }
        registry.custom.add(new Custom(id, stripped));
        save();
        return new DataSet(id, stripped, false);
    }

    private static boolean isFreeId(String id) {
        return isValidCustomId(id) && !containsCustom(registry, id);
    }

    /** Lower case letters and digits, with runs of anything else turned into one hyphen. */
    static String slug(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (ok) {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        if (sb.length() > MAX_ID_LENGTH) sb.setLength(MAX_ID_LENGTH);
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /** Records which set is in use. Does not move any data: see {@link FuelCycleTracker#switchDataSet(String)}. */
    static synchronized boolean setActive(String id) {
        if (!isKnown(id)) return false;
        registry.active = id;
        save();
        return true;
    }

    /**
     * Deletes a custom set and its file and backups. The built-in sets and the one in use are refused.
     *
     * @return null when it was deleted, otherwise why it was not
     */
    static synchronized String delete(String id) {
        if (MAIN_ID.equals(id) || DEBUG_ID.equals(id)) return "The built-in data sets can't be deleted.";
        if (registry.active.equals(id)) return "Switch to another data set before deleting the one in use.";
        Custom found = null;
        for (Custom c : registry.custom) {
            if (c.id.equals(id)) found = c;
        }
        if (found == null) return "That data set no longer exists.";

        registry.custom.remove(found);
        save();
        try {
            Files.deleteIfExists(dataFile(id));
            Files.deleteIfExists(tmpFile(id));
            if (Files.isDirectory(backupDir())) {
                String prefix = backupPrefix(id);
                try (var stream = Files.list(backupDir())) {
                    for (Path p : (Iterable<Path>) stream::iterator) {
                        if (p.getFileName().toString().startsWith(prefix)) Files.deleteIfExists(p);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[FuelDataSets] Could not remove the files of data set " + id + ": " + e.getMessage());
        }
        return null;
    }
}
