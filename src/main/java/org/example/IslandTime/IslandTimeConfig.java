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
    @Category(name = "Fuel Cycles", desc = "Track minion fuel and profit per fuel day")
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
    @Category(name = "New Year Cakes", desc = "Discord reminders for the New Year's Celebration cake")
    public NewYearCakes newYearCakes = new NewYearCakes();

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
    @Category(name = "Critter Safari", desc = "On-screen tracker for unique critters caught during a Critter Safari run")
    public CritterSafari critterSafari = new CritterSafari();

    @Expose
    @Category(name = "Player List", desc = "Manage a list of players for quick party/invite actions")
    public PlayerList playerList = new PlayerList();

    @Expose
    @Category(name = "Settings Loadouts", desc = "Save, load, and cycle between named settings profiles")
    public SettingsLoadouts settingsLoadouts = new SettingsLoadouts();

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
        @ConfigOption(name = "Open Fuel Day Tracker", desc = "Open the fuel day tracker GUI to view and edit per-fuel-day fuel and profit stats. Each entry is one real 24h fuel-day, so a stack of 4 fuel creates 4 entries at once and each alerts when its own fuel runs out. Also /it fueldays")
        @ConfigEditorButton(buttonText = " Open Fuel Day Tracker ")
        public Runnable viewFuelTimeButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new FuelDayScreen()));
            }
        };

        @Expose
        @ConfigOption(name = "Enable Tracking", desc = "Track minion fuel and profit per fuel day")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(
                name = "Use Test Data File",
                desc = "Read and write a separate test file (fuel_days_test.json) instead of your real " +
                        "tracked data, so you can try things out safely. Toggling reloads the GUI."
        )
        @ConfigEditorBoolean
        public boolean useTestDataFile = false;

        @Expose
        @ConfigOption(
                name = "Coins/Day @ 100% AFK",
                desc = "Baseline coins/day from mob kills at 100% AFK, scaled by each day's actual AFK%. " +
                        "Snapshotted per day, so changing it never affects past days."
        )
        @ConfigEditorText
        public String mobKillCoinsAt100PercentString = "0";

        @Expose
        @ConfigOption(
                name = "Default Gabagool Price",
                desc = "Sell price for one Crude Gabagool. Applies to the active day only; past days keep " +
                        "their own price."
        )
        @ConfigEditorText
        public String todayGabagoolPriceString = "0";

        @Expose
        @ConfigOption(
                name = "Current Day's Gabagool Amount Override",
                desc = "Override the Crude Gabagool counted for the active day. Blank uses the amount " +
                        "auto-tracked from '[Sacks]' messages. Accepts a total, or +/- to adjust it (e.g. +100000)."
        )
        @ConfigEditorText
        public String todayGabagoolAmountString = "";
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

    public static class NewYearCakes {
        @Expose
        @ConfigOption(
                name = "Webhook URL",
                desc = "Discord webhook for New Year's Celebration reminders (leave empty to disable). " +
                        "You may use the same URL as the main Discord webhook."
        )
        @ConfigEditorText
        public String webhookUrl = "";

        @Expose
        @ConfigOption(
                name = "Enable Discord Reminders",
                desc = "Notify Discord when the New Year's Celebration starts and remind you before the cake expires"
        )
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(
                name = "Reminder Speed",
                desc = "How often to remind during the 1-hour event\n" +
                        "OFF: No reminders | FAST: Every 5 min | MEDIUM: Every 15 min | SLOW: Every 30 min"
        )
        @ConfigEditorDropdown(values = {"OFF", "FAST (5 min)", "MEDIUM (15 min)", "SLOW (30 min)"})
        public int reminderSpeed = 2; // MEDIUM
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

        @Expose
        @ConfigOption(name = "Fuel Expired Alert Speed", desc = "How often to alert when a minion's fuel runs out, until you empty your sacks\nOFF: No alerts | FAST: Immediately, then every 5 min\nNORMAL: Immediately, then every 30 min | SLOW: Immediately, then every 3 hours")
        @ConfigEditorDropdown(values = {"OFF", "FAST", "NORMAL", "SLOW"})
        public int fuelExpiredSpeed = 2; // NORMAL
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

        // ── Aging Items ──────────────────────────────────────────────────────

        @ConfigEditorAccordion(id = 4)
        @ConfigOption(name = "Aging Items", desc = "Compare profit per hour for levelling items (Bottle of Jyrre, Dark Cacao Truffle, etc.)")
        public boolean agingItemsAccordion = false;

        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Compare Aging Items", desc = "Fetch Bazaar prices and compare profit/hour for all configured aging items")
        @ConfigEditorButton(buttonText = " Compare Now ")
        public Runnable agingItemsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("it agingItems");
                }
            }
        };

        @ConfigAccordionId(id = 4)
        @Expose
        @ConfigOption(name = "Time Bag Slots", desc = "Number of Time Pocket (time-bag) slots available for aging items (items age at 2× speed)")
        @ConfigEditorSlider(minValue = 0, maxValue = 12, minStep = 1)
        public int agingTimeBagSlots = 3;

        @ConfigAccordionId(id = 4)
        @Expose
        @ConfigOption(name = "Inventory Slots", desc = "Number of inventory slots dedicated to levelling items (items age at 1× speed)")
        @ConfigEditorSlider(minValue = 0, maxValue = 35, minStep = 1)
        public int agingInventorySlots = 0;

        @ConfigAccordionId(id = 4)
        @Expose
        @ConfigOption(name = "Use Sell Offer", desc = "When enabled, uses sell offer (sell order) prices instead of insta-sell prices for aged items")
        @ConfigEditorBoolean
        public boolean agingUseSellOffer = false;

        @ConfigAccordionId(id = 4)
        @Expose
        @ConfigOption(name = "Show Top X Items", desc = "Only show the top X most profitable items (0 = show all)")
        @ConfigEditorSlider(minValue = 0, maxValue = 10, minStep = 1)
        public int agingTopCount = 0;

        // ── Cookie Profit ────────────────────────────────────────────────────

        @ConfigEditorAccordion(id = 5)
        @ConfigOption(name = "Cookie Profit", desc = "Compare profit from spending Booster Cookie bits on various items")
        public boolean cookieProfitAccordion = false;

        @ConfigAccordionId(id = 5)
        @ConfigOption(name = "Calculate Cookie Profit", desc = "Fetch Bazaar prices and compare profit per bit for cookie items")
        @ConfigEditorButton(buttonText = " Calculate ")
        public Runnable cookieProfitButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("it cookieProfit");
                }
            }
        };

        @ConfigAccordionId(id = 5)
        @Expose
        @ConfigOption(name = "Bits Per Cookie", desc = "Your bits per cookie (base 4800, increases with Fame Rank multiplier)")
        @ConfigEditorSlider(minValue = 4800, maxValue = 30000, minStep = 100)
        public int bitsPerCookie = 4800;

        @ConfigAccordionId(id = 5)
        @Expose
        @ConfigOption(name = "Include Bits Talisman", desc = "When enabled, accounts for the Bits Talisman (10% chance to double bits earned, effectively 10% more bits per cookie)")
        @ConfigEditorBoolean
        public boolean includeBitsTalisman = false;

        @ConfigAccordionId(id = 5)
        @Expose
        @ConfigOption(name = "Inferno Fuel Blaze Slayer Perk", desc = "When enabled, uses the discounted x64 Inferno Fuel Block cost (3,120 bits instead of 3,600) from Blaze Slayer IX")
        @ConfigEditorBoolean
        public boolean infernoFuelBlazeSlayerPerk = false;

        @ConfigAccordionId(id = 5)
        @Expose
        @ConfigOption(name = "Show Top X Items", desc = "Only show the top X most profitable items (0 = show all)")
        @ConfigEditorSlider(minValue = 0, maxValue = 20, minStep = 1)
        public int cookieTopCount = 0;

        @ConfigAccordionId(id = 5)
        @ConfigEditorAccordion(id = 6)
        @ConfigOption(name = "Sell Mode Overrides", desc = "Toggle each item between Insta Sell and Sell Offer pricing")
        public boolean sellModeAccordion = false;

        @ConfigAccordionId(id = 6)
        @Expose
        @ConfigOption(name = "Kismet Feather", desc = "Sell mode for Kismet Feather (default: Sell Offer)")
        @ConfigEditorDropdown(values = {"Insta Sell", "Sell Offer"})
        public int kismetSellMode = 1; // Sell Offer

        @ConfigAccordionId(id = 6)
        @Expose
        @ConfigOption(name = "Magma Bucket", desc = "Sell mode for Magma Bucket")
        @ConfigEditorDropdown(values = {"Insta Sell", "Sell Offer"})
        public int magmaBucketSellMode = 0; // Insta Sell

        @ConfigAccordionId(id = 6)
        @Expose
        @ConfigOption(name = "Plasma Bucket", desc = "Sell mode for Plasma Bucket")
        @ConfigEditorDropdown(values = {"Insta Sell", "Sell Offer"})
        public int plasmaBucketSellMode = 0; // Insta Sell

        @ConfigAccordionId(id = 6)
        @Expose
        @ConfigOption(name = "Ultimate Carrot Candy", desc = "Sell mode for Ultimate Carrot Candy")
        @ConfigEditorDropdown(values = {"Insta Sell", "Sell Offer"})
        public int ultimateCarrotCandySellMode = 0; // Insta Sell

        @ConfigAccordionId(id = 6)
        @Expose
        @ConfigOption(name = "XL Minion Storage", desc = "Sell mode for XL Minion Storage")
        @ConfigEditorDropdown(values = {"Insta Sell", "Sell Offer"})
        public int xlStorageSellMode = 0; // Insta Sell

        @ConfigAccordionId(id = 6)
        @Expose
        @ConfigOption(name = "XXL Minion Storage", desc = "Sell mode for XXL Minion Storage")
        @ConfigEditorDropdown(values = {"Insta Sell", "Sell Offer"})
        public int xxlStorageSellMode = 0; // Insta Sell

        @ConfigAccordionId(id = 6)
        @Expose
        @ConfigOption(name = "Bitbug Shard", desc = "Sell mode for Bitbug Shard")
        @ConfigEditorDropdown(values = {"Insta Sell", "Sell Offer"})
        public int bitbugShardSellMode = 0; // Insta Sell

        @ConfigAccordionId(id = 6)
        @Expose
        @ConfigOption(name = "Inferno Fuel Block", desc = "Sell mode for Inferno Fuel Block")
        @ConfigEditorDropdown(values = {"Insta Sell", "Sell Offer"})
        public int infernoFuelSellMode = 0; // Insta Sell

        @ConfigAccordionId(id = 6)
        @Expose
        @ConfigOption(name = "Levelling Enchantments", desc = "Sell mode for all Levelling Enchantments")
        @ConfigEditorDropdown(values = {"Insta Sell", "Sell Offer"})
        public int enchantmentSellMode = 0; // Insta Sell

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

        @ConfigAccordionId(id = 3)
        @ConfigOption(
            name = "Force Redownload Pack",
            desc = "Click to clear the cached resource pack so it will be fully re-downloaded on next Hypixel login"
        )
        @ConfigEditorButton(buttonText = " Force Redownload ")
        public Runnable forceRedownloadButton = new Runnable() {
            @Override
            public void run() {
                ResourcePackManager.clearCache();
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("§a[IslandTime] Resource pack cache cleared. Rejoin Hypixel to re-download.")
                    );
                }
            }
        };

        @ConfigAccordionId(id = 3)
        @Expose
        @ConfigOption(
            name = "True Adaptive Color",
            desc = "When enabled, Adaptive armor pieces (chest, legs, boots — starred and unstarred) are re-dyed client-side to the color stored in their custom_data color tag instead of the forced default."
        )
        @ConfigEditorBoolean
        public boolean trueAdaptiveColor = false;
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
        @ConfigOption(name = "Open Highlights", desc = "Click to open the highlights GUI for managing blocks and any registered entity type, such as item frames or armor stands. You can also use /it highlights")
        @ConfigEditorButton(buttonText = " Open Highlights ")
        public Runnable openBlockHighlightsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new BlockHighlightScreen()));
            }
        };

        @Expose
        @ConfigOption(name = "Enabled", desc = "Toggle block and entity highlights on or off")
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

    public static class CritterSafari {
        @ConfigOption(name = "Move Widget", desc = "Click to open a screen where you can drag the Critter Safari widget anywhere on your screen. You can also use /it critters")
        @ConfigEditorButton(buttonText = " Move Widget ")
        public Runnable moveWidgetButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new CritterSafariHudScreen()));
            }
        };

        @Expose
        @ConfigOption(name = "Enabled", desc = "Show a widget listing the critters you still need for the current Critter Safari run. Caught critters are removed from the list. Only visible while you are inside the Safari, and resets when the Safari Manager starts a new run")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Only Show In Safari", desc = "Only show the widget and the Sparkling Alert while the tab list reports that you are inside the Critter Safari. Turn this off if the widget stays hidden while you are in the Safari")
        @ConfigEditorBoolean
        public boolean onlyInSafari = true;

        @Expose
        @ConfigOption(name = "Widget X", desc = "Horizontal widget position as a percentage of the screen (0 = left, 100 = right)")
        @ConfigEditorSlider(minValue = 0, maxValue = 100, minStep = 1)
        public int hudX = 50;

        @Expose
        @ConfigOption(name = "Widget Y", desc = "Vertical widget position as a percentage of the screen (0 = top, 100 = bottom)")
        @ConfigEditorSlider(minValue = 0, maxValue = 100, minStep = 1)
        public int hudY = 30;

        @Expose
        @ConfigOption(name = "Widget Scale", desc = "Size of the widget as a percentage. You can also scroll the mouse wheel in the Move Widget screen to resize it")
        @ConfigEditorSlider(minValue = 50, maxValue = 300, minStep = 5)
        public int hudScale = 100;

        // ── Announcements ────────────────────────────────────────────────────

        @ConfigEditorAccordion(id = 2)
        @ConfigOption(name = "Announcements", desc = "Automatically report each biome as you finish it")
        public boolean announceAccordion = false;

        @ConfigAccordionId(id = 2)
        @Expose
        @ConfigOption(name = "Announce Completion", desc = "Send a message as soon as every critter of a biome has been caught (e.g. 'Forest done (1m 12.7s)'). The timer starts when the Safari ticket is handed in. The biome that finishes the run sends 'All done' instead of its own name")
        @ConfigEditorBoolean
        public boolean announceCompletion = false;

        @ConfigAccordionId(id = 2)
        @Expose
        @ConfigOption(name = "Send To Party Chat", desc = "Send announcements to party chat with /pchat. Turn this off to print them only in your own chat as a local [IslandTime] message that nobody else sees")
        @ConfigEditorBoolean
        public boolean announceToParty = true;

        @ConfigAccordionId(id = 2)
        @Expose
        @ConfigOption(name = "Forest Minus Macaw", desc = "Treat the Forest as done without the Macaw, which often does not spawn. Announcements say 'minus Macaw' while it is still missing, then correct themselves to 'Forest done' and 'All done' if Macaw is caught later")
        @ConfigEditorBoolean
        public boolean announceMinusMacaw = false;

        // ── Sparkling Alert ──────────────────────────────────────────────────

        @ConfigEditorAccordion(id = 1)
        @ConfigOption(name = "Sparkling Alert", desc = "Alerts you when a SPARKLING critter (bonus Rainbow Feather) spawns nearby")
        public boolean sparklingAccordion = false;

        @ConfigAccordionId(id = 1)
        @Expose
        @ConfigOption(name = "Enable Sparkling Alert", desc = "Watch for SPARKLING critters while you are inside the Critter Safari. Each newly spotted sparkling plays a sound and gets a tracer line")
        @ConfigEditorBoolean
        public boolean sparklingAlert = true;

        @ConfigAccordionId(id = 1)
        @Expose
        @ConfigOption(name = "Play Sound", desc = "Play an XP pling when a SPARKLING critter is first spotted")
        @ConfigEditorBoolean
        public boolean sparklingSound = true;

        @ConfigAccordionId(id = 1)
        @Expose
        @ConfigOption(name = "Show Tracer", desc = "Draw a line from you to each nearby SPARKLING critter, visible through walls")
        @ConfigEditorBoolean
        public boolean sparklingTracer = true;

        @ConfigAccordionId(id = 1)
        @Expose
        @ConfigOption(name = "Show Name Tag", desc = "Draw a large SPARKLING label above each nearby sparkling critter, visible through walls")
        @ConfigEditorBoolean
        public boolean sparklingNameTag = true;

        @ConfigAccordionId(id = 1)
        @Expose
        @ConfigOption(name = "Tracer Color", desc = "Color of the SPARKLING critter tracer line")
        @ConfigEditorDropdown(values = {"YELLOW", "RED", "GREEN", "BLUE", "MAGENTA", "CYAN", "WHITE"})
        public int sparklingTracerColor = 4;
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

    public static class SettingsLoadouts {
        @ConfigOption(name = "Save Current Settings", desc = "Type a name and click to save your current settings as a loadout. You can also use /it settingssave <name>")
        @ConfigEditorButton(buttonText = " Save Settings ")
        public Runnable saveSettingsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("§e[IslandTime] Use /it settingssave <name> to save your current settings as a loadout.")
                    );
                }
            }
        };

        @ConfigOption(name = "List Loadouts & Groups", desc = "Click to list all saved loadouts and settings groups. You can also use /it settingslist")
        @ConfigEditorButton(buttonText = " List Loadouts ")
        public Runnable listSettingsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("it settingslist");
                }
            }
        };

        @ConfigOption(name = "Cycle Settings", desc = "Click to cycle to the next loadout in the current settings group. You can also use /it settingscycle")
        @ConfigEditorButton(buttonText = " Cycle Settings ")
        public Runnable cycleSettingsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("it settingscycle");
                }
            }
        };
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
