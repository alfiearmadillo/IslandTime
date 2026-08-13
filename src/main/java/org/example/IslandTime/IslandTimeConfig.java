package org.example.islandtime;

import io.github.notenoughupdates.moulconfig.Config;
import io.github.notenoughupdates.moulconfig.annotations.*;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.common.text.StructuredText;

/**
 * Main configuration class for IslandTime mod using MoulConfig.
 * All settings are accessible through a GUI opened via /islandtime.
 */
public class IslandTimeConfig extends Config {
    @Override
    public StructuredText getTitle() {
        return StructuredText.of("§3IslandTime Config");
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
    @Category(name = "Messages", desc = "Toggle local chat messages from the mod")
    public Messages messages = new Messages();

    @Expose
    @Category(name = "Signs", desc = "Sign editing helpers")
    public Signs signs = new Signs();

    @Expose
    @Category(name = "Block Highlight", desc = "Highlight all blocks of a specific type")
    public BlockHighlight blockHighlight = new BlockHighlight();

    @Expose
    @Category(name = "Player List", desc = "Manage a list of players for quick party/invite actions")
    public PlayerList playerList = new PlayerList();

    @Expose
    @Category(name = "Misc", desc = "Additional commands and miscellaneous settings")
    public Misc misc = new Misc();

    // -------------------------------------------------------------------------

    public static class IslandTime {
        @ConfigOption(name = "View Stats", desc = "Click to view time spent on your private island (last 7 days). You can also use /islandtime <date> to view a specific date (e.g., /islandtime 1-1-26)")
        @ConfigEditorButton(buttonText = " View Stats ")
        public Runnable viewStatsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("islandtime recent");
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
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("fueltime");
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
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("favouritegui");
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
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("trackitem");
                }
            }
        };

        @ConfigOption(name = "Set Tracked Item", desc = "Hold an item and click to track it")
        @ConfigEditorButton(buttonText = " Set from Held Item ")
        public Runnable setTrackedButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("trackitem set");
                }
            }
        };

        @ConfigOption(name = "Clear Tracked Item", desc = "Click to stop tracking the current item")
        @ConfigEditorButton(buttonText = " Clear Tracked Item ")
        public Runnable clearTrackedButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("trackitem clear");
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
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("webhook clear");
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
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("discordping clear");
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
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("chatrelay");
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
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("chatrelay webhook clear");
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

    public static class Misc {
        @ConfigOption(name = "Help", desc = "Click to view all available commands. You can also use /islandtime help <page> to view specific help pages (1-8)")
        @ConfigEditorButton(buttonText = " View Help ")
        public Runnable helpButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("islandtime help");
                }
            }
        };

        // ── Tooltips & Display ───────────────────────────────────────────────

        @ConfigEditorAccordion(id = 1)
        @ConfigOption(name = "Tooltips & Display", desc = "Settings for tooltip visibility and item display overlays")
        public boolean tooltipsAccordion = false;

        @ConfigAccordionId(id = 1)
        @Expose
        @ConfigOption(
            name = "Show Hidden Tooltips",
            desc = "When enabled, items that have hide_tooltip set (such as GUI filler panes) will still show their tooltip on hover. Toggle with /showtooltips"
        )
        @ConfigEditorBoolean
        public boolean showHiddenTooltips = false;

        @ConfigAccordionId(id = 1)
        @Expose
        @ConfigOption(
            name = "Show PPG (Price Per Gabagool)",
            desc = "When enabled, shows a PPG line below the Total line in the Very Crude Gabagool sell offer setup menu. PPG = Total / 192, rounded up to nearest 0.1"
        )
        @ConfigEditorBoolean
        public boolean showPpg = true;

        @ConfigAccordionId(id = 1)
        @Expose
        @ConfigOption(
            name = "Show Component Display",
            desc = "When enabled, shows specified item components at the bottom of item tooltips in dark gray text"
        )
        @ConfigEditorBoolean
        public boolean componentDisplayEnabled = false;

        @ConfigAccordionId(id = 1)
        @Expose
        @ConfigOption(
            name = "Component Names",
            desc = "Space-separated list of component names to display (e.g. catacombs_level_requirement id color)"
        )
        @ConfigEditorText
        public String componentNames = "";

        // ── Copy Skin ────────────────────────────────────────────────────────

        @ConfigEditorAccordion(id = 2)
        @ConfigOption(name = "Copy Skin", desc = "Copy skin textures from player heads to clipboard")
        public boolean copySkinAccordion = false;

        @ConfigAccordionId(id = 2)
        @Expose
        @ConfigOption(
            name = "Copy Skin from Hovered Item",
            desc = "When enabled, pressing the configured key while hovering over a player head in any inventory/GUI will copy the skin image to your clipboard"
        )
        @ConfigEditorBoolean
        public boolean copySkinKeybindEnabled = true;

        @ConfigAccordionId(id = 2)
        @Expose
        @ConfigOption(
            name = "Copy Skin Keybind",
            desc = "Keybind for copying skin from hovered item. Requires Copy Skin from Hovered Item to be enabled"
        )
        @ConfigEditorKeybind(defaultKey = 86) // GLFW_KEY_V
        public int copySkinKeybind = 86;

        // ── Other ────────────────────────────────────────────────────────────

        @ConfigEditorAccordion(id = 3)
        @ConfigOption(name = "Other", desc = "Ghost command, resource pack caching, and other settings")
        public boolean otherAccordion = false;

        @ConfigAccordionId(id = 3)
        @Expose
        @ConfigOption(
            name = "Enable Ghost Command",
            desc = "When enabled, /it ghost and /islandtime ghost commands are available for client-side block removal"
        )
        @ConfigEditorBoolean
        public boolean ghostEnabled = true;

        @ConfigAccordionId(id = 3)
        @Expose
        @ConfigOption(
            name = "Keep Resource Pack",
            desc = "When enabled, caches the Hypixel SkyBlock resource pack locally and keeps it active everywhere (even outside Hypixel). Hides the pack download prompt and only re-downloads if the pack changes."
        )
        @ConfigEditorBoolean
        public boolean keepResourcePack = false;
    }

    public static class Signs {
        @Expose
        @ConfigOption(name = "Enable Sign Helpers", desc = "When enabled, adds paste buttons to the sign edit screen. When disabled, signs behave normally.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Line 1", desc = "Pre-set text for sign line 1")
        @ConfigEditorText
        public String line1 = "";

        @Expose
        @ConfigOption(name = "Line 2", desc = "Pre-set text for sign line 2")
        @ConfigEditorText
        public String line2 = "";

        @Expose
        @ConfigOption(name = "Line 3", desc = "Pre-set text for sign line 3")
        @ConfigEditorText
        public String line3 = "";

        @Expose
        @ConfigOption(name = "Line 4", desc = "Pre-set text for sign line 4")
        @ConfigEditorText
        public String line4 = "";
    }

    public static class BlockHighlight {
        @ConfigOption(name = "Open Block Highlights", desc = "Click to open the block highlights GUI for managing individual entries. You can also use /it highlights")
        @ConfigEditorButton(buttonText = " Open Block Highlights ")
        public Runnable openBlockHighlightsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new BlockHighlightScreen()));
            }
        };

        @Expose
        @ConfigOption(name = "Enabled", desc = "Toggle the block highlight on or off")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Default Intensity", desc = "Default highlight intensity for new entries (0 = off, 100 = maximum brightness)")
        @ConfigEditorSlider(minValue = 0, maxValue = 100, minStep = 1)
        public int intensity = 50;

        @Expose
        @ConfigOption(name = "Default Color", desc = "Default highlight color for new entries")
        @ConfigEditorDropdown(values = {"YELLOW", "RED", "GREEN", "BLUE", "MAGENTA", "CYAN", "WHITE"})
        public int color = 0;

        @Expose
        @ConfigOption(name = "Default Through Walls", desc = "Default through-walls setting for new entries")
        @ConfigEditorBoolean
        public boolean throughWalls = true;

        @Expose
        @ConfigOption(name = "Default Range", desc = "Default scan range for new entries (in blocks)")
        @ConfigEditorSlider(minValue = 1, maxValue = 320, minStep = 1)
        public int range = 48;
    }

    public static class Messages {
        @Expose
        @ConfigOption(name = "Minion Fuel Messages", desc = "Show a local chat message when you fuel a minion (e.g. 'Coal Minion fueled with Enchanted Lava Bucket'). Toggle with /minionfuelmessages")
        @ConfigEditorBoolean
        public boolean minionFuelMessages = true;

        @Expose
        @ConfigOption(name = "Island Leave Messages", desc = "Show a local chat message when a visitor leaves your island (e.g. '[IslandTime] [VIP] Steve left Your Island.'). Toggle with /islandleavemessages")
        @ConfigEditorBoolean
        public boolean islandLeaveMessages = true;
    }

    public static class PlayerList {
        @ConfigOption(name = "Open Player List", desc = "Click to open the player list GUI. You can also use /it players")
        @ConfigEditorButton(buttonText = " Open Player List ")
        public Runnable openPlayerListButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new PlayerListScreen()));
            }
        };
    }
}
