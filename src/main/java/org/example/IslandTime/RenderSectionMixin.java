package org.example.islandtime.mixin;

import org.example.islandtime.EdgeChunkRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public class RenderSectionMixin {

    // Both the mesh builder and the visibility graph hold a section back until this is true
    @Inject(method = "hasAllNeighbors", at = @At("HEAD"), cancellable = true)
    private void islandtime$drawEdgeSections(CallbackInfoReturnable<Boolean> cir) {
        if (EdgeChunkRendering.isEnabled()) cir.setReturnValue(true);
    }
}
