package org.example.islandtime;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores recent chat messages for display in {@link PlayerListScreen}'s chat mirror.
 *
 * <p>Ordinary messages are captured from Fabric API's {@code GAME} event so they follow the same
 * single delivery path as the vanilla chat HUD. Sack messages are captured by
 * {@code SackChatPacketMixin} instead, before mods such as SkyHanni can cancel them, so they remain
 * available for diagnostics without affecting sack-income tracking.
 */
public class ChatMessageStore {
    private static final int MAX_MESSAGES = 100;
    private static final List<Component> messages = new ArrayList<>();

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && !SackIncomeTracker.isSackChatMessage(message)) {
                addRawMessage(message);
            }
        });
    }

    /**
     * Called from the chat event for ordinary messages and from {@code SackChatPacketMixin} for
     * sack messages.
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
