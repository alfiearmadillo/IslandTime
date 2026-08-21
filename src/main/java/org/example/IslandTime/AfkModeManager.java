package org.example.islandtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Manages AFK mode — drastically reduces resource usage by lowering
 * FPS cap, render distance, particles, sound, etc.
 *
 * All changes are client-side only; nothing touches packets or
 * server-side state.  Original settings are persisted to a backup
 * file so they survive crashes and vanilla options.txt auto-saves.
 */
public class AfkModeManager {

    private static boolean active = false;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File BACKUP_FILE = new File("config/islandtime_afk_backup.json");

    // AFK values
    private static final int AFK_FPS_LIMIT = 2;
    private static final int AFK_RENDER_DISTANCE = 2;
    private static final int AFK_SIMULATION_DISTANCE = 2;

    /** Serializable snapshot of all settings we override. */
    private static class SavedSettings {
        int fpsLimit;
        int renderDistance;
        int simulationDistance;
        String particles; // enum name
        double soundVolume;
        boolean entityShadows;
        String cloudStatus; // enum name
        int mipmapLevels;
        int biomeBlendRadius;
        boolean smoothLighting;
        double entityDistanceScaling;
        double screenEffectScale;
        double glintSpeed;
        double glintStrength;
        double darknessEffectScale;
        double damageTiltStrength;
    }

    private static SavedSettings saved;

    public static boolean isActive() {
        return active;
    }

    /**
     * Called on mod init — if a backup file exists, AFK mode was active
     * when the game last exited (crash or normal quit). Restore settings.
     */
    public static void restoreIfNeeded() {
        if (!BACKUP_FILE.exists()) return;
        try {
            Minecraft client = Minecraft.getInstance();
            Options opts = client.options;
            SavedSettings s;
            try (FileReader reader = new FileReader(BACKUP_FILE)) {
                s = GSON.fromJson(reader, SavedSettings.class);
            }
            if (s == null) {
                BACKUP_FILE.delete();
                return;
            }
            applySettings(opts, s);
            opts.save();
            BACKUP_FILE.delete();
            System.out.println("[IslandTime] Restored pre-AFK settings from backup (previous session ended during AFK mode)");
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to restore AFK backup: " + e.getMessage());
            BACKUP_FILE.delete();
        }
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

        // Snapshot current settings
        saved = captureSettings(opts);

        // Persist backup to disk so we can restore after crash/disconnect
        saveBackup(saved);

        // Apply AFK settings
        opts.framerateLimit().set(AFK_FPS_LIMIT);
        opts.renderDistance().set(AFK_RENDER_DISTANCE);
        opts.simulationDistance().set(AFK_SIMULATION_DISTANCE);
        opts.particles().set(ParticleStatus.MINIMAL);
        opts.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        opts.entityShadows().set(false);
        opts.cloudStatus().set(CloudStatus.OFF);
        opts.mipmapLevels().set(0);
        opts.biomeBlendRadius().set(0);
        opts.ambientOcclusion().set(false);
        opts.entityDistanceScaling().set(0.5);
        opts.screenEffectScale().set(0.0);
        opts.glintSpeed().set(0.0);
        opts.glintStrength().set(0.0);
        opts.darknessEffectScale().set(0.0);
        opts.damageTiltStrength().set(0.0);

        // Persist and force renderer to pick up new render distance
        opts.save();
        if (client.levelRenderer != null) {
            client.levelRenderer.allChanged();
        }

        active = true;

        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.literal("[IslandTime] AFK mode ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal("enabled").withStyle(ChatFormatting.GREEN))
                            .append(Component.literal(" — FPS 2, render 2, sounds off, particles minimal").withStyle(ChatFormatting.GRAY)));
        }
    }

    private static void disable(Minecraft client) {
        Options opts = client.options;

        if (saved != null) {
            applySettings(opts, saved);
        }

        // Persist restored settings to options.txt and remove backup
        opts.save();
        if (client.levelRenderer != null) {
            client.levelRenderer.allChanged();
        }
        BACKUP_FILE.delete();
        saved = null;
        active = false;

        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.literal("[IslandTime] AFK mode ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal("disabled").withStyle(ChatFormatting.RED))
                            .append(Component.literal(" — settings restored").withStyle(ChatFormatting.GRAY)));
        }
    }

    // ---- helpers ----

    private static SavedSettings captureSettings(Options opts) {
        SavedSettings s = new SavedSettings();
        s.fpsLimit = opts.framerateLimit().get();
        s.renderDistance = opts.renderDistance().get();
        s.simulationDistance = opts.simulationDistance().get();
        s.particles = opts.particles().get().name();
        s.soundVolume = opts.getSoundSourceVolume(SoundSource.MASTER);
        s.entityShadows = opts.entityShadows().get();
        s.cloudStatus = opts.cloudStatus().get().name();
        s.mipmapLevels = opts.mipmapLevels().get();
        s.biomeBlendRadius = opts.biomeBlendRadius().get();
        s.smoothLighting = opts.ambientOcclusion().get();
        s.entityDistanceScaling = opts.entityDistanceScaling().get();
        s.screenEffectScale = opts.screenEffectScale().get();
        s.glintSpeed = opts.glintSpeed().get();
        s.glintStrength = opts.glintStrength().get();
        s.darknessEffectScale = opts.darknessEffectScale().get();
        s.damageTiltStrength = opts.damageTiltStrength().get();
        return s;
    }

    private static void applySettings(Options opts, SavedSettings s) {
        opts.framerateLimit().set(s.fpsLimit);
        opts.renderDistance().set(s.renderDistance);
        opts.simulationDistance().set(s.simulationDistance);
        opts.particles().set(ParticleStatus.valueOf(s.particles));
        opts.getSoundSourceOptionInstance(SoundSource.MASTER).set(s.soundVolume);
        opts.entityShadows().set(s.entityShadows);
        opts.cloudStatus().set(CloudStatus.valueOf(s.cloudStatus));
        opts.mipmapLevels().set(s.mipmapLevels);
        opts.biomeBlendRadius().set(s.biomeBlendRadius);
        opts.ambientOcclusion().set(s.smoothLighting);
        opts.entityDistanceScaling().set(s.entityDistanceScaling);
        opts.screenEffectScale().set(s.screenEffectScale);
        opts.glintSpeed().set(s.glintSpeed);
        opts.glintStrength().set(s.glintStrength);
        opts.darknessEffectScale().set(s.darknessEffectScale);
        opts.damageTiltStrength().set(s.damageTiltStrength);
    }

    private static void saveBackup(SavedSettings s) {
        try {
            BACKUP_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(BACKUP_FILE)) {
                GSON.toJson(s, writer);
            }
        } catch (Exception e) {
            System.err.println("[IslandTime] Failed to save AFK backup: " + e.getMessage());
        }
    }
}
