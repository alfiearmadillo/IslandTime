package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Keeps the copied greenhouse blueprints, and which one is on show, in
 * {@code config/IslandTime/greenhouse_blueprints.json}.
 */
public class GreenhouseBlueprintManager {

    static final int MAX_BLUEPRINTS = 50;

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/greenhouse_blueprints.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** What is written to the file. */
    private static final class Data {
        String active = "";
        List<GreenhouseBlueprint> blueprints = new ArrayList<>();
    }

    private static Data data = new Data();

    private GreenhouseBlueprintManager() {}

    public static synchronized void load() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                Data loaded = GSON.fromJson(Files.readString(CONFIG_FILE), Data.class);
                if (loaded != null) {
                    if (loaded.blueprints == null) loaded.blueprints = new ArrayList<>();
                    if (loaded.active == null) loaded.active = "";
                    loaded.blueprints.removeIf(b -> b == null || b.boards == null || b.id == null);
                    for (GreenhouseBlueprint b : loaded.blueprints) {
                        if (b.plotBoards == null) b.plotBoards = new LinkedHashMap<>();
                    }
                    data = loaded;
                }
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to load greenhouse blueprints: " + e.getMessage());
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, GSON.toJson(data));
        } catch (IOException e) {
            System.err.println("[IslandTime] Failed to save greenhouse blueprints: " + e.getMessage());
        }
    }

    static synchronized List<GreenhouseBlueprint> getAll() {
        return List.copyOf(data.blueprints);
    }

    /** The blueprint that is on show, or null if there is none. */
    static synchronized GreenhouseBlueprint getActive() {
        for (GreenhouseBlueprint b : data.blueprints) {
            if (b.id.equals(data.active)) return b;
        }
        return null;
    }

    /**
     * Stores a newly copied blueprint and makes it the active one.
     *
     * @return false if there is no room for another blueprint
     */
    static synchronized boolean add(GreenhouseBlueprint blueprint) {
        if (data.blueprints.size() >= MAX_BLUEPRINTS) return false;
        blueprint.id = UUID.randomUUID().toString();
        blueprint.name = uniqueName(blueprint.name.isBlank() ? "Layout" : blueprint.name);
        data.blueprints.add(blueprint);
        data.active = blueprint.id;
        save();
        return true;
    }

    private static String uniqueName(String wanted) {
        String name = wanted;
        for (int suffix = 2; nameTaken(name); suffix++) {
            name = wanted + " " + suffix;
        }
        return name;
    }

    private static boolean nameTaken(String name) {
        for (GreenhouseBlueprint b : data.blueprints) {
            if (b.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Finds a blueprint by its 1 based position in the list, or by name (exact match first, then a
     * partial one).
     */
    static synchronized GreenhouseBlueprint find(String query) {
        if (query == null || query.isBlank()) return null;
        String q = query.strip();
        try {
            int index = Integer.parseInt(q);
            return index >= 1 && index <= data.blueprints.size() ? data.blueprints.get(index - 1) : null;
        } catch (NumberFormatException ignored) {
            // not an index, treat it as a name
        }
        String lower = q.toLowerCase(Locale.ROOT);
        for (GreenhouseBlueprint b : data.blueprints) {
            if (b.name.toLowerCase(Locale.ROOT).equals(lower)) return b;
        }
        for (GreenhouseBlueprint b : data.blueprints) {
            if (b.name.toLowerCase(Locale.ROOT).contains(lower)) return b;
        }
        return null;
    }

    static synchronized void select(GreenhouseBlueprint blueprint) {
        data.active = blueprint.id;
        save();
    }

    /** Stops showing any blueprint without deleting it. */
    static synchronized void clearActive() {
        data.active = "";
        save();
    }

    static synchronized void delete(GreenhouseBlueprint blueprint) {
        data.blueprints.remove(blueprint);
        if (blueprint.id.equals(data.active)) data.active = "";
        save();
    }

    static synchronized void setOrientation(GreenhouseBlueprint blueprint, int rotation, boolean mirrored) {
        blueprint.rotation = Math.floorMod(rotation, 4);
        blueprint.mirrored = mirrored;
        save();
    }

    /**
     * Adds a board to a blueprint, for another plot's greenhouse, and uses it for that plot.
     *
     * @return false if the blueprint already has the most boards it can hold
     */
    static synchronized boolean addBoard(GreenhouseBlueprint blueprint, GreenhouseBlueprint.Board board,
                                         int plotX, int plotZ) {
        if (blueprint.boards.size() >= GreenhouseBlueprint.MAX_BOARDS) return false;
        blueprint.boards.add(board);
        blueprint.plotBoards.put(GreenhouseGeometry.plotKey(plotX, plotZ), blueprint.boards.size() - 1);
        save();
        return true;
    }

    /** Uses a board (0 based) for a plot. */
    static synchronized void assignBoard(GreenhouseBlueprint blueprint, int plotX, int plotZ, int boardIndex) {
        blueprint.plotBoards.put(GreenhouseGeometry.plotKey(plotX, plotZ), boardIndex);
        save();
    }
}
