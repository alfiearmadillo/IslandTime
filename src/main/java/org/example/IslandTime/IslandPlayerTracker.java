package org.example.islandtime;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tracks players on the island by monitoring the client's player list.
 *
 * When a player is first seen we extract and store their rank bracket Text
 * (with full colours including custom plus colours) directly from the tab-list
 * display name. That stored Text is then used when they leave, both for the
 * local chat message and the Discord webhook notification.
 */
public class IslandPlayerTracker {

    private static final long CHECK_INTERVAL_MS = 1_000;
    private static final long INITIAL_DELAY_MS  = 5_000;

    private static final Pattern VALID_USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    /**
     * Sentinel value stored in playerRankComponents for players with no rank.
     * ConcurrentHashMap does not permit null values, so we use this instead.
     */
    private static final Text EMPTY_RANK = Text.empty();

    /**
     * Keyed by plain username.
     * Value is the rank bracket Text component (e.g. "[MVP+]" with full colours),
     * or null for rankless players. Populated when the player first appears in the
     * tab list, so it's available even after their entry is gone.
     */
    private static final Map<String, Text> playerRankComponents = new ConcurrentHashMap<>();

    /** Plain username set — used for departure detection. */
    private static final Set<String> currentPlayers = ConcurrentHashMap.newKeySet();

    private static boolean initialized     = false;
    private static long    lastCheckTime   = 0;
    private static long    islandEntryTime = 0;

    private static boolean showLeaveMessages = true;

    public static boolean isShowLeaveMessages()           { return showLeaveMessages; }
    public static void    setShowLeaveMessages(boolean v) { showLeaveMessages = v;    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public static void tick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_INTERVAL_MS) return;
        lastCheckTime = now;

        if (client.player == null || client.getNetworkHandler() == null) { reset(); return; }
        if (!IslandTimeTracker.isCurrentlyOnIsland())                    { reset(); return; }

        Collection<PlayerListEntry> playerList = client.getNetworkHandler().getPlayerList();
        String localPlayerName = client.player.getName().getString();

        // Build fresh name set and cache any new players' rank components
        Set<String> newNames = new HashSet<>();
        for (PlayerListEntry entry : playerList) {
            com.mojang.authlib.GameProfile profile = entry.getProfile();
            if (profile == null || profile.name() == null) continue;
            if (profile.name().equals(localPlayerName))    continue;
            if (isMinionOrNPC(profile))                    continue;

            String name = profile.name();
            newNames.add(name);

            // Store rank component the first time we see this player
            if (!playerRankComponents.containsKey(name)) {
                Text rank = extractRankComponent(entry.getDisplayName());
                playerRankComponents.put(name, rank != null ? rank : EMPTY_RANK);
            }
        }

        if (!initialized) {
            currentPlayers.clear();
            currentPlayers.addAll(newNames);
            initialized     = true;
            islandEntryTime = now;
            return;
        }

        // Grace period — update silently
        if (now - islandEntryTime < INITIAL_DELAY_MS) {
            currentPlayers.clear();
            currentPlayers.addAll(newNames);
            return;
        }

        // Detect departures
        Set<String> left = new HashSet<>(currentPlayers);
        left.removeAll(newNames);
        for (String name : left) {
            notifyPlayerLeft(client, name);
        }

        currentPlayers.clear();
        currentPlayers.addAll(newNames);
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    private static void reset() {
        currentPlayers.clear();
        playerRankComponents.clear();
        initialized     = false;
        islandEntryTime = 0;
    }

    // -------------------------------------------------------------------------
    // NPC / minion filter
    // -------------------------------------------------------------------------

    private static boolean isMinionOrNPC(com.mojang.authlib.GameProfile profile) {
        if (profile == null || profile.name() == null || profile.name().isEmpty()) return true;
        if (!VALID_USERNAME_PATTERN.matcher(profile.name()).matches()) return true;
        if (profile.id() != null) {
            int version = profile.id().version();
            if (version != 4) {
                System.out.println("[IslandPlayerTracker] Filtered non-player UUID (version "
                        + version + "): " + profile.name() + " (" + profile.id() + ")");
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private static void notifyPlayerLeft(MinecraftClient client, String playerName) {
        Text rankComponent = playerRankComponents.get(playerName);
        // Treat the EMPTY_RANK sentinel (rankless player) the same as absent
        if (rankComponent == EMPTY_RANK) rankComponent = null;

        // --- Discord webhook ---
        String webhookUrl = ChatRelayConfig.getChatWebhookUrl();
        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            // Build a plain-text version of the rank for the webhook
            String rankPrefix = rankComponent != null ? rankComponent.getString() + " " : "";
            IslandChatRelay.sendWebhookAsync(webhookUrl, "IslandTime",
                    rankPrefix + playerName + " left the island");
        }

        // --- Local chat message ---
        if (!showLeaveMessages || client.player == null) return;

        // [IslandTime] prefix in aqua
        MutableText msg = Text.empty()
                .append(Text.literal("[IslandTime] ").formatted(Formatting.AQUA));

        // Rank bracket with full original colours (including custom plus colour)
        if (rankComponent != null) {
            msg.append(rankComponent).append(Text.literal(" "));
        }

        // PlayerName left Your Island.
        msg.append(Text.literal(playerName + " ").formatted(Formatting.WHITE))
           .append(Text.literal("left ").formatted(Formatting.YELLOW))
           .append(Text.literal("Your Island.").formatted(Formatting.GREEN));

        client.player.sendMessage(msg, false);

        // Clean up stored rank now that the player has left
        playerRankComponents.remove(playerName);
    }

    // -------------------------------------------------------------------------
    // Tab-list parsing
    // -------------------------------------------------------------------------

    /**
     * Extracts the rank bracket component (e.g. "[VIP+]", "[MVP++]") from a
     * SkyBlock tab-list display name Text, preserving all original colours.
     *
     * SkyBlock builds display names as structured Text trees. The rank bracket
     * is always a sibling component whose full string contains "[" and "]".
     * Reading getString() on each sibling covers nested children too, so a
     * bracket like "[MVP§c++§6]" (where ++ is a differently coloured child)
     * is matched correctly and returned with all its nested formatting intact.
     *
     * Returns null for rankless players.
     */
    private static Text extractRankComponent(Text displayName) {
        if (displayName == null) return null;
        for (Text sibling : displayName.getSiblings()) {
            String plain = sibling.getString();
            if (plain.contains("[") && plain.contains("]")) {
                return sibling;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Debug
    // -------------------------------------------------------------------------

    public static Set<String> getCurrentPlayers() {
        return new HashSet<>(currentPlayers);
    }
}
