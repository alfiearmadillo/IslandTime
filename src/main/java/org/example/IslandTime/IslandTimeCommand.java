package org.example.islandtime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
                .then(literal("settingssave")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.save(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("settingsload")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.load(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("settingsdelete")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.delete(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("settingslist")
                        .executes(ctx -> {
                            SettingsLoadoutManager.list(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("settingsgroup")
                        .then(argument("names", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String[] parts = StringArgumentType.getString(ctx, "names").split("\\s+");
                                    if (parts.length < 3) {
                                        ctx.getSource().getClient().player.sendSystemMessage(
                                                Component.literal("[IslandTime] Usage: /it settingsgroup <groupName> <loadout1> <loadout2> [...]").withStyle(ChatFormatting.RED));
                                        return 1;
                                    }
                                    String groupName = parts[0];
                                    List<String> loadouts = new java.util.ArrayList<>();
                                    for (int i = 1; i < parts.length; i++) loadouts.add(parts[i]);
                                    SettingsLoadoutManager.setGroup(ctx.getSource().getClient(), groupName, loadouts);
                                    return 1;
                                })))
                .then(literal("settingscycle")
                        .executes(ctx -> {
                            SettingsLoadoutManager.cycle(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(buildFueltimeCommand())
                .then(buildFuelcostCommand())
                .then(buildMinionIdCommand())
                .then(buildComposterCommand())
                .then(GreenhouseBlueprintCommand.build())
                .then(buildWebhookCommand())
                .then(buildDiscordpingCommand())
                .then(buildChatrelayCommand())
                .then(buildTrackitemCommand())
                .then(buildTrackedalertspeedCommand())
                .then(buildIslandalertspeedCommand())
                .then(buildDisconnectalertspeedCommand())
                .then(buildTrackislandtimeCommand())
                .then(buildTrackfuelcyclesCommand())
                .then(buildMinionfuelmessagesCommand())
                .then(buildIslandleavemessagesCommand())
                .then(buildFavouriteguiCommand())
                .then(buildShowtooltipsCommand())
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
                .then(literal("trackedIslandTime")
                        .then(argument("date", StringArgumentType.word())
                                .executes(ctx -> {
                                    showSpecificDate(StringArgumentType.getString(ctx, "date"));
                                    return 1;
                                })))
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
                .then(literal("huntrapProfit")
                        .executes(ctx -> {
                            HuntrapProfitCommand.execute(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("infernoProfits")
                        .executes(ctx -> {
                            InfernoProfitsCommand.execute(ctx.getSource().getClient(), null);
                            return 1;
                        })
                        .then(argument("minions", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    int minions = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "minions");
                                    InfernoProfitsCommand.execute(ctx.getSource().getClient(), minions);
                                    return 1;
                                })))
                .then(literal("huntrapEditor")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new HuntrapEditorScreen()));
                            return 1;
                        }))
                .then(literal("players")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new PlayerListScreen()));
                            return 1;
                        }))
                .then(literal("critters")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new CritterSafariHudScreen()));
                            return 1;
                        }))
                .then(literal("fueldays")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new FuelDayScreen()));
                            return 1;
                        }))
                .then(literal("datasets")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            client.execute(() -> client.setScreen(new FuelDataSetsScreen()));
                            return 1;
                        }))
                .then(literal("settingssave")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.save(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("settingsload")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.load(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("settingsdelete")
                        .then(argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    SettingsLoadoutManager.delete(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("settingslist")
                        .executes(ctx -> {
                            SettingsLoadoutManager.list(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("settingsgroup")
                        .then(argument("names", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String[] parts = StringArgumentType.getString(ctx, "names").split("\\s+");
                                    if (parts.length < 3) {
                                        ctx.getSource().getClient().player.sendSystemMessage(
                                                Component.literal("[IslandTime] Usage: /it settingsgroup <groupName> <loadout1> <loadout2> [...]").withStyle(ChatFormatting.RED));
                                        return 1;
                                    }
                                    String groupName = parts[0];
                                    List<String> loadouts = new java.util.ArrayList<>();
                                    for (int i = 1; i < parts.length; i++) loadouts.add(parts[i]);
                                    SettingsLoadoutManager.setGroup(ctx.getSource().getClient(), groupName, loadouts);
                                    return 1;
                                })))
                .then(literal("settingscycle")
                        .executes(ctx -> {
                            SettingsLoadoutManager.cycle(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(buildFueltimeCommand())
                .then(buildFuelcostCommand())
                .then(buildMinionIdCommand())
                .then(buildComposterCommand())
                .then(GreenhouseBlueprintCommand.build())
                .then(buildWebhookCommand())
                .then(buildDiscordpingCommand())
                .then(buildChatrelayCommand())
                .then(buildTrackitemCommand())
                .then(buildTrackedalertspeedCommand())
                .then(buildIslandalertspeedCommand())
                .then(buildDisconnectalertspeedCommand())
                .then(buildTrackislandtimeCommand())
                .then(buildTrackfuelcyclesCommand())
                .then(buildMinionfuelmessagesCommand())
                .then(buildIslandleavemessagesCommand())
                .then(buildFavouriteguiCommand())
                .then(buildShowtooltipsCommand())
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
                .then(literal("trackedIslandTime")
                        .then(argument("date", StringArgumentType.word())
                                .executes(ctx -> {
                                    showSpecificDate(StringArgumentType.getString(ctx, "date"));
                                    return 1;
                                })))
        );

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
        // Input format: "target [color] [partial] [minsize]"
        // target can be: stone (block), entity:item_frame (entity), sb:DITTO_SKULL (skyblock id), skull:HASH,
        // or an entity with a name filter: entity:armor_stand[name=Some Name] / entity:*[name=Some Name]
        // (the name may contain spaces, so a bracketed filter is kept together as part of the target)
        // The words "partial" (match IDs/names by "contains") and "minsize" (1 block box for entities with
        // no hitbox) may follow the target in any order next to the color.
        String trimmed = input.trim();
        String target;
        String rest = "";
        int filterStart = trimmed.indexOf('[');
        int filterEnd = trimmed.lastIndexOf(']');
        if (trimmed.startsWith("entity:") && filterStart >= 0 && filterEnd > filterStart) {
            target = trimmed.substring(0, filterEnd + 1);
            rest = trimmed.substring(filterEnd + 1).trim();
        } else {
            String[] parts = trimmed.split("\\s+", 2);
            target = parts[0];
            if (parts.length > 1) rest = parts[1];
        }

        boolean wantPartial = false;
        boolean wantMinSize = false;
        StringBuilder colorBuilder = new StringBuilder();
        for (String word : rest.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (word.equalsIgnoreCase("partial")) {
                wantPartial = true;
            } else if (word.equalsIgnoreCase("minsize")) {
                wantMinSize = true;
            } else {
                if (colorBuilder.length() > 0) colorBuilder.append(' ');
                colorBuilder.append(word);
            }
        }
        String colorPart = colorBuilder.length() > 0 ? colorBuilder.toString() : null;

        if (target.startsWith("entity:")
                && !BlockHighlightManager.isValidEntitySpec(target.substring(7))) {
            client.player.sendSystemMessage(
                    Component.literal("Invalid entity target '" + target + "'. Use entity:TYPE, entity:TYPE[name=TEXT] or entity:*[name=TEXT].")
                            .withStyle(ChatFormatting.RED));
            return 1;
        }

        String color = "yellow";
        if (colorPart != null) {
            String colorStr = colorPart.trim();
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
            // Asking again with partial/minsize turns those options on for the existing entry
            BlockHighlightManager.HighlightEntry existingEntry = BlockHighlightManager.getEntries().get(existing);
            boolean turnOnPartial = wantPartial && !existingEntry.isPartialIds();
            boolean turnOnMinSize = wantMinSize && !existingEntry.minSizeBox;
            if (turnOnPartial || turnOnMinSize) {
                if (turnOnPartial) existingEntry.partialMatch = true;
                if (turnOnMinSize) existingEntry.minSizeBox = true;
                BlockHighlightManager.save();
                client.player.sendSystemMessage(
                        Component.literal("Updated highlight: ").withStyle(ChatFormatting.GREEN)
                                .append(Component.literal(target).withStyle(ChatFormatting.AQUA))
                                .append(Component.literal((turnOnPartial ? " (partial match on)" : "")
                                        + (turnOnMinSize ? " (min size box on)" : "")).withStyle(ChatFormatting.GRAY)));
                return 1;
            }
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
            entry.partialMatch = config.blockHighlight.partialMatch;
            entry.minSizeBox = config.blockHighlight.minSizeBox;
        }
        if (wantPartial) entry.partialMatch = true;
        if (wantMinSize) entry.minSizeBox = true;
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
                {"/it", "Open the configuration GUI (all settings in one place)"},
                {"/it recent", "Show last 7 days of island time"},
                {"/it trackedIslandTime <DD-MM-YY>", "Show specific date (e.g., /it trackedIslandTime 1-1-26)"},
                {"/it fueltime", "Open the fuel day tracker GUI (day-by-day fuel/profit stats). Same as /it fueldays"},
                {"/it fueltime <YYYY-MM-DD>", "Show a quick chat summary for a specific day (e.g., /it fueltime 2026-01-01)"},
                {"/it datasets", "Open the fuel data sets menu: create, browse and switch between sets of fuel day data (Main, Debug, your own). The one in use is remembered"},
                {"/it fuelcost [NUMBER]", "Show the buy-order breakdown for one fuel, or NUMBER fuels, using the cheapest distillate (or only Gabagool Distillate if that setting is on)"},
                {"/it infernoProfits [MINIONS]", "Show what your current cohort (or MINIONS minions) earns per fuel day on Rare, Epic and Legendary fuel: loot at the Bazaar sell offer (Legendary with its rare drops, partial ones included) less the fuel at buy orders and, on Legendary, the eyedrops. Uses the speed and eyedrop settings of the estimated return"},

                // Page 2 - Discord webhook & pings
                {"/it webhook", "Show current webhook URL"},
                {"/it webhook <url>", "Set webhook URL (for Discord alerts). Must start with https://discord.com/api/webhooks/"},
                {"/it webhook clear", "Remove the webhook URL"},
                {"/it discordping <userid>", "Set your Discord user ID to ping in webhook alerts (must be numeric)"},
                {"/it discordping clear", "Remove Discord ping (webhooks won't ping anyone)"},

                // Page 3 - Item tracking
                {"/it trackitem", "Show the currently tracked item"},
                {"/it trackitem set", "Track the item in your main hand (must have UUID + stats_book)"},
                {"/it trackitem clear", "Stop tracking the current item"},
                {"/it trackedalertspeed", "Show current tracked-item alert speed and options"},
                {"/it trackedalertspeed <speed>", "Set tracked-item alert speed (off / fast / normal / slow)"},

                // Page 4 - Alert speeds
                {"/it islandalertspeed", "Show current island alert speed and options"},
                {"/it islandalertspeed <speed>", "Set island alert speed (off / fast / normal / slow)"},
                {"/it disconnectalertspeed", "Show current disconnect alert speed and options"},
                {"/it disconnectalertspeed <speed>", "Set disconnect alert speed. Alerts fire after a 10s grace period — no alert if you reconnect within 10 seconds."},
                {"/it fueltime debug", "Inject a fake day record and attempt save (for diagnostics)"},

                // Page 5 - Tracking toggles & chat messages
                {"/it trackislandtime <on|off>", "Enable or disable island-time tracking. Disabling preserves existing data but no new days will be tracked."},
                {"/it trackfuelcycles <on|off>", "Enable or disable fuel-cycle tracking. Any active cycle completes naturally; no new cycles will start."},
                {"/it minionfuelmessages <on|off>", "Enable or disable local chat messages when you fuel a minion"},
                {"/it islandleavemessages <on|off>", "Enable or disable local chat messages when a visitor leaves your island"},
                {"/it showtooltips", "Toggle visibility of items that have hide_tooltip set (e.g. GUI filler panes). Also configurable under Misc in /it."},
                {"/it favouritegui toggle", "Toggle container snapshot tracking on/off. When off, /it favouritegui save has no data to work from."},

                // Page 6 - Favourite GUI
                {"/it favouritegui", "List all saved container layouts"},
                {"/it favouritegui save <name>", "Save the currently open container under <name>"},
                {"/it favouritegui view <name>", "Open a saved container layout as a read-only overlay"},
                {"/it favouritegui delete <name>", "Remove a previously saved container layout"},

                // Page 7 - Chat relay
                {"/it chatrelay", "Show current chat relay config (webhook, port, secret)"},
                {"/it chatrelay webhook <url>", "Set the Discord webhook for outbound island chat relay"},
                {"/it chatrelay webhook clear", "Disable outbound chat relay"},
                {"/it chatrelay port <number>", "Set localhost poll port for inbound relay (default 8765)"},
                {"/it chatrelay secret <string>", "Set shared secret key for the poll endpoint"},

                // Page 8 - Ghost, WAILA & Block Highlights
                {"/it ghost", "Client-side remove the block you are looking at. Can be disabled in /it under Misc."},
                {"/it highlight", "Open the Highlights GUI (same as /it highlights). Use /it highlight add <target> to add from chat."},
                {"/it highlight add <target>", "Add a block or any registered entity type to highlights. Use entity:item_frame or entity:armor_stand; filter entities by name with entity:armor_stand[name=TEXT] or entity:*[name=TEXT] (any type); sb:DITTO_SKULL and skull:HASH are also supported. Optionally add a color, plus partial (IDs and names only need to contain the text, e.g. entity:ca) and/or minsize (1 block box for entities with no hitbox)."},
                {"/it highlight remove <id>", "Remove a highlight entry by target name"},
                {"/it waila", "Inspect the block or entity you're aiming at, even out of reach or untargetable (like marker armor stands). Can be bound to a key in /it > WAILA — shows IDs, position, state, NBT, equipment, skull textures, and SkyBlock custom ID, with buttons to add it to highlights"},
                {"/it help [page]", "Show this help (pages 1-14)"},

                // Page 9 - Aging Items
                {"/it agingItems", "Compare profit/hour for all configured aging items using live Bazaar prices"},
                {"/it agingItems add <agingId> <agedId>", "Add a custom aging item pair by SkyBlock IDs (e.g. BOTTLE_OF_JYRRE REFINED_BOTTLE_OF_JYRRE)"},
                {"/it agingItems remove <agingId>", "Remove an aging item pair by its aging SkyBlock ID"},
                {"/it agingItems list", "List all configured aging item pairs"},
                {"Time Bag / Inventory Slots", "Configure slot counts under Misc > Aging Items in /it GUI"},

                // Page 10 - Cookie Profit & Settings Loadouts
                {"/it cookieProfit", "Compare profit/bit for spending cookie bits on various BZ items"},
                {"Bits Per Cookie", "Set your bits/cookie (with Fame multiplier) under Misc > Cookie Profit in /it GUI"},
                {"/it huntrapProfit", "Rank Huntrap locations by estimated shard profit/hour (5 traps) using live Bazaar prices"},
                {"/it huntrapEditor", "Open the Huntrap data editor: edit each location's shards and weights, add or remove locations and shards, or reset to the built-in data"},
                {"Huntrap Settings", "Set trap type, Hunter Fortune, Desert Temple buff, Forest/Combat Trap levels and how many locations to show under Misc > Huntrap Profit in /it GUI"},
                {"/it settingssave <name>", "Save current settings as a named loadout"},
                {"/it settingsload <name>", "Load a saved settings loadout (replaces current settings)"},
                {"/it settingsdelete <name>", "Delete a saved settings loadout"},

                // Page 11 - Settings Loadouts (continued)
                {"/it settingslist", "List all saved loadouts and settings groups"},
                {"/it settingsgroup <group> <n1> <n2> ...", "Create a settings group from named loadouts (a loadout can only be in one group)"},
                {"/it settingscycle", "Cycle to the next loadout in the current group"},
                {"/it composter", "Show composter tracking status and when it is estimated to run empty (configure alerts under Composter in /it GUI)"},
                {"/it composter debug", "Copy the tab list and last Composter menu contents to the clipboard (for when the composter is not found)"},
                {"/it blueprint copy", "Preview the greenhouse area of the Garden plot you stand in: a frame around it and a box for every crop (block and armor stand, with what the stands wear and hold) that would be copied, plus the soil under them"},
                {"/it blueprint save [name]|addboard|cancel", "Save what the preview shows (crops, armor stands and soil) as a blueprint that is drawn as see-through blocks and armor stands, with a red outline around what is wrong, over greenhouse plots in the Garden (addboard adds it to the selected blueprint as another plot's board; cancel stops the preview)"},
                {"/it blueprint list|select <name>|delete <name>", "List, switch between and delete copied greenhouse blueprints"},
                {"/it blueprint gui|export [name]|import", "Open the blueprint list (select, delete, copy a shareable import code per layout, import one from the clipboard at the top). export copies the code of the selected or named layout, import reads one from the clipboard"},
                {"/it blueprint board <n>|rotate [left|right]|mirror|align|toggle|status", "Pick the board for this plot, turn or mirror the layout (align fits it to what you have already built), switch the overlay on or off, or check why it isn't showing"},
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

    // -------------------------------------------------------------------------
    // Command subtrees shared under both /islandtime and /it (built once per
    // registration so every command lives under the /it prefix, alongside its
    // /islandtime alias).
    // -------------------------------------------------------------------------

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildFueltimeCommand() {
        return literal("fueltime")
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
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildComposterCommand() {
        return literal("composter")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    if (client.player == null) return 1;

                    client.player.sendSystemMessage(
                            Component.literal("[IslandTime] Composter:").withStyle(ChatFormatting.GOLD));
                    for (String line : ComposterTracker.describe()) {
                        client.player.sendSystemMessage(
                                Component.literal("  " + line).withStyle(ChatFormatting.GRAY));
                    }
                    return 1;
                })
                .then(literal("debug")
                        .executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            if (client.player == null) return 1;

                            client.keyboardHandler.setClipboard(ComposterTracker.dumpDiagnostics());
                            client.player.sendSystemMessage(Component.literal(
                                            "[IslandTime] Composter debug info (tab list + last Composter menu) copied to your clipboard.")
                                    .withStyle(ChatFormatting.GREEN));
                            return 1;
                        }));
    }

    /**
     * {@code /it minionid} — reports how the currently open minion GUI is being identified
     * and exactly what each upgrade slot contains. Minions are tracked by the permanent
     * uuid on an upgrade item (a Flycatcher, for instance); when no upgrade qualifies, this
     * shows the per-slot reason so it's clear whether the slot is empty, the item carries
     * no uuid, or its uuid is regenerated each time the menu opens.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildMinionIdCommand() {
        return literal("minionid")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    if (client.player == null) return 1;

                    java.util.List<String> report = MinionFuelTracker.describeOpenMinionGui();
                    if (report == null) {
                        client.player.sendSystemMessage(
                                Component.literal("[IslandTime] Open a minion's GUI first, then run /it minionid.")
                                        .withStyle(ChatFormatting.RED));
                        return 1;
                    }

                    client.player.sendSystemMessage(
                            Component.literal("[IslandTime] Minion identity:").withStyle(ChatFormatting.GOLD));
                    for (String line : report) {
                        client.player.sendSystemMessage(
                                Component.literal("  " + line).withStyle(ChatFormatting.GRAY));
                    }
                    return 1;
                });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildFuelcostCommand() {
        return literal("fuelcost")
                .executes(ctx -> {
                    showFuelCost(ctx.getSource().getClient(), 1);
                    return 1;
                })
                .then(argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            int count = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count");
                            showFuelCost(ctx.getSource().getClient(), count);
                            return 1;
                        }));
    }

    private static void showFuelCost(Minecraft client, int fuelCount) {
        if (client.player == null) return;
        client.player.sendSystemMessage(
                Component.literal("Fetching Bazaar buy-order prices for " + fuelCount + " fuel"
                                + (fuelCount == 1 ? "" : "s") + "...")
                        .withStyle(ChatFormatting.GRAY));

        new Thread(() -> {
            try {
                IslandTimeConfig config = ConfigManager.getConfig();
                boolean gabagoolOnly = config != null && config.fuelCycles.gabagoolDistillateOnly;
                PriceCache.FuelCostBreakdown breakdown =
                        PriceCache.getInfernoFuelCostBreakdown(PriceCache.refreshBazaar(), fuelCount, gabagoolOnly);
                if (breakdown == null) {
                    sendFuelCostError(client, "Could not fetch all required Bazaar buy-order prices.");
                    return;
                }

                client.execute(() -> {
                    if (client.player == null) return;
                    client.player.sendSystemMessage(
                            Component.literal("Inferno Minion Fuel cost (" + breakdown.fuelCount + "):")
                                    .withStyle(ChatFormatting.GOLD));
                    sendFuelCostLine(client, PriceCache.enchantedCoalQty(breakdown.grade) + "x Enchanted Coal",
                            breakdown.enchantedCoalPrice, PriceCache.enchantedCoalQty(breakdown.grade), breakdown.fuelCount);
                    sendFuelCostLine(client, InfernoProfits.formatAmount(PriceCache.enchantedSulphurQty(breakdown.grade))
                                    + "x Enchanted Sulphur",
                            breakdown.enchantedSulphurPrice, PriceCache.enchantedSulphurQty(breakdown.grade),
                            breakdown.fuelCount);
                    sendFuelCostLine(client, "3x Very Crude Gabagool",
                            breakdown.veryCrudeGabagoolPrice, 3, breakdown.fuelCount);
                    for (Map.Entry<String, Double> entry : breakdown.distillatePrices.entrySet()) {
                        boolean selected = entry.getKey().equals(breakdown.selectedDistillateId);
                        sendFuelCostLine(client,
                                "6x " + fuelCostDisplayName(entry.getKey())
                                        + (selected ? (breakdown.gabagoolOnly ? " (GABAGOOL ONLY)" : " (CHEAPEST)") : ""),
                                entry.getValue(), 6, breakdown.fuelCount);
                    }
                    sendFuelCostLine(client, "2x Inferno Fuel Block",
                            breakdown.fuelBlockPrice, 2, breakdown.fuelCount);
                    client.player.sendSystemMessage(
                            Component.literal("Per fuel: ").withStyle(ChatFormatting.YELLOW)
                                    .append(Component.literal(PriceCache.formatCoins(breakdown.unitCost))
                                            .withStyle(ChatFormatting.WHITE))
                                    .append(Component.literal(" | Total: ").withStyle(ChatFormatting.YELLOW))
                                    .append(Component.literal(PriceCache.formatCoins(breakdown.totalCost))
                                            .withStyle(ChatFormatting.GREEN)));
                });
            } catch (Exception e) {
                sendFuelCostError(client, "Fuel cost lookup failed: " + e.getMessage());
            }
        }, "IslandTime-FuelCost").start();
    }

    private static void sendFuelCostLine(Minecraft client, String label, double unitPrice,
                                         double quantityPerFuel, int fuelCount) {
        if (unitPrice <= 0) {
            client.player.sendSystemMessage(
                    Component.literal("  " + label + ": unavailable")
                            .withStyle(ChatFormatting.DARK_RED));
            return;
        }
        double totalQuantity = quantityPerFuel * fuelCount;
        double totalPrice = unitPrice * totalQuantity;
        client.player.sendSystemMessage(
                Component.literal("  " + label + " @ ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(PriceCache.formatCoinsPrecise(unitPrice))
                                .withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" = " + InfernoProfits.formatAmount(totalQuantity) + "x ")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(PriceCache.formatCoins(totalPrice))
                                .withStyle(ChatFormatting.AQUA)));
    }

    private static String fuelCostDisplayName(String productId) {
        return switch (productId) {
            case "BLAZE_ROD_DISTILLATE" -> "Blaze Rod Distillate";
            case "GLOWSTONE_DUST_DISTILLATE" -> "Glowstone Distillate";
            case "MAGMA_CREAM_DISTILLATE" -> "Magma Cream Distillate";
            case "NETHER_STALK_DISTILLATE" -> "Nether Stalk Distillate";
            case "CRUDE_GABAGOOL_DISTILLATE" -> "Crude Gabagool Distillate";
            default -> productId;
        };
    }

    private static void sendFuelCostError(Minecraft client, String message) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(
                        Component.literal("[FuelCost] " + message).withStyle(ChatFormatting.RED));
            }
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildWebhookCommand() {
        return literal("webhook")
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
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildDiscordpingCommand() {
        return literal("discordping")
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
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildChatrelayCommand() {
        return literal("chatrelay")
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
                            Component.literal("Use /it help 6 for subcommands.").withStyle(ChatFormatting.DARK_GRAY));
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
                                })));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildTrackitemCommand() {
        return literal("trackitem")
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
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildTrackedalertspeedCommand() {
        return literal("trackedalertspeed")
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
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildIslandalertspeedCommand() {
        return literal("islandalertspeed")
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
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildDisconnectalertspeedCommand() {
        return literal("disconnectalertspeed")
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
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildTrackislandtimeCommand() {
        return literal("trackislandtime")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    boolean current = TrackingToggleConfig.isIslandTimeEnabled();
                    client.player.sendSystemMessage(
                            Component.literal("Island-time tracking is currently: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(current ? "enabled" : "disabled")
                                            .withStyle(current ? ChatFormatting.GREEN : ChatFormatting.RED)));
                    client.player.sendSystemMessage(
                            Component.literal("Use /it trackislandtime <on|off> to change.").withStyle(ChatFormatting.GRAY));
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
                                        Component.literal("Usage: /it trackislandtime <on|off>").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildTrackfuelcyclesCommand() {
        return literal("trackfuelcycles")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    boolean current = TrackingToggleConfig.isFuelCycleEnabled();
                    client.player.sendSystemMessage(
                            Component.literal("Fuel-cycle tracking is currently: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(current ? "enabled" : "disabled")
                                            .withStyle(current ? ChatFormatting.GREEN : ChatFormatting.RED)));
                    client.player.sendSystemMessage(
                            Component.literal("Use /it trackfuelcycles <on|off> to change.").withStyle(ChatFormatting.GRAY));
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
                                        Component.literal("Usage: /it trackfuelcycles <on|off>").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildMinionfuelmessagesCommand() {
        return literal("minionfuelmessages")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    boolean current = MinionFuelTracker.isShowFuelMessages();
                    client.player.sendSystemMessage(
                            Component.literal("Minion fuel messages are currently: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(current ? "enabled" : "disabled")
                                            .withStyle(current ? ChatFormatting.GREEN : ChatFormatting.RED)));
                    client.player.sendSystemMessage(
                            Component.literal("Use /it minionfuelmessages <on|off> to change.").withStyle(ChatFormatting.GRAY));
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
                                        Component.literal("Usage: /it minionfuelmessages <on|off>").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildIslandleavemessagesCommand() {
        return literal("islandleavemessages")
                .executes(ctx -> {
                    Minecraft client = ctx.getSource().getClient();
                    boolean current = IslandPlayerTracker.isShowLeaveMessages();
                    client.player.sendSystemMessage(
                            Component.literal("Island leave messages are currently: ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(current ? "enabled" : "disabled")
                                            .withStyle(current ? ChatFormatting.GREEN : ChatFormatting.RED)));
                    client.player.sendSystemMessage(
                            Component.literal("Use /it islandleavemessages <on|off> to change.").withStyle(ChatFormatting.GRAY));
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
                                        Component.literal("Usage: /it islandleavemessages <on|off>").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildFavouriteguiCommand() {
        return literal("favouritegui")
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
                                                                    "/it favouritegui view " + name))
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
                                })));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildShowtooltipsCommand() {
        return literal("showtooltips")
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
                });
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
