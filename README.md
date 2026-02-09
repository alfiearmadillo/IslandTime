# IslandTime Mod

A comprehensive Minecraft Fabric mod for Hypixel SkyBlock that tracks your island time, fuel cycles, item stats, and sends Discord alerts to help you stay productive and never miss important events.

## Features

### 🏝️ Island Time Tracking
- Automatically tracks time spent on your private island
- View daily statistics for the past 7 days
- Query specific dates to see historical data
- Persists data between sessions

### ⛽ Fuel Cycle Tracking
- Tracks 24-hour fuel cycles when you fuel minions
- Records minions refueled per cycle
- Calculates active time on island during each cycle
- Stores last 4 completed cycles for review
- Automatically completes cycles after 24 hours

### 📦 Item Stat Tracking
- Track any item with a UUID and `stats_book` NBT tag
- Monitor if item stats are increasing every minute
- Get alerts if your item goes missing from inventory
- Get alerts if stats stop increasing (item not working)
- Useful for tracking minion production, farming tools, etc.

### 🔔 Discord Webhook Alerts

The mod can send Discord notifications for various events:

- **Island Alerts**: Get notified when you've been off your island for too long
- **Tracked Item Alerts**: Get notified when your tracked item stops working or goes missing
- **Disconnect Alerts**: Get notified immediately when you disconnect from the server (with optional repeats)

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

#### `/afkmod help`
Display all available commands and their usage.

#### `/islandtime [date]`
View your island time statistics.
- **No arguments**: Shows the last 7 days of data
- **With date**: Shows specific date (format: `DD-MM-YYYY` or `D-M-YY`)
  - Example: `/islandtime 1-1-26` or `/islandtime 01-01-2026`

#### `/fueltime`
Display your fuel cycle statistics.
- Shows current active cycle (if any)
- Shows last 4 completed cycles
- Includes minions refueled and active time per cycle

### Webhook Commands

#### `/webhook [url|clear]`
Manage Discord webhook.
- **No arguments**: Display current webhook URL
- **With URL**: Set webhook URL (must start with `https://discord.com/api/webhooks/`)
- **clear**: Remove webhook

#### `/discordping [userid|clear]`
Manage Discord ping settings.
- **No arguments**: Display current Discord user ID
- **With user ID**: Set user ID for pings (numeric only)
- **clear**: Remove user ID (alerts won't ping anyone)

### Item Tracking Commands

#### `/trackitem`
Display currently tracked item information.

#### `/trackitem set`
Track the item in your main hand.
- Item must have a UUID and `stats_book` NBT tag
- Checks every minute if stats are increasing
- Sends alerts if item is missing or stats stop increasing

#### `/trackitem clear`
Stop tracking the current item.

#### `/trackedalertspeed [speed]`
Configure tracked item alert settings.
- **No arguments**: Show current speed and options
- **Speeds available:**
  - `off`: No alerts
  - `fast`: Alert after 1 minute, repeat every 5 minutes
  - `normal`: Alert after 10 minutes, repeat every 30 minutes
  - `slow`: Alert after 1 hour, repeat every 3 hours

### Island Alert Commands

#### `/islandalertspeed [speed]`
Configure island alert settings.
- **No arguments**: Show current speed and options
- **Speeds available:**
  - `off`: No alerts
  - `fast`: Alert after 1 minute off island, repeat every 5 minutes
  - `normal`: Alert after 10 minutes off island, repeat every 30 minutes
  - `slow`: Alert after 1 hour off island, repeat every 3 hours

### Disconnect Alert Commands

#### `/disconnectalertspeed [speed]`
Configure disconnect alert settings.
- **No arguments**: Show current speed and options
- **Note**: First alert is always sent immediately upon disconnect
- **Speeds control repeat frequency:**
  - `off`: No alerts
  - `fast`: Immediate, then repeat every 5 minutes
  - `normal`: Immediate, then repeat every 30 minutes
  - `slow`: Immediate, then repeat every 3 hours

## Usage Examples

### Basic Island Time Tracking
```
/islandtime
```
See your island time for the past week.

### Set Up Minion Fuel Tracking
1. Just fuel any minion - tracking starts automatically!
2. Check your stats anytime with `/fueltime`
3. The mod will track up to 4 completed 24-hour cycles

### Track a Farming Tool
1. Hold your farming tool (must have UUID and stats_book)
2. Run `/trackitem set`
3. The mod will alert you if it stops working or goes missing
4. Adjust alert frequency with `/trackedalertspeed normal`

### Set Up Discord Alerts
```
/webhook https://discord.com/api/webhooks/123456789/your-webhook-here
/discordping 199061696386433024
/islandalertspeed normal
/trackedalertspeed normal
/disconnectalertspeed normal
```

## How It Works

### Island Detection
The mod detects when you're on your island by checking the scoreboard for "Your Island" text.

### Fuel Cycle Tracking
- Tracks when you fuel minions by monitoring the minion GUI
- A fuel cycle lasts 24 hours from the last minion you fuel
- Only counts time when your tracked item's stats are increasing (you're active)
- Automatically completes cycles after 24 hours

### Item Stat Tracking
- Monitors the `stats_book` NBT value of your tracked item
- Checks every minute if the value has increased
- Alerts if no increase is detected (configurable threshold)
- Works regardless of island status

### Discord Alerts
- All alerts are sent asynchronously (won't lag your game)
- Rate-limited to prevent spam
- Thread-safe implementation to prevent crashes

## Configuration Files

All settings are automatically saved to the `config` folder:

- `islandtime.txt` - Daily island time data
- `islandtime_fuel_cycles.txt` - Fuel cycle history
- `islandtime_tracked_item.txt` - Tracked item configuration
- `islandtime_webhooks.txt` - Discord webhook and user ID
- `islandtime_island_alerts.txt` - Island alert speed setting
- `islandtime_disconnect_alerts.txt` - Disconnect alert speed setting

**Note**: Never share these files publicly as they contain your Discord webhook URL!

## Privacy & Security

- ✅ No personal data is hardcoded in the mod
- ✅ All configuration is stored locally
- ✅ Webhook URLs are never sent anywhere except Discord
- ✅ No telemetry or external connections except your configured webhook
- ⚠️ **Important**: Add `config/` to your `.gitignore` if contributing to prevent sharing your webhook URL

## Troubleshooting

### Discord Alerts Not Working
1. Check webhook is set: `/webhook`
2. Verify webhook URL is correct (should start with `https://discord.com/api/webhooks/`)
3. Test by leaving your island for 10 minutes (if using normal speed)
4. Check Discord channel permissions for the webhook

### Item Tracking Not Working
1. Ensure item has both UUID and `stats_book` NBT tags
2. Check item is in your inventory
3. Verify alert speed is not set to `off`
4. Check console for debug messages (press F3 + C)

### Fuel Tracking Not Detecting Minions
1. Make sure you're opening the minion GUI
2. Fuel must be placed in the fuel slot (orange glass pane slot)
3. Check console for debug messages

### Stats Not Saving
1. Ensure `config` folder has write permissions
2. Check for errors in the Minecraft log
3. Try manually running `/islandtime` to trigger a save

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add your `config/` folder to `.gitignore`
5. Submit a pull request

## License

This mod is provided as-is for personal use. Feel free to modify and share!

## Support

If you encounter issues:
1. Check the troubleshooting section above
2. Review the Minecraft logs for errors
3. Open an issue on GitHub with details about the problem

## Credits

Created for the Hypixel SkyBlock community to help players optimize their island time and never miss important alerts!

---

**Disclaimer**: This is a client-side mod. Always follow Hypixel's rules and guidelines. Use at your own risk.
