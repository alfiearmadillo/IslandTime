package org.example.islandtime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

public class IslandTimeCommand {

    private static final DateTimeFormatter OUTPUT_FORMAT =
            DateTimeFormatter.ofPattern("MMM d");

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) ->
                        registerCommand(dispatcher)
        );
    }

    private static void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        // Register /afkmod help command
        dispatcher.register(literal("afkmod")
                .then(literal("help")
                        .executes(ctx -> {
                            showHelp(ctx.getSource().getClient(), 1);
                            return 1;
                        })
                        .then(argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    int page = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "page");
                                    showHelp(ctx.getSource().getClient(), page);
                                    return 1;
                                }))));

        // Register /islandtime command
        dispatcher.register(literal("islandtime")
                .executes(ctx -> {
                    showRecent();
                    return 1;
                })
                .then(argument("date", StringArgumentType.word())
                        .executes(ctx -> {
                            showSpecificDate(
                                    StringArgumentType.getString(ctx, "date")
                            );
                            return 1;
                        }))
        );

        // Register /fueltime command
        dispatcher.register(literal("fueltime")
                .executes(ctx -> {
                    FuelCycleTracker.displayStats(ctx.getSource().getClient(), null);
                    return 1;
                })
                .then(argument("cycle", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .executes(ctx -> {
                            int cycleNumber = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cycle");
                            FuelCycleTracker.displayStats(ctx.getSource().getClient(), cycleNumber);
                            return 1;
                        })));

        // Register webhook command
        dispatcher.register(literal("webhook")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    String url = DiscordWebhookManager.getWebhook();
                    if (url != null && !url.isEmpty()) {
                        client.player.sendMessage(
                                Text.literal("Webhook URL: " + url)
                                        .formatted(Formatting.AQUA),
                                false
                        );
                    } else {
                        client.player.sendMessage(
                                Text.literal("No webhook set")
                                        .formatted(Formatting.YELLOW),
                                false
                        );
                    }
                    return 1;
                })
                .then(argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String url = StringArgumentType.getString(ctx, "url");
                            MinecraftClient client = ctx.getSource().getClient();

                            if (url.equalsIgnoreCase("clear")) {
                                DiscordWebhookManager.clearWebhook();
                                client.player.sendMessage(
                                        Text.literal("Webhook cleared")
                                                .formatted(Formatting.GREEN),
                                        false
                                );
                            } else if (url.startsWith("https://discord.com/api/webhooks/")) {
                                DiscordWebhookManager.setWebhook(url);
                                client.player.sendMessage(
                                        Text.literal("Webhook set successfully")
                                                .formatted(Formatting.GREEN),
                                        false
                                );
                            } else {
                                client.player.sendMessage(
                                        Text.literal("Invalid webhook URL. Must start with https://discord.com/api/webhooks/")
                                                .formatted(Formatting.RED),
                                        false
                                );
                                return 0;
                            }
                            return 1;
                        })));

        // Register /discordping command
        dispatcher.register(literal("discordping")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    String userId = DiscordWebhookManager.getDiscordUserId();
                    if (userId != null && !userId.isEmpty()) {
                        client.player.sendMessage(
                                Text.literal("Discord ping ID: ")
                                        .formatted(Formatting.AQUA)
                                        .append(Text.literal(userId)
                                                .formatted(Formatting.YELLOW)),
                                false
                        );
                    } else {
                        client.player.sendMessage(
                                Text.literal("No Discord ping ID set (webhooks will not ping anyone)")
                                        .formatted(Formatting.YELLOW),
                                false
                        );
                    }
                    return 1;
                })
                .then(argument("userid", StringArgumentType.word())
                        .executes(ctx -> {
                            String userId = StringArgumentType.getString(ctx, "userid");
                            MinecraftClient client = ctx.getSource().getClient();

                            if (userId.equalsIgnoreCase("clear")) {
                                DiscordWebhookManager.clearDiscordUserId();
                                client.player.sendMessage(
                                        Text.literal("Discord ping ID cleared")
                                                .formatted(Formatting.GREEN),
                                        false
                                );
                                return 1;
                            }

                            // Validate it's numeric
                            if (!userId.matches("\\d+")) {
                                client.player.sendMessage(
                                        Text.literal("Invalid Discord user ID. Must be numeric (e.g., 123456789012345678)")
                                                .formatted(Formatting.RED),
                                        false
                                );
                                return 0;
                            }

                            DiscordWebhookManager.setDiscordUserId(userId);
                            client.player.sendMessage(
                                    Text.literal("Discord ping ID set to: ")
                                            .formatted(Formatting.GREEN)
                                            .append(Text.literal(userId)
                                                    .formatted(Formatting.YELLOW)),
                                    false
                            );
                            return 1;
                        })));

        // Register /trackitem command
        dispatcher.register(literal("trackitem")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    String uuid = ItemTracker.getTrackedUuid();
                    String name = ItemTracker.getTrackedItemName();

                    if (uuid != null && !uuid.isEmpty()) {
                        client.player.sendMessage(
                                Text.literal("Currently tracking: ")
                                        .formatted(Formatting.AQUA)
                                        .append(Text.literal(name != null ? name : "Unknown item")
                                                .formatted(Formatting.YELLOW))
                                        .append(Text.literal("\nUUID: " + uuid)
                                                .formatted(Formatting.GRAY)),
                                false
                        );
                    } else {
                        client.player.sendMessage(
                                Text.literal("No item is being tracked")
                                        .formatted(Formatting.YELLOW),
                                false
                        );
                    }
                    return 1;
                })
                .then(literal("set")
                        .executes(ctx -> {
                            MinecraftClient client = ctx.getSource().getClient();

                            if (ItemTracker.setTrackedItemFromMainHand(client)) {
                                String name = ItemTracker.getTrackedItemName();
                                String uuid = ItemTracker.getTrackedUuid();
                                client.player.sendMessage(
                                        Text.literal("Now tracking: ")
                                                .formatted(Formatting.GREEN)
                                                .append(Text.literal(name)
                                                        .formatted(Formatting.YELLOW))
                                                .append(Text.literal("\nUUID: " + uuid)
                                                        .formatted(Formatting.GRAY)),
                                        false
                                );
                            } else {
                                client.player.sendMessage(
                                        Text.literal("Cannot track this item. Item must have both 'uuid' and 'stats_book' in custom data.")
                                                .formatted(Formatting.RED),
                                        false
                                );
                            }
                            return 1;
                        }))
                .then(literal("clear")
                        .executes(ctx -> {
                            MinecraftClient client = ctx.getSource().getClient();
                            ItemTracker.clearTrackedItem();
                            client.player.sendMessage(
                                    Text.literal("Cleared tracked item")
                                            .formatted(Formatting.GREEN),
                                    false
                            );
                            return 1;
                        })));

        // Register /trackedalertspeed command (for tracked item alerts)
        dispatcher.register(literal("trackedalertspeed")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    ItemTracker.AlertSpeed currentSpeed = ItemTracker.getAlertSpeed();

                    client.player.sendMessage(
                            Text.literal("Current tracked item alert speed: ")
                                    .formatted(Formatting.AQUA)
                                    .append(Text.literal(currentSpeed.name().toLowerCase())
                                            .formatted(Formatting.YELLOW)),
                            false
                    );

                    showAlertSpeedOptions(client);
                    return 1;
                })
                .then(argument("speed", StringArgumentType.word())
                        .executes(ctx -> {
                            String speedStr = StringArgumentType.getString(ctx, "speed").toUpperCase();
                            MinecraftClient client = ctx.getSource().getClient();

                            try {
                                ItemTracker.AlertSpeed speed = ItemTracker.AlertSpeed.valueOf(speedStr);
                                ItemTracker.setAlertSpeed(speed);

                                client.player.sendMessage(
                                        Text.literal("Tracked item alert speed set to: ")
                                                .formatted(Formatting.GREEN)
                                                .append(Text.literal(speed.name().toLowerCase())
                                                        .formatted(Formatting.YELLOW)),
                                        false
                                );
                            } catch (IllegalArgumentException e) {
                                client.player.sendMessage(
                                        Text.literal("Invalid alert speed. Use: off, fast, normal, or slow")
                                                .formatted(Formatting.RED),
                                        false
                                );
                                return 0;
                            }
                            return 1;
                        })));

        // Register /islandalertspeed command (for off-island alerts)
        dispatcher.register(literal("islandalertspeed")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    IslandAlertTracker.AlertSpeed currentSpeed = IslandAlertTracker.getAlertSpeed();

                    client.player.sendMessage(
                            Text.literal("Current island alert speed: ")
                                    .formatted(Formatting.AQUA)
                                    .append(Text.literal(currentSpeed.name().toLowerCase())
                                            .formatted(Formatting.YELLOW)),
                            false
                    );

                    showAlertSpeedOptions(client);
                    return 1;
                })
                .then(argument("speed", StringArgumentType.word())
                        .executes(ctx -> {
                            String speedStr = StringArgumentType.getString(ctx, "speed").toUpperCase();
                            MinecraftClient client = ctx.getSource().getClient();

                            try {
                                IslandAlertTracker.AlertSpeed speed = IslandAlertTracker.AlertSpeed.valueOf(speedStr);
                                IslandAlertTracker.setAlertSpeed(speed);

                                client.player.sendMessage(
                                        Text.literal("Island alert speed set to: ")
                                                .formatted(Formatting.GREEN)
                                                .append(Text.literal(speed.name().toLowerCase())
                                                        .formatted(Formatting.YELLOW)),
                                        false
                                );
                            } catch (IllegalArgumentException e) {
                                client.player.sendMessage(
                                        Text.literal("Invalid alert speed. Use: off, fast, normal, or slow")
                                                .formatted(Formatting.RED),
                                        false
                                );
                                return 0;
                            }
                            return 1;
                        })));

        // Register /disconnectalertspeed command (for disconnection alerts)
        dispatcher.register(literal("disconnectalertspeed")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    DisconnectAlertTracker.AlertSpeed currentSpeed = DisconnectAlertTracker.getAlertSpeed();

                    client.player.sendMessage(
                            Text.literal("Current disconnect alert speed: ")
                                    .formatted(Formatting.AQUA)
                                    .append(Text.literal(currentSpeed.name().toLowerCase())
                                            .formatted(Formatting.YELLOW)),
                            false
                    );

                    client.player.sendMessage(
                            Text.literal("\nDisconnect alert speeds:")
                                    .formatted(Formatting.GRAY),
                            false
                    );
                    client.player.sendMessage(
                            Text.literal("  off: ").formatted(Formatting.WHITE)
                                    .append(Text.literal("No alerts").formatted(Formatting.GRAY)),
                            false
                    );
                    client.player.sendMessage(
                            Text.literal("  fast: ").formatted(Formatting.WHITE)
                                    .append(Text.literal("Immediate, then every 5 min").formatted(Formatting.GRAY)),
                            false
                    );
                    client.player.sendMessage(
                            Text.literal("  normal: ").formatted(Formatting.WHITE)
                                    .append(Text.literal("Immediate, then every 30 min").formatted(Formatting.GRAY)),
                            false
                    );
                    client.player.sendMessage(
                            Text.literal("  slow: ").formatted(Formatting.WHITE)
                                    .append(Text.literal("Immediate, then every 3 hours").formatted(Formatting.GRAY)),
                            false
                    );
                    return 1;
                })
                .then(argument("speed", StringArgumentType.word())
                        .executes(ctx -> {
                            String speedStr = StringArgumentType.getString(ctx, "speed").toUpperCase();
                            MinecraftClient client = ctx.getSource().getClient();

                            try {
                                DisconnectAlertTracker.AlertSpeed speed = DisconnectAlertTracker.AlertSpeed.valueOf(speedStr);
                                DisconnectAlertTracker.setAlertSpeed(speed);

                                client.player.sendMessage(
                                        Text.literal("Disconnect alert speed set to: ")
                                                .formatted(Formatting.GREEN)
                                                .append(Text.literal(speed.name().toLowerCase())
                                                        .formatted(Formatting.YELLOW)),
                                        false
                                );
                            } catch (IllegalArgumentException e) {
                                client.player.sendMessage(
                                        Text.literal("Invalid alert speed. Use: off, fast, normal, or slow")
                                                .formatted(Formatting.RED),
                                        false
                                );
                                return 0;
                            }
                            return 1;
                        })));
    }

    private static void showAlertSpeedOptions(MinecraftClient client) {
        client.player.sendMessage(
                Text.literal("\nAlert speeds:")
                        .formatted(Formatting.GRAY),
                false
        );
        client.player.sendMessage(
                Text.literal("  off: ").formatted(Formatting.WHITE)
                        .append(Text.literal("No alerts").formatted(Formatting.GRAY)),
                false
        );
        client.player.sendMessage(
                Text.literal("  fast: ").formatted(Formatting.WHITE)
                        .append(Text.literal("1 min, then every 5 min").formatted(Formatting.GRAY)),
                false
        );
        client.player.sendMessage(
                Text.literal("  normal: ").formatted(Formatting.WHITE)
                        .append(Text.literal("10 min, then every 30 min").formatted(Formatting.GRAY)),
                false
        );
        client.player.sendMessage(
                Text.literal("  slow: ").formatted(Formatting.WHITE)
                        .append(Text.literal("1 hour, then every 3 hours").formatted(Formatting.GRAY)),
                false
        );
    }

    private static void showHelp(MinecraftClient client, int page) {
        // Define all commands as an array
        String[][] commands = {
                // Page 1 - Basic commands
                {"/islandtime", "Show last 7 days of island time"},
                {"/islandtime <DD-MM-YY>", "Show specific date (e.g., /islandtime 1-1-26)"},
                {"/fueltime", "Show current and recent fuel cycle stats (last 4 cycles)"},
                {"/fueltime <cycle>", "Show details for a specific cycle number (e.g., /fueltime 6)"},
                {"/webhook", "Show current webhook URL"},
                {"/webhook <url>", "Set webhook URL (for Discord alerts)"},

                // Page 2 - Webhook & tracking
                {"/webhook clear", "Remove webhook"},
                {"/discordping", "Show current Discord user ID for webhook pings"},
                {"/discordping <userid>", "Set Discord user ID to ping in webhook alerts"},
                {"/discordping clear", "Remove Discord ping (webhooks won't ping anyone)"},
                {"/trackitem", "Show currently tracked item"},
                {"/trackitem set", "Track the item in your main hand (must have UUID + stats_book)"},

                // Page 3 - Alert settings
                {"/trackitem clear", "Stop tracking the current item"},
                {"/trackedalertspeed", "Show current tracked item alert speed and options"},
                {"/trackedalertspeed <speed>", "Set tracked item alert speed (off/fast/normal/slow)"},
                {"/islandalertspeed", "Show current island alert speed and options"},
                {"/islandalertspeed <speed>", "Set island alert speed (off/fast/normal/slow)"},
                {"/disconnectalertspeed", "Show current disconnect alert speed and options"},

                // Page 4
                {"/disconnectalertspeed <speed>", "Set disconnect alert speed (off/fast/normal/slow)"}
        };

        int commandsPerPage = 7;
        int totalCommands = commands.length;
        int totalPages = (int) Math.ceil((double) totalCommands / commandsPerPage);

        // Validate page number
        if (page < 1 || page > totalPages) {
            client.player.sendMessage(
                    Text.literal("Invalid page number. Valid range: 1-" + totalPages)
                            .formatted(Formatting.RED),
                    false
            );
            return;
        }

        // Display header
        client.player.sendMessage(
                Text.literal("=== Island Time Mod Commands (Page " + page + "/" + totalPages + ") ===")
                        .formatted(Formatting.GOLD),
                false
        );

        client.player.sendMessage(Text.literal(""), false);

        // Calculate which commands to show
        int startIdx = (page - 1) * commandsPerPage;
        int endIdx = Math.min(startIdx + commandsPerPage, totalCommands);

        // Display commands for this page
        for (int i = startIdx; i < endIdx; i++) {
            client.player.sendMessage(
                    Text.literal(commands[i][0]).formatted(Formatting.WHITE),
                    false
            );
            client.player.sendMessage(
                    Text.literal("  " + commands[i][1]).formatted(Formatting.GRAY),
                    false
            );

            // Add blank line after each command except the last one on the page
            if (i < endIdx - 1) {
                client.player.sendMessage(Text.literal(""), false);
            }
        }
    }

    private static void showRecent() {
        MinecraftClient client = MinecraftClient.getInstance();
        var times = IslandTimeTracker.getTimes();

        // Check if there's any data at all
        if (times.isEmpty()) {
            client.player.sendMessage(
                    Text.literal("No island time data recorded yet. Visit your island to start tracking!")
                            .formatted(Formatting.RED),
                    false
            );
            return;
        }

        List<String> days = new ArrayList<>(times.keySet());
        Collections.sort(days);
        Collections.reverse(days);

        int shown = 0;

        for (String day : days) {
            long ms = times.get(day);
            String line = formatLine(day, ms);

            if (shown == 1) {
                client.player.sendMessage(
                        Text.literal("-----------------------")
                                .formatted(Formatting.GRAY),
                        false
                );
            }

            client.player.sendMessage(Text.literal(line), false);

            shown++;
            if (shown >= 7) break;
        }
    }

    private static void showSpecificDate(String input) {
        MinecraftClient client = MinecraftClient.getInstance();
        var times = IslandTimeTracker.getTimes();

        LocalDate date;
        try {
            // Try parsing with flexible format
            date = parseFlexibleDate(input);
        } catch (DateTimeParseException | NumberFormatException e) {
            client.player.sendMessage(
                    Text.literal("Invalid date format. Use DD-MM-YYYY or D-M-YY (e.g., 1-1-26 or 01-01-2026)")
                            .formatted(Formatting.RED),
                    false
            );
            return;
        }

        String key = date.toString();

        if (!times.containsKey(key)) {
            client.player.sendMessage(
                    Text.literal("No data found for the specified date")
                            .formatted(Formatting.RED),
                    false
            );
            return;
        }

        long ms = times.get(key);
        client.player.sendMessage(
                Text.literal(formatLine(key, ms)),
                false
        );
    }

    private static LocalDate parseFlexibleDate(String input) {
        String[] parts = input.split("-");

        if (parts.length != 3) {
            throw new DateTimeParseException("Invalid format", input, 0);
        }

        int day = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int year = Integer.parseInt(parts[2]);

        // If year is 2 digits, assume 2000s
        if (year < 100) {
            year += 2000;
        }

        return LocalDate.of(year, month, day);
    }

    private static String formatLine(String date, long ms) {
        long seconds = ms / 1000;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;

        String prettyDate =
                LocalDate.parse(date).format(OUTPUT_FORMAT);

        return String.format(
                "%s: %02dh%02dm%02ds",
                prettyDate, h, m, s
        );
    }
}