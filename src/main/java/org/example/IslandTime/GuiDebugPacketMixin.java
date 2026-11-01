package org.example.islandtime.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import org.example.islandtime.GuiDebugLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link GuiDebugLog} the container packets the server sends: just before each is applied
 * (what the server says) and, for the ones that change the client's state, just after (what the
 * client ended up with). Every hook uses {@code require = 0} so that a signature mismatch on some
 * game version only loses this log, instead of the game.
 */
@Mixin(ClientPacketListener.class)
public class GuiDebugPacketMixin {

    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), require = 0)
    private void islandtime$setSlotIn(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(false, packet);
    }

    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"), require = 0)
    private void islandtime$setSlotDone(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(true, packet);
    }

    @Inject(method = "handleContainerContent", at = @At("HEAD"), require = 0)
    private void islandtime$contentIn(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(false, packet);
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"), require = 0)
    private void islandtime$contentDone(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(true, packet);
    }

    @Inject(method = "handleSetCursorItem", at = @At("HEAD"), require = 0)
    private void islandtime$cursorIn(ClientboundSetCursorItemPacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(false, packet);
    }

    @Inject(method = "handleSetCursorItem", at = @At("RETURN"), require = 0)
    private void islandtime$cursorDone(ClientboundSetCursorItemPacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(true, packet);
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("HEAD"), require = 0)
    private void islandtime$playerInventoryIn(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(false, packet);
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("RETURN"), require = 0)
    private void islandtime$playerInventoryDone(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(true, packet);
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), require = 0)
    private void islandtime$closeIn(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(false, packet);
    }

    @Inject(method = "handleContainerSetData", at = @At("HEAD"), require = 0)
    private void islandtime$dataIn(ClientboundContainerSetDataPacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(false, packet);
    }

    @Inject(method = "handleOpenScreen", at = @At("HEAD"), require = 0)
    private void islandtime$openScreenIn(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onPacket(false, packet);
    }
}
