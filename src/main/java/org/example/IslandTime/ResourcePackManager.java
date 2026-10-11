package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Manages caching the Hypixel SkyBlock resource pack.
 *
 * Hybrid approach:
 * - When the cache is fresh (url/hash match) AND the cached pack is actually applied (present in
 *   resourcepacks/ and selected): mixin intercepts the server pack push, avoiding reload delays
 *   when switching servers/instances.
 * - When the cache is stale or missing: vanilla handles the server pack normally
 *   (full texture support), and we download a copy in the background for next time.
 * - When the cache is fresh but its copy was never applied (e.g. an earlier update could not be
 *   copied): vanilla handles the server pack and we apply the cached copy.
 *
 * The copy in resourcepacks/ is named after the SHA-1 of its contents. Minecraft keeps the
 * zip it loaded open (and on Windows that makes it impossible to overwrite or delete), so a
 * changed pack must never be written over the previous file: it gets a new name instead, and
 * the enabled-pack lists are switched over to it. Otherwise the old version would silently
 * come back as soon as the server pack went away (leaving SkyBlock) or on the next restart.
 */
public class ResourcePackManager {

    private static final Path CACHE_DIR = Path.of("config/IslandTime");
    private static final Path CACHED_PACK = CACHE_DIR.resolve("resource_pack.zip");
    private static final Path CACHED_PACK_TMP = CACHE_DIR.resolve("resource_pack.zip.tmp");
    private static final String PROFILE_PREFIX = "file/";
    private static final String PACK_FILE_PREFIX = "IslandTime_SkyBlock";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final int HASH_NAME_LENGTH = 12;

    // The legacy fixed name (IslandTime_SkyBlock.zip) and the current hash-named copies
    // (IslandTime_SkyBlock_<12 hex chars>.zip). Everything matching is ours to swap/clean up.
    private static final Pattern MANAGED_PACK_FILE =
            Pattern.compile(PACK_FILE_PREFIX + "(?:_[0-9a-f]{" + HASH_NAME_LENGTH + "})?\\.zip");

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
     * meaning the cache holds the pack the server is offering.
     * (Hypixel's pack urls embed a per-deploy id, so a new pack always means a new url.)
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
     * Returns true if the cached pack is really what the game would load without the server
     * pack: its copy is in resourcepacks/ and is selected right now. A fresh cache alone is not
     * enough to skip the server pack — if the copy never made it into place (or the game still
     * has an older copy selected) cancelling the push would leave the player on an old pack.
     */
    public static boolean isCachedPackActive() {
        if (!hasCachedPack()) return false;

        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return false;

            String fileName = currentPackFileName();
            if (fileName == null) return false;

            Path pack = client.getResourcePackDirectory().resolve(fileName);
            if (!Files.exists(pack) || Files.size(pack) != Files.size(CACHED_PACK)) return false;

            PackRepository repository = client.getResourcePackRepository();
            return repository != null && repository.getSelectedIds().contains(PROFILE_PREFIX + fileName);
        } catch (Exception e) {
            return false;
        }
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
     * Ensure the cached pack is available as a file resource pack and is the one that is
     * enabled — both in the saved options (next launch) and in the live pack repository (the
     * next resource reload, e.g. when the server pack goes away on leaving Hypixel).
     * Called on server join and after every pack download, so the pack persists outside Hypixel.
     */
    public static void applyCachedPack() {
        if (!ResourcePackConfig.isEnabled()) return;
        if (!hasCachedPack()) return;

        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;

            String fileName = currentPackFileName();
            if (fileName == null) return;
            String profileId = PROFILE_PREFIX + fileName;

            Path resourcePacksDir = client.getResourcePackDirectory();
            Files.createDirectories(resourcePacksDir);
            Path targetPack = resourcePacksDir.resolve(fileName);

            // The name comes from the pack's contents, so a changed pack always lands in a new
            // file and never has to replace one that Minecraft has open.
            if (!Files.exists(targetPack) || Files.size(targetPack) != Files.size(CACHED_PACK)) {
                Path tempPack = resourcePacksDir.resolve(fileName + TEMP_SUFFIX);
                Files.copy(CACHED_PACK, tempPack, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tempPack, targetPack, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[IslandTime] Copied cached pack to resourcepacks/" + fileName);
            }

            // Make this copy the enabled one in the saved options (replacing older copies)
            if (swapManagedPackId(client.options.resourcePacks, profileId)) {
                client.options.save();
                System.out.println("[IslandTime] Enabled SkyBlock resource pack " + profileId);
            }

            selectInLiveRepository(client, profileId);
            removeOutdatedCopies(resourcePacksDir, fileName);
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to apply cached resource pack: " + e.getMessage());
        }
    }

    /**
     * Switches the pack repository's current selection over to the given copy, so that the
     * next resource reload uses it instead of an older copy that is still selected.
     */
    private static void selectInLiveRepository(Minecraft client, String profileId) {
        PackRepository repository = client.getResourcePackRepository();
        if (repository == null) return;

        Collection<String> selected = repository.getSelectedIds();
        boolean upToDate = selected.contains(profileId)
                && selected.stream().noneMatch(id -> isManagedProfileId(id) && !id.equals(profileId));
        if (upToDate) return;

        // Rescan resourcepacks/ so the copy written just now is known to the repository
        repository.reload();
        if (!repository.isAvailable(profileId)) return;

        // Only user-orderable packs are listed: the server pack has a fixed position at the top
        // and is put back there by the repository.
        List<String> ids = new ArrayList<>();
        for (Pack pack : repository.getSelectedPacks()) {
            if (!pack.isFixedPosition()) ids.add(pack.getId());
        }
        swapManagedPackId(ids, profileId);
        repository.setSelected(ids);
    }

    /**
     * Deletes older copies of the pack (and abandoned temp files). The copy Minecraft still has
     * open can't be deleted on Windows; it is retried on the next call, once it has been
     * released by a resource reload or a restart.
     */
    private static void removeOutdatedCopies(Path resourcePacksDir, String keepFileName) {
        try (Stream<Path> files = Files.list(resourcePacksDir)) {
            files.filter(path -> {
                        String name = path.getFileName().toString();
                        if (name.equals(keepFileName)) return false;
                        if (name.endsWith(TEMP_SUFFIX)) {
                            name = name.substring(0, name.length() - TEMP_SUFFIX.length());
                        }
                        return isManagedPackFile(name);
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            System.out.println("[IslandTime] Removed outdated resource pack copy " + path.getFileName());
                        } catch (IOException e) {
                            System.out.println("[IslandTime] Outdated pack copy " + path.getFileName()
                                    + " is still in use, will retry later");
                        }
                    });
        } catch (IOException e) {
            System.err.println("[IslandTime] Failed to clean up old resource pack copies: " + e.getMessage());
        }
    }

