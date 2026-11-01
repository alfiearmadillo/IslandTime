package org.example.islandtime;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * {@code /it guilog ...}: starts and stops the {@link GuiDebugLog} diagnostic log and lets the
 * player drop labelled markers into it while it runs.
 */
final class GuiDebugCommand {

    private static final String PREFIX = "[IslandTime] ";

    private GuiDebugCommand() {}

    static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return literal("guilog")
                .executes(ctx -> {
                    status(ctx.getSource().getClient());
                    return 1;
                })
                .then(literal("status")
                        .executes(ctx -> {
                            status(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("start")
                        .executes(ctx -> {
                            start(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("stop")
                        .executes(ctx -> {
                            stop(ctx.getSource().getClient());
                            return 1;
                        }))
                .then(literal("note")
                        .then(argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    note(ctx.getSource().getClient(), StringArgumentType.getString(ctx, "text"));
                                    return 1;
                                })));
    }

    private static void status(Minecraft client) {
        send(client, GuiDebugLog.status(), GuiDebugLog.isActive() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
        if (!GuiDebugLog.isActive()) {
            send(client, "Use /it guilog start, do what you want recorded, then /it guilog stop.", ChatFormatting.GRAY);
        }
    }

    private static void start(Minecraft client) {
        if (GuiDebugLog.isActive()) {
            send(client, "Already recording to " + GuiDebugLog.currentFile(), ChatFormatting.YELLOW);
            return;
        }
        try {
            Path file = GuiDebugLog.start();
            send(client, "Recording GUI activity to " + file, ChatFormatting.GREEN);
            send(client, "Chat is not recorded. Mark moments with /it guilog note <text>; finish with /it guilog stop.",
                    ChatFormatting.GRAY);
        } catch (IOException | RuntimeException e) {
            send(client, "Could not start the GUI log: " + e, ChatFormatting.RED);
        }
    }

    private static void stop(Minecraft client) {
        Path file = GuiDebugLog.stop("stopped by command");
        if (file == null) {
            send(client, "The GUI log was not running.", ChatFormatting.YELLOW);
            return;
        }
        send(client, "GUI log saved to " + file, ChatFormatting.GREEN);
    }

    private static void note(Minecraft client, String text) {
        if (!GuiDebugLog.isActive()) {
            send(client, "The GUI log is not running. Start it with /it guilog start.", ChatFormatting.YELLOW);
            return;
        }
        GuiDebugLog.note(text);
        send(client, "Noted.", ChatFormatting.GRAY);
    }

    private static void send(Minecraft client, String message, ChatFormatting colour) {
        if (client.player == null) return;
        client.player.sendSystemMessage(Component.literal(PREFIX + message).withStyle(colour));
    }
}
