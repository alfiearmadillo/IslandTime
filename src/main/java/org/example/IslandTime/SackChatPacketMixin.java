package org.example.islandtime.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.example.islandtime.SackIncomeTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads "[Sacks]" system chat packets directly from the network handler, bypassing Fabric API's
 * cancelable {@code ClientReceiveMessageEvents.ALLOW_GAME}/{@code GAME} event chain entirely.
 *
 * <p>This exists because other client-side mods (e.g. SkyHanni's "Sack Change Hider" setting)
 * can cancel a game message in {@code ALLOW_GAME} to hide it from the chat HUD. When that
 * happens, Fabric API's {@code GAME} event (which {@link org.example.islandtime.ChatMessageStore}
 * used to feed sack tracking from) never fires for that message at all, so gabagool income
 * tracking would silently stop working while such a hider is enabled — even though the
 * underlying packet was still fully received. Hooking {@code handleSystemChat} at
 * {@code HEAD} reads the packet's content before any mod (including our own chat display code)
 * has a chance to react to or cancel it, so sack tracking keeps working regardless of any other
 * mod's chat-hiding behavior.
 */
@Mixin(ClientPacketListener.class)
public class SackChatPacketMixin {

    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void islandtime$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (packet.overlay()) return;
        SackIncomeTracker.onSackChatMessage(packet.content());
    }
}
