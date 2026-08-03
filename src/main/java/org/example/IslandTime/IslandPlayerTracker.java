package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tracks players on the island by monitoring the client's player list.
 *
 * When a player is first seen we extract and store their SkyBlock level bracket
 * Text (e.g. "[360]" with its original colour) directly from the tab-list
 * display name. That stored Text is then used when they leave, both for the
 * local chat message and the Discord webhook notification.
 *
 * The level component is retried on every tick until a non-empty display name
 * is available, to handle the case where Hypixel sends the decorated name
 * asynchronously after the player entry first appears.
 */
public class IslandPlayerTracker {

    private static final long CHECK_INTERVAL_MS = 1_000;
    private static final long INITIAL_DELAY_MS  = 5_000;

    private static final Pattern VALID_USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    /**
     * Sentinel value stored in playerLevelComponents for players whose display
     * name has not loaded yet, or who have no level bracket.
     * ConcurrentHashMap does not permit null values, so we use this instead.
     */
    private static final Component EMPTY_LEVEL = Component.empty();

    /**
     * Keyed by plain username.
     * Value is the SkyBlock level bracket Text component (e.g. "[360]" with its
     * original colour), or EMPTY_LEVEL if not yet loaded / not present.
     * Retried on each tick while the player is still on the island, so a late-
     * arriving display name is picked up without missing the departure message.
     */
    private static final Map<String, Component> playerLevelComponents = new ConcurrentHashMap<>();

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

    public static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_INTERVAL_MS) return;
        lastCheckTime = now;

        if (client.player == null || client.getConnection() == null) { reset(); return; }
        if (!IslandTimeTracker.isCurrentlyOnIsland())                    { reset(); return; }

        Collection<PlayerInfo> playerList = client.getConnection().getOnlinePlayers();
        String localPlayerName = client.player.getName().getString();

        // Build fresh name set and cache/retry each player's level component
        Set<String> newNames = new HashSet<>();
        for (PlayerInfo entry : playerList) {
            com.mojang.authlib.GameProfile profile = entry.getProfile();
            if (profile == null || profile.name() == null) continue;
            if (profile.name().equals(localPlayerName))    continue;
            if (isMinionOrNPC(profile))                    continue;

            String name = profile.name();
            newNames.add(name);

            // Retry level lookup on every tick until we get a real value.
            // Hypixel sends decorated display names asynchronously, so the first
            // tick may see an empty/plain name — we keep trying until it loads.
            if (!playerLevelComponents.containsKey(name)
                    || playerLevelComponents.get(name) == EMPTY_LEVEL) {
                Component level = extractLevelComponent(entry.getTabListDisplayName());
                playerLevelComponents.put(name, level != null ? level : EMPTY_LEVEL);
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
        playerLevelComponents.clear();
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

    private static void notifyPlayerLeft(Minecraft client, String playerName) {
        Component levelComponent = playerLevelComponents.get(playerName);
        // Treat the EMPTY_LEVEL sentinel as absent
        if (levelComponent == EMPTY_LEVEL) levelComponent = null;

        // --- Discord webhook ---
        String webhookUrl = ChatRelayConfig.getChatWebhookUrl();
        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            String levelPrefix = levelComponent != null ? levelComponent.getString() + " " : "";
            IslandChatRelay.sendWebhookAsync(webhookUrl, "IslandTime",
                    levelPrefix + playerName + " left the island");
        }

        // --- Local chat message ---
        if (!showLeaveMessages || client.player == null) return;

        // [IslandTime] prefix in aqua
        MutableComponent msg = Component.empty()
                .append(Component.literal("[IslandTime] ").withStyle(ChatFormatting.AQUA));

        // SkyBlock level bracket with original colour (e.g. "[360]" in grey)
        if (levelComponent != null) {
            msg.append(levelComponent).append(Component.literal(" "));
        }

        // PlayerName left Your Island.
        msg.append(Component.literal(playerName + " ").withStyle(ChatFormatting.WHITE))
           .append(Component.literal("left ").withStyle(ChatFormatting.YELLOW))
           .append(Component.literal("Your Island.").withStyle(ChatFormatting.GREEN));

        client.player.displayClientMessage(msg, false);

        // Clean up stored level now that the player has left
        playerLevelComponents.remove(playerName);
    }

    // -------------------------------------------------------------------------
    // Tab-list parsing
    // -------------------------------------------------------------------------

    /**
     * Extracts the SkyBlock level bracket component (e.g. "[360]") from a
     * tab-list display name Text, preserving its original colour.
     *
     * SkyBlock builds display names as structured Text trees. The level bracket
     * is the first sibling component whose full string contains "[" and "]".
     * Returns null if the display name hasn't loaded yet or has no bracket.
     */
    private static Component extractLevelComponent(Component displayName) {
        if (displayName == null) return null;
        for (Component sibling : displayName.getSiblings()) {
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
