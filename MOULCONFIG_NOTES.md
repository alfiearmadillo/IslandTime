# MoulConfig Implementation Notes

## Sensitive Field Masking

MoulConfig version 3.0.0-beta.14 does not have built-in annotation support for masking sensitive fields like webhook URLs.

The current implementation stores webhook URLs as plain strings in the config GUI, which is standard practice for most Minecraft mods. Users can see and edit their webhook URLs directly.

### Future Enhancement Options

If masking is required in the future, it could be implemented by:

1. Creating a custom config element that extends MoulConfig's base element classes
2. Rendering the field as asterisks with a "show" button that toggles visibility
3. This would require custom rendering logic beyond the standard annotations

For now, the webhook URLs are stored and displayed as plain text, which is acceptable for a client-side mod configuration file stored locally on the user's machine.

## Features Implemented

1. **Player Leave Notifications**: The `IslandPlayerTracker` monitors the player list client-side (no packets sent to server) and sends Discord webhook notifications when players leave the island.

2. **Chat Error Relaying**: The chat relay now detects error messages like "You cannot say the same message twice!" and "You may not use links in your messages!" and relays them back to Discord with context about the attempted message.

3. **MoulConfig Integration**: All mod settings are now accessible through a GUI opened with `/islandtime`, while existing commands remain functional.
