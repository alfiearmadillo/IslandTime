# IslandTime

A Minecraft Fabric mod for Hypixel SkyBlock that tracks island time, minion fuel/profit per day, item stats, and more. Includes Discord alerts and a configuration GUI.

## Features

- **Island Time Tracking** — Tracks time spent on your private island with daily history
- **Fuel Day Tracker** — Tracks minion fuel and profit per calendar day via a dedicated GUI (`/it fueldays` or `/it fueltime`). Supports placing full stacks of fuel at once (each unit queues an additional 24h burn period), auto-estimates fuel cost from live Bazaar prices (cheapest of the 5 Epic Inferno Minion Fuel distillates), tracks mob-kill coins/day from a configurable 100%-AFK baseline scaled by that day's AFK%, and auto-tracks Crude Gabagool collected via Hypixel's in-game "[Sacks]" chat messages (with a manual override, and a manually-entered sell price, for full profit-per-day tracking). Rows are color-graded red→yellow→green by AFK% and every field — including a manual minion-count override for when lag desyncs auto-detection — is directly editable
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
- **Player List** — Scrollable GUI (`/it players`) to manage a list of players with quick party/invite buttons, editable notes, and head skin display
- **Sign Helpers** — Adds paste buttons to the sign edit screen with configurable pre-set text per line
- **WAILA (What Am I Looking At)** — Inspect any targeted block with `/it waila` to see block ID, position, state properties, tile entity data, skull textures, and SkyBlock custom IDs
- **Block Highlight** — Multi-entry block highlight system with a dedicated GUI (`/it highlights`). Each entry has its own target (block ID, `sb:` SkyBlock ID, or `skull:` texture hash), color, range, intensity, through-walls toggle, and optional area filter. Quickly add highlights from WAILA results or via `/it highlight add`
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
| `/it fueltime` | Open the fuel day tracker GUI (day-by-day fuel/profit stats). Same as `/it fueldays` |
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

