package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.JitterManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, priority = 1000)
public class MixinGameRenderer {

    /**
     * Update the Halton jitter sequence every frame so JitterManager stays in sync.
     * The jitter values are passed directly to DLSS as explicit parameters.
     *
     * NOTE: We do NOT apply jitter to the projection matrix here.
     * In hybrid RTX+raster rendering the Iris G-buffer must be unjittered so
     * that G-buffer texels align with the ray-traced surface hits. Applying
     * jitter to the projection would shift the rasterised G-buffer while the
     * ray origins stay fixed, causing shadow/lighting artefacts and flicker.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderUpdateJitter(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getWindow() != null) {
            JitterManager.updateJitter(client.getWindow().getFramebufferWidth(),
                    client.getWindow().getFramebufferHeight());
        }
    }
}
