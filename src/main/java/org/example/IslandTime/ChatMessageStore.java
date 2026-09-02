package org.example.islandtime;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores recent chat messages captured via Fabric API event for display in PlayerListScreen.
 */
public class ChatMessageStore {
    private static final int MAX_MESSAGES = 100;
    private static final List<Component> messages = new ArrayList<>();

    public static void register() {
        // Use the same GAME event that IslandChatRelay uses (proven to work in MC 26.1)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                addMessage(message);
            }
        });
    }

    private static void addMessage(Component message) {
        // Copy the component to preserve formatting (some messages use mutable/lazy components)
        Component copy = message.copy();
        messages.add(0, copy);
        if (messages.size() > MAX_MESSAGES) {
            messages.remove(messages.size() - 1);
        }
        // Sack-income tracking is handled by SackChatPacketMixin directly on the network
        // packet (not here), so it keeps working even if another mod (e.g. SkyHanni's
        // "Sack Change Hider") cancels this message before it reaches the GAME event.
    }

    public static List<Component> getMessages() {
        return messages;
    }
}
