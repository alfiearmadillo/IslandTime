package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    // Set of player names currently on the island (excluding the local player)
    private static final Set<String> currentPlayers = ConcurrentHashMap.newKeySet();

    // Track whether we're initialized to avoid false notifications on first check
    private static boolean initialized = false;

    // Track last check time for performance optimization
    private static long lastCheckTime = 0;

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
        
        // Build set of current player names (excluding local player)
        for (PlayerListEntry entry : playerList) {
            String name = entry.getProfile().getName();
            if (name != null && !name.equals(localPlayerName)) {
                newPlayers.add(name);
            }
        }

        // If this is the first check, just store the current state
        if (!initialized) {
            currentPlayers.clear();
            currentPlayers.addAll(newPlayers);
            initialized = true;
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
