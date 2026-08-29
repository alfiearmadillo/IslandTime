package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

import java.io.*;

/**
 * Persists the "keep resource pack" setting and cached pack metadata.
 */
public class ResourcePackConfig {

    private static final File FILE = new File("config/IslandTime/resource_pack.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean enabled = false;
    private static String cachedPackHash = "";
    private static String cachedPackUrl = "";

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    public static String getCachedPackHash() {
        return cachedPackHash;
    }

    public static String getCachedPackUrl() {
        return cachedPackUrl;
    }

    public static void setCachedPackMeta(String hash, String url) {
        cachedPackHash = hash != null ? hash : "";
        cachedPackUrl = url != null ? url : "";
        save();
    }

    public static void load() {
        if (!FILE.exists()) return;
        try (FileReader reader = new FileReader(FILE)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data != null) {
                enabled = data.enabled;
                cachedPackHash = data.cachedPackHash != null ? data.cachedPackHash : "";
                cachedPackUrl = data.cachedPackUrl != null ? data.cachedPackUrl : "";
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to load resource_pack.json: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(FILE)) {
                Data data = new Data();
                data.enabled = enabled;
                data.cachedPackHash = cachedPackHash;
                data.cachedPackUrl = cachedPackUrl;
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to save resource_pack.json: " + e.getMessage());
        }
    }

    private static class Data {
        @Expose boolean enabled = false;
        @Expose String cachedPackHash = "";
        @Expose String cachedPackUrl = "";
    }
}
