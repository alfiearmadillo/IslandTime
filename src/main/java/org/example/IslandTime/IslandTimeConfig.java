package org.example.islandtime;

import moe.nea.moulconfig.Config;
import moe.nea.moulconfig.annotations.*;

/**
 * Main configuration class for IslandTime mod using MoulConfig.
 * All settings are accessible through a GUI opened via /islandtime.
 */
public class IslandTimeConfig extends Config {

    @Override
    public String getConfigFile() {
        return "config/IslandTime/moulconfig.json";
    }

    @Category(name = "Tracking", desc = "Enable or disable tracking features")
    public Tracking tracking = new Tracking();

    @Category(name = "Webhooks & Alerts", desc = "Discord webhook and alert settings")
    public WebhooksAndAlerts webhooksAndAlerts = new WebhooksAndAlerts();

    @Category(name = "Alert Speeds", desc = "Configure alert timing for different events")
    public AlertSpeeds alertSpeeds = new AlertSpeeds();

    @Category(name = "Item Tracking", desc = "Track specific items")
    public ItemTracking itemTracking = new ItemTracking();

    @Category(name = "Chat Relay", desc = "Island chat relay configuration")
    public ChatRelay chatRelay = new ChatRelay();

    @Category(name = "Favourite GUI", desc = "Container snapshot management")
    public FavouriteGui favouriteGui = new FavouriteGui();

    public static class Tracking {
        @ConfigBool(name = "Island Time Tracking", desc = "Track time spent on your private island")
        public boolean islandTimeEnabled = true;

        @ConfigBool(name = "Fuel Cycle Tracking", desc = "Track fuel cycles for your minions")
        public boolean fuelCycleEnabled = true;

        @ConfigBool(name = "Favourite GUI Tracking", desc = "Enable container snapshots for /favouritegui save")
        public boolean favouriteGuiEnabled = true;
    }

    public static class WebhooksAndAlerts {
        @ConfigString(name = "Discord Webhook URL", desc = "Webhook URL for Discord alerts (leave empty to disable)\nMust start with: https://discord.com/api/webhooks/")
        public String webhookUrl = "";

        @ConfigString(name = "Discord User ID", desc = "Your Discord user ID for webhook pings (leave empty to disable pings)\nMust be numeric")
        public String discordUserId = "";
    }

    public static class AlertSpeeds {
        @Dropdown(name = "Tracked Item Alert Speed", desc = "How often to alert when tracked item stops working\nOFF: No alerts\nFAST: 1 min, then every 5 min\nNORMAL: 10 min, then every 30 min\nSLOW: 1 hour, then every 3 hours")
        @ConfigEnum(name = "Tracked Item Alert Speed")
        public AlertSpeed trackedItemSpeed = AlertSpeed.NORMAL;

        @Dropdown(name = "Island Alert Speed", desc = "How often to alert when off your island\nOFF: No alerts\nFAST: 1 min, then every 5 min\nNORMAL: 10 min, then every 30 min\nSLOW: 1 hour, then every 3 hours")
        @ConfigEnum(name = "Island Alert Speed")
        public AlertSpeed islandSpeed = AlertSpeed.NORMAL;

        @Dropdown(name = "Disconnect Alert Speed", desc = "How often to alert when disconnected\nOFF: No alerts\nFAST: After 10s, then every 5 min\nNORMAL: After 10s, then every 30 min\nSLOW: After 10s, then every 3 hours")
        @ConfigEnum(name = "Disconnect Alert Speed")
        public AlertSpeed disconnectSpeed = AlertSpeed.NORMAL;

        public enum AlertSpeed {
            OFF,
            FAST,
            NORMAL,
            SLOW
        }
    }

    public static class ItemTracking {
        @ConfigString(name = "Tracked Item UUID", desc = "UUID of the currently tracked item\nUse /trackitem set to set this")
        public String trackedUuid = "";

        @ConfigString(name = "Tracked Item Name", desc = "Name of the currently tracked item\nUse /trackitem set to set this")
        public String trackedItemName = "None";
    }

    public static class ChatRelay {
        @ConfigString(name = "Chat Webhook URL", desc = "Discord webhook for island chat relay (leave empty to disable)\nMust start with: https://discord.com/api/webhooks/")
        public String chatWebhookUrl = "";

        @ConfigInt(name = "Poll Port", desc = "Port of the companion bot's HTTP server", min = 1, max = 65535)
        public int pollPort = 8765;

        @ConfigString(name = "Poll Secret", desc = "Shared secret key for the poll endpoint")
        public String pollSecret = "changeme";
    }

    public static class FavouriteGui {
        @ConfigString(name = "Info", desc = "Use /favouritegui commands to manage saved containers\nSee /islandtime help 5 for more info")
        public String info = "";
    }
}
