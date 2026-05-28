package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tracks players on the island by monitoring the client's player list.
 * This is entirely client-side - no packets are sent to the server.
 *
 * When a player leaves the island, a notification is sent to the Discord webhook
 * via the chat relay system.
 */
public class IslandPlayerTracker {

    /** How often to check for player changes (1 second). */
    private static final long CHECK_INTERVAL_MS = 1_000;

    /** Delay before starting to track player departures after entering island (5 seconds). */
    private static final long INITIAL_DELAY_MS = 5_000;

    /** Pattern to match minion/NPC names (e.g., !C-a, !A-b, !D-f) to exclude them from tracking. */
    private static final Pattern MINION_PATTERN = Pattern.compile("^![A-Z]-[a-z]+$");

    /** Pattern to match Minecraft color/formatting codes (§ followed by any character). */
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§.");

    /** Pattern to match bot-like names (10 lowercase alphanumeric characters, like "2su5ddf0n8"). */
    private static final Pattern BOT_NAME_PATTERN = Pattern.compile("^[a-z0-9]{10}$");

    // Set of player names currently on the island (excluding the local player)
    private static final Set<String> currentPlayers = ConcurrentHashMap.newKeySet();

    // Track whether we're initialized to avoid false notifications on first check
    private static boolean initialized = false;

    // Track last check time for performance optimization
    private static long lastCheckTime = 0;

    // Track when we entered the island
    private static long islandEntryTime = 0;

    /**
     * Call this from the client tick when on the island.
     * Compares the current player list with the previous tick to detect
     * players who have left.
     *
     * Performance optimized: Only checks once per second instead of every tick.
     */
    public static void tick(MinecraftClient client) {
        // Only check once per second for performance
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_INTERVAL_MS) return;
        lastCheckTime = now;

        if (client.player == null || client.getNetworkHandler() == null) {
            reset();
            return;
        }

        if (!IslandTimeTracker.isCurrentlyOnIsland()) {
            reset();
            return;
        }

        // Get current player list from the client
        Collection<PlayerListEntry> playerList = client.getNetworkHandler().getPlayerList();
        Set<String> newPlayers = new HashSet<>();
        
        String localPlayerName = client.player.getName().getString();
        
        // Build set of current player names (excluding local player and minions/NPCs)
        for (PlayerListEntry entry : playerList) {
            com.mojang.authlib.GameProfile profile = entry.getProfile();
            if (profile != null && profile.name() != null
                && !profile.name().equals(localPlayerName)
                && !isMinionOrNPC(profile)) {
                newPlayers.add(profile.name());
            }
        }

        // If this is the first check, just store the current state
        if (!initialized) {
            currentPlayers.clear();
            currentPlayers.addAll(newPlayers);
            initialized = true;
            islandEntryTime = System.currentTimeMillis(); // Record when we entered
            return;
        }

        // Don't send notifications for the first 5 seconds after entering the island
        // This prevents false notifications from NPCs or initial player list changes
        long timeSinceEntry = System.currentTimeMillis() - islandEntryTime;
        if (timeSinceEntry < INITIAL_DELAY_MS) {
            // Still within the grace period - update the player list silently
            currentPlayers.clear();
            currentPlayers.addAll(newPlayers);
            return;
        }

        // Find players who have left (were in currentPlayers but not in newPlayers)
        Set<String> playersWhoLeft = new HashSet<>(currentPlayers);
        playersWhoLeft.removeAll(newPlayers);

        // Notify about players who left
        for (String playerName : playersWhoLeft) {
            notifyPlayerLeft(playerName);
        }

        // Update the current player set
        currentPlayers.clear();
        currentPlayers.addAll(newPlayers);
    }

    /**
     * Reset the tracker when leaving the island or disconnecting.
     */
    private static void reset() {
        currentPlayers.clear();
        initialized = false;
        islandEntryTime = 0;
    }

    /**
     * Check if a player profile matches the pattern for minions/NPCs/bots.
     * Uses multiple heuristics:
     * 1. Minion pattern: !C-a, !A-b, !D-f (exclamation mark, capital letter, dash, lowercase letter(s))
     * 2. Color codes: Names with § character (real players cannot have these)
     * 3. Bot name pattern: 10-character lowercase alphanumeric strings (e.g., "2su5ddf0n8")
     * 4. UUID validation: Check if UUID looks like a real Mojang player UUID (version 4)
     *
     * @param profile The player's GameProfile to check
     * @return true if the profile appears to be a minion/NPC/bot, false if it's likely a real player
     */
    private static boolean isMinionOrNPC(com.mojang.authlib.GameProfile profile) {
        if (profile == null || profile.name() == null || profile.name().isEmpty()) {
            return true;
        }

        String playerName = profile.name();

        // Check for minion pattern (!C-a, !A-b, etc.)
        if (MINION_PATTERN.matcher(playerName).matches()) {
            System.out.println("[IslandPlayerTracker] Filtered minion: " + playerName);
            return true;
        }

        // Check for Minecraft color/formatting codes (§) - real players cannot have these in their names
        // NPCs and server-generated entities often have colored names
        if (COLOR_CODE_PATTERN.matcher(playerName).find()) {
            System.out.println("[IslandPlayerTracker] Filtered NPC with color code: " + playerName);
            return true;
        }

        // Check for bot-like name pattern (10 lowercase alphanumeric characters)
        // Real player names typically have capitals, underscores, or recognizable words
        if (BOT_NAME_PATTERN.matcher(playerName).matches()) {
            System.out.println("[IslandPlayerTracker] Filtered bot-like name: " + playerName);
            return true;
        }

        // Check UUID validity - real Mojang player UUIDs are version 4 (random) UUIDs
        // NPCs/bots may have version 2 UUIDs or non-standard patterns
        if (profile.getId() != null) {
            java.util.UUID uuid = profile.getId();
            // Version 4 UUIDs have the format: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
            // where the 13th character (version) is '4' and 17th character (variant) is 8, 9, a, or b
            int version = uuid.version();
            if (version != 4) {
                // Real players have version 4 UUIDs, bots/NPCs often have version 2 or other versions
                System.out.println("[IslandPlayerTracker] Filtered non-player UUID (version " + version + "): " + playerName + " (" + uuid + ")");
                return true;
            }
        }

        return false;
    }

    /**
     * Send a notification to the Discord webhook that a player has left.
     */
    private static void notifyPlayerLeft(String playerName) {
        String webhookUrl = ChatRelayConfig.getChatWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String message = playerName + " left the island";

        // Use the same webhook posting mechanism as the chat relay
        // This is called from the game thread, so we'll need to make it async
        IslandChatRelay.sendWebhookAsync(webhookUrl, "IslandTime", message);
    }

    /**
     * Get the current set of players on the island (for debugging/display).
     */
    public static Set<String> getCurrentPlayers() {
        return new HashSet<>(currentPlayers);
    }
}
