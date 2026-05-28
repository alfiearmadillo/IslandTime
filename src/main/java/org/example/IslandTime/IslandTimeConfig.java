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
    @Category(name = "Island Time", desc = "Track time spent on your private island")
    public IslandTime islandTime = new IslandTime();

    @Expose
    @Category(name = "Fuel Cycles", desc = "Track fuel cycles for your minions")
    public FuelCycles fuelCycles = new FuelCycles();

    @Expose
    @Category(name = "Favourite GUI", desc = "Save and view container snapshots")
    public FavouriteGui favouriteGui = new FavouriteGui();

    @Expose
    @Category(name = "Item Tracking", desc = "Track specific items and get alerts")
    public ItemTracking itemTracking = new ItemTracking();

    @Expose
    @Category(name = "Discord", desc = "Discord webhook and alert settings")
    public Discord discord = new Discord();

    @Expose
    @Category(name = "Alert Speeds", desc = "Configure alert timing for different events")
    public AlertSpeeds alertSpeeds = new AlertSpeeds();

    @Expose
    @Category(name = "Chat Relay", desc = "Island chat relay configuration")
    public ChatRelay chatRelay = new ChatRelay();

    @Expose
    @Category(name = "Extras", desc = "Additional commands and features")
    public Extras extras = new Extras();

    public static class IslandTime {
        @Expose
        @ConfigOption(name = "About", desc = "Use /islandtime recent to view time spent on your private island")
        @ConfigEditorText
        public String about = "Use /islandtime recent";

        @Expose
        @ConfigOption(name = "Enable Tracking", desc = "Track time spent on your private island")
        @ConfigEditorBoolean
        public boolean enabled = true;
    }

    public static class FuelCycles {
        @Expose
        @ConfigOption(name = "About", desc = "Use /fueltime to view fuel cycles for your minions")
        @ConfigEditorText
        public String about = "Use /fueltime";

        @Expose
        @ConfigOption(name = "Enable Tracking", desc = "Track fuel cycles for your minions")
        @ConfigEditorBoolean
        public boolean enabled = true;
    }

    public static class FavouriteGui {
        @Expose
        @ConfigOption(name = "About", desc = "Use /favouritegui to save and view container snapshots")
        @ConfigEditorText
        public String about = "Use /favouritegui";

        @Expose
        @ConfigOption(name = "Enable Tracking", desc = "Enable container snapshots for /favouritegui save")
        @ConfigEditorBoolean
        public boolean enabled = true;
    }

    public static class ItemTracking {
        @Expose
        @ConfigOption(name = "About", desc = "Use /trackitem to view or set the currently tracked item")
        @ConfigEditorText
        public String about = "Use /trackitem";

        @Expose
        @ConfigOption(name = "Tracked Item UUID", desc = "UUID of the currently tracked item (read-only, use /trackitem set)")
        @ConfigEditorText
        public String trackedUuid = "";

        @Expose
        @ConfigOption(name = "Tracked Item Name", desc = "Display name of the tracked item (read-only, use /trackitem set)")
        @ConfigEditorText
        public String trackedItemName = "None";
    }

    public static class Discord {
        @Expose
        @ConfigOption(name = "Webhook URL", desc = "Discord webhook URL for alerts (leave empty to disable). Must start with: https://discord.com/api/webhooks/")
        @ConfigEditorText
        public String webhookUrl = "";

        @Expose
        @ConfigOption(name = "User ID for Pings", desc = "Your Discord user ID for webhook pings (leave empty to disable pings). Must be numeric.")
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

    public static class ChatRelay {
        @Expose
        @ConfigOption(name = "About", desc = "Use /chatrelay to configure the chat relay system")
        @ConfigEditorText
        public String about = "Use /chatrelay";

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

    public static class Extras {
        @Expose
        @ConfigOption(name = "Help", desc = "Use /islandtime help to view all available commands")
        @ConfigEditorText
        public String help = "Use /islandtime help";
    }
}
