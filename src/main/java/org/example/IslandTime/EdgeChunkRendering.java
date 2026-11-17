package org.example.islandtime;

import net.minecraft.client.Minecraft;

/**
 * The "Render Edge Chunks" setting. Vanilla builds a chunk section only once all eight chunks around it have loaded
 * (and have light), so sections along the edge of what the server sends stay undrawn although their blocks exist.
 * With this on, {@code RenderSectionMixin} skips that wait.
 */
public final class EdgeChunkRendering {

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
        if (client.level != null) client.execute(client.levelRenderer::allChanged);
    }
}
