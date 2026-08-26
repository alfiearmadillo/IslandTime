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
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Manages downloading, caching, and loading the Hypixel SkyBlock resource pack.
 * The cached pack is stored at config/IslandTime/resource_pack.zip and copied to
 * the resourcepacks folder as "IslandTime_SkyBlock.zip" so Minecraft can load it.
 *
 * When the feature is enabled:
 * - Intercepts Hypixel's resource pack send packet
 * - If hash matches cache, silently accepts without re-downloading
 * - If hash differs, downloads the new pack in the background and caches it
 * - On every server join, ensures the pack is enabled so it stays active everywhere
 */
public class ResourcePackManager {

    private static final Path CACHE_DIR = Path.of("config/IslandTime");
    private static final Path CACHED_PACK = CACHE_DIR.resolve("resource_pack.zip");
    private static final String PACK_FILE_NAME = "IslandTime_SkyBlock.zip";
    private static final String PACK_PROFILE_ID = "file/" + PACK_FILE_NAME;

    // Use a daemon-thread executor so it won't prevent JVM shutdown
    private static final ExecutorService DAEMON_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "IslandTime-ResourcePack");
        t.setDaemon(true);
        return t;
    });

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "IslandTime-ResourcePack-Scheduler");
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
     * Apply the cached resource pack to the client.
     * Copies the cached pack into the resourcepacks folder and ensures it's in the
     * enabled packs list. Does NOT force a resource reload — the pack will be loaded
     * on the next natural reload (game start, or after a new download).
     */
    public static void applyCachedPack() {
        if (!ResourcePackConfig.isEnabled()) return;
        if (!hasCachedPack()) return;

        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;

            // Copy cached pack to resourcepacks directory
            Path resourcePacksDir = client.getResourcePackDirectory();
            Path targetPack = resourcePacksDir.resolve(PACK_FILE_NAME);
            Files.createDirectories(resourcePacksDir);

            // Only copy if target doesn't exist or has a different size (avoids file-lock issues
            // when Minecraft already has the pack loaded)
            boolean needsCopy = !Files.exists(targetPack)
                    || Files.size(targetPack) != Files.size(CACHED_PACK);
            if (needsCopy) {
                try {
                    Files.copy(CACHED_PACK, targetPack, StandardCopyOption.REPLACE_EXISTING);
                } catch (FileSystemException fse) {
                    // File is locked by Minecraft (already loaded as a resource pack).
                    // Write to a temp file and atomically move over it.
                    Path tmp = targetPack.resolveSibling(PACK_FILE_NAME + ".tmp");
                    Files.copy(CACHED_PACK, tmp, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(tmp, targetPack, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception moveEx) {
                        // Atomic move not supported or still locked — the existing file is likely
                        // already the correct pack from a prior session, so just log and continue.
                        Files.deleteIfExists(tmp);
                        System.out.println("[IslandTime] Pack file locked, but already present — continuing with existing file");
                    }
                }
            }

            // Ensure the pack is in the enabled list (it will load on next natural reload)
            if (!client.options.resourcePacks.contains(PACK_PROFILE_ID)) {
                client.options.resourcePacks.add(PACK_PROFILE_ID);
                client.options.save();
                System.out.println("[IslandTime] Added SkyBlock resource pack to enabled packs list");
            }
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
        String cachedUrl = ResourcePackConfig.getCachedPackUrl();

        // Only use cache if: hash is non-empty, matches cached hash, URL matches, and file exists
        if (!cachedHash.isEmpty() && !hash.isEmpty()
                && cachedHash.equalsIgnoreCase(hash)
                && url.equals(cachedUrl)
                && hasCachedPack()) {
            System.out.println("[IslandTime] Server resource pack hash matches cache, skipping download");
            return true;
        }

        // Hash differs, URL changed, or no cache — download in background
        System.out.println("[IslandTime] Downloading updated server resource pack (url=" + url + ", hash=" + hash + ", cachedHash=" + cachedHash + ", cachedUrl=" + cachedUrl + ")");
        downloadPackAsync(url, hash);
        return true;
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

                // Store the server-provided hash (for comparing against future server packets)
                // and also store the computed hash for logging purposes
                ResourcePackConfig.setCachedPackMeta(hash != null && !hash.isEmpty() ? hash : computedHash, url);

                System.out.println("[IslandTime] Resource pack cached successfully (" + data.length + " bytes)");

                // Apply the pack (copy to resourcepacks dir, add to options)
                // then trigger a resource reload after a delay to avoid conflicts
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> {
                    applyCachedPack();
                    // Delay the reload using our daemon scheduler
                    SCHEDULER.schedule(() -> client.execute(() -> {
                        try {
                            client.reloadResourcePacks();
                            System.out.println("[IslandTime] Resource pack reload triggered");
                        } catch (Exception e) {
                            System.err.println("[IslandTime] Failed to reload: " + e.getMessage());
                        }
                    }), 2, TimeUnit.SECONDS);
                });

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
        // Also delete the copy in the resourcepacks folder so it's truly fresh
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
        // Remove from enabled packs on next resource reload
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
        SCHEDULER.shutdownNow();
        HTTP_CLIENT.close();
    }
}
