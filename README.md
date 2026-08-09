# IslandTime

A Minecraft Fabric mod for Hypixel SkyBlock that tracks island time, fuel cycles, item stats, and more. Includes Discord alerts and a configuration GUI.

## Features

- **Island Time Tracking** — Tracks time spent on your private island with daily history
- **Fuel Cycle Tracking** — Tracks minion fuel cycles (start, end, duration, minions fueled, active time)
- **Item Stat Tracking** — Monitors a tracked item's `stats_book` value and alerts if it stops increasing or goes missing
- **Discord Webhook Alerts** — Sends notifications for island absence, tracked item issues, and disconnects
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
- **MoulConfig GUI** — All settings accessible from a single GUI opened with `/islandtime`

## Installation

1. Requires **Minecraft 26.1** with **Fabric Loader** and **Fabric API**
2. Download the latest release from the [Releases](../../releases) page
3. Place the JAR in your `.minecraft/mods` folder

## Commands

All commands can also be configured from the GUI (`/islandtime`).

| Command | Description |
|---------|-------------|
| `/islandtime` | Open the config GUI |
| `/islandtime recent` | Show last 7 days of island time |
| `/islandtime <date>` | Show a specific date (DD-MM-YY) |
| `/fueltime [cycle]` | Show fuel cycle stats (optionally a specific cycle number) |
| `/trackitem set\|clear` | Track/untrack the held item |
| `/webhook <url\|clear>` | Set or clear the Discord webhook |
| `/discordping <userid\|clear>` | Set or clear Discord ping user ID |
| `/trackedalertspeed <speed>` | Set tracked item alert speed (off/fast/normal/slow) |
| `/islandalertspeed <speed>` | Set island alert speed |
| `/disconnectalertspeed <speed>` | Set disconnect alert speed |
| `/trackislandtime <on\|off>` | Enable/disable island time tracking |
| `/trackfuelcycles <on\|off>` | Enable/disable fuel cycle tracking |
| `/minionfuelmessages <on\|off>` | Toggle minion fuel chat messages |
| `/islandleavemessages <on\|off>` | Toggle island leave chat messages |
| `/favouritegui save\|view\|delete <name>` | Manage saved container snapshots |
| `/favouritegui toggle` | Enable/disable container snapshot tracking |
| `/chatrelay` | View/configure chat relay settings |
| `/showtooltips` | Toggle hidden tooltip visibility |
| `/it players` | Open the player list GUI (add/edit/delete/party/invite) |
| `/it ghost` | Client-side block removal tool |
| `/islandtime help [page]` | View help pages |

`/it` is an alias for `/islandtime`.

## Configuration

Settings are saved in the `config` folder as `islandtime*.txt` files. Never share these publicly — they contain your Discord webhook URL.
