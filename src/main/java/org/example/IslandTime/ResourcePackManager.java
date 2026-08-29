package org.example.islandtime;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;

/**
 * Manages caching the Hypixel SkyBlock resource pack.
 *
 * Hybrid approach:
 * - When cache is fresh (hash matches): mixin intercepts the server pack push,
 *   avoiding reload delays when switching servers/instances.
 * - When cache is stale or missing: vanilla handles the server pack normally
 *   (full texture support), and we download a copy in the background for next time.
 */
public class ResourcePackManager {

    private static final Path CACHE_DIR = Path.of("config/IslandTime");
    private static final Path CACHED_PACK = CACHE_DIR.resolve("resource_pack.zip");
    private static final String PACK_FILE_NAME = "IslandTime_SkyBlock.zip";
    private static final String PACK_PROFILE_ID = "file/" + PACK_FILE_NAME;

    private static final ExecutorService DAEMON_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "IslandTime-ResourcePack");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .executor(DAEMON_EXECUTOR)
            .build();

    /**
     * Returns true if we have a cached pack file on disk.
     */
    public static boolean hasCachedPack() {
        return Files.exists(CACHED_PACK);
    }

    /**
     * Returns true if the cached pack matches the given url and hash,
     * meaning we can safely skip the server pack reload.
     */
    public static boolean isCacheFresh(String url, String hash) {
        if (!hasCachedPack()) return false;

        String cachedHash = ResourcePackConfig.getCachedPackHash();
        String cachedUrl = ResourcePackConfig.getCachedPackUrl();

        // If server sends a non-empty hash and we have a cached hash, compare both
        if (!cachedHash.isEmpty() && hash != null && !hash.isEmpty()) {
            return cachedHash.equalsIgnoreCase(hash) && url.equals(cachedUrl);
        }

        // Server sent empty hash (Hypixel does this) — fall back to URL-only match
        // as long as we have a cached URL from a previous successful download
        if (!cachedUrl.isEmpty() && url.equals(cachedUrl)) {
            System.out.println("[IslandTime] Server sent empty hash, using URL-only cache match");
            return true;
        }

        return false;
    }

    /**
     * Called when the server sends a new/updated pack that vanilla will handle.
     * Downloads a copy in the background so next time we can intercept.
     */
    public static void cacheServerPack(String url, String hash) {
        System.out.println("[IslandTime] Caching server resource pack in background (url=" + url + ", hash=" + hash + ")");
        downloadPackAsync(url, hash);
    }

    /**
     * Ensure the cached pack is available as a file resource pack.
     * Called on server join — adds the pack to the enabled list so it persists
     * when leaving Hypixel.
     */
    public static void applyCachedPack() {
        if (!ResourcePackConfig.isEnabled()) return;
        if (!hasCachedPack()) return;

        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;

            Path resourcePacksDir = client.getResourcePackDirectory();
            Path targetPack = resourcePacksDir.resolve(PACK_FILE_NAME);
            Files.createDirectories(resourcePacksDir);

            // Copy cache to resourcepacks/ if needed
            boolean needsCopy = !Files.exists(targetPack)
                    || Files.size(targetPack) != Files.size(CACHED_PACK);
            if (needsCopy) {
                try {
                    Files.copy(CACHED_PACK, targetPack, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[IslandTime] Copied cached pack to resourcepacks/");
                } catch (FileSystemException fse) {
                    // File locked by Minecraft — already loaded, skip
                    System.out.println("[IslandTime] Pack file locked, already present — skipping copy");
                }
            }

            // Ensure the pack is in the enabled list
            if (!client.options.resourcePacks.contains(PACK_PROFILE_ID)) {
                client.options.resourcePacks.add(PACK_PROFILE_ID);
                client.options.save();
                System.out.println("[IslandTime] Added SkyBlock resource pack to enabled packs list");
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to apply cached resource pack: " + e.getMessage());
        }
    }

    private static void downloadPackAsync(String url, String hash) {
        DAEMON_EXECUTOR.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() != 200) {
                    System.err.println("[IslandTime] Failed to download resource pack: HTTP " + response.statusCode());
                    return;
                }

                byte[] data = response.body();
                System.out.println("[IslandTime] Downloaded resource pack: " + data.length + " bytes, server hash: " + hash);

                // Verify hash if provided (warn but still save on mismatch)
                String computedHash = sha1Hex(data);
                if (hash != null && !hash.isEmpty()) {
                    if (!computedHash.equalsIgnoreCase(hash)) {
                        System.err.println("[IslandTime] Resource pack hash mismatch (saving anyway). Expected: " + hash + " Got: " + computedHash);
                    }
                }

                // Save to cache
                Files.createDirectories(CACHE_DIR);
                Files.write(CACHED_PACK, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                ResourcePackConfig.setCachedPackMeta(hash != null && !hash.isEmpty() ? hash : computedHash, url);
                System.out.println("[IslandTime] Resource pack cached successfully (" + data.length + " bytes)");

                // Also copy to resourcepacks/ for offline use
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> applyCachedPack());

            } catch (Exception e) {
                System.err.println("[IslandTime] Error downloading resource pack: " + e.getMessage());
            }
        });
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Clear cached pack data so the next server pack push triggers a fresh download.
     */
    public static void clearCache() {
        try {
            Files.deleteIfExists(CACHED_PACK);
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to delete cached pack: " + e.getMessage());
        }
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                Path targetPack = client.getResourcePackDirectory().resolve(PACK_FILE_NAME);
                Files.deleteIfExists(targetPack);
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to delete resourcepacks copy: " + e.getMessage());
        }
        ResourcePackConfig.setCachedPackMeta("", "");
        System.out.println("[IslandTime] Resource pack cache cleared");
    }

    /**
     * Reset state when feature is disabled.
     */
    public static void reset() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.options.resourcePacks.remove(PACK_PROFILE_ID);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Shut down background threads. Call on mod shutdown.
     */
    public static void shutdown() {
        DAEMON_EXECUTOR.shutdownNow();
        HTTP_CLIENT.close();
    }
}
