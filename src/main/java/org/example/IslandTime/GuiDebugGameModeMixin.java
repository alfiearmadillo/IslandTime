package org.example.islandtime.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.example.islandtime.GuiDebugLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link GuiDebugLog} the container clicks the game sends to the server. {@code require = 0}
 * so that a signature mismatch on some game version only loses this log, instead of the game.
 */
@Mixin(MultiPlayerGameMode.class)
public class GuiDebugGameModeMixin {

    @Inject(method = "handleContainerInput", at = @At("HEAD"), require = 0)
    private void islandtime$logClickSent(int containerId, int slotId, int buttonNum, ContainerInput input,
                                         Player player, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onContainerInput(false, containerId, slotId, buttonNum, input);
    }

    @Inject(method = "handleContainerInput", at = @At("RETURN"), require = 0)
    private void islandtime$logClickPredicted(int containerId, int slotId, int buttonNum, ContainerInput input,
                                              Player player, CallbackInfo ci) {
        if (GuiDebugLog.isActive()) GuiDebugLog.onContainerInput(true, containerId, slotId, buttonNum, input);
    }
}
