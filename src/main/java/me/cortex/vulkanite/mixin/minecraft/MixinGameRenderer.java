package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.JitterManager;
import me.cortex.vulkanite.client.config.DLSSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GameRenderer.class, priority = 1000)
public class MixinGameRenderer {

    /**
     * Update the Halton jitter sequence every frame so JitterManager stays in sync.
     * The jitter values are passed directly to DLSS as explicit parameters.
     * Note: Jitter activation state is controlled by VulkanPipeline.updateTemporalPathState()
     * to avoid race conditions with DLSS processing state.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderUpdateJitter(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
    	MinecraftClient client = MinecraftClient.getInstance();
    	if (client.getWindow() != null) {
            DLSSConfig config = DLSSConfig.load();
            if (client.world == null || client.currentScreen != null || client.isPaused()
                    || !config.isEnabled() || config.isDebugEnabled()) {
                JitterManager.setDLSSActive(false);
            }
    		// Only update jitter values; activation state is managed by VulkanPipeline
    		JitterManager.updateJitter(client.getWindow().getFramebufferWidth(),
    			client.getWindow().getFramebufferHeight());
    	}
    }

    /**
     * Apply jitter to the projection matrix.
     * This ensures that both the rasterized G-buffer and the ray tracing (which uses this matrix)
     * are jittered consistently.
     */
    @Inject(method = "getBasicProjectionMatrix", at = @At("RETURN"))
    private void onGetBasicProjectionMatrix(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        Matrix4f projection = cir.getReturnValue();
        if (projection != null) {
            JitterManager.applyJitter(projection);
        }
    }
}
