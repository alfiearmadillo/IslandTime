package org.example.islandtime;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores recent chat messages captured via mixin for display in PlayerListScreen.
 */
public class ChatMessageStore {
    private static final int MAX_MESSAGES = 100;
    private static final List<Component> messages = new ArrayList<>();

    public static void addMessage(Component message) {
        messages.add(0, message);
        if (messages.size() > MAX_MESSAGES) {
            messages.remove(messages.size() - 1);
        }
    }

    public static List<Component> getMessages() {
        return messages;
    }
}
