package org.example.islandtime.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void islandtime$skipExtractIfPlayerNull(CallbackInfo ci) {
        if (this.minecraft.level != null && this.minecraft.player == null) {
            ci.cancel();
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void islandtime$skipRenderLevelIfPlayerNull(CallbackInfo ci) {
        if (this.minecraft.player == null) {
            ci.cancel();
        }
    }
}
