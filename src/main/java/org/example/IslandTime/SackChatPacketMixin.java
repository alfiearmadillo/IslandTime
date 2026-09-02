package org.example.islandtime.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.example.islandtime.ChatMessageStore;
import org.example.islandtime.SackIncomeTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads all non-overlay system chat packets directly from the network handler, bypassing Fabric
 * API's cancelable {@code ClientReceiveMessageEvents.ALLOW_GAME}/{@code GAME} event chain
 * entirely, and feeds both sack-income tracking and the Player List screen's chat mirror.
 *
 * <p>This exists because other client-side mods (e.g. SkyHanni's "Sack Change Hider" setting)
 * can cancel a game message in {@code ALLOW_GAME} to hide it from the chat HUD. When that
 * happens, Fabric API's {@code GAME} event never fires for that message at all, so both gabagool
 * income tracking and {@link ChatMessageStore}'s mirror (used for the diagnostic shift+right-click
 * component dump in {@code PlayerListScreen}) would silently miss it — even though the underlying
 * packet was still fully received, and even though the mirror's dump feature exists specifically
 * to inspect messages like a hidden "[Sacks]" line. Hooking {@code handleSystemChat} at
 * {@code HEAD} reads the packet's content before any mod (including our own chat display code)
 * has a chance to react to or cancel it, so both keep working regardless of any other mod's
 * chat-hiding behavior.
 */
@Mixin(ClientPacketListener.class)
public class SackChatPacketMixin {

    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void islandtime$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (packet.overlay()) return;
        SackIncomeTracker.onSackChatMessage(packet.content());
        ChatMessageStore.addRawMessage(packet.content());
    }
}

