# IslandTime Mod

A comprehensive Minecraft Fabric mod for Hypixel SkyBlock that tracks your island time, fuel cycles, item stats, and sends Discord alerts to help you stay productive and never miss important events.

## Features

### 🏝️ Island Time Tracking
- Automatically tracks time spent on your private island
- View daily statistics for the past 7 days
- Query specific dates to see historical data
- Persists data between sessions
- Auto-saves every 5 seconds while on island

### ⛽ Fuel Cycle Tracking
- Tracks fuel cycles that start when you fuel your first minion and end 24 hours after the last minion
- Records minions refueled per cycle
- Calculates **active time** during each cycle (when tracked item stats are increasing)
- Stores **all completed cycles** for unlimited history
- View the most recent 4 cycles or query any specific cycle by number
- Cycle duration can exceed 24 hours if you continue fueling minions

### 📦 Item Stat Tracking
- Track any item with a UUID and `stats_book` NBT tag
- Monitors if item stats are increasing every minute
- Alerts if your item goes missing from inventory
- Alerts if stats stop increasing (item not working)
- Works regardless of island status (useful for AFK farming)
- Perfect for tracking AFK weapons, minion production items, etc.

### 🔔 Discord Webhook Alerts

The mod can send Discord notifications for various events:

- **Island Alerts**: Get notified when you've been off your island for too long
- **Tracked Item Alerts**: Get notified when your tracked item stops working or goes missing
- **Disconnect Alerts**: Get notified **immediately** when you disconnect from the server, with optional repeat alerts
- **Auto-retry**: Failed alerts are queued and automatically resent when internet connection returns

All alerts support customizable speeds and can ping you on Discord!

## Installation

### Prerequisites
- Minecraft 1.21+ (Fabric)
- Fabric Loader
- Fabric API

### Steps
1. Download the latest release JAR from the [Releases](../../releases) page
2. Place the JAR file in your `.minecraft/mods` folder
3. Launch Minecraft with the Fabric profile

## Setup

### Discord Webhook Setup (Optional but Recommended)

1. **Create a Discord Webhook:**
   - Open Discord and go to the channel where you want alerts
   - Click the gear icon (Edit Channel)
   - Go to Integrations → Webhooks
   - Click "New Webhook"
   - Copy the webhook URL

2. **Configure the Webhook in-game:**
   ```
   /webhook https://discord.com/api/webhooks/YOUR_WEBHOOK_URL
   ```

3. **Enable Discord Pings (Optional):**
   - Get your Discord User ID (right-click yourself → Copy User ID)
   - Enable Developer Mode in Discord if needed (Settings → Advanced → Developer Mode)
   ```
   /discordping YOUR_USER_ID
   ```

## Commands

### Core Commands

#### `/afkmod help [page]`
Display all available commands and their usage.
- **No arguments**: Shows page 1
- **With page number**: Shows specific page (e.g., `/afkmod help 2`)
- **7 commands per page** for easy reading

#### `/islandtime [date]`
View your island time statistics.
- **No arguments**: Shows the last 7 days of data
- **With date**: Shows specific date (format: `DD-MM-YYYY` or `D-M-YY`)
  - Example: `/islandtime 1-1-26` or `/islandtime 01-01-2026`

#### `/fueltime [cycle]`
Display your fuel cycle statistics.
- **No arguments**: Shows current active cycle (if any) and last 4 completed cycles
- **With cycle number**: Shows details for a specific cycle (e.g., `/fueltime 6`)
  - Cycles are numbered from oldest (1) to newest
  - All cycles are kept permanently
  - Shows start time, end time, total duration, minions fueled, and active time

### Webhook Commands

#### `/webhook [url|clear]`
Manage Discord webhook.
- **No arguments**: Display current webhook URL
- **With URL**: Set webhook URL (must start with `https://discord.com/api/webhooks/`)
- **clear**: Remove webhook
  ```
  /webhook clear
  ```

