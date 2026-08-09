package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a persistent list of players (UUID/name/note) stored as JSON.
 */
public class PlayerListManager {

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/player_list.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<PlayerEntry>>() {}.getType();

    private static List<PlayerEntry> players = new ArrayList<>();

    public static class PlayerEntry {
        public String uuid;      // can be empty if only name was provided
        public String name;
        public String note;

        public PlayerEntry(String uuid, String name, String note) {
            this.uuid = uuid != null ? uuid : "";
            this.name = name != null ? name : "";
            this.note = note != null ? note : "";
        }
    }

    public static void load() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String json = Files.readString(CONFIG_FILE);
                List<PlayerEntry> loaded = GSON.fromJson(json, LIST_TYPE);
                if (loaded != null) {
                    players = loaded;
                }
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to load player list: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, GSON.toJson(players));
        } catch (IOException e) {
            System.err.println("[IslandTime] Failed to save player list: " + e.getMessage());
        }
    }

    public static List<PlayerEntry> getPlayers() {
        return players;
    }

    public static void addPlayer(String uuid, String name, String note) {
        players.add(new PlayerEntry(uuid, name, note));
        save();
    }

    public static void removePlayer(int index) {
        if (index >= 0 && index < players.size()) {
            players.remove(index);
            save();
        }
    }

    public static void updatePlayer(int index, String name, String note) {
        if (index >= 0 && index < players.size()) {
            PlayerEntry entry = players.get(index);
            entry.name = name;
            entry.note = note;
            save();
        }
    }
}
