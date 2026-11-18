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
    @Category(
            name = "Profit Calculators",
            desc = "Run buttons and settings for /it agingItems, /it cookieProfit, /it huntrapProfit and /it infernoProfits"
    )
    public ProfitCalculators profitCalculators = new ProfitCalculators();

    @Expose
    @Category(name = "Favourite GUI", desc = "Save and view container snapshots")
    public FavouriteGui favouriteGui = new FavouriteGui();

    @Expose
    @Category(name = "Item Tracking", desc = "Track specific items and get alerts")
    public ItemTracking itemTracking = new ItemTracking();

    @Expose
    @Category(
            name = "Discord",
            desc = "Discord webhook and ping settings for general IslandTime alerts such as island absence, item tracking, disconnects, fuel expiry, composter alerts, and evolution alerts. New Year Cake notifications have separate webhook and ping settings in the New Year Cakes category."
    )
    public Discord discord = new Discord();

    @Expose
    @Category(name = "Chat Alerts", desc = "Discord alerts triggered by specific in-game chat messages")
    public ChatAlerts chatAlerts = new ChatAlerts();

    @Expose
    @Category(name = "Composter", desc = "Discord alerts for when your Garden composter is about to run out or is empty")
    public Composter composter = new Composter();

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
    @Category(name = "WAILA", desc = "What Am I Looking At: inspect the block or entity under your crosshair")
    public Waila waila = new Waila();

    @Expose
    @Category(name = "Critter Safari", desc = "On-screen tracker for unique critters caught during a Critter Safari run")
    public CritterSafari critterSafari = new CritterSafari();

    @Expose
    @Category(name = "Greenhouse Blueprint", desc = "Litematica-style ghost preview of a greenhouse layout over your Garden greenhouse plots, copied from a greenhouse you have planted with /it blueprint copy and /it blueprint save")
    public GreenhouseBlueprintSettings greenhouseBlueprint = new GreenhouseBlueprintSettings();

    @Expose
    @Category(name = "Player List", desc = "Manage a list of players for quick party/invite actions")
    public PlayerList playerList = new PlayerList();

    @Expose
    @Category(name = "Settings Loadouts", desc = "Save, load, and cycle between named settings profiles")
    public SettingsLoadouts settingsLoadouts = new SettingsLoadouts();

    @Expose
    @Category(name = "Accounts", desc = "Log in, switch accounts and renew your login in game without disconnecting, and change your skin and cape")
    public Accounts accounts = new Accounts();

    @Expose
    @Category(name = "Misc", desc = "Additional commands and miscellaneous settings")
    public Misc misc = new Misc();

    // -------------------------------------------------------------------------

    public static class Accounts {
        @ConfigOption(name = "Open Accounts", desc = "Log an account in, switch between saved accounts and renew the login in use. Also /it account")
        @ConfigEditorButton(buttonText = " Open Accounts ")
        public Runnable openAccountsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new AccountsScreen(null)));
            }
        };

        @ConfigOption(name = "Open Skin & Capes", desc = "Paste or set a skin and pick a cape for the account in use. Also /it skin")
        @ConfigEditorButton(buttonText = " Open Skin & Capes ")
        public Runnable openSkinButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new SkinScreen(null)));
            }
        };

        @Expose
        @ConfigOption(
                name = "Microsoft Client ID",
                desc = "Needed to log an account in through the mod. It is the Application (client) ID of an app you " +
                        "register at portal.azure.com (App registrations) for 'personal Microsoft accounts', with " +
                        "'Allow public client flows' turned on. Microsoft also has to approve the app for Minecraft " +
                        "(aka.ms/AppRegInfo) before the final Minecraft login step accepts it. It is not a secret, " +
                        "but without it nothing here can log in. Skin and cape changes only need the login you are " +
                        "already using."
        )
        @ConfigEditorText
        public String msClientId = "";

        @Expose
        @ConfigOption(
                name = "Renew Login Automatically",
                desc = "Renew the login of a saved account in the background shortly before it expires (they last " +
                        "24 hours), without disconnecting. Only applies to accounts that were logged in through " +
                        "the mod, as renewing needs the login it saved."
        )
        @ConfigEditorBoolean
        public boolean autoRenew = true;

        @Expose
        @ConfigOption(
                name = "Set Skin Keybind",
                desc = "While hovering a player head in any inventory, press this key to make its skin your skin. " +
                        "Unbound by default. Press Escape while binding to clear it"
        )
        @ConfigEditorKeybind(defaultKey = -1)
        public int setSkinKeybind = -1;
    }
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

        @ConfigOption(name = "Edit Cohort Minions", desc = "Open the list of minions in the current fuel cohort to add or remove time from individual minions, sync one back to the rest, or remove it. Also /it cohort")
        @ConfigEditorButton(buttonText = " Edit Minions ")
        public Runnable editCohortMinionsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new FuelMinionsScreen(null)));
            }
        };

        @Expose
        @ConfigOption(
                name = "Highlight Out-of-Sync Minions",
                desc = "Highlight, through walls, any minion whose fuel ends more than 8 hours before or after " +
                        "the average of the minions with the most whole days of fuel left (everyone with 4d 0s to " +
                        "4d 23h 59m 59s when 4 days is the highest). Only minions you have opened since " +
                        "they were fueled are known by position."
        )
        @ConfigEditorBoolean
        public boolean highlightOutOfSyncMinions = false;

        @ConfigOption(
                name = "Fuel Data Sets",
                desc = "Create, view and switch between sets of fuel day data (Main, Debug, or your own). " +
                        "The set in use is the one new fuel days are recorded into, and is remembered across " +
                        "restarts; you can browse another set without switching to it. Also /it datasets"
        )
        @ConfigEditorButton(buttonText = " Open Data Sets ")
        public Runnable openDataSetsButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new FuelDataSetsScreen()));
            }
        };

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
                name = "Gabagool Distillate Only",
                desc = "Price the 6 distillates in a fuel's recipe as Crude Gabagool Distillate only, instead of " +
                        "whichever of the five distillates is cheapest. Turn this on if you aren't AFKing, as " +
                        "Gabagool is the only one that is viable then. Also applies to /it fuelcost. Each fuel " +
                        "cohort keeps the price it was first costed at, so this takes effect from the next cohort."
        )
        @ConfigEditorBoolean
        public boolean gabagoolDistillateOnly = false;

        @Expose
        @ConfigOption(
                name = "Default Gabagool Price",
                desc = "Sell price for one Crude Gabagool. Applied to the active and queued days (and any day " +
                        "without a price) and marked 'temp auto price' until you set that day's price manually. " +
                        "Ended days keep their own price."
        )
        @ConfigEditorText
        public String todayGabagoolPriceString = "";

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
        @ConfigOption(name = "Webhook URL", desc = "Discord webhook URL for general IslandTime alerts (leave empty to disable). Must start with: https://discord.com/api/webhooks/\nYou can also use /webhook <url> to set or /webhook clear to remove")
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
        @ConfigOption(name = "User ID for Pings", desc = "Discord user ID to ping for general IslandTime alerts (leave empty to disable). Must be numeric. New Year Cake pings use the separate ID in the New Year Cakes category.\nYou can also use /discordping <userid> to set or /discordping clear to remove")
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

    public static class ChatAlerts {
        @Expose
        @ConfigOption(
                name = "Evolution Alert",
                desc = "Send one Discord alert (main webhook and ping ID) each time an \"EVOLUTION! Your <item> has " +
                        "evolved!\" message appears in chat, e.g. for your Rosewater Flask"
        )
        @ConfigEditorBoolean
        public boolean evolutionEnabled = true;
    }

    public static class Composter {
        @ConfigOption(
                name = "Composter Status",
                desc = "Show what the mod currently knows about your composter. It reads the Composter tab widget " +
                        "(enable it in /widget, visit the Garden) or the Composter menu, and your upgrade levels " +
                        "(open the Composter Upgrades menu once). Also /it composter"
        )
        @ConfigEditorButton(buttonText = " View Status ")
        public Runnable statusButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("it composter");
                }
            }
        };

        @ConfigOption(
                name = "Copy Debug Info",
                desc = "Copies the tab list lines and the last Composter menu you opened to your clipboard. " +
                        "Use it when the composter is not being found. Also /it composter debug"
        )
        @ConfigEditorButton(buttonText = " Copy Debug Info ")
        public Runnable debugButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client.player != null) {
                    client.player.connection.sendCommand("it composter debug");
                }
            }
        };

        @Expose
        @ConfigOption(
                name = "Enable Composter Alerts",
                desc = "Track the Garden composter and send Discord alerts using the main Discord webhook and ping ID"
        )
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(
                name = "Alert When Empty",
                desc = "Send a Discord alert when the composter runs out of organic matter or fuel"
        )
        @ConfigEditorBoolean
        public boolean alertWhenEmpty = true;

        @Expose
        @ConfigOption(
                name = "Warn Minutes Before Empty",
                desc = "Send a Discord warning this many minutes before the composter runs empty (0 = no warning)"
        )
        @ConfigEditorSlider(minValue = 0, maxValue = 120, minStep = 1)
        public int warnMinutes = 10;
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
                name = "User ID for Pings",
                desc = "Discord user ID to ping for New Year's Celebration reminders (leave empty to disable). Must be numeric."
        )
        @ConfigEditorText
        public String discordUserId = "";

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
        @ConfigOption(name = "Fuel Expired Alert Speed", desc = "How often to alert when all of a minion's fuel runs out, until the one set in Repeat Fuel Alert Until (by default, until you empty your sacks). A fuel-day ending with fuel still left sends a single reminder instead\nOFF: No alerts | FAST: Immediately, then every 5 min\nNORMAL: Immediately, then every 30 min | SLOW: Immediately, then every 3 hours")
        @ConfigEditorDropdown(values = {"OFF", "FAST", "NORMAL", "SLOW"})
        public int fuelExpiredSpeed = 2; // NORMAL

        @Expose
        @ConfigOption(
                name = "Fuel Alert Only When All Fuel Expires",
                desc = "Send the fuel alert when the whole stack of fuel has run out, instead of a reminder " +
                        "each time a single fuel-day runs out (which says how much fuel is left, so you can " +
                        "collect it). Fuel added before that postpones the alert to when all of " +
                        "it has actually run out. This only changes when the " +
                        "alert is first sent; whether it then repeats is set by Repeat Fuel Alert Until."
        )
        @ConfigEditorBoolean
        public boolean fuelAlertOnlyWhenAllExpired = false;

        @Expose
        @ConfigOption(
                name = "Repeat Fuel Alert Until",
                desc = "What stops the fuel alert repeating at the speed above.\n" +
                        "Until Sacks Emptied: until you take Crude Gabagool out of your sacks, as AFKing past " +
                        "full sacks gains nothing.\n" +
                        "Until Minion GUI Opened: until you open a minion's menu, for when the gabagool goes " +
                        "into your minions rather than your sacks and you only need to get back and refuel.\n" +
                        "Don't Repeat: send it once only.\n" +
                        "The reminder sent when a fuel-day ends with fuel still left is always sent once only."
        )
        @ConfigEditorDropdown(values = {"Until Sacks Emptied", "Until Minion GUI Opened", "Don't Repeat"})
        public int fuelAlertRepeatMode = 0; // Until Sacks Emptied
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
            desc = "Space-separated list of component names to display (e.g. catacombs_level_requirement id color). Start a name with pet_ to read from a pet's petInfo (e.g. pet_candyUsed pet_exp)"
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
            desc = "When enabled, caches the Hypixel SkyBlock resource pack locally and keeps it active everywhere (even outside Hypixel). Hides the pack download prompt and only re-downloads if the pack changes; when Hypixel releases a newer pack, it is downloaded and replaces the old copy in your enabled packs."
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

        @ConfigAccordionId(id = 3)
        @Expose
        @ConfigOption(
            name = "Render Edge Chunks",
            desc = "Vanilla (and Sodium) only draw a chunk once every chunk around it has loaded, so chunks at the edge of what the server sends (their blocks exist and can be hit, but are never drawn) stay invisible. When enabled, they are drawn anyway, with the missing neighbours treated as empty air. Edge chunks the server sent no lighting for will look dark."
        )
        @ConfigEditorBoolean
        public boolean renderEdgeChunks = false;
    }

    /**
     * The profit commands: /it agingItems, /it cookieProfit, /it huntrapProfit and /it infernoProfits. Each has a
     * run button at the top and an accordion of its settings below. These used to be in Misc (and, for Inferno,
     * Fuel Cycles); {@link ProfitConfigMigration} carries old values over under the same field names.
     */
    public static class ProfitCalculators {
        private static void runCommand(String command) {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client.player != null) {
                client.player.connection.sendCommand(command);
            }
        }

        @ConfigOption(name = "Run Aging Items", desc = "Fetch Bazaar prices and compare profit/hour for all configured aging items. Also /it agingItems")
        @ConfigEditorButton(buttonText = " Compare Now ")
        public Runnable agingItemsButton = new Runnable() {
            @Override
            public void run() {
                runCommand("it agingItems");
            }
        };

        @ConfigOption(name = "Run Cookie Profit", desc = "Fetch Bazaar prices and compare profit per bit for cookie items. Also /it cookieProfit")
        @ConfigEditorButton(buttonText = " Calculate ")
        public Runnable cookieProfitButton = new Runnable() {
            @Override
            public void run() {
                runCommand("it cookieProfit");
            }
        };

        @ConfigOption(name = "Run Huntrap Profit", desc = "Fetch Bazaar prices and rank Huntrap locations by profit per hour. Also /it huntrapProfit")
        @ConfigEditorButton(buttonText = " Calculate ")
        public Runnable huntrapProfitButton = new Runnable() {
            @Override
            public void run() {
                runCommand("it huntrapProfit");
            }
        };

        @ConfigOption(name = "Run Inferno Profits", desc = "Fetch Bazaar prices and show what the minions of your current fuel cohort earn in a fuel day on each fuel, using the Inferno Profits settings below. Also /it infernoProfits")
        @ConfigEditorButton(buttonText = " Calculate ")
        public Runnable infernoProfitsButton = new Runnable() {
            @Override
            public void run() {
                runCommand("it infernoProfits");
            }
        };

        // ── Aging Items ──────────────────────────────────────────────────────

        @ConfigEditorAccordion(id = 4)
        @ConfigOption(name = "Aging Items", desc = "Compare profit per hour for levelling items (Bottle of Jyrre, Dark Cacao Truffle, etc.)")
        public boolean agingItemsAccordion = false;

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

        // ── Huntrap Profit ───────────────────────────────────────────────────

        @ConfigEditorAccordion(id = 7)
        @ConfigOption(name = "Huntrap Profit", desc = "Rank Huntrap locations by estimated shard profit per hour (5 Huntraps assumed)")
        public boolean huntrapProfitAccordion = false;

        @ConfigAccordionId(id = 7)
        @ConfigOption(name = "Edit Huntrap Data", desc = "Open the Huntrap data editor: change which shards each location can catch and their weights, disable, add or remove locations and shards, and restore the built-in data. Also /it huntrapEditor")
        @ConfigEditorButton(buttonText = " Edit Data ")
        public Runnable huntrapEditorButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new HuntrapEditorScreen()));
            }
        };

        @ConfigAccordionId(id = 7)
        @Expose
        @ConfigOption(name = "Huntrap Type", desc = "The Huntrap you place. Higher tiers shorten the time per catch (Small 0%, Medium 10%, Large 20%, Greater 35%, Astral 50%)")
        @ConfigEditorDropdown(values = {"Small Huntrap", "Medium Huntrap", "Large Huntrap", "Greater Huntrap", "Astral Huntrap"})
        public int huntrapType = 4; // Astral Huntrap

        @ConfigAccordionId(id = 7)
        @Expose
        @ConfigOption(name = "Hunter Fortune", desc = "Your Hunter Fortune. Every 100 gives one extra shard per catch on average (e.g. 150 = 2.5 shards)")
        @ConfigEditorText
        public String huntrapHunterFortune = "0";

        @ConfigAccordionId(id = 7)
        @Expose
        @ConfigOption(name = "Desert Temple Buff", desc = "Enable while the Desert Temple buff is active (Huntraps catch 25% faster)")
        @ConfigEditorBoolean
        public boolean huntrapDesertTemple = false;

        @ConfigAccordionId(id = 7)
        @Expose
        @ConfigOption(name = "Forest Trap Level", desc = "Forest Trap attribute level (+1% catch speed per level on Forest shards)")
        @ConfigEditorSlider(minValue = 0, maxValue = 10, minStep = 1)
        public int huntrapForestTrapLevel = 0;

        @ConfigAccordionId(id = 7)
        @Expose
        @ConfigOption(name = "Combat Trap Level", desc = "Combat Trap attribute level (+1% catch speed per level on Combat shards)")
        @ConfigEditorSlider(minValue = 0, maxValue = 10, minStep = 1)
        public int huntrapCombatTrapLevel = 0;

        @ConfigAccordionId(id = 7)
        @Expose
        @ConfigOption(name = "Use Sell Offer", desc = "When enabled, values shards at their sell offer (sell order) price instead of insta-sell")
        @ConfigEditorBoolean
        public boolean huntrapUseSellOffer = false;

        @ConfigAccordionId(id = 7)
        @Expose
        @ConfigOption(name = "Show Top X Locations", desc = "Only show the top X most profitable locations (0 = show all)")
        @ConfigEditorSlider(minValue = 0, maxValue = 45, minStep = 1)
        public int huntrapTopCount = 5;

        // ── Inferno Profits ──────────────────────────────────────────────────

        @ConfigEditorAccordion(id = 8)
        @ConfigOption(
                name = "Inferno Profits",
                desc = "The Inferno minion estimate behind /it infernoProfits and the Fuel Day Tracker's estimated " +
                        "profit (the Profit: Estimated button there). It estimates what the minions return each fuel " +
                        "day from their speed (one spawn + one kill = 2 actions per harvest, 1 Crude Gabagool each). " +
                        "Hypergolic fuel also gets its rare drops (Chili Pepper, Inferno Vertex, Reaper Pepper, " +
                        "Inferno Apex, Gabagool the Fish, Hypergolic Ionized Ceramics). Valued at the Bazaar sell " +
                        "offer taken when the fuel day ended (or the next time the game is open, if it was closed " +
                        "then). Gabagool the Fish isn't on the Bazaar and is worth the price set below (5M by " +
                        "default). Capsaicin Eyedrops, if on, are assumed used every day and their crafting cost " +
                        "is subtracted (Water Hydra Head price set below, 1.5M by default). For minions you aren't " +
                        "AFKing next to: item drops only, no mob-kill coins. Each day uses the grade of fuel that " +
                        "was placed for it, and a day that a time warp shortened still counts its full 24 hours. " +
                        "Whether a data set shows the estimated or the real profit is set per data set, with the " +
                        "'Profit' button in the Fuel Day Tracker GUI. " +
                        "Rising Celsius (+18% speed per Inferno minion placed, up to 10 minions) is always " +
                        "included, counting each day's minion count; the toggles below add to it."
        )
        public boolean estimateAccordion = false;

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Minion Tier",
                desc = "Tier of your Inferno minions (I to XI). Base time between actions: I 1013s, II 982s, " +
                        "III 950s, IV 919s, V 886s, VI 855s, VII 823s, VIII 792s, IX 760s, X 728s, XI 697s."
        )
        @ConfigEditorSlider(minValue = 1, maxValue = 11, minStep = 1)
        public int estimateMinionTier = 11;

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Fuel Grade (if unknown)",
                desc = "Each day's estimate uses the grade of fuel that was placed for it, taken from the colour of " +
                        "the fueling message (blue Rare, purple Epic, gold Legendary). This grade is only used for a " +
                        "day where that wasn't seen (days added by hand, or recorded before this was tracked). " +
                        "/it infernoProfits always shows all three. " +
                        "Fuel multiplies the speed AFTER the bonuses below are added: " +
                        "Fuel Gabagool (Rare) x11, Heavy Gabagool (Epic) x16, Hypergolic Gabagool (Legendary) x21. " +
                        "Only Hypergolic also has the rare drops (1 in 136 Chili Pepper, 1 in 5,950 Inferno Vertex, " +
                        "1 in 458,182 Reaper Pepper, 1 in 1,309,091 Inferno Apex, doubled for tier X and XI, per " +
                        "harvest, and 1 in 3,927,273 Gabagool the Fish, valued at the Gabagool the Fish price " +
                        "set below as it isn't on the Bazaar; 1 Hypergolic Ionized Ceramics per minion per fuel day). " +
                        "The fuel cost this tracker uses is for the Epic one."
        )
        @ConfigEditorDropdown(values = {"Fuel Gabagool (Rare, x11)", "Heavy Gabagool (Epic, x16)", "Hypergolic Gabagool (Legendary, x21)"})
        public int estimateFuelGrade = 1; // Heavy Gabagool (Epic)

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Capsaicin Eyedrops",
                desc = "Capsaicin Eyedrops in the Hypergolic fuel: +30% (x1.3) chance on each rare drop per " +
                        "harvest (Chili Pepper, Inferno Vertex, Reaper Pepper, Inferno Apex, Gabagool the Fish). " +
                        "No speed change, and nothing (no effect and no cost) for the other fuels. Assumed used on " +
                        "every minion every day. One craft (4 Chili Pepper, 128 Exportable Carrots, 128 Enchanted " +
                        "Carrot, 1 Water Hydra Head) makes 3 eyedrops, so each day crafts enough for the minions, " +
                        "rounded up: 31 minions need 11 crafts = 33 eyedrops. That crafting cost is subtracted " +
                        "from the estimated profit; the ingredients are priced at the Bazaar sell offer."
        )
        @ConfigEditorBoolean
        public boolean estimateEyedrops = false;

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Gabagool the Fish Price",
                desc = "What one Gabagool the Fish (a 1 in 3,927,273 Hypergolic drop per harvest) is worth, as it " +
                        "isn't on the Bazaar. Accepts 5000000, 5,000,000, 5m, 750k or 2b. Blank or invalid: 5m."
        )
        @ConfigEditorText
        public String estimateFishPriceString = "5m";

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Water Hydra Head Price",
                desc = "What one Water Hydra Head is worth, as it isn't on the Bazaar. One goes into each " +
                        "Capsaicin Eyedrops craft. Accepts 1500000, 1,500,000, 1.5m, 750k or 2b. Blank or " +
                        "invalid: 1.5m."
        )
        @ConfigEditorText
        public String estimateHydraHeadPriceString = "1.5m";

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Flycatcher",
                desc = "+20% minion speed. Needs Spider Slayer level 6 to use. Speed bonuses are added together. " +
                        "A minion only has 2 upgrade slots; nothing stops you picking more than fit."
        )
        @ConfigEditorBoolean
        public boolean estimateFlycatcher = false;

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Second Flycatcher",
                desc = "A second Flycatcher in the other slot: another +20% minion speed (so +40% with both). " +
                        "Together with a Super Compactor 3000 that is 3 upgrades, which a minion can't hold in " +
                        "game, but it is allowed here."
        )
        @ConfigEditorBoolean
        public boolean estimateSecondFlycatcher = false;

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Beacon",
                desc = "A Beacon V next to the minions, powered by: Power Crystal +10% minion speed, " +
                        "Scorched Power Crystal +11% minion speed. The beacon tier is assumed to be V."
        )
        @ConfigEditorDropdown(values = {"None", "Power Crystal (+10%)", "Scorched Power Crystal (+11%)"})
        public int estimateBeacon = 0;

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Postcard",
                desc = "+5% minion speed."
        )
        @ConfigEditorBoolean
        public boolean estimatePostcard = false;

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Infusion",
                desc = "Mithril Infusion: +10% minion speed."
        )
        @ConfigEditorBoolean
        public boolean estimateInfusion = false;

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Free Will",
                desc = "+10% minion speed. Assumes the minion stays loyal."
        )
        @ConfigEditorBoolean
        public boolean estimateFreeWill = false;

        @ConfigAccordionId(id = 8)
        @Expose
        @ConfigOption(
                name = "Super Compactor",
                desc = "Super Compactor 3000: no speed change. Compacts 192 Crude Gabagool into 1 Very Crude " +
                        "Gabagool, so the loot is valued as Very Crude Gabagool at its sell offer, with " +
                        "decimals for a partial one."
        )
        @ConfigEditorBoolean
        public boolean estimateSuperCompactor = false;
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
        @ConfigOption(name = "Open Highlights", desc = "Click to open the highlights GUI for managing blocks and any registered entity type, such as item frames or armor stands, optionally filtered by name (entity:armor_stand[name=TEXT]). You can also use /it highlights")
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

        @Expose
        @ConfigOption(name = "Default Partial Match", desc = "Default for new entries: match IDs and entity names by \"contains\" instead of exactly. \"d\" highlights every block with a d in its ID, entity:ca every entity type with ca in its ID, and entity names only need to contain the text. Can be changed per entry in the highlights GUI")
        @ConfigEditorBoolean
        public boolean partialMatch = false;

        @Expose
        @ConfigOption(name = "Smooth Entity Highlights", desc = "Move entity highlight boxes smoothly with the entity every frame, instead of only once per game tick. Turn this off if entity highlights ever look wrong or lag behind the entities")
        @ConfigEditorBoolean
        public boolean smoothEntities = true;

        @Expose
        @ConfigOption(name = "Default Min Size Box", desc = "Default for new entries: give entities that have no hitbox (marker armor stands, markers, display entities) a 1 block highlight box so they can be seen. Can be changed per entry in the highlights GUI")
        @ConfigEditorBoolean
        public boolean minSizeBox = false;
    }

    public static class GreenhouseBlueprintSettings {
        @ConfigOption(name = "Open Blueprint List", desc = "Open the greenhouse blueprint list to select, delete and share your layouts. Each layout has a button that copies an import code for it, and the button at the top imports a layout from a code on your clipboard. Also /it blueprint gui")
        @ConfigEditorButton(buttonText = " Open Blueprint List ")
        public Runnable openBlueprintListButton = new Runnable() {
            @Override
            public void run() {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                client.execute(() -> client.setScreen(new GreenhouseBlueprintScreen()));
            }
        };

        @Expose
        @ConfigOption(name = "Enabled", desc = "Draw the selected greenhouse blueprint over the greenhouse plot you are standing in. Make one by planting a greenhouse, then /it blueprint copy to preview the area and /it blueprint save <name> to keep it. /it blueprint toggle switches this on and off")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Only Show In Garden", desc = "Only show the blueprint while the tab list reports that you are in the Garden. Turn this off if the overlay stays hidden in the Garden")
        @ConfigEditorBoolean
        public boolean onlyInGarden = true;

        @Expose
        @ConfigOption(name = "Show In Any Plot", desc = "Show or copy the blueprint in whatever garden plot you are standing in, without checking that it has a greenhouse. A greenhouse plot is recognised by the Carpenter NPC; turn this on if your greenhouse isn't picked up (see /it blueprint status)")
        @ConfigEditorBoolean
        public boolean ignoreGreenhouseCheck = false;

        @Expose
        @ConfigOption(name = "Hide Completed", desc = "Stop drawing a ghost once the right crop has been planted in its spot, so only what is left to plant stays on screen")
        @ConfigEditorBoolean
        public boolean hideCompleted = true;

        @Expose
        @ConfigOption(name = "Ghost Block Models", desc = "Draw the ghosts as see-through copies of the real blocks (crops and soil), so they look like what you have to place. Turn this off to use plain coloured boxes instead, for example if the ghosts ever look wrong. Armor stands have their own setting")
        @ConfigEditorBoolean
        public boolean ghostBlocks = true;

        @Expose
        @ConfigOption(name = "Ghost Armor Stands", desc = "Draw the layout's armor stands as see-through copies of the real ones, with the armor and items they wear or hold and their skull textures, so a custom crop looks like what you have to build. Turn this off to use plain coloured boxes instead, for example if the ghosts ever look wrong. Layouts copied before this existed only have the stand's head name, so they stay boxes until you copy them again")
        @ConfigEditorBoolean
        public boolean ghostStands = true;

        @Expose
        @ConfigOption(name = "Outline Incorrect", desc = "Draw a red outline around every block (air included), soil block and armor stand that the layout wants and that isn't right, and around anything in the area that the layout doesn't have. The outline is also drawn over a ghost, so you can still see that a different block than the planned one is in its space")
        @ConfigEditorBoolean
        public boolean incorrectOutline = true;

        @Expose
        @ConfigOption(name = "Outline Thickness", desc = "How thick the red outline's lines are, in hundredths of a block (5 = a twentieth of a block)")
        @ConfigEditorSlider(minValue = 1, maxValue = 25, minStep = 1)
        public int outlineThickness = 5;

        @Expose
        @ConfigOption(name = "Outline Opacity", desc = "How solid the red outline is, in percent")
        @ConfigEditorSlider(minValue = 10, maxValue = 100, minStep = 5)
        public int outlineOpacity = 95;

        @Expose
        @ConfigOption(name = "Highlight Crop In Reach", desc = "Outline the crop head (an armor stand) you are aiming at while it is in hit range. A head has no block border to show it can be broken, like a block has. Works in any greenhouse, even with the layout off")
        @ConfigEditorBoolean
        public boolean highlightHeadInReach = true;

        @Expose
        @ConfigOption(name = "Check Floor", desc = "Also copy and compare the soil under the crops: a ghost block shows where a soil block is missing or the wrong type, and the legend lists the soil the layout needs. Turn this off to ignore the floor. Layouts always store the floor when you copy them")
        @ConfigEditorBoolean
        public boolean showFloor = true;

        @Expose
        @ConfigOption(name = "Crops Only On Correct Floor", desc = "Only show a crop's preview (ghost, outline and name) once the soil under it is what the layout wants, so you can lay the floor first and then the crops. A crop that is bigger than one block waits for all the soil under it. Needs Check Floor, and crops over soil the layout has no block for are always shown")
        @ConfigEditorBoolean
        public boolean cropsOnlyOnCorrectFloor = false;

        @Expose
        @ConfigOption(name = "Ghost Opacity", desc = "How solid the ghosts are, in percent")
        @ConfigEditorSlider(minValue = 5, maxValue = 100, minStep = 5)
        public int opacity = 40;

        @Expose
        @ConfigOption(name = "Show Through Walls", desc = "Draw the ghost boxes and red outlines on top of everything, including blocks and crops in front of them. Ghost block models and armor stands are always hidden behind blocks in front of them")
        @ConfigEditorBoolean
        public boolean throughWalls = false;

        @Expose
        @ConfigOption(name = "Show Crop Names", desc = "Write the crop's name above its ghost while you are close to it (for a custom crop, its block and armor stand)")
        @ConfigEditorBoolean
        public boolean showLabels = true;

        @Expose
        @ConfigOption(name = "Crop Name Range", desc = "How close (in blocks) you have to be to a ghost to see its name")
        @ConfigEditorSlider(minValue = 2, maxValue = 20, minStep = 1)
        public int labelRange = 6;

        @Expose
        @ConfigOption(name = "Show Legend", desc = "Show a list of the layout's crops and soil with how many of each are right")
        @ConfigEditorBoolean
        public boolean showLegend = true;

        @Expose
        @ConfigOption(name = "Hide Legend When Complete", desc = "Hide the legend while the greenhouse matches the saved layout perfectly: every crop, armor stand and soil block is right and there is nothing else in the area. It comes back as soon as something is off")
        @ConfigEditorBoolean
        public boolean hideLegendWhenComplete = true;

        @Expose
        @ConfigOption(name = "Legend X", desc = "Horizontal legend position as a percentage of the screen (0 = left, 100 = right)")
        @ConfigEditorSlider(minValue = 0, maxValue = 100, minStep = 1)
        public int legendX = 0;

        @Expose
        @ConfigOption(name = "Legend Y", desc = "Vertical legend position as a percentage of the screen (0 = top, 100 = bottom)")
        @ConfigEditorSlider(minValue = 0, maxValue = 100, minStep = 1)
        public int legendY = 30;
    }

    public static class Waila {
        @Expose
        @ConfigOption(name = "WAILA Keybind", desc = "Press this key while in the world (no screen open) to run /it waila on whatever you are aiming at. Unbound by default; pick a key that isn't used by something else. Press Escape while binding to clear it")
        @ConfigEditorKeybind(defaultKey = -1)
        public int keybind = -1;

        @Expose
        @ConfigOption(name = "Max Range", desc = "How far (in blocks) WAILA looks along the line you are aiming. Blocks and entities beyond your normal reach are still inspected as long as they are the first thing along that line. Only blocks in loaded chunks and entities your client knows about can be found")
        @ConfigEditorSlider(minValue = 5, maxValue = 256, minStep = 1)
        public int range = 128;
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

        @Expose
        @ConfigOption(
                name = "Copy Chat Message Keybind",
                desc = "When enabled, pressing the configured key while hovering a line of the chat mirror in the " +
                        "player list copies that message's full text and hover text to your clipboard (handy for " +
                        "reporting chat messages). Shift + right-click on a line always works too"
        )
        @ConfigEditorBoolean
        public boolean copyChatKeybindEnabled = true;

        @Expose
        @ConfigOption(
                name = "Copy Chat Message Key",
                desc = "Key used to copy the hovered chat mirror line in the player list. " +
                        "Requires Copy Chat Message Keybind to be enabled"
        )
        @ConfigEditorKeybind(defaultKey = 67) // GLFW_KEY_C
        public int copyChatKeybind = 67;
    }
}
