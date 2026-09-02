package org.example.islandtime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.awt.image.BufferedImage;

import java.io.IOException;
import javax.imageio.ImageIO;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

public class IslandTimeCommand {

    private static final DateTimeFormatter OUTPUT_FORMAT =
            DateTimeFormatter.ofPattern("MMM d");

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> registerCommand(dispatcher)
        );
    }

    private static void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {

        // /islandtime — open MoulConfig GUI
        dispatcher.register(literal("islandtime")
                .executes(ctx -> {
                    openConfigGui();
                    return 1;
                })
                .then(literal("ghost")
                        .executes(ctx -> {
                            ghostBlock(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("waila")
                        .executes(ctx -> {
                            WailaHandler.inspect(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("copyskin")
                        .executes(ctx -> {
                            copySkinImage(ctx.getSource().getClient(), null);
                            return 1;
                        })
                        .then(argument("hash", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    copySkinImage(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "hash"));
                                    return 1;
                                })))
                .then(literal("recent")
                        .executes(ctx -> {
                            showRecent();
                            return 1;
                        }))
                .then(literal("highlights")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new BlockHighlightScreen()));
                            return 1;
                        }))
                .then(literal("highlight")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new BlockHighlightScreen()));
                            return 1;
                        })
                        .then(literal("add")
                                .then(argument("blockid", StringArgumentType.greedyString())
                                        .executes(ctx -> { return highlightAdd(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "blockid")); })))
                        .then(literal("remove")
                                .then(argument("blockid", StringArgumentType.greedyString())
                                        .executes(ctx -> { return highlightRemove(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "blockid")); }))))
                .then(literal("savesettings")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.save(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("loadsettings")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.load(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("deletesettings")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.delete(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("listsettings")
                        .executes(ctx -> {
                            SettingsLoadoutManager.list(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("groupsettings")
                        .then(argument("names", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String[] parts = StringArgumentType.getString(ctx, "names").split("\\s+");
                                    if (parts.length < 3) {
                                        ctx.getSource().getClient().player.sendSystemMessage(
                                                Component.literal("[IslandTime] Usage: /it groupsettings <groupName> <loadout1> <loadout2> [...]").withStyle(ChatFormatting.RED));
                                        return 1;
                                    }
                                    String groupName = parts[0];
                                    List<String> loadouts = new java.util.ArrayList<>();
                                    for (int i = 1; i < parts.length; i++) loadouts.add(parts[i]);
                                    SettingsLoadoutManager.setGroup(ctx.getSource().getClient(), groupName, loadouts);
                                    return 1;
                                })))
                .then(literal("cyclesettings")
                        .executes(ctx -> {
                            SettingsLoadoutManager.cycle(ctx.getSource().getClient());
                            return 1;
                        }))
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

        // /it — alias for /islandtime
        dispatcher.register(literal("it")
                .executes(ctx -> {
                    openConfigGui();
                    return 1;
                })
                .then(literal("ghost")
                        .executes(ctx -> {
                            ghostBlock(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("waila")
                        .executes(ctx -> {
                            WailaHandler.inspect(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("copyskin")
                        .executes(ctx -> {
                            copySkinImage(ctx.getSource().getClient(), null);
                            return 1;
                        })
                        .then(argument("hash", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    copySkinImage(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "hash"));
                                    return 1;
                                })))
                .then(literal("recent")
                        .executes(ctx -> {
                            showRecent();
                            return 1;
                        }))
                .then(literal("highlights")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new BlockHighlightScreen()));
                            return 1;
                        }))
                .then(literal("highlight")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new BlockHighlightScreen()));
                            return 1;
                        })
                        .then(literal("add")
                                .then(argument("blockid", StringArgumentType.greedyString())
                                        .executes(ctx -> { return highlightAdd(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "blockid")); })))
                        .then(literal("remove")
                                .then(argument("blockid", StringArgumentType.greedyString())
                                        .executes(ctx -> { return highlightRemove(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "blockid")); }))))
                .then(literal("agingItems")
                        .executes(ctx -> {
                            AgingItemsCommand.execute(ctx.getSource().getClient());
                            return 1;
                        })
                        .then(literal("add")
                                .then(argument("agingId", StringArgumentType.word())
                                        .then(argument("agedId", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String agingId = StringArgumentType.getString(ctx, "agingId");
                                                    String agedId = StringArgumentType.getString(ctx, "agedId");
                                                    Minecraft client = ctx.getSource().getClient();
                                                    if (AgingItemsConfig.addPair(agingId, agedId)) {
                                                        client.player.sendSystemMessage(
                                                                Component.literal("Added aging pair: ").withStyle(ChatFormatting.GREEN)
                                                                        .append(Component.literal(agingId.toUpperCase()).withStyle(ChatFormatting.YELLOW))
                                                                        .append(Component.literal(" → ").withStyle(ChatFormatting.GRAY))
                                                                        .append(Component.literal(agedId.toUpperCase()).withStyle(ChatFormatting.YELLOW)));
                                                    } else {
                                                        client.player.sendSystemMessage(
                                                                Component.literal("That pair already exists.").withStyle(ChatFormatting.RED));
                                                    }
                                                    return 1;
                                                }))))
                        .then(literal("remove")
                                .then(argument("agingId", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String agingId = StringArgumentType.getString(ctx, "agingId");
                                            Minecraft client = ctx.getSource().getClient();
                                            if (AgingItemsConfig.removePair(agingId)) {
                                                client.player.sendSystemMessage(
                                                        Component.literal("Removed aging pair for: ").withStyle(ChatFormatting.GREEN)
                                                                .append(Component.literal(agingId.toUpperCase()).withStyle(ChatFormatting.YELLOW)));
                                            } else {
                                                client.player.sendSystemMessage(
                                                        Component.literal("No pair found with aging ID: ").withStyle(ChatFormatting.RED)
                                                                .append(Component.literal(agingId.toUpperCase()).withStyle(ChatFormatting.YELLOW)));
                                            }
                                            return 1;
                                        })))
                        .then(literal("list")
                                .executes(ctx -> {
                                    Minecraft client = ctx.getSource().getClient();
                                    List<String[]> pairs = AgingItemsConfig.getItemPairs();
                                    if (pairs.isEmpty()) {
                                        client.player.sendSystemMessage(
                                                Component.literal("No aging item pairs configured.").withStyle(ChatFormatting.YELLOW));
                                    } else {
                                        client.player.sendSystemMessage(
                                                Component.literal("Aging item pairs (" + pairs.size() + "):").withStyle(ChatFormatting.GOLD));
                                        for (String[] p : pairs) {
                                            client.player.sendSystemMessage(
                                                    Component.literal("  ").withStyle(ChatFormatting.GRAY)
                                                            .append(Component.literal(p[0]).withStyle(ChatFormatting.YELLOW))
                                                            .append(Component.literal(" → ").withStyle(ChatFormatting.GRAY))
                                                            .append(Component.literal(p[1]).withStyle(ChatFormatting.AQUA)));
                                        }
                                    }
                                    return 1;
                                })))
                .then(literal("cookieProfit")
                        .executes(ctx -> {
                            CookieProfitCommand.execute(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("players")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new PlayerListScreen()));
                            return 1;
                        }))
                .then(literal("fueldays")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new FuelDayScreen()));
                            return 1;
                        }))
                .then(literal("savesettings")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.save(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("loadsettings")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.load(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("deletesettings")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.delete(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("listsettings")
                        .executes(ctx -> {
                            SettingsLoadoutManager.list(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("groupsettings")
                        .then(argument("names", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String[] parts = StringArgumentType.getString(ctx, "names").split("\\s+");
                                    if (parts.length < 3) {
                                        ctx.getSource().getClient().player.sendSystemMessage(
                                                Component.literal("[IslandTime] Usage: /it groupsettings <groupName> <loadout1> <loadout2> [...]").withStyle(ChatFormatting.RED));
                                        return 1;
                                    }
                                    String groupName = parts[0];
                                    List<String> loadouts = new java.util.ArrayList<>();
                                    for (int i = 1; i < parts.length; i++) loadouts.add(parts[i]);
                                    SettingsLoadoutManager.setGroup(ctx.getSource().getClient(), groupName, loadouts);
                                    return 1;
                                })))
                .then(literal("cyclesettings")
                        .executes(ctx -> {
                            SettingsLoadoutManager.cycle(ctx.getSource().getClient());
                            return 1;
                        }))
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

        // /fueltime — opens the fuel day tracker GUI (use /it fueldays for the same thing).
        // A date argument still prints a quick chat summary for that day.
        dispatcher.register(literal("fueltime")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    client.execute(() -> client.setScreen(new FuelDayScreen()));
                    return 1;
                })
                .then(literal("debug")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.player.sendSystemMessage(
                                    Component.literal("[FuelCycleTracker Debug] Injecting fake day record and saving...")
                                            .withStyle(ChatFormatting.YELLOW));
                            boolean saved = FuelCycleTracker.debugSimulateDay();
                            if (saved) {
                                client.player.sendSystemMessage(
                                        Component.literal("[FuelCycleTracker Debug] Save succeeded — file exists on disk.")
                                                .withStyle(ChatFormatting.GREEN));
                                client.player.sendSystemMessage(
                                        Component.literal("[FuelCycleTracker Debug] Run /fueltime to confirm the day appears, then check latest.log for any errors.")
                                                .withStyle(ChatFormatting.GRAY));
                            } else {
                                client.player.sendSystemMessage(
                                        Component.literal("[FuelCycleTracker Debug] Save FAILED — file not found after save attempt. Check latest.log for the IOException.")
                                                .withStyle(ChatFormatting.RED));
                            }
                            return 1;
                        }))
                .then(argument("date", StringArgumentType.word())
                        .executes(ctx -> {
                            String date = StringArgumentType.getString(ctx, "date");
                            FuelCycleTracker.displayStats(ctx.getSource().getClient(), date);
                            return 1;
                        })));

        // /webhook
        dispatcher.register(literal("webhook")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    String url = DiscordWebhookManager.getWebhook();
                    if (url != null && !url.isEmpty()) {
                        client.player.sendSystemMessage(
                                Component.literal("Webhook URL: " + url).withStyle(ChatFormatting.AQUA));
                    } else {
                        client.player.sendSystemMessage(
                                Component.literal("No webhook set").withStyle(ChatFormatting.YELLOW));
                    }
                    return 1;
                })
                .then(argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String url = StringArgumentType.getString(ctx, "url");
                            Minecraft client = ctx.getSource().getClient();
                            if (url.equalsIgnoreCase("clear")) {
                                DiscordWebhookManager.clearWebhook();
                                client.player.sendSystemMessage(
                                        Component.literal("Webhook cleared").withStyle(ChatFormatting.GREEN));
                            } else if (url.startsWith("https://discord.com/api/webhooks/")) {
                                DiscordWebhookManager.setWebhook(url);
                                client.player.sendSystemMessage(
                                        Component.literal("Webhook set successfully").withStyle(ChatFormatting.GREEN));
                            } else {
                                client.player.sendSystemMessage(
                                        Component.literal("Invalid webhook URL. Must start with https://discord.com/api/webhooks/")
                                                .withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })));

        // /discordping
        dispatcher.register(literal("discordping")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    String userId = DiscordWebhookManager.getDiscordUserId();
                    if (userId != null && !userId.isEmpty()) {
                        client.player.sendSystemMessage(
                                Component.literal("Discord ping ID: ").withStyle(ChatFormatting.AQUA)
                                        .append(Component.literal(userId).withStyle(ChatFormatting.YELLOW)));
                    } else {
                        client.player.sendSystemMessage(
                                Component.literal("No Discord ping ID set (webhooks will not ping anyone)")
                                        .withStyle(ChatFormatting.YELLOW));
                    }
                    return 1;
                })
                .then(argument("userid", StringArgumentType.word())
                        .executes(ctx -> {
                            String userId = StringArgumentType.getString(ctx, "userid");
                            Minecraft client = ctx.getSource().getClient();
                            if (userId.equalsIgnoreCase("clear")) {
                                DiscordWebhookManager.clearDiscordUserId();
                                client.player.sendSystemMessage(
                                        Component.literal("Discord ping ID cleared").withStyle(ChatFormatting.GREEN));
                                return 1;
                            }
                            if (!userId.matches("\\d+")) {
                                client.player.sendSystemMessage(
                                        Component.literal("Invalid Discord user ID. Must be numeric (e.g., 123456789012345678)")
                                                .withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            DiscordWebhookManager.setDiscordUserId(userId);
                            client.player.sendSystemMessage(
                                    Component.literal("Discord ping ID set to: ").withStyle(ChatFormatting.GREEN)
                                            .append(Component.literal(userId).withStyle(ChatFormatting.YELLOW)));
                            return 1;
                        })));

        // /chatrelay
        dispatcher.register(literal("chatrelay")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    String url    = ChatRelayConfig.getChatWebhookUrl();
                    int    port   = ChatRelayConfig.getPollPort();
                    String secret = ChatRelayConfig.getPollSecret();

                    client.player.sendSystemMessage(
                            Component.literal("─── Chat Relay Config ───").withStyle(ChatFormatting.AQUA));
                    client.player.sendSystemMessage(
                            Component.literal("Webhook: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(url.isEmpty() ? "(not set)" : url)
                                            .withStyle(url.isEmpty() ? ChatFormatting.YELLOW : ChatFormatting.GREEN)));
                    client.player.sendSystemMessage(
                            Component.literal("Poll port: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.valueOf(port)).withStyle(ChatFormatting.WHITE)));
                    client.player.sendSystemMessage(
                            Component.literal("Poll secret: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(secret).withStyle(ChatFormatting.WHITE)));
                    client.player.sendSystemMessage(
                            Component.literal("Use /islandtime help 6 for subcommands.").withStyle(ChatFormatting.DARK_GRAY));
                    return 1;
                })
                .then(literal("webhook")
                        .then(argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String url = StringArgumentType.getString(ctx, "url");
                                    Minecraft client = ctx.getSource().getClient();
                                    if (url.equalsIgnoreCase("clear")) {
                                        ChatRelayConfig.setChatWebhookUrl("");
                                        client.player.sendSystemMessage(
                                                Component.literal("Chat relay webhook cleared.").withStyle(ChatFormatting.GREEN));
                                    } else if (url.startsWith("https://discord.com/api/webhooks/")) {
                                        ChatRelayConfig.setChatWebhookUrl(url);
                                        client.player.sendSystemMessage(
                                                Component.literal("Chat relay webhook set.").withStyle(ChatFormatting.GREEN));
                                    } else {
                                        client.player.sendSystemMessage(
                                                Component.literal("Invalid URL. Must start with https://discord.com/api/webhooks/")
                                                        .withStyle(ChatFormatting.RED));
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(literal("port")
                        .then(argument("port", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 65535))
                                .executes(ctx -> {
                                    int port = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "port");
                                    ChatRelayConfig.setPollPort(port);
                                    ctx.getSource().getClient().player.sendSystemMessage(
                                            Component.literal("Poll port set to " + port + ". Rejoin the server to apply.")
                                                    .withStyle(ChatFormatting.GREEN));
                                    return 1;
                                })))
                .then(literal("secret")
                        .then(argument("secret", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String secret = StringArgumentType.getString(ctx, "secret");
                                    ChatRelayConfig.setPollSecret(secret);
                                    ctx.getSource().getClient().player.sendSystemMessage(
                                            Component.literal("Poll secret updated.").withStyle(ChatFormatting.GREEN));
                                    return 1;
                                }))));

        // /trackitem
        dispatcher.register(literal("trackitem")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    String uuid = ItemTracker.getTrackedUuid();
                    String name = ItemTracker.getTrackedItemName();
                    if (uuid != null && !uuid.isEmpty()) {
                        client.player.sendSystemMessage(
                                Component.literal("Currently tracking: ").withStyle(ChatFormatting.AQUA)
                                        .append(Component.literal(name != null ? name : "Unknown item").withStyle(ChatFormatting.YELLOW))
                                        .append(Component.literal("\nUUID: " + uuid).withStyle(ChatFormatting.GRAY)));
                    } else {
                        client.player.sendSystemMessage(
                                Component.literal("No item is being tracked").withStyle(ChatFormatting.YELLOW));
                    }
                    return 1;
                })
                .then(literal("set")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            if (ItemTracker.setTrackedItemFromMainHand(client)) {
                                String name = ItemTracker.getTrackedItemName();
                                String uuid = ItemTracker.getTrackedUuid();
                                client.player.sendSystemMessage(
                                        Component.literal("Now tracking: ").withStyle(ChatFormatting.GREEN)
                                                .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                                                .append(Component.literal("\nUUID: " + uuid).withStyle(ChatFormatting.GRAY)));
                            } else {
                                client.player.sendSystemMessage(
                                        Component.literal("Cannot track this item. Item must have both 'uuid' and 'stats_book' in custom data.")
                                                .withStyle(ChatFormatting.RED));
                            }
                            return 1;
                        }))
                .then(literal("clear")
                        .executes(ctx -> {
                            ItemTracker.clearTrackedItem();
                            ctx.getSource().getClient().player.sendSystemMessage(
                                    Component.literal("Cleared tracked item").withStyle(ChatFormatting.GREEN));
                            return 1;
                        })));

        // /trackedalertspeed
        dispatcher.register(literal("trackedalertspeed")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    client.player.sendSystemMessage(
                            Component.literal("Current tracked item alert speed: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(ItemTracker.getAlertSpeed().name().toLowerCase())
                                            .withStyle(ChatFormatting.YELLOW)));
                    showAlertSpeedOptions(client);
                    return 1;
                })
                .then(argument("speed", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("off");
                            builder.suggest("fast");
                            builder.suggest("normal");
                            builder.suggest("slow");
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String speedStr = StringArgumentType.getString(ctx, "speed").toUpperCase();
                            Minecraft client = ctx.getSource().getClient();
                            try {
                                ItemTracker.AlertSpeed speed = ItemTracker.AlertSpeed.valueOf(speedStr);
                                ItemTracker.setAlertSpeed(speed);
                                client.player.sendSystemMessage(
                                        Component.literal("Tracked item alert speed set to: ").withStyle(ChatFormatting.GREEN)
                                                .append(Component.literal(speed.name().toLowerCase()).withStyle(ChatFormatting.YELLOW)));
                            } catch (IllegalArgumentException e) {
                                client.player.sendSystemMessage(
                                        Component.literal("Invalid alert speed. Use: off, fast, normal, or slow")
                                                .withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })));

        // /islandalertspeed
        dispatcher.register(literal("islandalertspeed")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    client.player.sendSystemMessage(
                            Component.literal("Current island alert speed: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(IslandAlertTracker.getAlertSpeed().name().toLowerCase())
                                            .withStyle(ChatFormatting.YELLOW)));
                    showAlertSpeedOptions(client);
                    return 1;
                })
                .then(argument("speed", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("off");
                            builder.suggest("fast");
                            builder.suggest("normal");
                            builder.suggest("slow");
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String speedStr = StringArgumentType.getString(ctx, "speed").toUpperCase();
                            Minecraft client = ctx.getSource().getClient();
                            try {
                                IslandAlertTracker.AlertSpeed speed = IslandAlertTracker.AlertSpeed.valueOf(speedStr);
                                IslandAlertTracker.setAlertSpeed(speed);
                                client.player.sendSystemMessage(
                                        Component.literal("Island alert speed set to: ").withStyle(ChatFormatting.GREEN)
                                                .append(Component.literal(speed.name().toLowerCase()).withStyle(ChatFormatting.YELLOW)));
                            } catch (IllegalArgumentException e) {
                                client.player.sendSystemMessage(
                                        Component.literal("Invalid alert speed. Use: off, fast, normal, or slow")
                                                .withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })));

        // /disconnectalertspeed
        dispatcher.register(literal("disconnectalertspeed")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    client.player.sendSystemMessage(
                            Component.literal("Current disconnect alert speed: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(DisconnectAlertTracker.getAlertSpeed().name().toLowerCase())
                                            .withStyle(ChatFormatting.YELLOW)));
                    client.player.sendSystemMessage(Component.literal("\nDisconnect alert speeds:").withStyle(ChatFormatting.GRAY));
                    client.player.sendSystemMessage(Component.literal("  off: ").withStyle(ChatFormatting.WHITE)
                            .append(Component.literal("No alerts").withStyle(ChatFormatting.GRAY)));
                    client.player.sendSystemMessage(Component.literal("  fast: ").withStyle(ChatFormatting.WHITE)
                            .append(Component.literal("After 10s, then every 5 min").withStyle(ChatFormatting.GRAY)));
                    client.player.sendSystemMessage(Component.literal("  normal: ").withStyle(ChatFormatting.WHITE)
                            .append(Component.literal("After 10s, then every 30 min").withStyle(ChatFormatting.GRAY)));
                    client.player.sendSystemMessage(Component.literal("  slow: ").withStyle(ChatFormatting.WHITE)
                            .append(Component.literal("After 10s, then every 3 hours").withStyle(ChatFormatting.GRAY)));
                    return 1;
                })
                .then(argument("speed", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("off");
                            builder.suggest("fast");
                            builder.suggest("normal");
                            builder.suggest("slow");
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String speedStr = StringArgumentType.getString(ctx, "speed").toUpperCase();
                            Minecraft client = ctx.getSource().getClient();
                            try {
                                DisconnectAlertTracker.AlertSpeed speed = DisconnectAlertTracker.AlertSpeed.valueOf(speedStr);
                                DisconnectAlertTracker.setAlertSpeed(speed);
                                client.player.sendSystemMessage(
                                        Component.literal("Disconnect alert speed set to: ").withStyle(ChatFormatting.GREEN)
                                                .append(Component.literal(speed.name().toLowerCase()).withStyle(ChatFormatting.YELLOW)));
                            } catch (IllegalArgumentException e) {
                                client.player.sendSystemMessage(
                                        Component.literal("Invalid alert speed. Use: off, fast, normal, or slow")
                                                .withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })));

        // /trackislandtime
        dispatcher.register(literal("trackislandtime")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    boolean current = TrackingToggleConfig.isIslandTimeEnabled();
                    client.player.sendSystemMessage(
                            Component.literal("Island-time tracking is currently: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(current ? "enabled" : "disabled")
                                            .withStyle(current ? ChatFormatting.GREEN : ChatFormatting.RED)));
                    client.player.sendSystemMessage(
                            Component.literal("Use /trackislandtime <on|off> to change.").withStyle(ChatFormatting.GRAY));
                    return 1;
                })
                .then(argument("state", StringArgumentType.word())
                        .executes(ctx -> {
                            String state = StringArgumentType.getString(ctx, "state").toLowerCase();
                            Minecraft client = ctx.getSource().getClient();
                            if (state.equals("on")) {
                                TrackingToggleConfig.setIslandTimeEnabled(true);
                                client.player.sendSystemMessage(
                                        Component.literal("Island-time tracking enabled.").withStyle(ChatFormatting.GREEN));
                            } else if (state.equals("off")) {
                                TrackingToggleConfig.setIslandTimeEnabled(false);
                                client.player.sendSystemMessage(
                                        Component.literal("Island-time tracking disabled. Current day data is preserved; no new days will be tracked.")
                                                .withStyle(ChatFormatting.YELLOW));
                            } else {
                                client.player.sendSystemMessage(
                                        Component.literal("Usage: /trackislandtime <on|off>").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })));

        // /trackfuelcycles
        dispatcher.register(literal("trackfuelcycles")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    boolean current = TrackingToggleConfig.isFuelCycleEnabled();
                    client.player.sendSystemMessage(
                            Component.literal("Fuel-cycle tracking is currently: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(current ? "enabled" : "disabled")
                                            .withStyle(current ? ChatFormatting.GREEN : ChatFormatting.RED)));
                    client.player.sendSystemMessage(
                            Component.literal("Use /trackfuelcycles <on|off> to change.").withStyle(ChatFormatting.GRAY));
                    return 1;
                })
                .then(argument("state", StringArgumentType.word())
                        .executes(ctx -> {
                            String state = StringArgumentType.getString(ctx, "state").toLowerCase();
                            Minecraft client = ctx.getSource().getClient();
                            if (state.equals("on")) {
                                TrackingToggleConfig.setFuelCycleEnabled(true);
                                client.player.sendSystemMessage(
                                        Component.literal("Fuel-cycle tracking enabled.").withStyle(ChatFormatting.GREEN));
                            } else if (state.equals("off")) {
                                TrackingToggleConfig.setFuelCycleEnabled(false);
                                client.player.sendSystemMessage(
                                        Component.literal("Fuel-cycle tracking disabled. Any active cycle will complete naturally; no new cycles will start.")
                                                .withStyle(ChatFormatting.YELLOW));
                            } else {
                                client.player.sendSystemMessage(
                                        Component.literal("Usage: /trackfuelcycles <on|off>").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })));

        // /minionfuelmessages
        dispatcher.register(literal("minionfuelmessages")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    boolean current = MinionFuelTracker.isShowFuelMessages();
                    client.player.sendSystemMessage(
                            Component.literal("Minion fuel messages are currently: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(current ? "enabled" : "disabled")
                                            .withStyle(current ? ChatFormatting.GREEN : ChatFormatting.RED)));
                    client.player.sendSystemMessage(
                            Component.literal("Use /minionfuelmessages <on|off> to change.").withStyle(ChatFormatting.GRAY));
                    return 1;
                })
                .then(argument("state", StringArgumentType.word())
                        .executes(ctx -> {
                            String state = StringArgumentType.getString(ctx, "state").toLowerCase();
                            Minecraft client = ctx.getSource().getClient();
                            if (state.equals("on")) {
                                MinionFuelTracker.setShowFuelMessages(true);
                                ConfigManager.syncFromExistingConfigs();
                                client.player.sendSystemMessage(
                                        Component.literal("Minion fuel messages enabled.").withStyle(ChatFormatting.GREEN));
                            } else if (state.equals("off")) {
                                MinionFuelTracker.setShowFuelMessages(false);
                                ConfigManager.syncFromExistingConfigs();
                                client.player.sendSystemMessage(
                                        Component.literal("Minion fuel messages disabled.").withStyle(ChatFormatting.YELLOW));
                            } else {
                                client.player.sendSystemMessage(
                                        Component.literal("Usage: /minionfuelmessages <on|off>").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })));

        // /islandleavemessages
        dispatcher.register(literal("islandleavemessages")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    boolean current = IslandPlayerTracker.isShowLeaveMessages();
                    client.player.sendSystemMessage(
                            Component.literal("Island leave messages are currently: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(current ? "enabled" : "disabled")
                                            .withStyle(current ? ChatFormatting.GREEN : ChatFormatting.RED)));
                    client.player.sendSystemMessage(
                            Component.literal("Use /islandleavemessages <on|off> to change.").withStyle(ChatFormatting.GRAY));
                    return 1;
                })
                .then(argument("state", StringArgumentType.word())
                        .executes(ctx -> {
                            String state = StringArgumentType.getString(ctx, "state").toLowerCase();
                            Minecraft client = ctx.getSource().getClient();
                            if (state.equals("on")) {
                                IslandPlayerTracker.setShowLeaveMessages(true);
                                ConfigManager.syncFromExistingConfigs();
                                client.player.sendSystemMessage(
                                        Component.literal("Island leave messages enabled.").withStyle(ChatFormatting.GREEN));
                            } else if (state.equals("off")) {
                                IslandPlayerTracker.setShowLeaveMessages(false);
                                ConfigManager.syncFromExistingConfigs();
                                client.player.sendSystemMessage(
                                        Component.literal("Island leave messages disabled.").withStyle(ChatFormatting.YELLOW));
                            } else {
                                client.player.sendSystemMessage(
                                        Component.literal("Usage: /islandleavemessages <on|off>").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })));

        // /favouritegui
        dispatcher.register(literal("favouritegui")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    Set<String> savedNames = FavouriteGuiManager.getSavedNames();

                    if (savedNames.isEmpty()) {
                        client.player.sendSystemMessage(
                                Component.literal("No saved containers yet. Use /favouritegui save <name> to save a container.")
                                        .withStyle(ChatFormatting.YELLOW));
                    } else {
                        client.player.sendSystemMessage(
                                Component.literal("Saved containers (" + savedNames.size() + "):").withStyle(ChatFormatting.GOLD));
                        for (String name : savedNames) {
                            FavouriteGuiManager.SavedContainer container = FavouriteGuiManager.getSavedContainer(name);
                            client.player.sendSystemMessage(
                                    Component.literal("  • ").withStyle(ChatFormatting.GRAY)
                                            .append(Component.literal(name).withStyle(ChatFormatting.AQUA)
                                                    .withStyle(style -> style
                                                            .withClickEvent(new ClickEvent.RunCommand(
                                                                    "/favouritegui view " + name))
                                                            .withHoverEvent(new HoverEvent.ShowText(
                                                                    Component.literal("Click to view").withStyle(ChatFormatting.GRAY)))))
                                            .append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                                            .append(Component.literal(container.title).withStyle(ChatFormatting.WHITE))
                                            .append(Component.literal(" (" + container.items.size() + " items)").withStyle(ChatFormatting.GRAY)));
                        }
                    }
                    return 1;
                })
                .then(literal("save")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    Minecraft client = ctx.getSource().getClient();

                                    if (FavouriteGuiManager.saveCurrentContainer(name)) {
                                        FavouriteGuiManager.SavedContainer container = FavouriteGuiManager.getSavedContainer(name);
                                        client.player.sendSystemMessage(
                                                Component.literal("Saved container '").withStyle(ChatFormatting.GREEN)
                                                        .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                                                        .append(Component.literal("' (").withStyle(ChatFormatting.GREEN))
                                                        .append(Component.literal(container.title).withStyle(ChatFormatting.WHITE))
                                                        .append(Component.literal(", " + container.size + " slots, " + container.items.size() + " items)").withStyle(ChatFormatting.GREEN)));
                                    } else {
                                        client.player.sendSystemMessage(
                                                Component.literal("Cannot save: No container is currently open or it's not a valid container type.")
                                                        .withStyle(ChatFormatting.RED));
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(literal("toggle")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            boolean nowEnabled = !FavouriteGuiManager.isTrackingEnabled();
                            FavouriteGuiManager.setTrackingEnabled(nowEnabled);
                            if (nowEnabled) {
                                client.player.sendSystemMessage(
                                        Component.literal("Favourite GUI tracking ")
                                                .withStyle(ChatFormatting.GREEN)
                                                .append(Component.literal("enabled").withStyle(ChatFormatting.GREEN))
                                                .append(Component.literal(". Container snapshots will be kept for /favouritegui save.").withStyle(ChatFormatting.GRAY)));
                            } else {
                                client.player.sendSystemMessage(
                                        Component.literal("Favourite GUI tracking ")
                                                .withStyle(ChatFormatting.YELLOW)
                                                .append(Component.literal("disabled").withStyle(ChatFormatting.RED))
                                                .append(Component.literal(". Containers will no longer be snapshotted — /favouritegui save will have no data to work from.").withStyle(ChatFormatting.GRAY)));
                            }
                            return 1;
                        }))
                .then(literal("delete")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    FavouriteGuiManager.getSavedNames().forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    Minecraft client = ctx.getSource().getClient();

                                    if (FavouriteGuiManager.deleteSavedContainer(name)) {
                                        client.player.sendSystemMessage(
                                                Component.literal("Deleted saved container '").withStyle(ChatFormatting.GREEN)
                                                        .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                                                        .append(Component.literal("'").withStyle(ChatFormatting.GREEN)));
                                    } else {
                                        client.player.sendSystemMessage(
                                                Component.literal("No saved container found with name '").withStyle(ChatFormatting.RED)
                                                        .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                                                        .append(Component.literal("'").withStyle(ChatFormatting.RED)));
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
                                    Minecraft client = ctx.getSource().getClient();

                                    if (FavouriteGuiViewer.open(name)) {
                                        client.player.sendSystemMessage(
                                                Component.literal("Opening saved container '").withStyle(ChatFormatting.GREEN)
                                                        .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                                                        .append(Component.literal("'").withStyle(ChatFormatting.GREEN)));
                                    } else {
                                        client.player.sendSystemMessage(
                                                Component.literal("No saved container found with name '").withStyle(ChatFormatting.RED)
                                                        .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                                                        .append(Component.literal("'").withStyle(ChatFormatting.RED)));
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
                                    Minecraft client = ctx.getSource().getClient();

                                    if (FavouriteGuiViewer.open(name)) {
                                        client.player.sendSystemMessage(
                                                Component.literal("Opening saved container '").withStyle(ChatFormatting.GREEN)
                                                        .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                                                        .append(Component.literal("'").withStyle(ChatFormatting.GREEN)));
                                    } else {
                                        client.player.sendSystemMessage(
                                                Component.literal("No saved container found with name '").withStyle(ChatFormatting.RED)
                                                        .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                                                        .append(Component.literal("'").withStyle(ChatFormatting.RED)));
                                        return 0;
                                    }
                                    return 1;
                                }))));

        // /showtooltips
        dispatcher.register(literal("showtooltips")
                .executes(ctx -> {
                    TooltipOverrideConfig.toggle();
                    boolean enabled = TooltipOverrideConfig.isShowHiddenTooltips();

                    // Keep MoulConfig in sync so the GUI reflects the change
                    if (ConfigManager.getConfig() != null) {
                        ConfigManager.getConfig().misc.showHiddenTooltips = enabled;
                        ConfigManager.saveToFile();
                    }

                    ctx.getSource().getClient().player.sendSystemMessage(
                            Component.literal("Show hidden tooltips: ")
                                    .withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(enabled ? "ON" : "OFF")
                                            .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED)));
                    return 1;
                }));

        // /highlight — alias, opens highlight GUI
        dispatcher.register(literal("highlight")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    client.execute(() -> client.setScreen(new BlockHighlightScreen()));
                    return 1;
                })
                .then(literal("add")
                        .then(argument("blockid", StringArgumentType.greedyString())
                                .executes(ctx -> { return highlightAdd(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "blockid")); })))
                .then(literal("remove")
                        .then(argument("blockid", StringArgumentType.greedyString())
                                .executes(ctx -> { return highlightRemove(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "blockid")); }))));
    }

    // -------------------------------------------------------------------------
    // Copy skin image to clipboard
    // -------------------------------------------------------------------------

    private static void copySkinImage(Minecraft client, String textureHash) {
        if (client.level == null || client.player == null) {
            return;
        }

        String hash = textureHash;

        if (hash == null || hash.isBlank()) {
            // Extract texture hash from the targeted skull block
            if (client.hitResult == null || client.hitResult.getType() != HitResult.Type.BLOCK) {
                client.player.sendSystemMessage(
                        Component.literal("Look at a player head/skull block and try again.").withStyle(ChatFormatting.RED));
                return;
            }
            BlockHitResult hit = (BlockHitResult) client.hitResult;
            BlockPos pos = hit.getBlockPos();
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = client.level.getBlockEntity(pos);
            if (!(blockEntity instanceof net.minecraft.world.level.block.entity.SkullBlockEntity skull)) {
                client.player.sendSystemMessage(
                        Component.literal("Not a player head/skull block.").withStyle(ChatFormatting.RED));
                return;
            }
            var profile = skull.getOwnerProfile();
            if (profile == null) {
                client.player.sendSystemMessage(
                        Component.literal("This skull has no owner profile.").withStyle(ChatFormatting.RED));
                return;
            }
            // Extract texture hash from profile properties
            var gp = profile.partialProfile();
            for (com.mojang.authlib.properties.Property prop : gp.properties().get("textures")) {
                try {
                    String decoded = new String(Base64.getDecoder().decode(prop.value()));
                    int urlIdx = decoded.indexOf("textures.minecraft.net/texture/");
                    if (urlIdx >= 0) {
                        int hashStart = urlIdx + "textures.minecraft.net/texture/".length();
                        int hashEnd = decoded.indexOf('"', hashStart);
                        if (hashEnd < 0) hashEnd = decoded.indexOf('}', hashStart);
                        if (hashEnd >= 0) {
                            hash = decoded.substring(hashStart, hashEnd);
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (hash == null || hash.isBlank()) {
                client.player.sendSystemMessage(
                        Component.literal("Could not extract texture hash from skull.").withStyle(ChatFormatting.RED));
                return;
            }
        }

        hash = hash.trim();

        client.player.sendSystemMessage(
                Component.literal("Reading skin texture from cache...").withStyle(ChatFormatting.GRAY));

        // Read the skin PNG from Minecraft's local skin cache
        final String finalHash = hash;
        new Thread(() -> {
            try {
                // Skin cache path: <game_dir>/assets/skins/<first2>/<hash>.png
                java.io.File gameDir = client.gameDirectory;
                String subDir = finalHash.substring(0, 2);
                java.io.File skinFile = new java.io.File(gameDir, "assets/skins/" + subDir + "/" + finalHash + ".png");

                BufferedImage image;
                if (skinFile.exists()) {
                    image = ImageIO.read(skinFile);
                } else {
                    // Fallback: download from Mojang texture server
                    String url = "https://textures.minecraft.net/texture/" + finalHash;
                    try (java.io.InputStream in = java.net.URI.create(url).toURL().openStream()) {
                        image = ImageIO.read(in);
                    }
                }

                if (image == null) {
                    client.execute(() -> client.player.sendSystemMessage(
                            Component.literal("Failed to read skin image.").withStyle(ChatFormatting.RED)));
                    return;
                }

                // Save to temp file and copy to clipboard via platform command
                java.io.File tempFile = java.io.File.createTempFile("islandtime_skin_", ".png");
                tempFile.deleteOnExit();
                ImageIO.write(image, "png", tempFile);

                String os = System.getProperty("os.name", "").toLowerCase();
                String copyError = null;
                if (os.contains("win")) {
                    String absPath = tempFile.getAbsolutePath().replace("'", "''");
                    String psScript =
                            "Add-Type -AssemblyName System.Windows.Forms; " +
                            "Add-Type -AssemblyName System.Drawing; " +
                            "$pngBytes = [System.IO.File]::ReadAllBytes('" + absPath + "'); " +
                            "$ms = New-Object System.IO.MemoryStream(,$pngBytes); " +
                            "$bmp = [System.Drawing.Image]::FromStream($ms); " +
                            "$dataObj = New-Object System.Windows.Forms.DataObject; " +
                            "$pngStream = New-Object System.IO.MemoryStream(,$pngBytes); " +
                            "$dataObj.SetData('PNG',$pngStream); " +
                            "$dataObj.SetData([System.Windows.Forms.DataFormats]::Bitmap,$bmp); " +
                            "[System.Windows.Forms.Clipboard]::SetDataObject($dataObj,$true);";
                    copyError = runClipboardCommand(
                            new String[]{"powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-STA", "-Command", psScript},
                            null);
                } else if (os.contains("mac") || os.contains("darwin")) {
                    String absPath = tempFile.getAbsolutePath().replace("\"", "\\\"");
                    String appleScript = "set the clipboard to (read POSIX file \"" + absPath + "\" as \u00ABclass PNGf\u00BB)";
                    copyError = runClipboardCommand(new String[]{"osascript", "-e", appleScript}, null);
                } else {
                    // Linux: try wl-copy (Wayland) then xclip (X11)
                    byte[] pngBytes = java.nio.file.Files.readAllBytes(tempFile.toPath());
                    if (isCommandAvailable("wl-copy")) {
                        copyError = runClipboardCommand(new String[]{"wl-copy", "--type", "image/png"}, pngBytes);
                    } else if (isCommandAvailable("xclip")) {
                        copyError = runClipboardCommand(new String[]{"xclip", "-selection", "clipboard", "-t", "image/png", "-i"}, pngBytes);
                    } else {
                        copyError = "No clipboard tool found. Install wl-copy (Wayland) or xclip (X11).";
                    }
                }

                if (copyError == null || copyError.isEmpty()) {
                    client.execute(() -> client.player.sendSystemMessage(
                            Component.literal("Skin image copied to clipboard!").withStyle(ChatFormatting.GREEN)));
                } else {
                    String path = tempFile.getAbsolutePath();
                    final String errMsg = copyError;
                    client.execute(() -> client.player.sendSystemMessage(
                            Component.literal("Clipboard failed: " + errMsg + ". Skin saved to: " + path).withStyle(ChatFormatting.YELLOW)));
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                client.execute(() -> client.player.sendSystemMessage(
                        Component.literal("Failed to read skin texture: " + msg).withStyle(ChatFormatting.RED)));
            }
        }, "IslandTime-SkinRead").start();
    }

    // -------------------------------------------------------------------------
    // Copy skin image from an ItemStack (for keybind in inventory screens)
    // -------------------------------------------------------------------------

    public static void copySkinFromItem(Minecraft client, net.minecraft.world.item.ItemStack stack) {
        if (client.player == null) return;

        if (stack == null || stack.isEmpty()) {
            client.player.sendSystemMessage(
                    Component.literal("No item under cursor.").withStyle(ChatFormatting.RED));
            return;
        }

        // Check if it's a player head
        if (!stack.is(net.minecraft.world.item.Items.PLAYER_HEAD)) {
            client.player.sendSystemMessage(
                    Component.literal("Not a player head item.").withStyle(ChatFormatting.RED));
            return;
        }

        // Extract profile from item component
        var profile = stack.get(net.minecraft.core.component.DataComponents.PROFILE);
        if (profile == null) {
            client.player.sendSystemMessage(
                    Component.literal("This head has no profile data.").withStyle(ChatFormatting.RED));
            return;
        }

        // Extract texture hash from profile properties
        String hash = null;
        var gp = profile.partialProfile();
        for (com.mojang.authlib.properties.Property prop : gp.properties().get("textures")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(prop.value()));
                int urlIdx = decoded.indexOf("textures.minecraft.net/texture/");
                if (urlIdx >= 0) {
                    int hashStart = urlIdx + "textures.minecraft.net/texture/".length();
                    int hashEnd = decoded.indexOf('"', hashStart);
                    if (hashEnd < 0) hashEnd = decoded.indexOf('}', hashStart);
                    if (hashEnd >= 0) {
                        hash = decoded.substring(hashStart, hashEnd);
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (hash == null || hash.isBlank()) {
            client.player.sendSystemMessage(
                    Component.literal("Could not extract texture hash from this head.").withStyle(ChatFormatting.RED));
            return;
        }

        copySkinImage(client, hash);
    }

    /** Run a clipboard command with optional stdin bytes. Returns null on success, error message on failure. */
    private static String runClipboardCommand(String[] cmd, byte[] stdin) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            if (stdin != null) {
                p.getOutputStream().write(stdin);
                p.getOutputStream().close();
            }
            boolean exited = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return "Command timed out";
            }
            if (p.exitValue() == 0) return null;
            String err = new String(p.getErrorStream().readAllBytes()).trim();
            return err.isEmpty() ? "Exit code " + p.exitValue() : err;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Check if a command-line tool is available on the system. */
    private static boolean isCommandAvailable(String command) {
        try {
            Process p = new ProcessBuilder("which", command).redirectErrorStream(true).start();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Highlight helpers
    // -------------------------------------------------------------------------

    private static int highlightAdd(Minecraft client, String input) {
        // Input format: "target" or "target color"
        // target can be: stone (block), sb:DITTO_SKULL (skyblock id), skull:HASH
        String[] parts = input.trim().split("\\s+", 2);
        String target = parts[0];
        String color = "yellow";
        if (parts.length > 1) {
            String colorStr = parts[1].trim();
            float[] parsed = BlockHighlightRenderer.parseColor(colorStr);
            if (parsed != null) {
                color = colorStr;
            } else {
                client.player.sendSystemMessage(
                        Component.literal("Unknown color '" + colorStr + "'. Using default. Options: yellow, red, green, blue, magenta, cyan, white, or #rrggbb")
                                .withStyle(ChatFormatting.YELLOW));
            }
        }

        // Check for duplicates
        int existing = BlockHighlightManager.findByTarget(target);
        if (existing >= 0) {
            client.player.sendSystemMessage(
                    Component.literal("Already highlighting: ").withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(target).withStyle(ChatFormatting.AQUA)));
            return 1;
        }

        BlockHighlightManager.HighlightEntry entry = new BlockHighlightManager.HighlightEntry(target, color);
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config != null) {
            entry.range = config.blockHighlight.range;
            entry.intensity = config.blockHighlight.intensity;
            entry.throughWalls = config.blockHighlight.throughWalls;
        }
        BlockHighlightManager.getEntries().add(entry);
        BlockHighlightManager.save();
        client.player.sendSystemMessage(
                Component.literal("Added to highlights: ").withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(target).withStyle(ChatFormatting.AQUA)));
        return 1;
    }

    private static int highlightRemove(Minecraft client, String input) {
        String target = input.trim();

        int index = BlockHighlightManager.findByTarget(target);
        if (index < 0) {
            client.player.sendSystemMessage(
                    Component.literal("Not found in highlights: ").withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(target).withStyle(ChatFormatting.AQUA)));
            return 1;
        }

        BlockHighlightManager.removeEntry(index);
        client.player.sendSystemMessage(
                Component.literal("Removed from highlights: ").withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(target).withStyle(ChatFormatting.AQUA)));
        return 1;
    }

    // -------------------------------------------------------------------------
    // Help
    // -------------------------------------------------------------------------

    private static void showHelp(Minecraft client, int page) {
        String[][] commands = {
                // Page 1 - Island time & fuel time
                {"/islandtime", "Open the configuration GUI (all settings in one place)"},
                {"/islandtime recent", "Show last 7 days of island time"},
                {"/islandtime <DD-MM-YY>", "Show specific date (e.g., /islandtime 1-1-26)"},
                {"/fueltime", "Open the fuel day tracker GUI (day-by-day fuel/profit stats). Same as /it fueldays"},
                {"/fueltime <YYYY-MM-DD>", "Show a quick chat summary for a specific day (e.g., /fueltime 2026-01-01)"},

                // Page 2 - Discord webhook & pings
                {"/webhook", "Show current webhook URL"},
                {"/webhook <url>", "Set webhook URL (for Discord alerts). Must start with https://discord.com/api/webhooks/"},
                {"/webhook clear", "Remove the webhook URL"},
                {"/discordping <userid>", "Set your Discord user ID to ping in webhook alerts (must be numeric)"},
                {"/discordping clear", "Remove Discord ping (webhooks won't ping anyone)"},

                // Page 3 - Item tracking
                {"/trackitem", "Show the currently tracked item"},
                {"/trackitem set", "Track the item in your main hand (must have UUID + stats_book)"},
                {"/trackitem clear", "Stop tracking the current item"},
                {"/trackedalertspeed", "Show current tracked-item alert speed and options"},
                {"/trackedalertspeed <speed>", "Set tracked-item alert speed (off / fast / normal / slow)"},

                // Page 4 - Alert speeds
                {"/islandalertspeed", "Show current island alert speed and options"},
                {"/islandalertspeed <speed>", "Set island alert speed (off / fast / normal / slow)"},
                {"/disconnectalertspeed", "Show current disconnect alert speed and options"},
                {"/disconnectalertspeed <speed>", "Set disconnect alert speed. Alerts fire after a 10s grace period — no alert if you reconnect within 10 seconds."},
                {"/fueltime debug", "Inject a fake day record and attempt save (for diagnostics)"},

                // Page 5 - Tracking toggles & chat messages
                {"/trackislandtime <on|off>", "Enable or disable island-time tracking. Disabling preserves existing data but no new days will be tracked."},
                {"/trackfuelcycles <on|off>", "Enable or disable fuel-cycle tracking. Any active cycle completes naturally; no new cycles will start."},
                {"/minionfuelmessages <on|off>", "Enable or disable local chat messages when you fuel a minion"},
                {"/islandleavemessages <on|off>", "Enable or disable local chat messages when a visitor leaves your island"},
                {"/showtooltips", "Toggle visibility of items that have hide_tooltip set (e.g. GUI filler panes). Also configurable under Misc in /islandtime."},
                {"/favouritegui toggle", "Toggle container snapshot tracking on/off. When off, /favouritegui save has no data to work from."},

                // Page 6 - Favourite GUI
                {"/favouritegui", "List all saved container layouts"},
                {"/favouritegui save <name>", "Save the currently open container under <name>"},
                {"/favouritegui view <name>", "Open a saved container layout as a read-only overlay"},
                {"/favouritegui delete <name>", "Remove a previously saved container layout"},

                // Page 7 - Chat relay
                {"/chatrelay", "Show current chat relay config (webhook, port, secret)"},
                {"/chatrelay webhook <url>", "Set the Discord webhook for outbound island chat relay"},
                {"/chatrelay webhook clear", "Disable outbound chat relay"},
                {"/chatrelay port <number>", "Set localhost poll port for inbound relay (default 8765)"},
                {"/chatrelay secret <string>", "Set shared secret key for the poll endpoint"},

                // Page 8 - Ghost, WAILA & Block Highlights
                {"/islandtime ghost", "Client-side remove the block you are looking at (alias: /it ghost). Can be disabled in /islandtime under Misc."},
                {"/it highlight", "Open the Block Highlights GUI (same as /it highlights). Use /it highlight add <id> to add from chat."},
                {"/it highlight add <id>", "Add to highlights. stone=block, sb:DITTO_SKULL=skyblock ID, skull:HASH=texture hash. Optionally add color: /it highlight add stone red"},
                {"/it highlight remove <id>", "Remove a highlight entry by target name"},
                {"/it waila", "Inspect the block you're looking at — shows block ID, position, state, NBT, skull textures, and SkyBlock custom ID"},
                {"/showtooltips", "Toggle visibility of items that have hide_tooltip set"},
                {"/islandtime help [page]", "Show this help (pages 1-11)"},

                // Page 9 - Aging Items
                {"/it agingItems", "Compare profit/hour for all configured aging items using live Bazaar prices"},
                {"/it agingItems add <agingId> <agedId>", "Add a custom aging item pair by SkyBlock IDs (e.g. BOTTLE_OF_JYRRE REFINED_BOTTLE_OF_JYRRE)"},
                {"/it agingItems remove <agingId>", "Remove an aging item pair by its aging SkyBlock ID"},
                {"/it agingItems list", "List all configured aging item pairs"},
                {"Time Bag / Inventory Slots", "Configure slot counts under Misc > Aging Items in /islandtime GUI"},

                // Page 10 - Cookie Profit & Settings Loadouts
                {"/it cookieProfit", "Compare profit/bit for spending cookie bits on various BZ items"},
                {"Bits Per Cookie", "Set your bits/cookie (with Fame multiplier) under Misc > Cookie Profit in /islandtime GUI"},
                {"/it savesettings <name>", "Save current settings as a named loadout"},
                {"/it loadsettings <name>", "Load a saved settings loadout (replaces current settings)"},
                {"/it deletesettings <name>", "Delete a saved settings loadout"},

                // Page 11 - Settings Loadouts (continued)
                {"/it listsettings", "List all saved loadouts and settings groups"},
                {"/it groupsettings <group> <n1> <n2> ...", "Create a settings group from named loadouts (a loadout can only be in one group)"},
                {"/it cyclesettings", "Cycle to the next loadout in the current group"},
        };

        int commandsPerPage = 5;
        int totalCommands   = commands.length;
        int totalPages      = (int) Math.ceil((double) totalCommands / commandsPerPage);

        if (page < 1 || page > totalPages) {
            client.player.sendSystemMessage(
                    Component.literal("Invalid page number. Valid range: 1-" + totalPages).withStyle(ChatFormatting.RED));
            return;
        }

        client.player.sendSystemMessage(
                Component.literal("=== Island Time Mod Commands (Page " + page + "/" + totalPages + ") ===")
                        .withStyle(ChatFormatting.GOLD));
        client.player.sendSystemMessage(Component.literal(""));

        int startIdx = (page - 1) * commandsPerPage;
        int endIdx   = Math.min(startIdx + commandsPerPage, totalCommands);

        for (int i = startIdx; i < endIdx; i++) {
            client.player.sendSystemMessage(Component.literal(commands[i][0]).withStyle(ChatFormatting.WHITE));
            client.player.sendSystemMessage(Component.literal("  " + commands[i][1]).withStyle(ChatFormatting.GRAY));
            if (i < endIdx - 1) client.player.sendSystemMessage(Component.literal(""));
        }
    }

    // -------------------------------------------------------------------------
    // Ghost (client-side block removal)
    // -------------------------------------------------------------------------

    private static void ghostBlock(Minecraft client) {
        if (ConfigManager.getConfig() != null && !ConfigManager.getConfig().misc.ghostEnabled) {
            client.player.sendSystemMessage(
                    Component.literal("Ghost command is disabled. Enable it in /islandtime under Misc.")
                            .withStyle(ChatFormatting.RED));
            return;
        }
        if (client.level == null || client.hitResult == null) {
            client.player.sendSystemMessage(
                    Component.literal("No block targeted.").withStyle(ChatFormatting.RED));
            return;
        }
        if (client.hitResult.getType() != HitResult.Type.BLOCK) {
            client.player.sendSystemMessage(
                    Component.literal("No block targeted.").withStyle(ChatFormatting.RED));
            return;
        }
        BlockPos pos = ((BlockHitResult) client.hitResult).getBlockPos();
        client.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        client.player.sendSystemMessage(
                Component.literal("Ghosted block at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
                        .withStyle(ChatFormatting.GRAY));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void showAlertSpeedOptions(Minecraft client) {
        client.player.sendSystemMessage(Component.literal("\nAlert speeds:").withStyle(ChatFormatting.GRAY));
        client.player.sendSystemMessage(Component.literal("  off: ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("No alerts").withStyle(ChatFormatting.GRAY)));
        client.player.sendSystemMessage(Component.literal("  fast: ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("1 min, then every 5 min").withStyle(ChatFormatting.GRAY)));
        client.player.sendSystemMessage(Component.literal("  normal: ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("10 min, then every 30 min").withStyle(ChatFormatting.GRAY)));
        client.player.sendSystemMessage(Component.literal("  slow: ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("1 hour, then every 3 hours").withStyle(ChatFormatting.GRAY)));
    }

    private static void showRecent() {
        Minecraft client = Minecraft.getInstance();
        var times = IslandTimeTracker.getTimes();

        if (times.isEmpty()) {
            client.player.sendSystemMessage(
                    Component.literal("No island time data recorded yet. Visit your island to start tracking!")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        List<String> days = new ArrayList<>(times.keySet());
        Collections.sort(days);
        Collections.reverse(days);

        String today = LocalDate.now().toString();
        int    shown = 0;

        client.player.sendSystemMessage(Component.literal("» Island Time (Last 7 Days)").withStyle(ChatFormatting.GOLD));

        for (String day : days) {
            long    ms      = times.get(day);
            boolean isToday = day.equals(today);
            if (shown == 1) {
                client.player.sendSystemMessage(Component.literal("-----------------------").withStyle(ChatFormatting.GRAY));
            }
            formatLineColored(client, day, ms, isToday);
            shown++;
            if (shown >= 7) break;
        }
    }

    private static void showSpecificDate(String input) {
        Minecraft client = Minecraft.getInstance();
        var times = IslandTimeTracker.getTimes();

        LocalDate date;
        try {
            date = parseFlexibleDate(input);
        } catch (DateTimeParseException | NumberFormatException e) {
            client.player.sendSystemMessage(
                    Component.literal("Invalid date format. Use DD-MM-YYYY or D-M-YY (e.g., 1-1-26 or 01-01-2026)")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        String key = date.toString();
        if (!times.containsKey(key)) {
            client.player.sendSystemMessage(
                    Component.literal("No data found for the specified date").withStyle(ChatFormatting.RED));
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

    // -------------------------------------------------------------------------
    // Config GUI
    // -------------------------------------------------------------------------

    private static void openConfigGui() {
        Minecraft client = Minecraft.getInstance();
        // Sync from existing configs before opening GUI to ensure latest values
        ConfigManager.syncFromExistingConfigs();
        // Open the MoulConfig GUI
        client.execute(() -> {
            io.github.notenoughupdates.moulconfig.gui.MoulConfigEditor<?> editor =
                new io.github.notenoughupdates.moulconfig.gui.MoulConfigEditor<>(ConfigManager.getProcessor());
            io.github.notenoughupdates.moulconfig.gui.GuiElementComponent editorComponent =
                new io.github.notenoughupdates.moulconfig.gui.GuiElementComponent(editor);
            io.github.notenoughupdates.moulconfig.gui.GuiContext guiContext =
                new io.github.notenoughupdates.moulconfig.gui.GuiContext(editorComponent);
            io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent screen =
                new io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent(
                    net.minecraft.network.chat.Component.literal("IslandTime (1.6.0)"),
                    guiContext,
                    client.screen
                );
            client.setScreen(screen);
        });
        // Schedule a sync after the GUI is closed to apply any changes
        // This will happen on the next tick when the screen is null
    }

    private static void formatLineColored(Minecraft client, String date, long ms, boolean isToday) {
        long seconds = ms / 1000;
        long h       = seconds / 3600;
        long m       = (seconds % 3600) / 60;
        long s       = seconds % 60;

        String    prettyDate = LocalDate.parse(date).format(OUTPUT_FORMAT);
        String    timeStr    = String.format(": %02dh%02dm%02ds", h, m, s);
        ChatFormatting dateColor = isToday ? ChatFormatting.GREEN : ChatFormatting.YELLOW;

        client.player.sendSystemMessage(
                Component.literal(prettyDate).withStyle(dateColor)
                        .append(Component.literal(timeStr).withStyle(ChatFormatting.WHITE)));
    }
}