    /**
     * Makes {@code profileId} the only enabled copy of the pack in {@code ids}: older copies are
     * removed and the pack takes the place of the first one removed (or goes on top when none
     * was enabled). Returns true if the list changed.
     */
    static boolean swapManagedPackId(List<String> ids, String profileId) {
        boolean changed = false;
        int insertAt = -1;
        for (int i = 0; i < ids.size(); ) {
            String id = ids.get(i);
            if (isManagedProfileId(id) && !id.equals(profileId)) {
                ids.remove(i);
                if (insertAt < 0) insertAt = i;
                changed = true;
            } else {
                i++;
            }
        }
        if (!ids.contains(profileId)) {
            ids.add(insertAt >= 0 ? insertAt : ids.size(), profileId);
            changed = true;
        }
        return changed;
    }

    static boolean isManagedPackFile(String fileName) {
        return fileName != null && MANAGED_PACK_FILE.matcher(fileName).matches();
    }

    static boolean isManagedProfileId(String profileId) {
        return profileId != null
                && profileId.startsWith(PROFILE_PREFIX)
                && isManagedPackFile(profileId.substring(PROFILE_PREFIX.length()));
    }

    static String packFileNameForSha1(String sha1) {
        if (sha1 == null || sha1.length() < HASH_NAME_LENGTH) return null;
        String fileName = PACK_FILE_PREFIX + "_" + sha1.substring(0, HASH_NAME_LENGTH).toLowerCase() + ".zip";
        return isManagedPackFile(fileName) ? fileName : null;
    }

    /**
     * The resourcepacks/ file name for the pack currently in the cache, or null if it can't be
     * determined.
     */
    private static String currentPackFileName() throws IOException {
        String sha1 = ResourcePackConfig.getCachedPackSha1();
        if (sha1.isEmpty()) {
            // Cache written before the hash was recorded
            sha1 = sha1Hex(Files.readAllBytes(CACHED_PACK));
            if (sha1.isEmpty()) return null;
            ResourcePackConfig.setCachedPackSha1(sha1);
        }
        return packFileNameForSha1(sha1);
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

                // A zip starts with "PK" — don't cache an error page that came back as a 200
                if (data.length < 4 || data[0] != 'P' || data[1] != 'K') {
                    System.err.println("[IslandTime] Downloaded resource pack is not a zip file, not caching it");
                    return;
                }

                // Verify hash if provided (warn but still save on mismatch)
                String computedHash = sha1Hex(data);
                if (hash != null && !hash.isEmpty()) {
                    if (!computedHash.equalsIgnoreCase(hash)) {
                        System.err.println("[IslandTime] Resource pack hash mismatch (saving anyway). Expected: " + hash + " Got: " + computedHash);
                    }
                }

                // Save to cache (via a temp file so a half-written pack is never picked up)
                Files.createDirectories(CACHE_DIR);
                Files.write(CACHED_PACK_TMP, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(CACHED_PACK_TMP, CACHED_PACK, StandardCopyOption.REPLACE_EXISTING);
                ResourcePackConfig.setCachedPackMeta(hash != null && !hash.isEmpty() ? hash : computedHash, url, computedHash);
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
                // Copies still open in this session can't be deleted on Windows; they are
                // replaced (and removed later) once a new pack has been downloaded and applied.
                removeOutdatedCopies(client.getResourcePackDirectory(), null);
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to delete resourcepacks copy: " + e.getMessage());
        }
        ResourcePackConfig.setCachedPackMeta("", "", "");
        System.out.println("[IslandTime] Resource pack cache cleared");
    }

    /**
     * Reset state when feature is disabled.
     */
    public static void reset() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.options.resourcePacks.removeIf(ResourcePackManager::isManagedProfileId);
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
