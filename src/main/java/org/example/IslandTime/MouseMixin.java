package org.example.islandtime.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.MouseInput;
import org.example.islandtime.BackpackHoverHandler;
import org.example.islandtime.FavouriteGuiViewer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseMixin {

    @Unique
    private static boolean islandtime$shouldBlockMouse() {
        return FavouriteGuiViewer.isActive() || BackpackHoverHandler.isFrozen();
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void islandtime$blockWhileFrozen(long window, MouseInput input, int action,
                                             CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS && islandtime$shouldBlockMouse()) {
            ci.cancel();
        }
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void islandtime$blockScrollWhileViewerActive(long window, double horizontal, double vertical,
                                                         CallbackInfo ci) {
        if (islandtime$shouldBlockMouse()) {
            ci.cancel();
        }
    }
}
