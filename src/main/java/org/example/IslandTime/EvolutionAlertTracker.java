package org.example.islandtime;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends a single Discord alert for every "EVOLUTION! Your &lt;item&gt; has evolved!" chat message
 * (e.g. "EVOLUTION! Your Rosewater Flask has evolved!"). There are no repeats: one chat message
 * produces exactly one alert.
 */
public class EvolutionAlertTracker {

    private static final Pattern FORMAT_CODES = Pattern.compile("§[0-9a-fk-orA-FK-OR]");
    // Anchored on both ends so a player chat line that merely contains the phrase (it would
    // always carry a "[RANK] Name:" prefix) can never trigger an alert.
    private static final Pattern EVOLVED = Pattern.compile("^EVOLUTION! Your (.{1,64}?) has evolved!$");

    /** The last message instance handled, so one delivery can never produce two alerts. */
    private static Component lastHandled;

    private EvolutionAlertTracker() {}

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onChatMessage(message);
        });
    }

    static void onChatMessage(Component message) {
        if (message == null || message == lastHandled) return;

        String itemName = parseEvolvedItem(message.getString());
        if (itemName == null) return;
        lastHandled = message;

        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !config.chatAlerts.evolutionEnabled) return;

        DiscordWebhookManager.sendEvolutionAlert(itemName);
    }

    /** Returns the evolved item's name, or null when the text is not an evolution message. */
    static String parseEvolvedItem(String text) {
        if (text == null) return null;
        String plain = FORMAT_CODES.matcher(text).replaceAll("").trim();
        Matcher matcher = EVOLVED.matcher(plain);
        return matcher.matches() ? matcher.group(1).trim() : null;
    }
}
