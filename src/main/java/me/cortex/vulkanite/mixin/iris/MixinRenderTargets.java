package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.compat.IRenderTargetVkGetter;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to track which render target index is being created.
 * This allows MixinRenderTarget to determine the appropriate resolution
 * based on whether the render target is an output buffer (colortex0) or
 * a G-buffer (colortex1-5).
 */
@Mixin(value = RenderTargets.class, remap = false)
public class MixinRenderTargets {
    /**
     * Sets the current render target index before the render target is created.
     * This is read by MixinRenderTarget to determine the appropriate resolution.
     */
    @Inject(method = "getOrCreate", at = @At("HEAD"))
    private void onGetOrCreateHead(int index, CallbackInfoReturnable<RenderTarget> cir) {
        IRenderTargetVkGetter.setCurrentIndex(index);
    }
    
    /**
     * Clears the current render target index after the render target is created.
     */
    @Inject(method = "getOrCreate", at = @At("RETURN"))
    private void onGetOrCreateReturn(int index, CallbackInfoReturnable<RenderTarget> cir) {
        IRenderTargetVkGetter.clearCurrentIndex();
    }
}
