package org.example.islandtime;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.regex.Pattern;

/**
 * Tracks the New Year's Celebration chat announcement and sends Discord reminders while its cake
 * remains claimable.
 */
public class NewYearCakeTracker {

    private static final long EVENT_DURATION_MS = 60 * 60 * 1000L;
    private static final long LAST_CHANCE_LEAD_MS = 5 * 60 * 1000L;
    private static final long REGULAR_REMINDER_CUTOFF_MS = LAST_CHANCE_LEAD_MS + 1_000L;
    private static final long MIN_REMAINING_MS = 60 * 1000L;
    private static final Pattern FORMAT_CODES = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    private static long eventStartTime = -1L;
    private static long lastRegularReminderElapsed = 0L;
    private static boolean finalReminderSent = false;

    private NewYearCakeTracker() {}

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onChatMessage(message.getString());
        });
    }

    public static void onChatMessage(String message) {
        String raw = FORMAT_CODES.matcher(message == null ? "" : message).replaceAll("").trim();
        long now = System.currentTimeMillis();

        if (raw.contains("You claimed a New Year Cake!")) {
            if (isEventActive(now)) eventStartTime = -1L;
            return;
        }

        if (!raw.contains("Event: New Year's Celebration!")) return;
        if (isEventActive(now)) return;

        eventStartTime = now;
        lastRegularReminderElapsed = 0L;
        finalReminderSent = false;
        if (getReminderSpeed() != ReminderSpeed.OFF) {
            sendNotification(
                    "🍰 **New Year Cake Available!**",
                    "The New Year's Celebration has started. A special cake is available for 1 hour.");
        }
    }

    public static void tick() {
        long start = eventStartTime;
        if (start < 0L) return;

        long elapsed = System.currentTimeMillis() - start;
        long remaining = EVENT_DURATION_MS - elapsed;
        if (remaining <= 0L) {
            eventStartTime = -1L;
            return;
        }

        ReminderSpeed speed = getReminderSpeed();
        if (speed == ReminderSpeed.OFF) return;

        // Never send a late reminder if the client was paused or the game was suspended.
        if (remaining < MIN_REMAINING_MS) return;

        // Check the final reminder before regular reminders. In FAST mode, both are due on
        // the same five-minute cadence, but only the final reminder may be sent in this window.
        if (!finalReminderSent && remaining <= LAST_CHANCE_LEAD_MS) {
            sendNotification(
                    "⚠️ **New Year Cake Last Chance!**",
                    "The New Year Cake expires in about 5 minutes and has not been claimed yet.");
            finalReminderSent = true;
            return;
        }

        // The extra second keeps a regular five-minute reminder from being queued immediately
        // before the final reminder when a client tick lands on the boundary.
        if (remaining <= REGULAR_REMINDER_CUTOFF_MS) return;
        if (elapsed - lastRegularReminderElapsed < speed.intervalMs) return;

        long minutesRemaining = (remaining + 59_999L) / 60_000L;
        sendNotification(
                "🍰 **New Year Cake Reminder**",
                "The New Year Cake has about " + minutesRemaining + " minutes left to claim.");
        lastRegularReminderElapsed = elapsed;
    }

    private static boolean isEventActive(long now) {
        return eventStartTime >= 0L && now - eventStartTime < EVENT_DURATION_MS;
    }

    private static ReminderSpeed getReminderSpeed() {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null || !config.newYearCakes.enabled) return ReminderSpeed.OFF;

        return switch (config.newYearCakes.reminderSpeed) {
            case 1 -> ReminderSpeed.FAST;
            case 2 -> ReminderSpeed.MEDIUM;
            case 3 -> ReminderSpeed.SLOW;
            default -> ReminderSpeed.OFF;
        };
    }

    private static void sendNotification(String title, String body) {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null) return;

        String webhookUrl = config.newYearCakes.webhookUrl == null
                ? ""
                : config.newYearCakes.webhookUrl.trim();
        if (webhookUrl.isEmpty()) return;

        String userId = DiscordWebhookManager.getDiscordUserId();
        String ping = userId != null && !userId.isBlank() ? "<@" + userId + ">\n" : "";
        DiscordWebhookManager.sendCustomMessage(webhookUrl, title + "\n" + ping + body);
    }

    private enum ReminderSpeed {
        OFF(0L),
        FAST(5 * 60 * 1000L),
        MEDIUM(15 * 60 * 1000L),
        SLOW(30 * 60 * 1000L);

        private final long intervalMs;

        ReminderSpeed(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }
}
