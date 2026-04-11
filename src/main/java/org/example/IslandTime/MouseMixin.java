package org.example.islandtime.mixin;

import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.example.islandtime.BackpackHoverHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void islandtime$blockWhileFrozen(long window, MouseInput input, int action,
                                             CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS && BackpackHoverHandler.isFrozen()) {
            ci.cancel();
        }
    }
}
