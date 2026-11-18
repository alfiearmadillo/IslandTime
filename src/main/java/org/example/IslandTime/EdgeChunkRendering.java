package org.example.islandtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

/**
 * The "Render Edge Chunks" setting. Vanilla builds a chunk section only once all eight chunks around it have loaded
 * (and have light), so sections along the edge of what the server sends stay undrawn although their blocks exist.
 * Sodium, which replaces the vanilla renderer, has the same rule for whole chunks. With this on,
 * {@code RenderSectionMixin} (vanilla) and {@code SodiumChunkTrackerMixin} (Sodium) skip that wait.
 */
public final class EdgeChunkRendering {

    private static final String SODIUM_HOLDER = "net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder";

    private static volatile boolean enabled = false;

    private EdgeChunkRendering() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Turns it on or off, and rebuilds what is on screen so sections that were waiting are drawn (or dropped) at once. */
    public static void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        client.execute(() -> {
            // Sodium decides which chunks are ready as they load, so the ones already loaded have to be looked at again
            reevaluateSodiumChunks(client);
            client.levelRenderer.allChanged();
        });
    }

    /** Asks Sodium to work out again which loaded chunks are ready to build. Does nothing without Sodium. */
    private static void reevaluateSodiumChunks(Minecraft client) {
        if (client.level == null) return;
        try {
            Object tracker = Class.forName(SODIUM_HOLDER).getMethod("get", ClientLevel.class).invoke(null, client.level);
            if (tracker == null) return;

            Field statusField = tracker.getClass().getDeclaredField("chunkStatus");
            statusField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Long, Integer> statuses = (Map<Long, Integer>) statusField.get(tracker);

            Method updateMerged = tracker.getClass().getDeclaredMethod("updateMerged", int.class, int.class);
            updateMerged.setAccessible(true);
            for (Long chunk : new ArrayList<>(statuses.keySet())) {
                updateMerged.invoke(tracker, ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            }
        } catch (ClassNotFoundException e) {
            // Sodium isn't installed
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[IslandTime] Could not refresh Sodium's chunks for Render Edge Chunks: " + e);
        }
    }
}
