package org.example.islandtime;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Announces Critter Safari progress for you, so you don't have to type "Forest done" in party chat
 * every time you clear a biome.
 *
 * <p>Announcements go to party chat as {@code /pchat <message>}, or, when that is turned off, to your
 * own chat only as a local {@code [IslandTime]} message that nobody else can see.
 *
 * <p>A message is sent the moment a biome's critters are all caught, and the biome that finishes the
 * run reports "All done" instead of its own name. The Macaw frequently fails to spawn in the Forest,
 * so an option lets it be skipped; when it is, the wording says so ("Forest done minus Macaw").
 */
public class CritterSafariAnnouncer {

    private CritterSafariAnnouncer() {}

    /**
     * Sends any announcements that became due from the chat line just processed.
     *
     * <p>The tracker is always polled, even when announcements are turned off, so that turning the
     * option on mid-run does not dump every biome completed so far into chat at once.
     */
    public static void pollAndSend() {
        IslandTimeConfig config = ConfigManager.getConfig();
        boolean macawOptional = config != null && config.critterSafari.announceMinusMacaw;

        List<String> announcements = CritterSafariTracker.pollAnnouncements(macawOptional);
        if (announcements.isEmpty()) return;
        if (config == null || !config.critterSafari.announceCompletion) return;

        boolean partyChat = config.critterSafari.announceToParty;
        for (String announcement : announcements) {
            send(announcement, partyChat);
        }
    }

    private static void send(String announcement, boolean partyChat) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;

        client.execute(() -> {
            if (client.player == null) return;
            if (partyChat && client.player.connection != null) {
                // sendCommand() takes the command without its leading slash, so this is "/pchat <msg>".
                client.player.connection.sendCommand("pchat " + announcement);
            } else {
                client.player.sendSystemMessage(Component.literal("[IslandTime] ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(announcement).withStyle(ChatFormatting.GREEN)));
            }
        });
    }
}
