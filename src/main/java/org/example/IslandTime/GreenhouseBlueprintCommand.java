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
 * {@code /it blueprint ...}: copies a planted greenhouse into a blueprint (with a preview of the area
 * first) and adjusts how the overlay in {@link GreenhouseBlueprintRenderer} is laid on the plot.
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
                .then(literal("copy")
                        .executes(ctx -> {
                            copy(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("save")
                        .executes(ctx -> {
                            save(ctx.getSource().getClient(), null);
                            return 1;
                        })
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    save(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "name"));
                                    return 1;
                                })))
                .then(literal("addboard")
                        .executes(ctx -> {
                            addBoard(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("cancel")
                        .executes(ctx -> {
                            cancel(ctx.getSource().getClient());
                            return 1;
                        }))
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
            send(client, "No blueprint is selected. Copy a greenhouse first with /it blueprint copy, then "
                    + "/it blueprint save <name>.", ChatFormatting.RED);
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
        sendLine(client, "Commands: copy, save [name], addboard, cancel, list, select <name>, delete <name>, "
                + "board <n>, rotate [left|right], mirror, align, toggle", ChatFormatting.DARK_GRAY);
    }

    /** Starts the preview of the greenhouse area in the plot the player stands in. */
    private static void copy(Minecraft client) {
        if (client.player == null) return;
        String blocker = GreenhouseBlueprintRenderer.startCopy(client);
        if (blocker != null) {
            send(client, blocker, ChatFormatting.RED);
            return;
        }
        send(client, "Copy preview on. The gold frame is the greenhouse area. Coloured boxes are the crops that "
                + "would be saved: the blocks, and the armor stands with what they wear and hold (a custom crop "
                + "is a block with an armor stand on it). The soil under them is saved too.", ChatFormatting.GREEN);
        sendLine(client, "Plant or change things until it looks right, then /it blueprint save <name>. To add it "
                + "to the selected blueprint as another plot's board, use /it blueprint addboard instead. "
                + "/it blueprint cancel stops the preview.", ChatFormatting.GRAY);
        sendLine(client, "Stay close to the greenhouse so all of it is loaded.", ChatFormatting.GRAY);
    }

    /** Saves the crops planted in the plot's greenhouse area as a new blueprint. */
    private static void save(Minecraft client, String name) {
        if (client.player == null) return;
        GreenhouseBlueprintRenderer.Capture capture = GreenhouseBlueprintRenderer.captureNow(client);
        if (!capture.ok()) {
            send(client, capture.error(), ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprintRenderer.PlotRef plot = capture.plot();
        GreenhouseBlueprint blueprint =
                GreenhouseCapture.newBlueprint(name, capture.board(), plot.x(), plot.z());
        if (!GreenhouseBlueprintManager.add(blueprint)) {
            send(client, "You already have " + GreenhouseBlueprintManager.MAX_BLUEPRINTS
                    + " blueprints. Delete one with /it blueprint delete <name> first.", ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprintRenderer.stopCopy();

        send(client, "Saved \"" + blueprint.name + "\" (" + summary(capture.board()) + " from plot "
                + plot.x() + "," + plot.z() + ") and selected it.", ChatFormatting.GREEN);
        sendLine(client, "It is drawn over greenhouse plots in the Garden as see-through blocks and armor stands, "
                + "with a red outline around what isn't right yet, and whatever you build correctly disappears "
                + "from it. A greenhouse with more plots can have up to "
                + GreenhouseBlueprint.MAX_BOARDS + " boards: copy another plot and use /it blueprint addboard.",
                ChatFormatting.GRAY);
        int boxes = standsWithoutItems(capture.board());
        if (boxes > 0) {
            sendLine(client, boxes + " armor stand" + (boxes == 1 ? "" : "s") + " couldn't be copied with "
                    + (boxes == 1 ? "its" : "their") + " items, so " + (boxes == 1 ? "it is" : "they are")
                    + " drawn as a box.", ChatFormatting.YELLOW);
        }
    }

    /** How many of a copied board's armor stands have no items to draw them with. */
    private static int standsWithoutItems(GreenhouseBlueprint.Board board) {
        int n = 0;
        for (GreenhouseBlueprint.Placement p : board.placements) {
            for (GreenhouseBlueprint.Stand stand : p.stands) {
                if (!stand.drawable()) n++;
            }
        }
        return n;
    }

    /** Adds the plot's greenhouse area to the selected blueprint as another board. */
    private static void addBoard(Minecraft client) {
        GreenhouseBlueprint active = requireActive(client);
        if (active == null) return;
        GreenhouseBlueprintRenderer.Capture capture = GreenhouseBlueprintRenderer.captureNow(client);
        if (!capture.ok()) {
            send(client, capture.error(), ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprintRenderer.PlotRef plot = capture.plot();
        if (!GreenhouseBlueprintManager.addBoard(active, capture.board(), plot.x(), plot.z())) {
            send(client, "\"" + active.name + "\" already has " + GreenhouseBlueprint.MAX_BOARDS
                    + " boards, the most it can hold.", ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprintRenderer.stopCopy();
        send(client, "Added board " + active.boards.size() + " (" + summary(capture.board()) + ") to \""
                + active.name + "\" and set it for plot " + plot.x() + "," + plot.z() + ".", ChatFormatting.GREEN);
    }

    /** "12 crops (3 with armor stands), 100 soil blocks of 2 kinds" for a copied board. */
    private static String summary(GreenhouseBlueprint.Board board) {
        int crops = board.placements.size();
        int withStands = 0;
        for (GreenhouseBlueprint.Placement p : board.placements) {
            if (!p.stands.isEmpty()) withStands++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(crops).append(" crop").append(crops == 1 ? "" : "s");
        if (withStands > 0) sb.append(" (").append(withStands).append(" with armor stands)");
        if (board.hasFloor()) {
            int soil = 0;
            for (int z = 0; z < board.size; z++) {
                for (int x = 0; x < board.size; x++) {
                    if (!board.floorAt(x, z).isEmpty()) soil++;
                }
            }
            sb.append(", ").append(soil).append(" soil block").append(soil == 1 ? "" : "s").append(" of ")
                    .append(board.floorPalette.size()).append(" kind").append(board.floorPalette.size() == 1 ? "" : "s");
        }
        return sb.toString();
    }

    private static void cancel(Minecraft client) {
        if (client.player == null) return;
        boolean wasCopying = GreenhouseBlueprintRenderer.isCopying();
        GreenhouseBlueprintRenderer.stopCopy();
        send(client, wasCopying ? "Copy preview off." : "The copy preview wasn't on.",
                wasCopying ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
    }

    private static void list(Minecraft client) {
        if (client.player == null) return;
        List<GreenhouseBlueprint> all = GreenhouseBlueprintManager.getAll();
        if (all.isEmpty()) {
            send(client, "No blueprints yet. Copy one from a planted greenhouse with /it blueprint copy.",
                    ChatFormatting.YELLOW);
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

    /** Picks the rotation and mirroring that best fits what is already built in this plot. */
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
        if (!GreenhouseBlueprintRenderer.areaLoaded(client.level, plot)) {
            send(client, "Part of this plot's greenhouse area isn't loaded. Move closer to it and try again.",
                    ChatFormatting.RED);
            return;
        }

        GreenhouseScan.Scan scan = GreenhouseBlueprintRenderer.scan(client.level, plot);
        if (!scan.hasCrops() && !board.hasFloor()) {
            send(client, "Nothing is planted in this plot's greenhouse area. Plant a few crops from the "
                    + "layout first, then run this again.", ChatFormatting.RED);
            return;
        }
        GreenhouseEvaluator.Orientation best =
                GreenhouseEvaluator.bestOrientation(board, scan, active.rotation, active.mirrored);
        boolean changed = best.rotation() != Math.floorMod(active.rotation, 4) || best.mirrored() != active.mirrored;
        if (best.done() == 0 && !changed) {
            send(client, "Couldn't tell which way the layout is turned: nothing planted matches it, and the soil "
                    + "fits every direction equally. The direction was left as it is.", ChatFormatting.RED);
            return;
        }
        GreenhouseBlueprintManager.setOrientation(active, best.rotation(), best.mirrored());
        GreenhouseBlueprintRenderer.requestRefresh();
        send(client, "Aligned: rotated " + active.rotation * 90 + "°" + (active.mirrored ? ", mirrored" : "")
                + " (" + best.done() + " crop" + (best.done() == 1 ? "" : "s") + " and " + best.floorDone()
                + " soil block" + (best.floorDone() == 1 ? "" : "s") + " match).", ChatFormatting.GREEN);
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
