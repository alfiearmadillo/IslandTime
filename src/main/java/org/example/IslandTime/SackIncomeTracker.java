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
 * This format (and the fact that the detail is in the hover text of the "[Sacks]" line's
 * component siblings, not the plain chat text) was confirmed by inspecting SkyHanni's
 * {@code SackApi.onChat}, which parses the exact same message via the regex
 * {@code ([+-][\d,]+) (.+) \((.+)\)} applied to the hover text of the message's siblings whose
 * hover starts with "Added"/"Removed". This is a genuine periodic notification (fires whenever
 * sack contents change, not something we have to poll or macro for), so it satisfies the "only
 * implement if you can for sure find a working example" requirement.
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
     * hover text of its sibling components (the actual added/removed item list lives there,
     * not in the plain visible text).
     */
    public static void onSackChatMessage(Component message) {
        if (message == null) return;
        String plain = message.getString();
        if (!plain.startsWith("[Sacks]")) return;

        // Hypixel's "[Sacks]" line is built from several sibling components (e.g. one per
        // word/segment of the visible text), and more than one of those siblings can carry
        // its own copy of the "Added"/"Removed" hover tooltip — not always a byte-identical
        // copy (e.g. one sibling's hover may include extra surrounding lines/whitespace that
        // another doesn't), so deduplicating whole hover-text blocks isn't fully reliable and
        // duplicate "+145 Crude Gabagool (Slayer Sack)" entries can still slip through under
        // slightly different block text. Instead, every individual regex match (a single
        // "<delta> <item> (<source>)" entry) is deduped by its own exact matched text — the
        // same delta+item+source triple genuinely appearing twice within one single message
        // isn't a real scenario Hypixel produces (deltas for the same item are always
        // combined into one line per message), so this is safe regardless of how many
        // siblings/blocks the entry happened to be read from.
        java.util.LinkedHashSet<String> hoverBlocks = new java.util.LinkedHashSet<>();
        collectHoverText(message, hoverBlocks);
        if (hoverBlocks.isEmpty()) return;

        java.util.Set<String> seenEntries = new java.util.HashSet<>();
        double gained = 0;
        boolean removed = false;
        for (String block : hoverBlocks) {
            Matcher matcher = SACK_CHANGE_PATTERN.matcher(block);
            while (matcher.find()) {
                if (!seenEntries.add(matcher.group())) continue; // already counted this exact entry
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
        }

        if (gained > 0) {
            FuelCycleTracker.addAutoGabagoolAmount(FuelCycleTracker.getCurrentCycleDateKey(), gained);
        }
        if (removed) {
            FuelExpiredAlertTracker.onSackEmptied();
        }
    }

    /**
     * Recursively collects unique hover-text (SHOW_TEXT) blocks from this component and its
     * siblings into {@code out}. A {@link java.util.LinkedHashSet} is used (rather than a
     * plain StringBuilder) so that the same tooltip text repeated across multiple sibling
     * components — as Hypixel's "[Sacks]" message does — is only counted once.
     */
    private static void collectHoverText(Component component, java.util.Set<String> out) {
        Style style = component.getStyle();
        if (style != null) {
            HoverEvent hover = style.getHoverEvent();
            if (hover instanceof HoverEvent.ShowText showText) {
                out.add(showText.value().getString());
            }
        }
        for (Component sibling : component.getSiblings()) {
            collectHoverText(sibling, out);
        }
    }
}
