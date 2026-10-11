package org.example.islandtime.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.example.islandtime.ResourcePackConfig;
import org.example.islandtime.ResourcePackManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Hybrid resource pack handling:
 * - If we already have the pack cached (hash matches) and that cached copy is the one that is
 *   applied: intercept and cancel to avoid reload delays when switching servers/instances.
 * - If the pack is new/updated (hash differs): let vanilla handle it normally
 *   so textures load correctly, and cache a copy in the background.
 * - If the cache matches but its copy isn't applied yet: let vanilla handle it so the player
 *   still gets the right pack, and apply the cached copy for when the server pack goes away.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ResourcePackMixin {

    @Shadow @Final
    private Connection connection;

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void islandtime$interceptResourcePack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        if (!ResourcePackConfig.isEnabled()) return;

        String url = packet.url();
        String hash = packet.hash();
        UUID packId = packet.id();

        // Only handle Hypixel packs
        if (url == null || (!url.contains("hypixel") && !url.contains("Hypixel"))) return;

        if (ResourcePackManager.isCacheFresh(url, hash)) {
            if (ResourcePackManager.isCachedPackActive()) {
                // Cache is up-to-date and applied — intercept to avoid reload delay
                System.out.println("[IslandTime] Pack hash matches cache, skipping reload");
                connection.send(new ServerboundResourcePackPacket(packId, ServerboundResourcePackPacket.Action.ACCEPTED));
                connection.send(new ServerboundResourcePackPacket(packId, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
                ci.cancel();
            } else {
                // Cache is current but its copy isn't what the game has selected (an earlier
                // update could not be put in place) — cancelling would leave an old pack active
                System.out.println("[IslandTime] Pack hash matches cache but the cached copy is not applied, letting vanilla load it");
                ResourcePackManager.applyCachedPack();
            }
        } else {
            // Pack is new/updated — let vanilla handle it, cache a copy in background
            System.out.println("[IslandTime] Pack hash differs from cache, letting vanilla load it");
            ResourcePackManager.cacheServerPack(url, hash);
        }
    }
}
