package org.example.islandtime;

import com.google.gson.JsonObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Discord webhook notifications for island alerts.
 *
 * THREAD SAFETY: Uses thread pool to prevent memory leak from unbounded thread creation.
 * Maximum 2 concurrent webhook requests to prevent resource exhaustion.
 */
public class DiscordWebhookManager {

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/webhooks.txt");
    private static final Path CONFIG_TMP  = Paths.get("config/IslandTime/webhooks.tmp");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a");

    private static volatile String webhookUrl    = null;
    private static volatile String discordUserId = null;

    private static final ExecutorService webhookExecutor = Executors.newFixedThreadPool(2, new WebhookThreadFactory());

    private static final ConcurrentLinkedQueue<String> failedMessages = new ConcurrentLinkedQueue<>();

    private static final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("IslandTime-Webhook-Retry");
        return t;
    });

    static {
        retryScheduler.scheduleWithFixedDelay(DiscordWebhookManager::retryFailedMessages, 30, 30, TimeUnit.SECONDS);
    }

    private static class WebhookThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("IslandTime-Webhook-" + threadNumber.getAndIncrement());
            t.setUncaughtExceptionHandler((thread, throwable) -> {
                System.err.println("Uncaught exception in webhook thread " + thread.getName() + ": " + throwable.getMessage());
                throwable.printStackTrace();
            });
            return t;
        }
    }

    // -------------------------------------------------------------------------
    // Config getters / setters
    // -------------------------------------------------------------------------

    public static void setWebhook(String url)      { webhookUrl = url;    save(); }
    public static String getWebhook()              { return webhookUrl; }
    public static void clearWebhook()              { webhookUrl = null;   save(); }

    public static void setDiscordUserId(String id) { discordUserId = id;  save(); }
    public static String getDiscordUserId()        { return discordUserId; }
    public static void clearDiscordUserId()        { discordUserId = null; save(); }

    // -------------------------------------------------------------------------
    // Send helpers
    // -------------------------------------------------------------------------

    public static void sendIslandAlert() {
        String url = webhookUrl;
        if (url == null || url.isEmpty()) return;

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String ping = (discordUserId != null && !discordUserId.isEmpty()) ? "<@" + discordUserId + ">\n" : "";
        String message = String.format(
                "⚠️ **Island Alert**\n%sYou've been off your island for 10+ minutes\nTime: %s",
                ping, time);
        sendWebhook(url, message);
    }

    /**
     * Sent when a minion's queued fuel-days have run out. Repeats at the configured interval
     * until stopped (see FuelExpiredAlertTracker), primarily because sack storage caps mean
     * an idle AFK setup stops gaining anything once sacks fill up.
     */
    public static void sendFuelExpiredAlert(int minionCount) {
        String url = webhookUrl;
        if (url == null || url.isEmpty()) return;

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String ping = (discordUserId != null && !discordUserId.isEmpty()) ? "<@" + discordUserId + ">\n" : "";
        String message = String.format(
                "⛽ **Fuel Alert**\n%sYour minion fuel has run out (%d minion%s)\n" +
                        "Time to refuel and/or empty sacks before selling.\nTime: %s",
                ping, minionCount, minionCount == 1 ? "" : "s", time);
        sendWebhook(url, message);
    }

    public static void sendCustomMessage(String message) {
        String url = webhookUrl;
        if (url == null || url.isEmpty()) return;
        sendWebhook(url, message);
    }

    private static void sendWebhook(String webhookUrl, String message) {
        sendWebhook(webhookUrl, message, true);
    }

    private static void sendWebhook(String webhookUrl, String message, boolean queueOnFailure) {
        webhookExecutor.submit(() -> {
            HttpURLConnection connection = null;
            boolean success = false;
            try {
                URL url = new URL(webhookUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "IslandTimeMod/1.0");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                JsonObject json = new JsonObject();
                json.addProperty("content", message);

                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode != 200 && responseCode != 204) {
                    System.err.println("Discord webhook failed with code: " + responseCode);
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) response.append(responseLine.trim());
                        System.err.println("Discord error response: " + response);
                    } catch (Exception ignored) {}
                } else {
                    success = true;
                }
            } catch (java.net.UnknownHostException | java.net.SocketException e) {
                System.err.println("Discord webhook network error (no internet?): " + e.getMessage());
            } catch (Exception e) {
                System.err.println("Failed to send Discord webhook: " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (connection != null) connection.disconnect();
                if (!success && queueOnFailure) {
                    failedMessages.offer(message);
                    System.out.println("Queued failed message for retry (" + failedMessages.size() + " in queue)");
                }
            }
        });
    }

    private static void retryFailedMessages() {
        String url = webhookUrl;
        if (url == null || url.isEmpty()) return;
        int retried = 0;
        while (retried < 5 && !failedMessages.isEmpty()) {
            String message = failedMessages.poll();
            if (message != null) { sendWebhook(url, message, true); retried++; }
        }
        if (!failedMessages.isEmpty())
            System.out.println(failedMessages.size() + " messages still in retry queue");
    }

    // -------------------------------------------------------------------------
    // Persistence — atomic write via tmp → rename
    // -------------------------------------------------------------------------

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_TMP)) {
                writer.write("WEBHOOK\n");
                writer.write((webhookUrl != null ? webhookUrl : "") + "\n");
                writer.write("DISCORD_ID\n");
                writer.write((discordUserId != null ? discordUserId : "") + "\n");
            }
            Files.move(CONFIG_TMP, CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[DiscordWebhookManager] Save failed: " + e.getMessage());
            e.printStackTrace();
            try { Files.deleteIfExists(CONFIG_TMP); } catch (IOException ignored) {}
        }
    }

    public static void load() {
        // Promote .tmp if the real file is missing (crash-recovery)
        if (!Files.exists(CONFIG_FILE) && Files.exists(CONFIG_TMP)) {
            try {
                Files.move(CONFIG_TMP, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[DiscordWebhookManager] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[DiscordWebhookManager] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(CONFIG_FILE)) return;

        try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE)) {
            String line;
            String section = "";
            while ((line = reader.readLine()) != null) {
                if      (line.equals("WEBHOOK"))    { section = "WEBHOOK";    continue; }
                else if (line.equals("DISCORD_ID")) { section = "DISCORD_ID"; continue; }

                if (section.equals("WEBHOOK") && !line.isEmpty()) {
                    webhookUrl = line; section = "";
                } else if (section.equals("DISCORD_ID") && !line.isEmpty()) {
                    discordUserId = line; section = "";
                }
            }
        } catch (IOException e) {
            System.err.println("[DiscordWebhookManager] Load failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    public static void shutdown() {
        retryScheduler.shutdown();
        webhookExecutor.shutdown();
        try {
            if (!webhookExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("[DiscordWebhookManager] Thread pool timed out, forcing shutdown");
                webhookExecutor.shutdownNow();
                if (!webhookExecutor.awaitTermination(5, TimeUnit.SECONDS))
                    System.err.println("[DiscordWebhookManager] Thread pool did not terminate after forced shutdown");
            }
            if (!retryScheduler.awaitTermination(5, TimeUnit.SECONDS))
                retryScheduler.shutdownNow();
        } catch (InterruptedException e) {
            webhookExecutor.shutdownNow();
            retryScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
