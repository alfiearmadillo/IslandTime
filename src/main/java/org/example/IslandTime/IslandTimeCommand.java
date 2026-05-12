package org.example.islandtime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
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
                (dispatcher, registryAccess) -> registerCommand(dispatcher)
        );
    }

    private static void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {

        // /islandtime — show recent, lookup a date, or show help
        dispatcher.register(literal("islandtime")
                .executes(ctx -> {
                    showRecent();
                    return 1;
                })
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
                                })))
                .then(argument("date", StringArgumentType.word())
                        .executes(ctx -> {
                            showSpecificDate(StringArgumentType.getString(ctx, "date"));
                            return 1;
                        }))
        );

        // /fueltime
        dispatcher.register(literal("fueltime")
                .executes(ctx -> {
                    FuelCycleTracker.displayStats(ctx.getSource().getClient(), null);
                    return 1;
                })
                .then(literal("debug")
                        .executes(ctx -> {
                            MinecraftClient client = ctx.getSource().getClient();
                            client.player.sendMessage(
                                    Text.literal("[FuelCycleTracker Debug] Injecting fake cycle and saving...")
                                            .formatted(Formatting.YELLOW),
                                    false
                            );
                            boolean saved = FuelCycleTracker.debugSimulateCycle();
                            if (saved) {
                                client.player.sendMessage(
                                        Text.literal("[FuelCycleTracker Debug] Save succeeded — file exists on disk.")
                                                .formatted(Formatting.GREEN),
                                        false
                                );
                                client.player.sendMessage(
                                        Text.literal("[FuelCycleTracker Debug] Run /fueltime to confirm the cycle appears, then check latest.log for any errors.")
                                                .formatted(Formatting.GRAY),
                                        false
                                );
                            } else {
                                client.player.sendMessage(
                                        Text.literal("[FuelCycleTracker Debug] Save FAILED — file not found after save attempt. Check latest.log for the IOException.")
                                                .formatted(Formatting.RED),
                                        false
                                );
                            }
                            return 1;
                        }))
                .then(argument("cycle", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .executes(ctx -> {
                            int cycleNumber = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cycle");
                            FuelCycleTracker.displayStats(ctx.getSource().getClient(), cycleNumber);
                            return 1;
                        })));

        // /webhook
        dispatcher.register(literal("webhook")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    String url = DiscordWebhookManager.getWebhook();
                    if (url != null && !url.isEmpty()) {
                        client.player.sendMessage(
                                Text.literal("Webhook URL: " + url).formatted(Formatting.AQUA), false);
                    } else {
                        client.player.sendMessage(
                                Text.literal("No webhook set").formatted(Formatting.YELLOW), false);
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
                                        Text.literal("Webhook cleared").formatted(Formatting.GREEN), false);
                            } else if (url.startsWith("https://discord.com/api/webhooks/")) {
                                DiscordWebhookManager.setWebhook(url);
                                client.player.sendMessage(
                                        Text.literal("Webhook set successfully").formatted(Formatting.GREEN), false);
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

        // /discordping
        dispatcher.register(literal("discordping")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    String userId = DiscordWebhookManager.getDiscordUserId();
                    if (userId != null && !userId.isEmpty()) {
                        client.player.sendMessage(
                                Text.literal("Discord ping ID: ").formatted(Formatting.AQUA)
                                        .append(Text.literal(userId).formatted(Formatting.YELLOW)),
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
                                        Text.literal("Discord ping ID cleared").formatted(Formatting.GREEN), false);
                                return 1;
                            }
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
                                    Text.literal("Discord ping ID set to: ").formatted(Formatting.GREEN)
                                            .append(Text.literal(userId).formatted(Formatting.YELLOW)),
                                    false
                            );
                            return 1;
                        })));

        // /chatrelay
        dispatcher.register(literal("chatrelay")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    String url    = ChatRelayConfig.getChatWebhookUrl();
                    int    port   = ChatRelayConfig.getPollPort();
                    String secret = ChatRelayConfig.getPollSecret();

                    client.player.sendMessage(
                            Text.literal("─── Chat Relay Config ───").formatted(Formatting.AQUA), false);
                    client.player.sendMessage(
                            Text.literal("Webhook: ").formatted(Formatting.GRAY)
                                    .append(Text.literal(url.isEmpty() ? "(not set)" : url)
                                            .formatted(url.isEmpty() ? Formatting.YELLOW : Formatting.GREEN)),
                            false);
                    client.player.sendMessage(
                            Text.literal("Poll port: ").formatted(Formatting.GRAY)
                                    .append(Text.literal(String.valueOf(port)).formatted(Formatting.WHITE)),
                            false);
                    client.player.sendMessage(
                            Text.literal("Poll secret: ").formatted(Formatting.GRAY)
                                    .append(Text.literal(secret).formatted(Formatting.WHITE)),
                            false);
                    client.player.sendMessage(
                            Text.literal("Use /chatrelay help for subcommands.").formatted(Formatting.DARK_GRAY), false);
                    return 1;
                })
                .then(literal("help")
                        .executes(ctx -> {
                            MinecraftClient client = ctx.getSource().getClient();
                            client.player.sendMessage(
                                    Text.literal("─── /chatrelay subcommands ───").formatted(Formatting.AQUA), false);
                            client.player.sendMessage(
                                    Text.literal("/chatrelay webhook <url|clear>").formatted(Formatting.YELLOW)
                                            .append(Text.literal(" — set/clear the outbound Discord webhook").formatted(Formatting.GRAY)),
                                    false);
                            client.player.sendMessage(
                                    Text.literal("/chatrelay port <number>").formatted(Formatting.YELLOW)
                                            .append(Text.literal(" — set localhost poll port (default 8765)").formatted(Formatting.GRAY)),
                                    false);
                            client.player.sendMessage(
                                    Text.literal("/chatrelay secret <string>").formatted(Formatting.YELLOW)
                                            .append(Text.literal(" — set the poll secret key").formatted(Formatting.GRAY)),
                                    false);
                            return 1;
                        }))
                .then(literal("webhook")
                        .then(argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String url = StringArgumentType.getString(ctx, "url");
                                    MinecraftClient client = ctx.getSource().getClient();
                                    if (url.equalsIgnoreCase("clear")) {
                                        ChatRelayConfig.setChatWebhookUrl("");
                                        client.player.sendMessage(
                                                Text.literal("Chat relay webhook cleared.").formatted(Formatting.GREEN), false);
                                    } else if (url.startsWith("https://discord.com/api/webhooks/")) {
                                        ChatRelayConfig.setChatWebhookUrl(url);
                                        client.player.sendMessage(
                                                Text.literal("Chat relay webhook set.").formatted(Formatting.GREEN), false);
                                    } else {
                                        client.player.sendMessage(
                                                Text.literal("Invalid URL. Must start with https://discord.com/api/webhooks/")
                                                        .formatted(Formatting.RED),
                                                false);
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(literal("port")
                        .then(argument("port", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 65535))
                                .executes(ctx -> {
                                    int port = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "port");
                                    ChatRelayConfig.setPollPort(port);
                                    ctx.getSource().getClient().player.sendMessage(
                                            Text.literal("Poll port set to " + port + ". Rejoin the server to apply.")
                                                    .formatted(Formatting.GREEN),
                                            false);
                                    return 1;
                                })))
                .then(literal("secret")
                        .then(argument("secret", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String secret = StringArgumentType.getString(ctx, "secret");
                                    ChatRelayConfig.setPollSecret(secret);
                                    ctx.getSource().getClient().player.sendMessage(
                                            Text.literal("Poll secret updated.").formatted(Formatting.GREEN), false);
                                    return 1;
                                }))));

        // /trackitem
        dispatcher.register(literal("trackitem")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    String uuid = ItemTracker.getTrackedUuid();
                    String name = ItemTracker.getTrackedItemName();
                    if (uuid != null && !uuid.isEmpty()) {
                        client.player.sendMessage(
                                Text.literal("Currently tracking: ").formatted(Formatting.AQUA)
                                        .append(Text.literal(name != null ? name : "Unknown item").formatted(Formatting.YELLOW))
                                        .append(Text.literal("\nUUID: " + uuid).formatted(Formatting.GRAY)),
                                false
                        );
                    } else {
                        client.player.sendMessage(
                                Text.literal("No item is being tracked").formatted(Formatting.YELLOW), false);
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
                                        Text.literal("Now tracking: ").formatted(Formatting.GREEN)
                                                .append(Text.literal(name).formatted(Formatting.YELLOW))
                                                .append(Text.literal("\nUUID: " + uuid).formatted(Formatting.GRAY)),
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
                            ItemTracker.clearTrackedItem();
                            ctx.getSource().getClient().player.sendMessage(
                                    Text.literal("Cleared tracked item").formatted(Formatting.GREEN), false);
                            return 1;
                        })));

        // /trackedalertspeed
        dispatcher.register(literal("trackedalertspeed")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    client.player.sendMessage(
                            Text.literal("Current tracked item alert speed: ").formatted(Formatting.AQUA)
                                    .append(Text.literal(ItemTracker.getAlertSpeed().name().toLowerCase())
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
                                        Text.literal("Tracked item alert speed set to: ").formatted(Formatting.GREEN)
                                                .append(Text.literal(speed.name().toLowerCase()).formatted(Formatting.YELLOW)),
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

        // /islandalertspeed
        dispatcher.register(literal("islandalertspeed")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    client.player.sendMessage(
                            Text.literal("Current island alert speed: ").formatted(Formatting.AQUA)
                                    .append(Text.literal(IslandAlertTracker.getAlertSpeed().name().toLowerCase())
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
                                        Text.literal("Island alert speed set to: ").formatted(Formatting.GREEN)
                                                .append(Text.literal(speed.name().toLowerCase()).formatted(Formatting.YELLOW)),
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

        // /disconnectalertspeed
        dispatcher.register(literal("disconnectalertspeed")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    client.player.sendMessage(
                            Text.literal("Current disconnect alert speed: ").formatted(Formatting.AQUA)
                                    .append(Text.literal(DisconnectAlertTracker.getAlertSpeed().name().toLowerCase())
                                            .formatted(Formatting.YELLOW)),
                            false
                    );
                    client.player.sendMessage(Text.literal("\nDisconnect alert speeds:").formatted(Formatting.GRAY), false);
                    client.player.sendMessage(Text.literal("  off: ").formatted(Formatting.WHITE)
                            .append(Text.literal("No alerts").formatted(Formatting.GRAY)), false);
                    client.player.sendMessage(Text.literal("  fast: ").formatted(Formatting.WHITE)
                            .append(Text.literal("After 10s, then every 5 min").formatted(Formatting.GRAY)), false);
                    client.player.sendMessage(Text.literal("  normal: ").formatted(Formatting.WHITE)
                            .append(Text.literal("After 10s, then every 30 min").formatted(Formatting.GRAY)), false);
                    client.player.sendMessage(Text.literal("  slow: ").formatted(Formatting.WHITE)
                            .append(Text.literal("After 10s, then every 3 hours").formatted(Formatting.GRAY)), false);
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
                                        Text.literal("Disconnect alert speed set to: ").formatted(Formatting.GREEN)
                                                .append(Text.literal(speed.name().toLowerCase()).formatted(Formatting.YELLOW)),
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

        // /trackislandtime
        dispatcher.register(literal("trackislandtime")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    boolean current = TrackingToggleConfig.isIslandTimeEnabled();
                    client.player.sendMessage(
                            Text.literal("Island-time tracking is currently: ").formatted(Formatting.AQUA)
                                    .append(Text.literal(current ? "enabled" : "disabled")
                                            .formatted(current ? Formatting.GREEN : Formatting.RED)),
                            false
                    );
                    client.player.sendMessage(
                            Text.literal("Use /trackislandtime <on|off> to change.").formatted(Formatting.GRAY),
                            false
                    );
                    return 1;
                })
                .then(argument("state", StringArgumentType.word())
                        .executes(ctx -> {
                            String state = StringArgumentType.getString(ctx, "state").toLowerCase();
                            MinecraftClient client = ctx.getSource().getClient();
                            if (state.equals("on")) {
                                TrackingToggleConfig.setIslandTimeEnabled(true);
                                client.player.sendMessage(
                                        Text.literal("Island-time tracking enabled.").formatted(Formatting.GREEN), false);
                            } else if (state.equals("off")) {
                                TrackingToggleConfig.setIslandTimeEnabled(false);
                                client.player.sendMessage(
                                        Text.literal("Island-time tracking disabled. Current day data is preserved; no new days will be tracked.")
                                                .formatted(Formatting.YELLOW),
                                        false
                                );
                            } else {
                                client.player.sendMessage(
                                        Text.literal("Usage: /trackislandtime <on|off>").formatted(Formatting.RED), false);
                                return 0;
                            }
                            return 1;
                        })));

        // /trackfuelcycles
        dispatcher.register(literal("trackfuelcycles")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    boolean current = TrackingToggleConfig.isFuelCycleEnabled();
                    client.player.sendMessage(
                            Text.literal("Fuel-cycle tracking is currently: ").formatted(Formatting.AQUA)
                                    .append(Text.literal(current ? "enabled" : "disabled")
                                            .formatted(current ? Formatting.GREEN : Formatting.RED)),
                            false
                    );
                    client.player.sendMessage(
                            Text.literal("Use /trackfuelcycles <on|off> to change.").formatted(Formatting.GRAY),
                            false
                    );
                    return 1;
                })
                .then(argument("state", StringArgumentType.word())
                        .executes(ctx -> {
                            String state = StringArgumentType.getString(ctx, "state").toLowerCase();
                            MinecraftClient client = ctx.getSource().getClient();
                            if (state.equals("on")) {
                                TrackingToggleConfig.setFuelCycleEnabled(true);
                                client.player.sendMessage(
                                        Text.literal("Fuel-cycle tracking enabled.").formatted(Formatting.GREEN), false);
                            } else if (state.equals("off")) {
                                TrackingToggleConfig.setFuelCycleEnabled(false);
                                client.player.sendMessage(
                                        Text.literal("Fuel-cycle tracking disabled. Any active cycle will complete naturally; no new cycles will start.")
                                                .formatted(Formatting.YELLOW),
                                        false
                                );
                            } else {
                                client.player.sendMessage(
                                        Text.literal("Usage: /trackfuelcycles <on|off>").formatted(Formatting.RED), false);
                                return 0;
                            }
                            return 1;
                        })));

        // /favouritegui
        dispatcher.register(literal("favouritegui")
                .executes(ctx -> {
                    MinecraftClient client = ctx.getSource().getClient();
                    Set<String> savedNames = FavouriteGuiManager.getSavedNames();

                    if (savedNames.isEmpty()) {
                        client.player.sendMessage(
                                Text.literal("No saved containers yet. Use /favouritegui save <name> to save a container.")
                                        .formatted(Formatting.YELLOW),
                                false
                        );
                    } else {
                        client.player.sendMessage(
                                Text.literal("Saved containers (" + savedNames.size() + "):").formatted(Formatting.GOLD),
                                false
                        );
                        for (String name : savedNames) {
                            FavouriteGuiManager.SavedContainer container = FavouriteGuiManager.getSavedContainer(name);
                            client.player.sendMessage(
                                    Text.literal("  • ").formatted(Formatting.GRAY)
                                            .append(Text.literal(name).formatted(Formatting.AQUA)
                                                    .styled(style -> style
                                                            .withClickEvent(new ClickEvent.RunCommand(
                                                                    "/favouritegui view " + name))
                                                            .withHoverEvent(new HoverEvent.ShowText(
                                                                    Text.literal("Click to view").formatted(Formatting.GRAY)))))
                                            .append(Text.literal(" - ").formatted(Formatting.GRAY))
                                            .append(Text.literal(container.title).formatted(Formatting.WHITE))
                                            .append(Text.literal(" (" + container.items.size() + " items)").formatted(Formatting.GRAY)),
                                    false
                            );
                        }
                    }
                    return 1;
                })
                .then(literal("save")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    MinecraftClient client = ctx.getSource().getClient();

                                    if (FavouriteGuiManager.saveCurrentContainer(name)) {
                                        FavouriteGuiManager.SavedContainer container = FavouriteGuiManager.getSavedContainer(name);
                                        client.player.sendMessage(
                                                Text.literal("Saved container '").formatted(Formatting.GREEN)
                                                        .append(Text.literal(name).formatted(Formatting.YELLOW))
                                                        .append(Text.literal("' (").formatted(Formatting.GREEN))
                                                        .append(Text.literal(container.title).formatted(Formatting.WHITE))
                                                        .append(Text.literal(", " + container.size + " slots, " + container.items.size() + " items)").formatted(Formatting.GREEN)),
                                                false
                                        );
                                    } else {
                                        client.player.sendMessage(
                                                Text.literal("Cannot save: No container is currently open or it's not a valid container type.")
                                                        .formatted(Formatting.RED),
                                                false
                                        );
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(literal("delete")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    FavouriteGuiManager.getSavedNames().forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    MinecraftClient client = ctx.getSource().getClient();

                                    if (FavouriteGuiManager.deleteSavedContainer(name)) {
                                        client.player.sendMessage(
                                                Text.literal("Deleted saved container '").formatted(Formatting.GREEN)
                                                        .append(Text.literal(name).formatted(Formatting.YELLOW))
                                                        .append(Text.literal("'").formatted(Formatting.GREEN)),
                                                false
                                        );
                                    } else {
                                        client.player.sendMessage(
                                                Text.literal("No saved container found with name '").formatted(Formatting.RED)
                                                        .append(Text.literal(name).formatted(Formatting.YELLOW))
                                                        .append(Text.literal("'").formatted(Formatting.RED)),
                                                false
                                        );
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(literal("view")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    FavouriteGuiManager.getSavedNames().forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    MinecraftClient client = ctx.getSource().getClient();

                                    if (FavouriteGuiViewer.open(name)) {
                                        client.player.sendMessage(
                                                Text.literal("Opening saved container '").formatted(Formatting.GREEN)
                                                        .append(Text.literal(name).formatted(Formatting.YELLOW))
                                                        .append(Text.literal("'").formatted(Formatting.GREEN)),
                                                false
                                        );
                                    } else {
                                        client.player.sendMessage(
                                                Text.literal("No saved container found with name '").formatted(Formatting.RED)
                                                        .append(Text.literal(name).formatted(Formatting.YELLOW))
                                                        .append(Text.literal("'").formatted(Formatting.RED)),
                                                false
                                        );
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(literal("open")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    FavouriteGuiManager.getSavedNames().forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    MinecraftClient client = ctx.getSource().getClient();

                                    if (FavouriteGuiViewer.open(name)) {
                                        client.player.sendMessage(
                                                Text.literal("Opening saved container '").formatted(Formatting.GREEN)
                                                        .append(Text.literal(name).formatted(Formatting.YELLOW))
                                                        .append(Text.literal("'").formatted(Formatting.GREEN)),
                                                false
                                        );
                                    } else {
                                        client.player.sendMessage(
                                                Text.literal("No saved container found with name '").formatted(Formatting.RED)
                                                        .append(Text.literal(name).formatted(Formatting.YELLOW))
                                                        .append(Text.literal("'").formatted(Formatting.RED)),
                                                false
                                        );
                                        return 0;
                                    }
                                    return 1;
                                }))));
    }

    // -------------------------------------------------------------------------
    // Help
    // -------------------------------------------------------------------------

    private static void showHelp(MinecraftClient client, int page) {
        String[][] commands = {
                // Page 1 - Basic commands
                {"/islandtime", "Show last 7 days of island time"},
                {"/islandtime <DD-MM-YY>", "Show specific date (e.g., /islandtime 1-1-26)"},
                {"/islandtime help [page]", "Show this help page"},
                {"/fueltime", "Show current and recent fuel cycle stats (last 4 cycles)"},
                {"/fueltime <cycle>", "Show details for a specific cycle number (e.g., /fueltime 6)"},
                {"/fueltime debug", "Inject a fake completed cycle and attempt save (for diagnostics)"},
                {"/webhook", "Show current webhook URL"},

                // Page 2 - Webhook & tracking
                {"/webhook <url>", "Set webhook URL (for Discord alerts)"},
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
                {"/disconnectalertspeed <speed>", "Set disconnect alert speed (off/fast/normal/slow). " +
                        "Alerts fire after a 10s grace period — no alert if you reconnect within 10 seconds."},

                // Page 4 - Tracking toggles
                {"/trackislandtime", "Show whether island-time tracking is on or off"},
                {"/trackislandtime <on|off>", "Enable or disable island-time tracking. " +
                        "Disabling preserves all existing data and finishes the current day, " +
                        "but no new days will be tracked."},
                {"/trackfuelcycles", "Show whether fuel-cycle tracking is on or off"},
                {"/trackfuelcycles <on|off>", "Enable or disable fuel-cycle tracking. " +
                        "Disabling lets any active cycle complete naturally, " +
                        "but fueling a minion will not start a new cycle."},

                // Page 5 - Favourite GUI
                {"/favouritegui", "List all saved container layouts"},
                {"/favouritegui save <name>", "Save the currently open container under <name>"},
                {"/favouritegui delete <name>", "Remove a previously saved container layout"},
                {"/favouritegui view <name>", "Open a saved container layout as a read-only overlay"},
                {"/favouritegui open <name>", "Alias for /favouritegui view <name>"},

                // Page 6 - Chat relay
                {"/chatrelay", "Show current chat relay config (webhook, port, secret)"},
                {"/chatrelay webhook <url>", "Set the Discord webhook for outbound island chat relay"},
                {"/chatrelay webhook clear", "Disable outbound chat relay"},
                {"/chatrelay port <number>", "Set localhost poll port for inbound relay (default 8765)"},
                {"/chatrelay secret <string>", "Set shared secret key for the poll endpoint"},
                {"/chatrelay help", "Show chat relay subcommand reference"},
        };

        int commandsPerPage = 7;
        int totalCommands   = commands.length;
        int totalPages      = (int) Math.ceil((double) totalCommands / commandsPerPage);

        if (page < 1 || page > totalPages) {
            client.player.sendMessage(
                    Text.literal("Invalid page number. Valid range: 1-" + totalPages).formatted(Formatting.RED),
                    false
            );
            return;
        }

        client.player.sendMessage(
                Text.literal("=== Island Time Mod Commands (Page " + page + "/" + totalPages + ") ===")
                        .formatted(Formatting.GOLD),
                false
        );
        client.player.sendMessage(Text.literal(""), false);

        int startIdx = (page - 1) * commandsPerPage;
        int endIdx   = Math.min(startIdx + commandsPerPage, totalCommands);

        for (int i = startIdx; i < endIdx; i++) {
            client.player.sendMessage(Text.literal(commands[i][0]).formatted(Formatting.WHITE), false);
            client.player.sendMessage(Text.literal("  " + commands[i][1]).formatted(Formatting.GRAY), false);
            if (i < endIdx - 1) client.player.sendMessage(Text.literal(""), false);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void showAlertSpeedOptions(MinecraftClient client) {
        client.player.sendMessage(Text.literal("\nAlert speeds:").formatted(Formatting.GRAY), false);
        client.player.sendMessage(Text.literal("  off: ").formatted(Formatting.WHITE)
                .append(Text.literal("No alerts").formatted(Formatting.GRAY)), false);
        client.player.sendMessage(Text.literal("  fast: ").formatted(Formatting.WHITE)
                .append(Text.literal("1 min, then every 5 min").formatted(Formatting.GRAY)), false);
        client.player.sendMessage(Text.literal("  normal: ").formatted(Formatting.WHITE)
                .append(Text.literal("10 min, then every 30 min").formatted(Formatting.GRAY)), false);
        client.player.sendMessage(Text.literal("  slow: ").formatted(Formatting.WHITE)
                .append(Text.literal("1 hour, then every 3 hours").formatted(Formatting.GRAY)), false);
    }

    private static void showRecent() {
        MinecraftClient client = MinecraftClient.getInstance();
        var times = IslandTimeTracker.getTimes();

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

        String today = LocalDate.now().toString();
        int    shown = 0;

        client.player.sendMessage(Text.literal("» Island Time (Last 7 Days)").formatted(Formatting.GOLD), false);

        for (String day : days) {
            long    ms      = times.get(day);
            boolean isToday = day.equals(today);
            if (shown == 1) {
                client.player.sendMessage(Text.literal("-----------------------").formatted(Formatting.GRAY), false);
            }
            formatLineColored(client, day, ms, isToday);
            shown++;
            if (shown >= 7) break;
        }
    }

    private static void showSpecificDate(String input) {
        MinecraftClient client = MinecraftClient.getInstance();
        var times = IslandTimeTracker.getTimes();

        LocalDate date;
        try {
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
                    Text.literal("No data found for the specified date").formatted(Formatting.RED), false);
            return;
        }

        String  today   = LocalDate.now().toString();
        boolean isToday = key.equals(today);
        formatLineColored(client, key, times.get(key), isToday);
    }

    private static LocalDate parseFlexibleDate(String input) {
        String[] parts = input.split("-");
        if (parts.length != 3) throw new DateTimeParseException("Invalid format", input, 0);
        int day   = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int year  = Integer.parseInt(parts[2]);
        if (year < 100) year += 2000;
        return LocalDate.of(year, month, day);
    }

    private static void formatLineColored(MinecraftClient client, String date, long ms, boolean isToday) {
        long seconds = ms / 1000;
        long h       = seconds / 3600;
        long m       = (seconds % 3600) / 60;
        long s       = seconds % 60;

        String    prettyDate = LocalDate.parse(date).format(OUTPUT_FORMAT);
        String    timeStr    = String.format(": %02dh%02dm%02ds", h, m, s);
        Formatting dateColor = isToday ? Formatting.GREEN : Formatting.YELLOW;

        client.player.sendMessage(
                Text.literal(prettyDate).formatted(dateColor)
                        .append(Text.literal(timeStr).formatted(Formatting.WHITE)),
                false
        );
    }
}
