package org.example.islandtime;

/**
 * Placeholder hook for tracking sack income (e.g. Crude Gabagool collected via a Slayer
 * Sack) from Hypixel's periodic sack chat messages while fuel is active.
 *
 * Hypixel does send a chat message when items enter your sacks (of the form
 * "&lt;amount&gt; &lt;item&gt; were added to your &lt;sack&gt; sacks!"), but this is only sent
 * per-pickup while sack messages are enabled in-game — it is NOT the same as a periodic
 * "every minute or so" summary message. No verified example of such a periodic summary
 * message could be found (SkyHanni's GetFromSackApi only handles an unrelated
 * "no items in sack" error message and NPC "grab from sacks" click prompts), so this
 * class is intentionally a disabled no-op for now.
 *
 * Once a confirmed, working chat message format for sack income is found, implement the
 * regex matching here and call into {@link FuelCycleTracker#setGabagoolAmount} (or a new
 * accumulating setter) to automatically track Crude Gabagool collected per day instead of
 * relying on the manual daily input.
 */
public class SackIncomeTracker {

    /** Kept disabled until a verified chat message format is confirmed. */
    private static final boolean ENABLED = false;

    /**
     * Called for every chat line received. Currently a no-op — see class docs.
     *
     * @param line the plain (unformatted) chat message text
     */
    public static void onSackChatLine(String line) {
        if (!ENABLED || line == null) return;
        // Intentionally unimplemented — no verified periodic sack-summary message format.
    }
}
