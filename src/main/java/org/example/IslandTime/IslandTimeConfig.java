package org.example.islandtime;

import io.github.notenoughupdates.moulconfig.Config;
import io.github.notenoughupdates.moulconfig.annotations.*;
import com.google.gson.annotations.Expose;

/**
 * Main configuration class for IslandTime mod using MoulConfig.
 * All settings are accessible through a GUI opened via /islandtime.
 */
public class IslandTimeConfig extends Config {

    @Expose
    @Category(name = "Tracking", desc = "Enable or disable tracking features")
    public Tracking tracking = new Tracking();

    @Expose
    @Category(name = "Webhooks & Alerts", desc = "Discord webhook and alert settings")
    public WebhooksAndAlerts webhooksAndAlerts = new WebhooksAndAlerts();

    @Expose
    @Category(name = "Alert Speeds", desc = "Configure alert timing for different events")
    public AlertSpeeds alertSpeeds = new AlertSpeeds();

    @Expose
    @Category(name = "Item Tracking", desc = "Track specific items (use /trackitem set command)")
    public ItemTracking itemTracking = new ItemTracking();

    @Expose
    @Category(name = "Chat Relay", desc = "Island chat relay configuration")
    public ChatRelay chatRelay = new ChatRelay();

    public static class Tracking {
        @Expose
        @ConfigOption(name = "Island Time Tracking", desc = "Track time spent on your private island")
        @ConfigEditorBoolean
        public boolean islandTimeEnabled = true;

        @Expose
        @ConfigOption(name = "Fuel Cycle Tracking", desc = "Track fuel cycles for your minions")
        @ConfigEditorBoolean
        public boolean fuelCycleEnabled = true;

        @Expose
        @ConfigOption(name = "Favourite GUI Tracking", desc = "Enable container snapshots for /favouritegui save")
        @ConfigEditorBoolean
        public boolean favouriteGuiEnabled = true;
    }

    public static class WebhooksAndAlerts {
        @Expose
        @ConfigOption(name = "Discord Webhook URL", desc = "Webhook URL for Discord alerts (leave empty to disable). Must start with: https://discord.com/api/webhooks/")
        @ConfigEditorText
        public String webhookUrl = "";

        @Expose
        @ConfigOption(name = "Discord User ID", desc = "Your Discord user ID for webhook pings (leave empty to disable pings). Must be numeric.")
        @ConfigEditorText
        public String discordUserId = "";
    }

    public static class AlertSpeeds {
        @Expose
        @ConfigOption(name = "Tracked Item Alert Speed", desc = "How often to alert when tracked item stops working\nOFF: No alerts | FAST: 1 min, then every 5 min\nNORMAL: 10 min, then every 30 min | SLOW: 1 hour, then every 3 hours")
        @ConfigEditorDropdown(values = {"OFF", "FAST", "NORMAL", "SLOW"})
        public int trackedItemSpeed = 2; // NORMAL

        @Expose
        @ConfigOption(name = "Island Alert Speed", desc = "How often to alert when off your island\nOFF: No alerts | FAST: 1 min, then every 5 min\nNORMAL: 10 min, then every 30 min | SLOW: 1 hour, then every 3 hours")
        @ConfigEditorDropdown(values = {"OFF", "FAST", "NORMAL", "SLOW"})
        public int islandSpeed = 2; // NORMAL

        @Expose
        @ConfigOption(name = "Disconnect Alert Speed", desc = "How often to alert when disconnected\nOFF: No alerts | FAST: After 10s, then every 5 min\nNORMAL: After 10s, then every 30 min | SLOW: After 10s, then every 3 hours")
        @ConfigEditorDropdown(values = {"OFF", "FAST", "NORMAL", "SLOW"})
        public int disconnectSpeed = 2; // NORMAL
    }

    public static class ItemTracking {
        @Expose
        @ConfigOption(name = "Tracked Item UUID", desc = "UUID of the currently tracked item. Use /trackitem set while holding an item to set this.")
        @ConfigEditorText
        public String trackedUuid = "";

        @Expose
        @ConfigOption(name = "Tracked Item Name", desc = "Display name of the tracked item (read-only, set via commands)")
        @ConfigEditorText
        public String trackedItemName = "None";
    }

    public static class ChatRelay {
        @Expose
        @ConfigOption(name = "Chat Webhook URL", desc = "Discord webhook for island chat relay (leave empty to disable). Must start with: https://discord.com/api/webhooks/")
        @ConfigEditorText
        public String chatWebhookUrl = "";

        @Expose
        @ConfigOption(name = "Poll Port", desc = "Port of the companion bot's HTTP server (1-65535)")
        @ConfigEditorText
        public String pollPortString = "8765";

        @Expose
        @ConfigOption(name = "Poll Secret", desc = "Shared secret key for the poll endpoint")
        @ConfigEditorText
        public String pollSecret = "changeme";
    }
}
