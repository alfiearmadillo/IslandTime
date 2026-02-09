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
                            showHelp(ctx.getSource().getClient());
                            return 1;
                        })));

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
                    FuelCycleTracker.displayStats(ctx.getSource().getClient());
                    return 1;
                }));

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
                                        Text.literal("Invalid Discord user ID. Must be numeric (e.g., 199061696386433024)")
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

    private static void showHelp(MinecraftClient client) {
        client.player.sendMessage(
                Text.literal("=== Island Time Mod Commands ===")
                        .formatted(Formatting.GOLD),
                false
        );

        client.player.sendMessage(Text.literal(""), false);

        // /islandtime
        client.player.sendMessage(
                Text.literal("/islandtime").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Show last 7 days of island time").formatted(Formatting.GRAY),
                false
        );

        // /islandtime <date>
        client.player.sendMessage(
                Text.literal("/islandtime <DD-MM-YY>").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Show specific date (e.g., /islandtime 1-1-26)").formatted(Formatting.GRAY),
                false
        );

        client.player.sendMessage(Text.literal(""), false);

        // /fueltime
        client.player.sendMessage(
                Text.literal("/fueltime").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Show 24-hour fuel cycle stats (minions fueled + island time)").formatted(Formatting.GRAY),
                false
        );

        client.player.sendMessage(Text.literal(""), false);

        // /webhook
        client.player.sendMessage(
                Text.literal("/webhook").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Show current webhook URL").formatted(Formatting.GRAY),
                false
        );

        // /webhook <url>
        client.player.sendMessage(
                Text.literal("/webhook <url>").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Set webhook URL (for Discord alerts)").formatted(Formatting.GRAY),
                false
        );

        // /webhook clear
        client.player.sendMessage(
                Text.literal("/webhook clear").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Remove webhook").formatted(Formatting.GRAY),
                false
        );

        client.player.sendMessage(Text.literal(""), false);


        // /discordping
        client.player.sendMessage(
                Text.literal("/discordping").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Show current Discord user ID for webhook pings").formatted(Formatting.GRAY),
                false
        );

        // /discordping <userid>
        client.player.sendMessage(
                Text.literal("/discordping <userid>").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Set Discord user ID to ping in webhook alerts").formatted(Formatting.GRAY),
                false
        );

        // /discordping clear
        client.player.sendMessage(
                Text.literal("/discordping clear").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Remove Discord ping (webhooks won't ping anyone)").formatted(Formatting.GRAY),
                false
        );

        client.player.sendMessage(Text.literal(""), false);

        // /trackitem
        client.player.sendMessage(
                Text.literal("/trackitem").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Show currently tracked item").formatted(Formatting.GRAY),
                false
        );

        // /trackitem set
        client.player.sendMessage(
                Text.literal("/trackitem set").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Track the item in your main hand (must have UUID + stats_book)").formatted(Formatting.GRAY),
                false
        );

        // /trackitem clear
        client.player.sendMessage(
                Text.literal("/trackitem clear").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Stop tracking the current item").formatted(Formatting.GRAY),
                false
        );

        client.player.sendMessage(Text.literal(""), false);

        // /trackedalertspeed
        client.player.sendMessage(
                Text.literal("/trackedalertspeed").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Show current tracked item alert speed and options").formatted(Formatting.GRAY),
                false
        );

        // /trackedalertspeed <speed>
        client.player.sendMessage(
                Text.literal("/trackedalertspeed <speed>").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Set tracked item alert speed (off/fast/normal/slow)").formatted(Formatting.GRAY),
                false
        );

        client.player.sendMessage(Text.literal(""), false);

        // /islandalertspeed
        client.player.sendMessage(
                Text.literal("/islandalertspeed").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Show current island alert speed and options").formatted(Formatting.GRAY),
                false
        );

        // /islandalertspeed <speed>
        client.player.sendMessage(
                Text.literal("/islandalertspeed <speed>").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Set island alert speed (off/fast/normal/slow)").formatted(Formatting.GRAY),
                false
        );

        client.player.sendMessage(Text.literal(""), false);

        // /disconnectalertspeed
        client.player.sendMessage(
                Text.literal("/disconnectalertspeed").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Show current disconnect alert speed and options").formatted(Formatting.GRAY),
                false
        );

        // /disconnectalertspeed <speed>
        client.player.sendMessage(
                Text.literal("/disconnectalertspeed <speed>").formatted(Formatting.WHITE),
                false
        );
        client.player.sendMessage(
                Text.literal("  Set disconnect alert speed (off/fast/normal/slow)").formatted(Formatting.GRAY),
                false
        );
        client.player.sendMessage(
                Text.literal("  Note: Disconnect alerts are sent immediately, speed controls repeat frequency").formatted(Formatting.DARK_GRAY),
                false
        );
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