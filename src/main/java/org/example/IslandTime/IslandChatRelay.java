package org.example.islandtime;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges Hypixel Skyblock island chat to/from a companion Discord bot over localhost.
 *
 * Outbound (island chat → Discord webhook):
 *   Listens for GAME chat messages via ClientReceiveMessageEvents.GAME.
 *   When the player is on their island, messages matching "PlayerName: text" are
 *   forwarded to the configured Discord webhook, excluding the local player's own
 *   messages. [SkyBlock] system messages (visitor alerts, etc.) are also forwarded.
 *   Inbound whispers ("From PlayerName: text") are relayed with a [MSG] tag so
 *   you know to reply with /r in Discord.
 *   HTTP POST is dispatched on a dedicated daemon thread.
 *
 * Inbound (Discord → island /ac):
 *   A background thread polls http://127.0.0.1:<PORT>/poll?key=<SECRET> every 1500 ms.
 *   Non-empty responses are forwarded as "/ac <message>" commands.
 *   Messages prefixed with "/r " are sent as whisper replies instead.
 *   If the player is not on their island, the message is consumed and a failure
 *   notice is posted back to the webhook instead.
 *   Polling silently skips errors (bot not running, timeouts).
 *
 * Lifecycle:
 *   Call register() once from IslandTimeMod.onInitializeClient().
 *   Call startPolling() on ClientPlayConnectionEvents.JOIN.
 *   Call stopPolling()  on ClientPlayConnectionEvents.DISCONNECT.
 *   Call shutdown()     in the mod's shutdown hook.
 */
public class IslandChatRelay {

    // Strips Minecraft §-formatting codes before parsing
    private static final Pattern FORMAT_STRIP =
            Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    // Valid Minecraft username: 3–16 alphanumeric/underscore characters
    private static final Pattern VALID_NAME =
            Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    // Matches [SkyBlock] system announcements (visitor alerts, etc.)
    // e.g. "[SkyBlock] [MVP++] MarvellousG is visiting Your Island!"
    private static final Pattern SKYBLOCK_MSG =
            Pattern.compile("^\\[SkyBlock\\]\\s+(.+)$");

    // Filters out server/system announcements that are not real player chat.
    // Catches messages like:
    //   "Latest update: SkyBlock v0.24.5 CLICK"
    //   "[MVP+] alfiearmadillo enabled Island Visits: Guild!"
    //   "Your SkyBlock profile was loaded."
    //   "Co-op invite from ..."
    private static final Pattern SYSTEM_MSG = Pattern.compile(
            "^(" +
            "Latest update:"                     + "|" + // patch note announcements
            "Your SkyBlock"                      + "|" + // profile/settings messages
            "Co-op invite"                       + "|" + // co-op system messages
            "\\[\\S+\\]\\s+\\S+\\s+(enabled|disabled|toggled|set|joined|left|" +
                "loaded|activated|deactivated|updated|changed|turned|" +
                "unlocked|purchased|sold|used|applied|opened|closed|" +
                "accepted|declined|invited|kicked|promoted|demoted)" +
            ")",
            Pattern.CASE_INSENSITIVE
    );

