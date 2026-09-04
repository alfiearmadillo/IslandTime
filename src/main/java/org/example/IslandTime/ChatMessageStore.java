package org.example.islandtime;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores recent chat messages for display in {@link PlayerListScreen}'s chat mirror.
 *
 * <p>Fed directly from {@code SackChatPacketMixin}'s packet-level hook on {@code
 * handleSystemChat} (the same hook that feeds {@link SackIncomeTracker}), not from Fabric API's
 * cancelable {@code ClientReceiveMessageEvents.GAME} event. This matters because other
 * client-side mods (e.g. SkyHanni's "Sack Change Hider") can cancel a system message before the
 * {@code GAME} event fires, hiding it from the vanilla chat HUD — if this store only listened to
 * {@code GAME}, the very messages a user most needs to inspect via the mirror's shift+right-click
 * component dump (like a hidden "[Sacks]" line) would never appear in it at all.
 */
public class ChatMessageStore {
    private static final int MAX_MESSAGES = 100;
    private static final List<Component> messages = new ArrayList<>();

    /** No-op kept for compatibility; capture now happens implicitly via SackChatPacketMixin. */
    public static void register() {
    }

    /**
     * Called from {@code SackChatPacketMixin} for every system chat packet received, before any
     * other mod has a chance to cancel or hide it.
     */
    public static void addRawMessage(Component message) {
        if (message == null) return;
        // Copy the component to preserve formatting (some messages use mutable/lazy components)
        Component copy = message.copy();
        messages.add(0, copy);
        if (messages.size() > MAX_MESSAGES) {
            messages.remove(messages.size() - 1);
        }
    }

    public static List<Component> getMessages() {
        return messages;
    }
}
