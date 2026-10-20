package org.example.islandtime;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

/**
 * Watches for the Enchanted Clock's "TIME WARP! You have successfully warped time for your
 * Minions!" chat message and tells {@link FuelCycleTracker} that every minion's timeline
 * just moved forward, so the fuel-day times (and everything built on them: alerts, the
 * fuel-day list, expiries) stay accurate.
 */
public class TimeWarpTracker {

    /** How far one use of the Enchanted Clock moves minion time forward. */
    static final long WARP_MS = 60 * 60 * 1000L;

    private static final Pattern FORMAT_CODES = Pattern.compile("§[0-9a-fk-orA-FK-OR]");
    // Anchored on both ends so a player chat line that merely quotes the phrase (it would
    // always carry a "[RANK] Name:" prefix) can never move the fuel-days.
    private static final Pattern TIME_WARP = Pattern.compile(
            "^TIME WARP! You have successfully warped time for your Minions!$");

    /** A real use of the clock has a 48h cooldown, so two within this window are one message delivered twice. */
    private static final long DUPLICATE_WINDOW_MS = 5_000L;

    /** The last message instance handled, so one delivery can never warp twice. */
    private static Component lastHandled;
    private static long lastHandledAtMs = 0;

    private TimeWarpTracker() {}

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onChatMessage(message);
        });
    }

    static void onChatMessage(Component message) {
        if (message == null || message == lastHandled) return;
        if (!isTimeWarpMessage(message.getString())) return;
        lastHandled = message;

        long now = System.currentTimeMillis();
        if (now - lastHandledAtMs < DUPLICATE_WINDOW_MS) return;
        lastHandledAtMs = now;

        int minionsMoved = FuelCycleTracker.onTimeWarp(WARP_MS);

        Minecraft client = Minecraft.getInstance();
        if (client.player != null && MinionFuelTracker.isShowFuelMessages()) {
            client.player.sendSystemMessage(Component.empty()
                    .append(Component.literal("Time warp: ").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("fuel-days moved up 1 hour").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(minionsMoved > 0
                            ? " (" + minionsMoved + (minionsMoved == 1 ? " minion)" : " minions)")
                            : "").withStyle(ChatFormatting.GRAY)));
        }
    }

    /** True when {@code text} is exactly the Enchanted Clock's time-warp message. */
    static boolean isTimeWarpMessage(String text) {
        if (text == null) return false;
        String plain = FORMAT_CODES.matcher(text).replaceAll("").trim();
        return TIME_WARP.matcher(plain).matches();
    }
}
