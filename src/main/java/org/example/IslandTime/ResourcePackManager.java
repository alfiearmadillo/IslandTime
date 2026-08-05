package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.server.ServerResourcePackManager;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages downloading, caching, and loading the Hypixel SkyBlock resource pack.
 * The cached pack is stored at config/IslandTime/resource_pack.zip.
 *
 * When the feature is enabled:
 * - Intercepts Hypixel's resource pack send packet
 * - If hash matches cache, silently accepts without re-downloading
 * - If hash differs, downloads the new pack in the background and caches it
 * - On every server join, re-applies the cached pack so it stays active everywhere
 */
public class ResourcePackManager {

    private static final Path CACHE_DIR = Path.of("config/IslandTime");
    private static final Path CACHED_PACK = CACHE_DIR.resolve("resource_pack.zip");
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("islandtime-skyblock-pack".getBytes());

    /**
     * Returns true if we have a cached pack file on disk.
     */
    public static boolean hasCachedPack() {
        return Files.exists(CACHED_PACK);
    }

    /**
     * Apply the cached resource pack to the client.
     * Uses Minecraft's server resource pack manager to load from our cached file.
     */
    public static void applyCachedPack() {
        if (!ResourcePackConfig.isEnabled()) return;
        if (!hasCachedPack()) return;

        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;

            ServerResourcePackManager packManager = client.getServerResourcePackManager();
            Path absolutePath = CACHED_PACK.toAbsolutePath();
            // Push our cached pack via the server resource pack manager's URL-based API
            // Using file:// URI so it loads from disk without a network request
            packManager.addPack(PACK_ID, absolutePath.toUri().toURL(), ResourcePackConfig.getCachedPackHash());
            System.out.println("[IslandTime] Applied cached SkyBlock resource pack");
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to apply cached resource pack: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle an incoming server resource pack packet from Hypixel.
     * Returns true if we handled it (caller should cancel vanilla handling).
     */
    public static boolean handleServerPack(String url, String hash, UUID packId) {
        if (!ResourcePackConfig.isEnabled()) return false;

        String cachedHash = ResourcePackConfig.getCachedPackHash();

        // If hash matches cache, no download needed
        if (!cachedHash.isEmpty() && cachedHash.equalsIgnoreCase(hash) && hasCachedPack()) {
            System.out.println("[IslandTime] Server resource pack hash matches cache, skipping download");
            return true;
        }

        // Hash differs or no cache — download in background
        System.out.println("[IslandTime] Downloading updated server resource pack...");
        downloadPackAsync(url, hash);
        return true;
    }

    private static void downloadPackAsync(String url, String hash) {
        CompletableFuture.runAsync(() -> {
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

                // Verify hash if provided
                if (hash != null && !hash.isEmpty()) {
                    String computedHash = sha1Hex(data);
                    if (!computedHash.equalsIgnoreCase(hash)) {
                        System.err.println("[IslandTime] Resource pack hash mismatch! Expected: " + hash + " Got: " + computedHash);
                        return;
                    }
                }

                // Save to cache
                Files.createDirectories(CACHE_DIR);
                Files.write(CACHED_PACK, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                // Update stored hash
                ResourcePackConfig.setCachedPackHash(hash != null ? hash : "");

                System.out.println("[IslandTime] Resource pack cached successfully (" + data.length + " bytes)");

                // Apply the new pack on the render thread
                MinecraftClient.getInstance().execute(ResourcePackManager::applyCachedPack);

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
     * Reset state when feature is disabled.
     */
    public static void reset() {
        // Nothing to actively unload — the pack will be gone next time the client restarts
        // or the server sends a new pack list.
    }
}
