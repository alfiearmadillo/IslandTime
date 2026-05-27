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
        @ConfigOption(name = "About", desc = "Tracks time spent on your private island. View with /islandtime or /islandtime <date>")
        @ConfigEditorText
        public String info = "Use /islandtime to view your stats";

        @Expose
        @ConfigOption(name = "Enable Tracking", desc = "Track time spent on your private island")
        @ConfigEditorBoolean
        public boolean enabled = true;
    }

    public static class FuelCycles {
        @Expose
        @ConfigOption(name = "About", desc = "Tracks fuel cycles for your minions. View with /fueltime")
        @ConfigEditorText
        public String info = "Use /fueltime to view your cycles";

        @Expose
        @ConfigOption(name = "Enable Tracking", desc = "Track fuel cycles for your minions")
        @ConfigEditorBoolean
        public boolean enabled = true;
    }

    public static class FavouriteGui {
        @Expose
        @ConfigOption(name = "About", desc = "Save container snapshots. Use /favouritegui to list saved containers, /favouritegui save <name> to save one")
        @ConfigEditorText
        public String info = "Use /favouritegui to manage saved containers";

        @Expose
        @ConfigOption(name = "Enable Tracking", desc = "Enable container snapshots for /favouritegui save")
        @ConfigEditorBoolean
        public boolean enabled = true;
    }

    public static class ItemTracking {
        @Expose
        @ConfigOption(name = "About", desc = "Track specific items. Use /trackitem set while holding an item, /trackitem to view, /trackitem clear to stop")
        @ConfigEditorText
        public String info = "Use /trackitem to manage tracking";

        @Expose
        @ConfigOption(name = "Tracked Item UUID", desc = "UUID of the currently tracked item. Use /trackitem set while holding an item to set this.")
        @ConfigEditorText
        public String trackedUuid = "";

        @Expose
        @ConfigOption(name = "Tracked Item Name", desc = "Display name of the tracked item (read-only, set via commands)")
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
        @ConfigOption(name = "About", desc = "Relay island chat to Discord. Configure with /chatrelay commands")
        @ConfigEditorText
        public String info = "Use /chatrelay to view settings";

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
        @ConfigOption(name = "Help Command", desc = "View all available commands and help pages")
        @ConfigEditorText
        public String helpCommand = "/islandtime help";

        @Expose
        @ConfigOption(name = "Webhook Command", desc = "View or set the Discord webhook URL")
        @ConfigEditorText
        public String webhookCommand = "/webhook [url]";

        @Expose
        @ConfigOption(name = "Discord Ping Command", desc = "View or set Discord user ID for pings")
        @ConfigEditorText
        public String discordPingCommand = "/discordping [id]";

        @Expose
        @ConfigOption(name = "Alert Speed Commands", desc = "Configure alert speeds for different events")
        @ConfigEditorText
        public String alertSpeedCommands = "/trackedalertspeed, /islandalertspeed, /disconnectalertspeed";

        @Expose
        @ConfigOption(name = "Toggle Commands", desc = "Enable or disable tracking features")
        @ConfigEditorText
        public String toggleCommands = "/trackislandtime <on|off>, /trackfuelcycles <on|off>";
    }
}
