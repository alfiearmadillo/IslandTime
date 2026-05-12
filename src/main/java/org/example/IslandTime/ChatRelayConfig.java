package org.example.islandtime;

import java.io.*;
import java.nio.file.*;

/**
 * Persists configuration for the island chat relay feature:
 *   - chatWebhookUrl : Discord webhook that receives island chat messages
 *   - pollPort       : port of the companion Python bot's HTTP server
 *   - pollSecret     : shared secret sent as ?key= on every poll request
 *
 * Written atomically (tmp → rename) to match the rest of the mod's save pattern.
 * If the file is missing, sensible defaults are used and the file is created so
 * the user knows what to edit.
 */
public class ChatRelayConfig {

    private static final Path CONFIG_FILE = Paths.get("config/IslandTime/chatrelay.txt");
    private static final Path CONFIG_TMP  = Paths.get("config/IslandTime/chatrelay.tmp");

    private static volatile String chatWebhookUrl = "";
    private static volatile int    pollPort       = 8765;
    private static volatile String pollSecret     = "changeme";

    // -------------------------------------------------------------------------
    // Getters / setters (setters persist immediately)
    // -------------------------------------------------------------------------

    public static String getChatWebhookUrl()            { return chatWebhookUrl; }
    public static int    getPollPort()                  { return pollPort;       }
    public static String getPollSecret()                { return pollSecret;     }

    public static void setChatWebhookUrl(String url)    { chatWebhookUrl = (url != null ? url : ""); save(); }
    public static void setPollPort(int port)            { pollPort = port;       save(); }
    public static void setPollSecret(String secret)     { pollSecret = (secret != null ? secret : ""); save(); }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    public static void load() {
        // Crash-recovery: promote .tmp if the real file is gone
        if (!Files.exists(CONFIG_FILE) && Files.exists(CONFIG_TMP)) {
            try {
                Files.move(CONFIG_TMP, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[ChatRelayConfig] Recovered from .tmp after crash.");
            } catch (IOException e) {
                System.err.println("[ChatRelayConfig] Could not promote .tmp: " + e.getMessage());
            }
        }

        if (!Files.exists(CONFIG_FILE)) {
            // Write defaults so the user has a file to edit
            System.out.println("[ChatRelayConfig] No config found — writing defaults to " + CONFIG_FILE);
            save();
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("chatWebhookUrl=")) {
                    chatWebhookUrl = line.substring("chatWebhookUrl=".length());
                } else if (line.startsWith("pollPort=")) {
                    try {
                        pollPort = Integer.parseInt(line.substring("pollPort=".length()).trim());
                    } catch (NumberFormatException e) {
                        System.err.println("[ChatRelayConfig] Invalid pollPort value, using default 8765");
                    }
                } else if (line.startsWith("pollSecret=")) {
                    pollSecret = line.substring("pollSecret=".length());
                }
            }
        } catch (IOException e) {
            System.err.println("[ChatRelayConfig] Load failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // Save (atomic)
    // -------------------------------------------------------------------------

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_TMP)) {
                writer.write("# Island Chat Relay configuration");
                writer.newLine();
                writer.write("# chatWebhookUrl : Discord webhook that receives island chat (leave blank to disable outbound)");
                writer.newLine();
                writer.write("# pollPort       : must match the companion Python bot's port");
                writer.newLine();
                writer.write("# pollSecret     : must match the companion Python bot's secret key");
                writer.newLine();
                writer.newLine();
                writer.write("chatWebhookUrl=" + chatWebhookUrl);
                writer.newLine();
                writer.write("pollPort=" + pollPort);
                writer.newLine();
                writer.write("pollSecret=" + pollSecret);
                writer.newLine();
            }
            Files.move(CONFIG_TMP, CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[ChatRelayConfig] Save failed: " + e.getMessage());
            e.printStackTrace();
            try { Files.deleteIfExists(CONFIG_TMP); } catch (IOException ignored) {}
        }
    }
}
