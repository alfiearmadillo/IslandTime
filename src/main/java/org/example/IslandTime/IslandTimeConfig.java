package org.example.islandtime;

import io.github.notenoughupdates.moulconfig.Config;
import io.github.notenoughupdates.moulconfig.annotations.*;
import com.google.gson.annotations.Expose;

/**
 * Main configuration class for IslandTime mod using MoulConfig.
 * All settings are accessible through a GUI opened via /islandtime.
 */
public class IslandTimeConfig extends Config {

    public String getTitle() {
        return "§3IslandTime Config";
    }

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
        @ConfigOption(name = "View Stats", desc = "Click to view time spent on your private island (last 7 days). You can also use /islandtime <date> to view a specific date (e.g., /islandtime 1-1-26)")
        @ConfigEditorButton(buttonText = " View Stats ")
        public Runnable viewStatsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("islandtime recent");
                }
            }
        };

        @Expose
        @ConfigOption(name = "Enable Tracking", desc = "Track time spent on your private island")
        @ConfigEditorBoolean
        public boolean enabled = true;
    }

    public static class FuelCycles {
        @ConfigOption(name = "View Fuel Time", desc = "Click to view fuel cycles for your minions (last 4 cycles). You can also use /fueltime <cycle> to view a specific cycle number (e.g., /fueltime 6)")
        @ConfigEditorButton(buttonText = " View Fuel Time ")
        public Runnable viewFuelTimeButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("fueltime");
                }
            }
        };

        @Expose
        @ConfigOption(name = "Enable Tracking", desc = "Track fuel cycles for your minions")
        @ConfigEditorBoolean
        public boolean enabled = true;
    }

    public static class FavouriteGui {
        @ConfigOption(name = "List Saved Containers", desc = "Click to view all saved container snapshots. You can also use /favouritegui save <name> to save, /favouritegui view <name> to open, or /favouritegui delete <name> to remove saved containers")
        @ConfigEditorButton(buttonText = " List Saved ")
        public Runnable listGuisButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("favouritegui");
                }
            }
        };

        @Expose
        @ConfigOption(name = "Enable Tracking", desc = "Enable container snapshots for /favouritegui save")
        @ConfigEditorBoolean
        public boolean enabled = true;
    }

    public static class ItemTracking {
        @ConfigOption(name = "View Tracked Item", desc = "Click to view currently tracked item")
        @ConfigEditorButton(buttonText = " View Tracked ")
        public Runnable viewTrackedButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("trackitem");
                }
            }
        };

        @ConfigOption(name = "Set Tracked Item", desc = "Hold an item and click to track it")
        @ConfigEditorButton(buttonText = " Set from Held Item ")
        public Runnable setTrackedButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("trackitem set");
                }
            }
        };

        @ConfigOption(name = "Clear Tracked Item", desc = "Click to stop tracking the current item")
        @ConfigEditorButton(buttonText = " Clear Tracked Item ")
        public Runnable clearTrackedButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("trackitem clear");
                }
            }
        };

        @Expose
        @ConfigOption(name = "Tracked Item UUID", desc = "UUID of the currently tracked item. You can also manually paste a UUID here.")
        @ConfigEditorText
        public String trackedUuid = "";
    }

    public static class Discord {
        @Expose
        @ConfigOption(name = "Webhook URL", desc = "Discord webhook URL for alerts (leave empty to disable). Must start with: https://discord.com/api/webhooks/\nYou can also use /webhook <url> to set or /webhook clear to remove")
        @ConfigEditorText
        public String webhookUrl = "";

        @ConfigOption(name = "Clear Webhook", desc = "Click to clear the webhook URL")
        @ConfigEditorButton(buttonText = " Clear Webhook ")
        public Runnable clearWebhookButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("webhook clear");
                }
            }
        };

        @Expose
        @ConfigOption(name = "User ID for Pings", desc = "Your Discord user ID for webhook pings (leave empty to disable pings). Must be numeric.\nYou can also use /discordping <userid> to set or /discordping clear to remove")
        @ConfigEditorText
        public String discordUserId = "";

        @ConfigOption(name = "Clear Ping ID", desc = "Click to clear the Discord user ID for pings")
        @ConfigEditorButton(buttonText = " Clear Ping ID ")
        public Runnable clearPingButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("discordping clear");
                }
            }
        };
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
        @ConfigOption(name = "View Settings", desc = "Click to view chat relay settings")
        @ConfigEditorButton(buttonText = " View Settings ")
        public Runnable viewSettingsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("chatrelay");
                }
            }
        };

        @Expose
        @ConfigOption(name = "Chat Webhook URL", desc = "Discord webhook for island chat relay (leave empty to disable). Must start with: https://discord.com/api/webhooks/")
        @ConfigEditorText
        public String chatWebhookUrl = "";

        @ConfigOption(name = "Clear Chat Webhook", desc = "Click to clear the chat relay webhook URL")
        @ConfigEditorButton(buttonText = " Clear Chat Webhook ")
        public Runnable clearChatWebhookButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("chatrelay webhook clear");
                }
            }
        };

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
        @ConfigOption(name = "Help", desc = "Click to view all available commands. You can also use /islandtime help <page> to view specific help pages (1-6)")
        @ConfigEditorButton(buttonText = " View Help ")
        public Runnable helpButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.networkHandler.sendChatCommand("islandtime help");
                }
            }
        };
    }
}
