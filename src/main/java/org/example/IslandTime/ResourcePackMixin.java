package org.example.islandtime.mixin;

import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import org.example.islandtime.ResourcePackConfig;
import org.example.islandtime.ResourcePackManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Intercepts server resource pack packets to cache and persist them
 * when the "Keep Resource Pack" feature is enabled.
 */
@Mixin(ClientCommonNetworkHandler.class)
public class ResourcePackMixin {

    @Inject(method = "onResourcePackSend", at = @At("HEAD"), cancellable = true)
    private void islandtime$interceptResourcePack(ResourcePackSendS2CPacket packet, CallbackInfo ci) {
        if (!ResourcePackConfig.isEnabled()) return;

        String url = packet.url();
        String hash = packet.hash();
        UUID packId = packet.id();

        // Only intercept Hypixel packs (the URL typically contains mc.hypixel.net or hypixel)
        if (url == null || (!url.contains("hypixel") && !url.contains("Hypixel"))) return;

        boolean handled = ResourcePackManager.handleServerPack(url, hash, packId);
        if (handled) {
            // Cancel the vanilla resource pack download/prompt — we handle it ourselves
            ci.cancel();
        }
    }
}
