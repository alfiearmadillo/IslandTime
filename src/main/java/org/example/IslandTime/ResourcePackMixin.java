package org.example.islandtime.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import org.example.islandtime.ResourcePackConfig;
import org.example.islandtime.ResourcePackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches for Hypixel server resource pack packets and caches a copy
 * for offline use. Does NOT intercept or cancel the vanilla handling —
 * lets vanilla load the server pack normally so all textures work.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ResourcePackMixin {

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"))
    private void islandtime$cacheResourcePack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        if (!ResourcePackConfig.isEnabled()) return;

        String url = packet.url();
        String hash = packet.hash();

        // Only cache Hypixel packs
        if (url == null || (!url.contains("hypixel") && !url.contains("Hypixel"))) return;

        // Cache a copy in the background for offline use (don't block vanilla handling)
        ResourcePackManager.cacheServerPack(url, hash);
    }
}
