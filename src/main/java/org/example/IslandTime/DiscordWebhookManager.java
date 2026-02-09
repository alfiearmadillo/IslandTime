package org.example.islandtime;

import com.google.gson.JsonObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Discord webhook notifications for island alerts
 *
 * THREAD SAFETY: Uses thread pool to prevent memory leak from unbounded thread creation
 * Maximum 2 concurrent webhook requests to prevent resource exhaustion
 */
public class DiscordWebhookManager {

    private static final String CONFIG_FILE = "config/islandtime_webhooks.txt";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a");

    // Thread-safe webhook URL storage
    private static volatile String webhookUrl = null;

    // Thread-safe Discord user ID storage
    private static volatile String discordUserId = null;

    // Thread pool for webhook requests - prevents memory leak
    private static final ExecutorService webhookExecutor = Executors.newFixedThreadPool(
            2,  // Max 2 concurrent webhooks (prevents overwhelming Discord API and limits resource usage)
            new ThreadFactory()
    );

    // Custom thread factory for better debugging and daemon threads
    private static class ThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);  // Don't prevent JVM shutdown
            t.setName("IslandTime-Webhook-" + threadNumber.getAndIncrement());
            t.setUncaughtExceptionHandler((thread, throwable) -> {
                System.err.println("Uncaught exception in webhook thread " + thread.getName() + ": " + throwable.getMessage());
                throwable.printStackTrace();
            });
            return t;
        }
    }

    /**
     * Set the webhook URL
     */
    public static void setWebhook(String url) {
        webhookUrl = url;
        save();
    }

    /**
     * Get the webhook URL
     */
    public static String getWebhook() {
        return webhookUrl;
    }

    /**
     * Clear the webhook
     */
    public static void clearWebhook() {
        webhookUrl = null;
        save();
    }

    /**
     * Set the Discord user ID to ping
     */
    public static void setDiscordUserId(String userId) {
        discordUserId = userId;
        save();
    }

    /**
     * Get the Discord user ID
     */
    public static String getDiscordUserId() {
        return discordUserId;
    }

    /**
     * Clear the Discord user ID
     */
    public static void clearDiscordUserId() {
        discordUserId = null;
        save();
    }

    /**
     * Send island alert (left island for 10+ minutes)
     */
    public static void sendIslandAlert() {
        String url = webhookUrl; // Read once to avoid race condition
        if (url == null || url.isEmpty()) {
            return;
        }

        String time = LocalDateTime.now().format(TIME_FORMATTER);
        String ping = (discordUserId != null && !discordUserId.isEmpty())
                ? "<@" + discordUserId + ">\n"
                : "";

        String message = String.format(
                "⚠️ **Island Alert**\n" +
                        "%s" +
                        "You've been off your island for 10+ minutes\n" +
                        "Time: %s",
                ping,
                time
        );

        sendWebhook(url, message);
    }

    /**
     * Send a custom message to the webhook
     * Used by ItemTracker for custom alerts
     */
    public static void sendCustomMessage(String message) {
        String url = webhookUrl; // Read once to avoid race condition
        if (url == null || url.isEmpty()) {
            return;
        }

        sendWebhook(url, message);
    }

    /**
     * Send a webhook notification using thread pool
     */
    private static void sendWebhook(String webhookUrl, String message) {
        // Submit to thread pool instead of creating new thread
        webhookExecutor.submit(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(webhookUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", "IslandTimeMod/1.0");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000); // 5 second timeout
                connection.setReadTimeout(5000);

                // Create JSON payload
                JsonObject json = new JsonObject();
                json.addProperty("content", message);

                // Send request
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                // Check response
                int responseCode = connection.getResponseCode();
                if (responseCode != 200 && responseCode != 204) {
                    System.err.println("Discord webhook failed with code: " + responseCode);

                    // Try to read error response
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                        System.err.println("Discord error response: " + response);
                    } catch (Exception e) {
                        // Ignore error reading error response
                    }
                }

            } catch (Exception e) {
                System.err.println("Failed to send Discord webhook: " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    /**
     * Shutdown the webhook thread pool gracefully
     * Should be called when mod is unloading
     */
    public static void shutdown() {
        webhookExecutor.shutdown();
        try {
            // Wait up to 10 seconds for pending webhooks to complete
            if (!webhookExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("Webhook thread pool did not terminate in time, forcing shutdown");
                webhookExecutor.shutdownNow();

                // Wait a bit more for forced shutdown
                if (!webhookExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("Webhook thread pool did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            System.err.println("Webhook shutdown interrupted, forcing shutdown");
            webhookExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Save webhook URLs to config
     */
    public static void save() {
        try {
            Path configFile = Paths.get(CONFIG_FILE);
            Files.createDirectories(configFile.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(configFile)) {
                writer.write("WEBHOOK\n");
                writer.write((webhookUrl != null ? webhookUrl : "") + "\n");
                writer.write("DISCORD_ID\n");
                writer.write((discordUserId != null ? discordUserId : "") + "\n");
            }
        } catch (IOException e) {
            System.err.println("Failed to save webhook config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load webhook URLs from config
     */
    public static void load() {
        Path configFile = Paths.get(CONFIG_FILE);
        if (!Files.exists(configFile)) {
            return;
        }

        try {
            try (BufferedReader reader = Files.newBufferedReader(configFile)) {
                String line;
                String section = "";

                while ((line = reader.readLine()) != null) {
                    if (line.equals("WEBHOOK")) {
                        section = "WEBHOOK";
                        continue;
                    } else if (line.equals("DISCORD_ID")) {
                        section = "DISCORD_ID";
                        continue;
                    }

                    if (section.equals("WEBHOOK") && !line.isEmpty()) {
                        webhookUrl = line;
                        section = "";
                    } else if (section.equals("DISCORD_ID") && !line.isEmpty()) {
                        discordUserId = line;
                        section = "";
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load webhook config: " + e.getMessage());
            e.printStackTrace();
        }
    }
}