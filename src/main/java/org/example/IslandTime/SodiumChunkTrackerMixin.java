package org.example.islandtime.mixin;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.example.islandtime.EdgeChunkRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sodium replaces the vanilla chunk renderer, and has its own version of "wait for the neighbours": a chunk is only
 * built once it and all eight chunks around it have both block and light data. The Render Edge Chunks setting
 * (see {@link EdgeChunkRendering}) makes it ignore the neighbours' status. Does nothing when Sodium isn't installed.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker")
public class SodiumChunkTrackerMixin {

    private static final int ALL_DATA = 3; // ChunkStatus.FLAG_ALL: block data and light data

    // updateMerged reads the chunk's own status first (ordinal 0), then those of the 3x3 chunks around it, which
    // it ANDs together (ordinal 1, in the loop). A neighbour that counts as complete leaves the chunk's own status.
    @Redirect(method = "updateMerged",
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2IntOpenHashMap;get(J)I",
                    ordinal = 1),
            require = 0)
    private int islandtime$ignoreNeighbours(Long2IntOpenHashMap statuses, long chunk) {
        return EdgeChunkRendering.isEnabled() ? ALL_DATA : statuses.get(chunk);
    }
}
