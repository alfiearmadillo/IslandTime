# IslandTime

A Minecraft Fabric mod for Hypixel SkyBlock that tracks island time, minion fuel/profit per fuel day, item stats, and more. Includes Discord alerts and a configuration GUI.

## Features

- **Island Time Tracking** — Tracks time spent on your private island with daily history
- **Fuel Day Tracker** — Tracks minion fuel and profit per real 24h fuel-day via a dedicated GUI (`/it fueldays` or `/it fueltime`). Placing a stack of fuel across a batch of minions (e.g. 4 each) creates that many entries immediately — one currently ticking down, the rest future/queued days already carrying their share of the auto-detected minion count and estimated fuel cost — bucketed purely by elapsed time since the batch started, not by live calendar date, so a day that happens to cross midnight is never split across two records. Each fuel-day gets its own "fuel day up" Discord alert the moment IT runs out (not just once, when the entire queued batch finally finishes), so the next queued day is already ticking by the time you're pinged about the one that just ended. Each row/entry is titled with its day's actual start and end time (`DD/MM/YY HH:mm - DD/MM/YY HH:mm`), and a "Total Profit" widget in the top-right corner sums the final profit of every tracked day (supports up to the billions, e.g. `1.234B`). Each physical minion is identified by its armor stand's position and full equipped outfit (not its entity UUID, which resets on server reload), so fueling many minions in one sitting is tracked as that many distinct minions instead of collapsing into one. Supports placing full stacks of fuel at once (each unit queues an additional 24h fuel-day), auto-estimates each day's fuel cost from live Bazaar prices (the full Epic/medium Inferno Minion Fuel recipe cost: 6x the cheapest of the 5 distillates + 2x Inferno Fuel Block + 1x Heavy Gabagool, with a manual override) — the unit price is snapshotted once per fuel cohort/batch rather than re-checked live on every fueling, so re-fueling the same batch later never changes an already-recorded day's cost — tracks mob-kill coins/day from a configurable 100%-AFK baseline scaled by that day's full-day AFK% (so coins accumulate steadily over the day, while the displayed AFK% itself is live — showing progress against time elapsed so far while a day is still in progress, rather than instantly looking tiny), and auto-tracks Crude Gabagool collected via Hypixel's in-game "[Sacks]" chat messages — including while a message-hiding mod like SkyHanni's "Sack Change Hider" is enabled, since the packet is read directly rather than from the visible chat log — (with a manual override, and a manually-entered sell price, for full profit-per-day tracking). The GUI's live values refresh once per second rather than every frame. Rows are color-graded red→yellow→green by AFK% and every field — including a manual minion-count override for when lag desyncs auto-detection — is directly editable, with minion-count, gabagool-amount, and fuel-cost override fields all accepting either an absolute value or a `+`/`-` prefixed delta (e.g. `+100000` or `-20000`) to adjust the auto-tracked value instead of replacing it. Future/queued days that haven't started yet show a condensed row (just minion count and fuel cost — everything else genuinely hasn't happened yet) and fill in normally once their cycle begins
- **Item Stat Tracking** — Monitors a tracked item's `stats_book` value and alerts if it stops increasing or goes missing
- **Discord Webhook Alerts** — Sends notifications for island absence, tracked item issues, disconnects, and when a minion's queued fuel-days run out (repeats until you empty your sacks, since sack storage caps mean AFKing further gains nothing)
- **Chat Relay** — Bridges island chat to/from Discord via a companion bot (outbound webhook + inbound localhost polling)
- **Backpack Preview** — Hover over a backpack to see its contents in an overlay; press Shift to inspect
- **Favourite GUI** — Save and view container snapshots (e.g. minion layouts) as read-only overlays
- **Island Player Tracking** — Detects when visitors leave your island with optional chat/Discord notifications
- **Show Hidden Tooltips** — Reveals tooltips on items that have `hide_tooltip` set (e.g. GUI filler panes)
- **PPG Display** — Shows Price Per Gabagool in the Very Crude Gabagool sell offer menu
- **Ghost Command** — Client-side block removal with `/it ghost`
- **Keep Resource Pack** — Caches the Hypixel SkyBlock resource pack locally and keeps it active everywhere (even outside Hypixel), skipping re-download if unchanged
- **Component Display** — Shows specified item components (e.g. `id`, `color`) at the bottom of tooltips in dark gray
- **Player List** — Scrollable GUI (`/it players`) to manage a list of players with quick party/invite buttons, editable notes, and head skin display. Also mirrors recent chat to the left of the list; shift + right-click a mirrored line to copy its full raw component structure (text and hover text, including every sibling) to the clipboard — a diagnostic aid for reporting chat-parsing bugs
- **Sign Helpers** — Adds paste buttons to the sign edit screen with configurable pre-set text per line
- **WAILA (What Am I Looking At)** — Inspect any targeted block with `/it waila` to see block ID, position, state properties, tile entity data, skull textures, and SkyBlock custom IDs
- **Block Highlight** — Multi-entry block highlight system with a dedicated GUI (`/it highlights`). Each entry has its own target (block ID, `sb:` SkyBlock ID, or `skull:` texture hash), color, range, intensity, through-walls toggle, and optional area filter. Quickly add highlights from WAILA results or via `/it highlight add`
- **Critter Safari Tracker** — On-screen widget listing all 37 unique Critter Safari critters (grouped by Forest/Cavern/Icy/Haunted biome) with caught/uncaught state and a `caught/total` counter, so you can see at a glance which critters you still need this run. Catches are read from the `CAPTURE!` and `LOOT SHARE!` chat messages (including `SPARKLING` catches and the Hideyho "You found" variant), and the tracker resets when the Safari Manager starts a new run — both the party line (`[NPC] Safari Manager: Looks good to me. Have fun out there!`) and the solo line (`[NPC] Safari Manager: I already saw your ticket, so you're free to go.`). Only rendered while you are inside the Safari, using the same tab-list area detection as Block Highlight (matched by keyword, so the shortened `Safari` area name and the biome sub-areas all count; this gate can be turned off if detection ever fails). The move screen also shows the area name currently detected, so mismatches are easy to spot. Toggle it and drag it anywhere on screen from the Critter Safari config category or `/it critters`. Also includes a **Sparkling Alert**: when a `SPARKLING` critter (the variant that awards a bonus Rainbow Feather) spawns near you, an XP pling plays once and a through-walls tracer line is drawn from you to it. The alert, its sound, the tracer and the tracer color are configurable under the Critter Safari category
- **Copy Skin** — Copy the skin texture of a targeted player head block to clipboard with `/it copyskin`, or copy from a skull item in any inventory/GUI by pressing a configurable keybind (default: `V`). Preserves transparency. The keybind and toggle are configurable in the MoulConfig GUI under Misc
- **Aging Items** — Compare profit per hour for all levelling items (Bottle of Jyrre, Dark Cacao Truffle, Discrite, Moby-Duck, Not Very Moldy Bread, Rosewater Flask) using live Bazaar prices. Configure time-bag and inventory slot counts for estimated hourly income. Add or remove custom item pairs with `/it agingItems add/remove`
- **Cookie Profit** — Compare profit per bit from spending Booster Cookie bits on Kismet Feathers, Magma Buckets, and Plasma Buckets using live Bazaar prices. Accounts for material costs (enchanted lava buckets) on crafted items. Configure your bits-per-cookie in the MoulConfig GUI
- **MoulConfig GUI** — All settings accessible from a single GUI opened with `/it` (alias: `/islandtime`)

## Installation

1. Requires **Minecraft 26.1** with **Fabric Loader** and **Fabric API**
2. Download the latest release from the [Releases](../../releases) page
3. Place the JAR in your `.minecraft/mods` folder

## Commands

All commands can also be configured from the GUI (`/islandtime`).

| Command | Description |
|---------|-------------|
| `/it` | Open the config GUI |
| `/it recent` | Show last 7 days of island time |
| `/it trackedIslandTime <date>` | Show a specific date (DD-MM-YY) |
| `/it fueltime` | Open the fuel day tracker GUI (per-fuel-day fuel/profit stats). Same as `/it fueldays` |
| `/it fueltime <YYYY-MM-DD>` | Show a quick chat summary for a specific day |
| `/it fueltime debug` | Inject a fake day record and attempt a save (diagnostics) |
| `/it trackitem set\|clear` | Track/untrack the held item |
| `/it webhook <url\|clear>` | Set or clear the Discord webhook |
| `/it discordping <userid\|clear>` | Set or clear Discord ping user ID |
| `/it trackedalertspeed <speed>` | Set tracked item alert speed (off/fast/normal/slow) |
| `/it islandalertspeed <speed>` | Set island alert speed |
| `/it disconnectalertspeed <speed>` | Set disconnect alert speed |
| Fuel Expired Alert Speed | Configurable in the MoulConfig GUI under Alert Speeds (pings repeat until you empty your sacks) |
| `/it trackislandtime <on\|off>` | Enable/disable island time tracking |
| `/it trackfuelcycles <on\|off>` | Enable/disable fuel cycle tracking |
| `/it minionfuelmessages <on\|off>` | Toggle minion fuel chat messages |
| `/it islandleavemessages <on\|off>` | Toggle island leave chat messages |
| `/it favouritegui save\|view\|delete <name>` | Manage saved container snapshots |
| `/it favouritegui toggle` | Enable/disable container snapshot tracking |
| `/it chatrelay` | View/configure chat relay settings |
| `/it showtooltips` | Toggle hidden tooltip visibility |
| `/it waila` | Inspect the targeted block (ID, position, state, NBT, skull textures, SkyBlock ID) |
| `/it copyskin` | Copy the skin texture of the targeted player head to clipboard (reads from local cache) |
| `/it highlight` or `/it highlights` | Open the Block Highlights GUI |
| `/it highlight add <target> [color]` | Add a highlight entry (target: block ID, `sb:ID`, or `skull:HASH`) |
| `/it highlight remove <target>` | Remove a highlight entry by target |
| `/it critters` | Open the Critter Safari widget mover (drag the widget to reposition it) |
| `/it players` | Open the player list GUI (add/edit/delete/party/invite) |
| `/it fueldays` | Open the fuel day tracker GUI (same as `/it fueltime`) |
| `/it ghost` | Client-side block removal tool |
| `/it agingItems` | Compare profit/hour for all configured aging items using Bazaar prices |
| `/it agingItems add <agingId> <agedId>` | Add a custom aging item pair by SkyBlock IDs |
| `/it agingItems remove <agingId>` | Remove an aging item pair |
| `/it agingItems list` | List all configured aging item pairs |
| `/it cookieProfit` | Compare profit/bit for spending cookie bits (Kismet, Magma Bucket, Plasma Bucket) |
| `/it help [page]` | View help pages |

`/islandtime` is an alias for `/it`. All commands are available under both prefixes.

## Configuration

Settings are saved in the `config` folder as `islandtime*.txt` files (and `fuel_days.json` for the fuel day tracker). Never share these publicly — they contain your Discord webhook URL.

The Fuel Day Tracker has a "Use Test Data File" toggle (in the Fuel Cycles config category) that redirects all fuel/profit tracking to a separate `fuel_days_test.json` file instead of your real `fuel_days.json`, so you can safely experiment without risking your actual tracked history. Toggling it reloads the Fuel Day Tracker GUI's data from whichever file is now active.

