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
 * Intercepts server resource pack packets to cache and persist them
 * when the "Keep Resource Pack" feature is enabled.
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

        // Only intercept Hypixel packs (the URL typically contains mc.hypixel.net or hypixel)
        if (url == null || (!url.contains("hypixel") && !url.contains("Hypixel"))) return;

        boolean handled = ResourcePackManager.handleServerPack(url, hash, packId);
        if (handled) {
            // Send acceptance responses back to the server so it doesn't wait forever
            connection.send(new ServerboundResourcePackPacket(packId, ServerboundResourcePackPacket.Action.ACCEPTED));
            connection.send(new ServerboundResourcePackPacket(packId, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
            ci.cancel();
        }
    }
}
