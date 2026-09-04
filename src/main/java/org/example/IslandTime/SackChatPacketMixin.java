package org.example.islandtime.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.example.islandtime.ChatMessageStore;
import org.example.islandtime.SackIncomeTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads all non-overlay system chat packets directly from the network handler, bypassing Fabric
 * API's cancelable {@code ClientReceiveMessageEvents.ALLOW_GAME}/{@code GAME} event chain
 * entirely for sack messages, and feeds sack-income tracking plus the Player List screen's chat
 * mirror for those messages. Ordinary chat is captured by {@link
 * org.example.islandtime.ChatMessageStore} through Fabric's normal game-message event so it is
 * not recorded twice.
 *
 * <p>This exists because other client-side mods (e.g. SkyHanni's "Sack Change Hider" setting)
 * can cancel a game message in {@code ALLOW_GAME} to hide it from the chat HUD. When that
 * happens, Fabric API's {@code GAME} event never fires for that message at all, so both gabagool
 * income tracking and the sack entry in {@link ChatMessageStore}'s mirror (used for the diagnostic
 * shift+right-click component dump in {@code PlayerListScreen}) would silently miss it — even
 * though the underlying packet was still fully received. Hooking {@code handleSystemChat} at
 * {@code HEAD} reads the packet's content before any mod has a chance to react to or cancel it.
 *
 * <p><b>Duplicate-invocation guard:</b> real-world testing showed both gabagool tracking and the
 * chat mirror consistently double-counting/double-displaying every message — not a parsing
 * artifact (a captured "+147 items" message was independently verified to parse to a single
 * "+147 Crude Gabagool" match), but the {@code islandtime$onSystemChat} hook itself apparently
 * running twice per real chat line. Since this happened uniformly for every kind of message (not
 * just "[Sacks]" ones), and no other mixin or code path in this mod touches
 * {@code handleSystemChat} or calls {@code SackIncomeTracker}/{@code ChatMessageStore} a second
 * time, the most likely explanation is the exact same {@link ClientboundSystemChatPacket}
 * instance reaching this injected hook twice for one real network delivery. Guarding by object
 * identity (not content) against immediate repeat invocation of the same packet instance fixes
 * this without any risk of ever suppressing two separate, genuinely repeated real messages
 * (which would always be different packet instances even if their text happens to match).
 */
@Mixin(ClientPacketListener.class)
public class SackChatPacketMixin {

    /** The last packet instance this hook actually processed, to guard against re-invocation. */
    @Unique
    private static ClientboundSystemChatPacket islandtime$lastPacket;

    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void islandtime$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (packet.overlay()) return;
        if (packet == islandtime$lastPacket) return;
        islandtime$lastPacket = packet;
        SackIncomeTracker.onSackChatMessage(packet.content());
        if (SackIncomeTracker.isSackChatMessage(packet.content())) {
            ChatMessageStore.addRawMessage(packet.content());
        }
    }
}
