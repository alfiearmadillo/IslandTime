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

import net.minecraft.client.OptionInstance;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;

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

    /**
     * Bypass OptionInstance's validator and set the value directly via reflection.
     * This is needed because Sodium/vanilla clamp slider values (e.g. render min 8).
     */
    @SuppressWarnings("unchecked")
    private static <T> void forceSet(OptionInstance<T> option, T value) {
        try {
            // OptionInstance stores value in a field called "value"
            Field valueField = OptionInstance.class.getDeclaredField("value");
            valueField.setAccessible(true);
            valueField.set(option, value);
        } catch (NoSuchFieldException e) {
            // Try alternative field names (obfuscated environments)
            try {
                for (Field f : OptionInstance.class.getDeclaredFields()) {
                    if (f.getType() == Object.class || f.getType() == value.getClass()) {
                        f.setAccessible(true);
                        Object current = f.get(option);
                        if (current != null && current.getClass() == value.getClass()) {
                            f.set(option, value);
                            return;
                        }
                    }
                }
                // Fallback to normal set if reflection fails
                option.set(value);
            } catch (Exception ex) {
                option.set(value);
            }
        } catch (Exception e) {
            // Fallback to normal set
            option.set(value);
        }
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

        // Apply AFK settings — use forceSet to bypass OptionInstance validators
        // which clamp values (e.g. Sodium sets render distance min to 8)
        forceSet(opts.framerateLimit(), AFK_FPS_LIMIT);
        forceSet(opts.renderDistance(), AFK_RENDER_DISTANCE);
        forceSet(opts.simulationDistance(), AFK_SIMULATION_DISTANCE);
        opts.particles().set(ParticleStatus.MINIMAL);
        forceSet(opts.getSoundSourceOptionInstance(SoundSource.MASTER), 0.0);
        opts.entityShadows().set(false);
        opts.cloudStatus().set(CloudStatus.OFF);
        // Don't change graphicsPreset — Sodium splits it into per-feature quality
        // settings (like leaves). Changing it breaks those individual settings.
        forceSet(opts.mipmapLevels(), 0);
        forceSet(opts.biomeBlendRadius(), 0);
        opts.ambientOcclusion().set(false);
        forceSet(opts.entityDistanceScaling(), 0.5);
        forceSet(opts.screenEffectScale(), 0.0);
        forceSet(opts.glintSpeed(), 0.0);
        forceSet(opts.glintStrength(), 0.0);
        forceSet(opts.darknessEffectScale(), 0.0);
        forceSet(opts.damageTiltStrength(), 0.0);

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
                            .append(Component.literal(" — FPS 2, render 2, graphics fast, sounds off, particles minimal").withStyle(ChatFormatting.GRAY)));
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
        forceSet(opts.framerateLimit(), s.fpsLimit);
        forceSet(opts.renderDistance(), s.renderDistance);
        forceSet(opts.simulationDistance(), s.simulationDistance);
        opts.particles().set(ParticleStatus.valueOf(s.particles));
        forceSet(opts.getSoundSourceOptionInstance(SoundSource.MASTER), s.soundVolume);
        opts.entityShadows().set(s.entityShadows);
        opts.cloudStatus().set(CloudStatus.valueOf(s.cloudStatus));
        forceSet(opts.mipmapLevels(), s.mipmapLevels);
        forceSet(opts.biomeBlendRadius(), s.biomeBlendRadius);
        opts.ambientOcclusion().set(s.smoothLighting);
        forceSet(opts.entityDistanceScaling(), s.entityDistanceScaling);
        forceSet(opts.screenEffectScale(), s.screenEffectScale);
        forceSet(opts.glintSpeed(), s.glintSpeed);
        forceSet(opts.glintStrength(), s.glintStrength);
        forceSet(opts.darknessEffectScale(), s.darknessEffectScale);
        forceSet(opts.damageTiltStrength(), s.damageTiltStrength);
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