#### `/discordping [userid|clear]`
Manage Discord ping settings.
- **No arguments**: Display current Discord user ID
- **With user ID**: Set user ID for pings (numeric only)
- **clear**: Remove user ID (alerts won't ping anyone)
  ```
  /discordping clear
  ```

### Item Tracking Commands

#### `/trackitem`
Display currently tracked item information.
- Shows item name, UUID, current stats value
- Shows alert speed setting

#### `/trackitem set`
Track the item in your main hand.
- Item must have a UUID and `stats_book` NBT tag
- Checks every 60 seconds if stats are increasing
- Sends alerts if item is missing or stats stop increasing

#### `/trackitem clear`
Stop tracking the current item.

#### `/trackedalertspeed [speed]`
Configure tracked item alert settings.
- **No arguments**: Show current speed and detailed options
- **Speeds available:**
  - `off`: No alerts
  - `fast`: Alert after 1 minute, repeat every 5 minutes
  - `normal`: Alert after 10 minutes, repeat every 30 minutes (default)
  - `slow`: Alert after 1 hour, repeat every 3 hours

### Island Alert Commands

#### `/islandalertspeed [speed]`
Configure island alert settings.
- **No arguments**: Show current speed and detailed options
- **Speeds available:**
  - `off`: No alerts
  - `fast`: Alert after 1 minute off island, repeat every 5 minutes
  - `normal`: Alert after 10 minutes off island, repeat every 30 minutes (default)
  - `slow`: Alert after 1 hour off island, repeat every 3 hours

### Disconnect Alert Commands

#### `/disconnectalertspeed [speed]`
Configure disconnect alert settings.
- **No arguments**: Show current speed and detailed options
- **Important**: First alert is **always sent immediately** upon disconnect
- **Speeds control repeat frequency only:**
  - `off`: No alerts at all
  - `fast`: Immediate alert, then repeat every 5 minutes
  - `normal`: Immediate alert, then repeat every 30 minutes (default)
  - `slow`: Immediate alert, then repeat every 3 hours

## Usage Examples

### Basic Island Time Tracking
```
/islandtime
```
See your island time for the past week.

```
/islandtime 15-1-26
```
Check a specific date.

### Set Up Minion Fuel Tracking
1. Fuel any minion - tracking starts automatically!
2. Check your stats anytime:
   ```
   /fueltime
   ```
3. View older cycles:
   ```
   /fueltime 1    # View oldest cycle
   /fueltime 5    # View cycle #5
   ```

### Track an AFK Weapon
1. Hold your AFK weapon in your main hand (must have UUID and stats_book NBT)
2. Set it as tracked:
   ```
   /trackitem set
   ```
3. The mod will check every minute and alert you if it stops working
4. Adjust alert frequency:
   ```
   /trackedalertspeed fast    # More frequent alerts
   /trackedalertspeed normal  # Default timing
   /trackedalertspeed slow    # Less frequent alerts
   ```

### Set Up Discord Alerts
```
/webhook https://discord.com/api/webhooks/123456789/your-webhook-here
/discordping <@your user id>
/islandalertspeed normal
/trackedalertspeed normal
/disconnectalertspeed normal
```

Now you'll get pinged on Discord for:
- Being off island too long
- Your tracked item stopping/missing
- Getting disconnected from the server

### Browse Help Pages
```
/afkmod help       # View page 1
/afkmod help 2     # View page 2
/afkmod help 3     # View page 3
```

## How It Works

### Island Detection
The mod detects when you're on your island by scanning the scoreboard sidebar for "Your Island" text. This works on Hypixel SkyBlock's standard scoreboard display.

### Fuel Cycle Tracking
- Automatically detects when you fuel a minion by monitoring the minion GUI
- A fuel cycle **starts** when you fuel your first minion
- The cycle **ends** 24 hours after the **last** minion you fuel
  - This means fueling more minions extends the cycle beyond 24 hours
  - The cycle duration is from first fuel to 24h after last fuel
- **Active time** only counts when your tracked item's `stats_book` value is increasing
  - This ensures you're actually AFK farming, not just online
- Cycles automatically complete 24 hours after the last fuel
- All completed cycles are saved permanently with no limit

**Example**: 
- Day 1, 8:00 PM: Fuel first minion (cycle starts, will end 8:00 PM next day)
- Day 1, 10:00 PM: Fuel more minions (cycle now ends at 10:00 PM next day)
- Day 2, 10:00 PM: Cycle auto-completes (duration was 26 hours total)
- Cycle saved showing: Feb 1 8:00 PM - Feb 2 10:00 PM, Duration: 26h, Minions: 15

### Item Stat Tracking
- Monitors the `stats_book` NBT value of your tracked item
- Checks every **60 seconds** if the value has increased since last check
- Alerts based on your configured alert speed if no increase is detected
- Works **regardless of island status** (you can track items anywhere)
- Uses UUID to identify the exact item (won't confuse with similar items)

### Discord Alerts
- All alerts are sent **asynchronously** in background threads (won't lag your game)
- Maximum 2 concurrent webhook requests to prevent resource exhaustion
- Failed messages are automatically queued and retried every 30 seconds
- Thread-safe implementation prevents crashes
- Graceful shutdown when you close Minecraft

#### Alert Types and Timing

**Island Alerts:**
- First alert: After configured threshold (1 min / 10 min / 1 hour)
- Repeat alerts: At configured intervals (5 min / 30 min / 3 hours)

**Tracked Item Alerts:**
- First alert: After configured threshold (1 min / 10 min / 1 hour)
- Repeat alerts: At configured intervals (5 min / 30 min / 3 hours)
- Separate alerts for missing vs. not increasing

**Disconnect Alerts:**
- First alert: **Immediate** (no delay)
- Repeat alerts: At configured intervals (5 min / 30 min / 3 hours)
- Only sent if you've connected to a server at least once

## Configuration Files

All settings are automatically saved to the `config` folder in your Minecraft directory:

| File | Purpose |
|------|---------|
| `islandtime.txt` | Daily island time data |
| `islandtime_fuel_cycles.txt` | Fuel cycle history (all cycles, unlimited) |
| `islandtime_tracked_item.txt` | Tracked item UUID, name, and alert speed |
| `islandtime_webhooks.txt` | Discord webhook URL and user ID for pings |
| `islandtime_island_alerts.txt` | Island alert speed setting |
| `islandtime_disconnect_alerts.txt` | Disconnect alert speed setting |

**⚠️ Security Warning**: Never share these files publicly as they contain your Discord webhook URL! Anyone with your webhook URL can send messages to your Discord channel.

## Privacy & Security

- ✅ No personal data is hardcoded in the mod
- ✅ All configuration is stored locally on your computer
- ✅ Webhook URLs are never sent anywhere except Discord's official API
- ✅ No telemetry, analytics, or tracking
- ✅ No external connections except your configured Discord webhook
- ⚠️ **Important**: Add `config/islandtime*.txt` to your `.gitignore` if contributing to prevent accidentally sharing your webhook URL

## Troubleshooting

### Discord Alerts Not Working

**Check your webhook configuration:**
```
/webhook
```
Should display your webhook URL. If it says "No webhook set", configure it first.

**Common issues:**
1. Webhook URL must start with `https://discord.com/api/webhooks/`
2. Check Discord channel permissions - the webhook must have permission to post
3. Test by deliberately triggering an alert (e.g., leave island for 10+ minutes)
4. Look for error messages in the Minecraft log (`latest.log`)
5. Check your internet connection - failed alerts are queued for retry

**Network errors:**
- If you see "network error (no internet?)" in logs, alerts will retry automatically
- Check firewall settings - ensure Minecraft can access `discord.com`

### Item Tracking Not Working

**Verify item requirements:**
```
/trackitem set
```
If it fails, your item doesn't have the required NBT tags.

**Required NBT tags:**
- `uuid` - Unique identifier for the item
- `stats_book` - The stat value to monitor

**Common issues:**
1. Item must be in your main hand when using `/trackitem set`
2. Check alert speed isn't set to `off`: `/trackedalertspeed`
3. Stats must be **actively increasing** - checks happen every 60 seconds
4. Item must stay in your inventory (any slot works after tracking is set)
5. Be patient - first check happens 60 seconds after setting

**Debug steps:**
1. Check console for "[ItemTracker]" messages showing stats values
2. Verify your item is actually gaining stats (check manually in-game)
3. Try `/trackitem` to see current tracked item info

### Fuel Tracking Not Detecting Minions

**The mod detects fueling by monitoring the minion GUI:**

1. You must **open the minion GUI** (right-click the minion)
2. Fuel must be placed in the **fuel slot** (slot with orange glass pane placeholder)
3. The mod detects when the glass pane changes to actual fuel
4. You should see a message in chat: `[Minion Name] fueled with [Fuel Name]`

**Common issues:**
- Adding fuel to an already fueled minion will not trigger tracking or the chat message, the fuel slot must first be empty.

**To verify it's working:**
```
/fueltime
```
Should show a current cycle if you've fueled any minions recently.

### Fuel Cycle Not Showing Older Cycles

The default `/fueltime` command only shows the **most recent 4 cycles**.

**To view older cycles:**
```
/fueltime 1     # View oldest cycle
/fueltime 2     # View second oldest
/fueltime 10    # View cycle #10
```

**How to find your cycle numbers:**
- Run `/fueltime` - it shows "Recent Completed Cycles" with cycle numbers
- Look for "Cycle 7:" in the output - that's the cycle number
- Use that number: `/fueltime 7`

**Cycle completion:**
- Cycles start when you fuel the first minion
- Cycles end 24 hours after the **last** minion you fuel
- If you keep fueling, the cycle keeps extending (can be longer than 24 hours total)
- Check "Time Remaining" in current cycle display to see when it will complete

### Stats Not Saving

**Check file permissions:**
1. Ensure `config` folder exists in your Minecraft directory
2. Verify you have write permissions to the folder
3. On Windows: Right-click folder → Properties → Security
4. On Linux/Mac: Check folder permissions with `ls -l`

**Auto-save behavior:**
- Island time saves every 5 seconds while on island
- All data saves when you leave island
- All data saves when Minecraft closes
- Check for errors in `latest.log`

**Manual verification:**
1. Visit your island for a few minutes
2. Close Minecraft completely
3. Check if files exist in `config` folder
4. Look for files starting with `islandtime`

### Disconnect Alerts Repeating Too Often

Remember: The first alert is **always immediate** - this is by design so you know right away when you disconnect.

**To reduce repeat frequency:**
```
/disconnectalertspeed slow     # Repeat every 3 hours
/disconnectalertspeed off      # Disable completely
```

**If you're frequently disconnecting intentionally:**
- Set to `off` or `slow`
- Or disable Discord webhook temporarily: `/webhook clear`

### Help Command Shows Wrong Page

The help system has 4 pages total (7 commands per page).

**Valid commands:**
```
/afkmod help       # Page 1
/afkmod help 1     # Page 1
/afkmod help 2     # Page 2
/afkmod help 3     # Page 3
/afkmod help 4     # Page 4
```

If you see "Invalid page number", you're requesting a page that doesn't exist.

## License

This mod is provided as-is for personal use. Feel free to modify and share!

## Support

If you encounter issues:

1. **Check this README** - especially the Troubleshooting section
2. **Review Minecraft logs** - Look in `.minecraft/logs/latest.log` for errors
3. **Open a GitHub issue** with:
   - Minecraft version (e.g., 1.21.4)
   - Mod version
   - Fabric Loader version
   - Steps to reproduce the problem
   - Relevant log output (remove webhook URLs!)
   - Screenshots if applicable

**⚠️ When sharing logs, always remove your webhook URL first!**

**Disclaimer**: This is a client-side mod that uses only Hypixel's public scoreboard information. Always follow Hypixel's rules and guidelines. Use at your own risk.