    // Single-thread executor for outbound webhook POSTs
    private static final ExecutorService webhookExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IslandTime-ChatRelay-Webhook");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
                System.err.println("[IslandChatRelay] Uncaught exception in webhook thread: " + ex.getMessage()));
        return t;
    });

    // Single-thread scheduler for inbound polling
    private static final ScheduledExecutorService pollExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "IslandTime-ChatRelay-Poller");
                t.setDaemon(true);
                return t;
            });

    private static volatile ScheduledFuture<?> pollTask = null;
    private static final AtomicBoolean registered = new AtomicBoolean(false);

    // -------------------------------------------------------------------------
    // Registration — call once from IslandTimeMod
    // -------------------------------------------------------------------------

    public static void register() {
        if (!registered.compareAndSet(false, true)) return;

        // Listen for incoming game chat messages (not action bar)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;                                  // ignore action bar
            if (!IslandTimeTracker.isCurrentlyOnIsland()) return; // only relay on island
            processOutbound(message);
        });
    }

    // -------------------------------------------------------------------------
    // Outbound: island chat → Discord webhook
    // -------------------------------------------------------------------------

    private static void processOutbound(Text message) {
        String raw = FORMAT_STRIP.matcher(message.getString()).replaceAll("").trim();

        // ── [SkyBlock] system messages (visitor alerts, etc.) ─────────────────
        // e.g. "[SkyBlock] [MVP++] MarvellousG is visiting Your Island!"
        Matcher sbMatcher = SKYBLOCK_MSG.matcher(raw);
        if (sbMatcher.matches()) {
            String webhookUrl = ChatRelayConfig.getChatWebhookUrl();
            if (webhookUrl != null && !webhookUrl.isEmpty()) {
                final String finalUrl     = webhookUrl;
                final String finalContent = sbMatcher.group(1).trim();
                webhookExecutor.submit(() ->
                        postToWebhook(finalUrl, "SkyBlock", finalContent));
            }
            return; // don't fall through to player-chat parsing
        }
        // ─────────────────────────────────────────────────────────────────────

        // ── Server/system announcements ───────────────────────────────────────
        // Drop messages that look like server announcements rather than player
        // chat, e.g. "Latest update: SkyBlock v0.24.5 CLICK" or
        // "[MVP+] alfiearmadillo enabled Island Visits: Guild!"
        if (SYSTEM_MSG.matcher(raw).find()) return;
        // ─────────────────────────────────────────────────────────────────────

        // Strategy: find the first ": " separator.
        // Everything before it is the prefix (level + decorators + rank + username);
        // everything after is the message content.
        // The username is the last whitespace-delimited token of the prefix.
        //
        // This handles all real Hypixel formats, e.g.:
        //   [25] Jaxwrr: text
        //   [104] [MVP+] utso: text
        //   [222] ⛂ [MVP+] DarkSoulsTwo: text
        //   [525] Σ [✌] [MVP+] alfiearmadillo: text

        int colonIdx = raw.indexOf(": ");
        if (colonIdx <= 0) return; // no separator — not a player chat message

        String prefix  = raw.substring(0, colonIdx);  // e.g. "[MVP+] alfiearmadillo"
        String content = raw.substring(colonIdx + 2); // everything after ": "

        if (content.isEmpty()) return;

        // ── Whisper handling ──────────────────────────────────────────────────
        // Outbound whispers ("To PlayerName: text") — skip entirely, not useful
        if (prefix.startsWith("To ")) return;

        // Inbound whispers ("From [RANK] PlayerName: text") — relay to Discord
        // with a [MSG] tag so you know to reply with /r
        if (prefix.startsWith("From ")) {
            int lastSpace = prefix.lastIndexOf(' ');
            String whisperSender = lastSpace >= 0 ? prefix.substring(lastSpace + 1) : prefix;
            if (!VALID_NAME.matcher(whisperSender).matches()) return;

            String webhookUrl = ChatRelayConfig.getChatWebhookUrl();
            if (webhookUrl == null || webhookUrl.isEmpty()) return;

            final String finalUrl     = webhookUrl;
            final String finalSender  = whisperSender;
            final String finalContent = "[MSG] " + content;
            webhookExecutor.submit(() -> postToWebhook(finalUrl, finalSender, finalContent));
            return;
        }
        // ─────────────────────────────────────────────────────────────────────

        // The username is the last token of the prefix
        int lastSpace   = prefix.lastIndexOf(' ');
        String senderName = lastSpace >= 0 ? prefix.substring(lastSpace + 1) : prefix;

        if (!VALID_NAME.matcher(senderName).matches()) return; // not a username — skip

        // Don't relay the local player's own messages back to Discord
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            String localName = mc.player.getName().getString();
            if (localName.equalsIgnoreCase(senderName)) return;
        }

        String webhookUrl = ChatRelayConfig.getChatWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) return; // feature disabled

        // Capture finals for lambda
        final String finalUrl     = webhookUrl;
        final String finalSender  = senderName;
        final String finalContent = content;

        webhookExecutor.submit(() -> postToWebhook(finalUrl, finalSender, finalContent));
    }

    /**
     * HTTP POST to the Discord webhook with the chat relay username/content payload.
     * Runs on the webhook executor thread — never on the game thread.
     */
    private static void postToWebhook(String webhookUrl, String username, String content) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(webhookUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "IslandTimeMod/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            // Build payload manually — keeps zero new dependencies
            String json = "{\"username\":" + escapeJson(username)
                        + ",\"content\":"  + escapeJson(content) + "}";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200 && code != 204) {
                System.err.println("[IslandChatRelay] Webhook returned HTTP " + code
                        + " for sender=" + username);
            }
        } catch (java.net.UnknownHostException | java.net.SocketException e) {
            System.err.println("[IslandChatRelay] Webhook network error (no internet?): " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[IslandChatRelay] Webhook error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Inbound: poll localhost → /ac command
    // -------------------------------------------------------------------------

    /** Start the polling loop. Call from ClientPlayConnectionEvents.JOIN. */
    public static void startPolling() {
        if (pollTask != null && !pollTask.isDone()) return; // already running
        pollTask = pollExecutor.scheduleWithFixedDelay(
                IslandChatRelay::doPoll,
                0L, 1500L, TimeUnit.MILLISECONDS);
        System.out.println("[IslandChatRelay] Polling started on port " + ChatRelayConfig.getPollPort());
    }

    /** Stop the polling loop. Call from ClientPlayConnectionEvents.DISCONNECT. */
    public static void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
            System.out.println("[IslandChatRelay] Polling stopped.");
        }
    }

    private static void doPoll() {
        int    port   = ChatRelayConfig.getPollPort();
        String secret = ChatRelayConfig.getPollSecret();

        HttpURLConnection conn = null;
        try {
            // Use 127.0.0.1 explicitly — "localhost" can resolve to ::1 (IPv6)
            // on Windows, which won't reach a bot bound to 127.0.0.1 (IPv4).
            URL url = new URL("http://127.0.0.1:" + port + "/poll?key=" + secret);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            int code = conn.getResponseCode();
            if (code != 200) return; // bot returned an error status — skip silently

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                String body = sb.toString().trim();
                if (!body.isEmpty()) {
                    if (!IslandTimeTracker.isCurrentlyOnIsland()) {
                        // Not on island — consume the message but report back to Discord
                        String webhookUrl = ChatRelayConfig.getChatWebhookUrl();
                        if (webhookUrl != null && !webhookUrl.isEmpty()) {
                            final String failMsg = "Not on island, failed to send message: " + body;
                            webhookExecutor.submit(() -> postToWebhook(webhookUrl, "IslandTime", failMsg));
                        }
                    } else {
                        dispatchAcCommand(body);
                    }
                }
            }
        } catch (java.net.ConnectException e) {
            // Bot is not running — silently skip, this is the normal idle state
        } catch (java.net.SocketTimeoutException e) {
            // Bot is slow / unreachable — skip without spamming the log
        } catch (Exception e) {
            System.err.println("[IslandChatRelay] Poll error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Enqueue a chat command on the game thread.
     * Messages prefixed with "/r " are sent as whisper replies ("r <text>").
     * All other messages are sent to island chat ("ac <text>").
     * sendChatCommand() sends a command WITHOUT a leading slash.
     */
    private static void dispatchAcCommand(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        final String command;
        if (message.startsWith("/r ")) {
            command = "r " + message.substring(3); // strip leading "/r "
        } else {
            command = "ac " + message;
        }

        mc.execute(() -> mc.getNetworkHandler().sendChatCommand(command));
    }

    // -------------------------------------------------------------------------
    // Shutdown — call from the mod's shutdown hook
    // -------------------------------------------------------------------------

    public static void shutdown() {
        stopPolling();
        webhookExecutor.shutdown();
        pollExecutor.shutdown();
        try {
            if (!webhookExecutor.awaitTermination(5, TimeUnit.SECONDS))
                webhookExecutor.shutdownNow();
            if (!pollExecutor.awaitTermination(3, TimeUnit.SECONDS))
                pollExecutor.shutdownNow();
        } catch (InterruptedException e) {
            webhookExecutor.shutdownNow();
            pollExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Util — minimal JSON string escaping, zero new dependencies
    // -------------------------------------------------------------------------

    private static String escapeJson(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
