package org.example.islandtime.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "ensureHasSentCarriedItem", at = @At("HEAD"), cancellable = true)
    private void islandtime$skipIfPlayerNull(CallbackInfo ci) {
        if (this.minecraft.player == null) {
            ci.cancel();
        }
    }
}
