package org.example.islandtime;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks Crude Gabagool collected via a Slayer Sack using Hypixel's "[Sacks]" chat message.
 *
 * When sack pickup notifications are enabled in-game (they fire periodically while items are
 * flowing into your sacks, not just on manual /sacks visits), Hypixel sends a chat line whose
 * visible text starts with "[Sacks]" and whose hover text (shown when you mouse over the
 * message) lists every item added/removed since the last message, e.g. a hover tooltip
 * containing lines like:
 * <pre>
 *   Added
 *   +64 Crude Gabagool (Slayer Sack)
 * </pre>
 * This format was confirmed by inspecting SkyHanni's {@code SackApi.onChat} (GitHub:
 * {@code hannibal002/SkyHanni}, {@code src/main/java/at/hannibal2/skyhanni/data/SackApi.kt}).
 * Its exact approach — which this class now mirrors precisely, after an earlier
 * recursive/dedup-based implementation here still double- and quadruple-counted amounts — is:
 * <ol>
 *   <li>Only look at the "[Sacks]" message's <b>direct</b> siblings ({@code
 *       chatComponent.siblings}), never recursing into a sibling's own nested siblings. The
 *       component tree Hypixel sends nests siblings-of-siblings, and several of those deeper
 *       levels carry copies of the same hover text — recursing into them (as an earlier
 *       version of this method did) reads the same "+145 Crude Gabagool (Slayer Sack)" entry
 *       multiple times over, no matter how the resulting duplicate text is deduplicated
 *       afterwards.</li>
 *   <li>Of those direct siblings, take only the <i>first</i> one whose hover text starts with
 *       "Added" (for gains), and separately only the first one whose hover text starts with
 *       "Removed" (for losses) — {@code firstNotNullOfOrNull} in SkyHanni's Kotlin. There is
 *       always at most one genuine "Added" block and one genuine "Removed" block per message;
 *       any other sibling's hover text is either absent or an incidental duplicate/partial
 *       copy that must be ignored, not merged in.</li>
 *   <li>Concatenate those (at most) two blocks and run the regex
 *       {@code ([+-][\d,]+) (.+) \((.+)\)} over the combined text once.</li>
 * </ol>
 * This is a genuine periodic notification (fires whenever sack contents change, not something
 * we have to poll or macro for), so it satisfies the "only implement if you can for sure find
 * a working example" requirement.
 *
 * Negative deltas (gabagool taken out of the sack, e.g. to sell) never subtract from the day's
 * tracked total — they only stop the "fuel day up" Discord alert (see FuelExpiredAlertTracker),
 * since removing gabagool means the sack has been emptied.
 */
public class SackIncomeTracker {

    /** Matches a "+123 Crude Gabagool (Slayer Sack)" style entry in the sack-change hover text. */
    private static final Pattern SACK_CHANGE_PATTERN = Pattern.compile("([+-][\\d,]+) (.+?) \\((.+)\\)");

    /** Only Crude Gabagool matters for Inferno minion fuel profit tracking. */
    private static final String TRACKED_ITEM_NAME = "Crude Gabagool";

    /**
     * Called for every chat message received. Looks for a "[Sacks]" message and reads the
     * hover text of its direct sibling components (the actual added/removed item list lives
     * there, not in the plain visible text) — mirroring SkyHanni's {@code SackApi.onChat}
     * exactly (see class javadoc): only the first direct sibling whose hover starts with
     * "Added", and only the first direct sibling whose hover starts with "Removed", are read.
     */
    public static void onSackChatMessage(Component message) {
        if (!isSackChatMessage(message)) return;

        String addedText = firstSiblingHoverStartingWith(message, "Added");
        String removedText = firstSiblingHoverStartingWith(message, "Removed");
        String text = addedText + removedText;
        if (text.isEmpty()) return;

        Matcher matcher = SACK_CHANGE_PATTERN.matcher(text);
        double gained = 0;
        boolean removed = false;
        while (matcher.find()) {
            String item = matcher.group(2).trim();
            if (!item.equalsIgnoreCase(TRACKED_ITEM_NAME)) continue;
            try {
                double delta = Double.parseDouble(matcher.group(1).replace(",", ""));
                if (delta > 0) {
                    gained += delta;
                } else if (delta < 0) {
                    // Taking gabagool out of the sack (e.g. to sell) means it's been emptied —
                    // stop any active "fuel day up" alert. This intentionally does NOT subtract
                    // from the day's tracked amount: you're just withdrawing what was already
                    // counted, not losing it.
                    removed = true;
                }
            } catch (NumberFormatException ignored) {}
        }

        if (gained > 0) {
            FuelCycleTracker.addAutoGabagoolAmount(FuelCycleTracker.getCurrentCycleDateKey(), gained);
        }
        if (removed) {
            FuelExpiredAlertTracker.onSackEmptied();
        }
    }

    public static boolean isSackChatMessage(Component message) {
        return message != null && message.getString().startsWith("[Sacks]");
    }

    /**
     * Returns the hover text (SHOW_TEXT) of the first <b>direct</b> sibling of {@code message}
     * whose hover text starts with {@code prefix}, or {@code ""} if none match. Deliberately
     * does not recurse into a sibling's own nested siblings — see class javadoc for why.
     */
    private static String firstSiblingHoverStartingWith(Component message, String prefix) {
        for (Component sibling : message.getSiblings()) {
            Style style = sibling.getStyle();
            if (style == null) continue;
            HoverEvent hover = style.getHoverEvent();
            if (!(hover instanceof HoverEvent.ShowText showText)) continue;
            String hoverText = showText.value().getString();
            if (hoverText.startsWith(prefix)) return hoverText;
        }
        return "";
    }
}
