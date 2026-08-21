package org.example.islandtime;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

/**
 * Manages AFK mode — drastically reduces resource usage by lowering
 * FPS cap, render distance, particles, sound, etc.
 *
 * All changes are client-side only; nothing touches packets or
 * server-side state.  Original settings are restored on toggle-off.
 */
public class AfkModeManager {

    private static boolean active = false;

    // Saved originals
    private static int savedFpsLimit;
    private static int savedRenderDistance;
    private static int savedSimulationDistance;
    private static ParticleStatus savedParticles;
    private static double savedSoundVolume;
    private static boolean savedVsync;
    private static boolean savedEntityShadows;
    private static int savedCloudStatus; // CloudStatus ordinal
    private static GraphicsStatus savedGraphicsMode;
    private static int savedMipmapLevels;
    private static int savedBiomeBlendRadius;

    // AFK values
    private static final int AFK_FPS_LIMIT = 2;
    private static final int AFK_RENDER_DISTANCE = 2;
    private static final int AFK_SIMULATION_DISTANCE = 2;

    public static boolean isActive() {
        return active;
    }

    public static void toggle(Minecraft client) {
        if (active) {
            disable(client);
        } else {
            enable(client);
        }
    }

    private static void enable(Minecraft client) {
        Options opts = client.options;

        // Save current settings
        savedFpsLimit = opts.framerateLimit().get();
        savedRenderDistance = opts.renderDistance().get();
        savedSimulationDistance = opts.simulationDistance().get();
        savedParticles = opts.particles().get();
        savedSoundVolume = opts.getSoundSourceVolume(SoundSource.MASTER);
        savedVsync = opts.enableVsync().get();
        savedEntityShadows = opts.entityShadows().get();
        savedCloudStatus = opts.cloudStatus().get().getId();
        savedGraphicsMode = opts.graphicsMode().get();
        savedMipmapLevels = opts.mipmapLevels().get();
        savedBiomeBlendRadius = opts.biomeBlendRadius().get();

        // Apply AFK settings
        opts.framerateLimit().set(AFK_FPS_LIMIT);
        opts.renderDistance().set(AFK_RENDER_DISTANCE);
        opts.simulationDistance().set(AFK_SIMULATION_DISTANCE);
        opts.particles().set(ParticleStatus.MINIMAL);
        opts.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        opts.enableVsync().set(true);
        opts.entityShadows().set(false);
        opts.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        opts.graphicsMode().set(GraphicsStatus.FAST);
        opts.mipmapLevels().set(0);
        opts.biomeBlendRadius().set(0);

        active = true;

        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.literal("[IslandTime] AFK mode ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal("enabled").withStyle(ChatFormatting.GREEN))
                            .append(Component.literal(" — FPS 2, render 2, graphics fast, sounds off, particles minimal").withStyle(ChatFormatting.GRAY)));
        }
    }

    private static void disable(Minecraft client) {
        Options opts = client.options;

        // Restore saved settings
        opts.framerateLimit().set(savedFpsLimit);
        opts.renderDistance().set(savedRenderDistance);
        opts.simulationDistance().set(savedSimulationDistance);
        opts.particles().set(savedParticles);
        opts.getSoundSourceOptionInstance(SoundSource.MASTER).set(savedSoundVolume);
        opts.enableVsync().set(savedVsync);
        opts.entityShadows().set(savedEntityShadows);
        opts.cloudStatus().set(net.minecraft.client.CloudStatus.byId(savedCloudStatus));
        opts.graphicsMode().set(savedGraphicsMode);
        opts.mipmapLevels().set(savedMipmapLevels);
        opts.biomeBlendRadius().set(savedBiomeBlendRadius);

        active = false;

        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.literal("[IslandTime] AFK mode ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal("disabled").withStyle(ChatFormatting.RED))
                            .append(Component.literal(" — settings restored").withStyle(ChatFormatting.GRAY)));
        }
    }
}
