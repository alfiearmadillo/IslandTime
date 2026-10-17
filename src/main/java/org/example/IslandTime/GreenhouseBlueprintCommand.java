package org.example.islandtime;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * {@code /it blueprint ...}: imports greenhouse layouts from skylayouts.io or skymutations.eu and
 * adjusts how the overlay in {@link GreenhouseBlueprintRenderer} is laid on the plot.
 */
final class GreenhouseBlueprintCommand {

    private static final String PREFIX = "[IslandTime] ";

    private GreenhouseBlueprintCommand() {}

    static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return literal("blueprint")
                .executes(ctx -> {
                    status(ctx.getSource().getClient());
                    return 1;
                })
                .then(literal("status")
                        .executes(ctx -> {
                            status(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("import")
                        .executes(ctx -> {
                            importLayout(ctx.getSource().getClient(), null);
                            return 1;
                        })
                        .then(argument("link", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    importLayout(ctx.getSource().getClient(),
                                            StringArgumentType.getString(ctx, "link"));
                                    return 1;
                                })))
                .then(literal("list")
                        .executes(ctx -> {
                            list(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("select")
                        .then(argument("blueprint", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    select(ctx.getSource().getClient(),
                                            StringArgumentType.getString(ctx, "blueprint"));
                                    return 1;
                                })))
                .then(literal("delete")
                        .then(argument("blueprint", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    delete(ctx.getSource().getClient(),
                                            StringArgumentType.getString(ctx, "blueprint"));
                                    return 1;
                                })))
                .then(literal("board")
                        .then(argument("number", IntegerArgumentType.integer(1, GreenhouseBlueprint.MAX_BOARDS))
                                .executes(ctx -> {
                                    board(ctx.getSource().getClient(),
                                            IntegerArgumentType.getInteger(ctx, "number"));
                                    return 1;
                                })))
                .then(literal("rotate")
                        .executes(ctx -> {
                            rotate(ctx.getSource().getClient(), 1);
                            return 1;
                        })
                        .then(literal("right")
                                .executes(ctx -> {
                                    rotate(ctx.getSource().getClient(), 1);
                                    return 1;
                                }))
                        .then(literal("left")
                                .executes(ctx -> {
                                    rotate(ctx.getSource().getClient(), -1);
                                    return 1;
                                })))
                .then(literal("mirror")
                        .executes(ctx -> {
                            mirror(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("align")
                        .executes(ctx -> {
                            align(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("toggle")
                        .executes(ctx -> {
                            toggle(ctx.getSource().getClient());
                            return 1;
                        }));
    }

    // -------------------------------------------------------------------------
    // Messages
    // -------------------------------------------------------------------------

    private static void send(Minecraft client, String text, ChatFormatting color) {
        if (client.player == null) return;
        client.player.sendSystemMessage(Component.literal(PREFIX + text).withStyle(color));
    }

    private static void sendLine(Minecraft client, String text, ChatFormatting color) {
        if (client.player == null) return;
        client.player.sendSystemMessage(Component.literal("  " + text).withStyle(color));
    }

    private static GreenhouseBlueprint requireActive(Minecraft client) {
        GreenhouseBlueprint active = GreenhouseBlueprintManager.getActive();
        if (active == null) {
            send(client, "No blueprint is selected. Import one with /it blueprint import <link>.", ChatFormatting.RED);
        }
        return active;
    }

    // -------------------------------------------------------------------------
    // Subcommands
    // -------------------------------------------------------------------------

    private static void status(Minecraft client) {
        if (client.player == null) return;
        send(client, "Greenhouse blueprint:", ChatFormatting.GOLD);
        for (String line : GreenhouseBlueprintRenderer.describe(client)) {
            sendLine(client, line, ChatFormatting.GRAY);
        }
        sendLine(client, "Commands: import [link], list, select <name>, delete <name>, board <n>, "
                + "rotate [left|right], mirror, align, toggle", ChatFormatting.DARK_GRAY);
    }

    private static void importLayout(Minecraft client, String link) {
        if (client.player == null) return;
        String text = link;
        if (text == null || text.isBlank()) {
            text = client.keyboardHandler.getClipboard();
        }

        GreenhouseLayoutImporter.ImportResult result = GreenhouseLayoutImporter.parse(text);
        if (!result.ok()) {
            send(client, result.error(), ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprint blueprint = result.blueprint();
        if (!GreenhouseBlueprintManager.add(blueprint)) {
            send(client, "You already have " + GreenhouseBlueprintManager.MAX_BLUEPRINTS
                    + " blueprints. Delete one with /it blueprint delete <name> first.", ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprintRenderer.requestRefresh();

        int boards = blueprint.boards.size();
        send(client, "Imported \"" + blueprint.name + "\" from " + blueprint.source + " (" + boards + " board"
                + (boards == 1 ? "" : "s") + ") and selected it.", ChatFormatting.GREEN);
        for (String note : result.notes()) {
            sendLine(client, note, ChatFormatting.YELLOW);
        }
        sendLine(client, "Stand in a greenhouse plot in the Garden to see it. If it is turned the wrong way, use "
                + "/it blueprint align (after planting a few crops), rotate or mirror.", ChatFormatting.GRAY);
        if (boards > 1) {
            sendLine(client, "This layout has " + boards + " boards: stand in each plot and use "
                    + "/it blueprint board <n> to pick which one it shows.", ChatFormatting.GRAY);
        }
    }

    private static void list(Minecraft client) {
        if (client.player == null) return;
        List<GreenhouseBlueprint> all = GreenhouseBlueprintManager.getAll();
        if (all.isEmpty()) {
            send(client, "No blueprints yet. Import one with /it blueprint import <link>.", ChatFormatting.YELLOW);
            return;
        }
        GreenhouseBlueprint active = GreenhouseBlueprintManager.getActive();
        send(client, "Greenhouse blueprints:", ChatFormatting.GOLD);
        for (int i = 0; i < all.size(); i++) {
            GreenhouseBlueprint b = all.get(i);
            boolean isActive = b == active;
            sendLine(client, (i + 1) + ". " + b.name + " (" + b.source + ", " + b.boards.size() + " board"
                            + (b.boards.size() == 1 ? "" : "s") + ")" + (isActive ? "  <- selected" : ""),
                    isActive ? ChatFormatting.GREEN : ChatFormatting.GRAY);
        }
    }

    private static void select(Minecraft client, String query) {
        GreenhouseBlueprint blueprint = GreenhouseBlueprintManager.find(query);
        if (blueprint == null) {
            send(client, "No blueprint matches \"" + query + "\". See /it blueprint list.", ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprintManager.select(blueprint);
        GreenhouseBlueprintRenderer.requestRefresh();
        send(client, "Selected \"" + blueprint.name + "\".", ChatFormatting.GREEN);
    }

    private static void delete(Minecraft client, String query) {
        GreenhouseBlueprint blueprint = GreenhouseBlueprintManager.find(query);
        if (blueprint == null) {
            send(client, "No blueprint matches \"" + query + "\". See /it blueprint list.", ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprintManager.delete(blueprint);
        GreenhouseBlueprintRenderer.requestRefresh();
        send(client, "Deleted \"" + blueprint.name + "\".", ChatFormatting.GREEN);
    }

    private static void board(Minecraft client, int number) {
        GreenhouseBlueprint active = requireActive(client);
        if (active == null) return;
        GreenhouseBlueprintRenderer.PlotRef plot = GreenhouseBlueprintRenderer.currentPlot(client);
        if (plot == null) {
            send(client, "Stand inside a garden plot first.", ChatFormatting.RED);
            return;
        }
        if (number > active.boards.size()) {
            send(client, "\"" + active.name + "\" only has " + active.boards.size() + " board"
                    + (active.boards.size() == 1 ? "" : "s") + ".", ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprintManager.assignBoard(active, plot.x(), plot.z(), number - 1);
        GreenhouseBlueprintRenderer.requestRefresh();
        send(client, "Plot " + plot.x() + "," + plot.z() + " now shows board " + number + ".", ChatFormatting.GREEN);
    }

    private static void rotate(Minecraft client, int quarterTurns) {
        GreenhouseBlueprint active = requireActive(client);
        if (active == null) return;
        GreenhouseBlueprintManager.setOrientation(active, active.rotation + quarterTurns, active.mirrored);
        GreenhouseBlueprintRenderer.requestRefresh();
        send(client, "Rotated to " + active.rotation * 90 + "°" + (active.mirrored ? " (mirrored)" : "") + ".",
                ChatFormatting.GREEN);
    }

    private static void mirror(Minecraft client) {
        GreenhouseBlueprint active = requireActive(client);
        if (active == null) return;
        GreenhouseBlueprintManager.setOrientation(active, active.rotation, !active.mirrored);
        GreenhouseBlueprintRenderer.requestRefresh();
        send(client, "Mirroring " + (active.mirrored ? "on" : "off") + ".", ChatFormatting.GREEN);
    }

    /** Picks the rotation and mirroring that best fits the crops already planted in this plot. */
    private static void align(Minecraft client) {
        GreenhouseBlueprint active = requireActive(client);
        if (active == null) return;
        GreenhouseBlueprintRenderer.PlotRef plot = GreenhouseBlueprintRenderer.currentPlot(client);
        if (plot == null || client.level == null) {
            send(client, "Stand inside the garden plot you want to align to first.", ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprint.Board board = active.boardForPlot(plot.x(), plot.z());
        if (board == null) return;

        List<GreenhouseEvaluator.Detected> planted = GreenhouseBlueprintRenderer.detectPlanted(client.level, plot);
        if (planted.isEmpty()) {
            send(client, "Nothing is planted in this plot's greenhouse area. Plant a few crops from the "
                    + "layout first, then run this again.", ChatFormatting.RED);
            return;
        }
        GreenhouseEvaluator.Orientation best =
                GreenhouseEvaluator.bestOrientation(board, planted, active.rotation, active.mirrored);
        if (best.done() == 0) {
            send(client, "None of the planted crops match the layout in any direction, so the direction "
                    + "was left as it is.", ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprintManager.setOrientation(active, best.rotation(), best.mirrored());
        GreenhouseBlueprintRenderer.requestRefresh();
        send(client, "Aligned: rotated " + active.rotation * 90 + "°" + (active.mirrored ? ", mirrored" : "")
                + " (" + best.done() + " planted crops match).", ChatFormatting.GREEN);
    }

    private static void toggle(Minecraft client) {
        IslandTimeConfig config = ConfigManager.getConfig();
        if (config == null) return;
        config.greenhouseBlueprint.enabled = !config.greenhouseBlueprint.enabled;
        ConfigManager.saveToFile();
        GreenhouseBlueprintRenderer.requestRefresh();
        send(client, "Greenhouse blueprint overlay " + (config.greenhouseBlueprint.enabled ? "on" : "off") + ".",
                ChatFormatting.GREEN);
    }
}
