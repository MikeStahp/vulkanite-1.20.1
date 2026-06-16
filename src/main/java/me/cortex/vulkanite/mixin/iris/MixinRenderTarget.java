package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.ResolutionScaleManager;
import me.cortex.vulkanite.compat.IRenderTargetVkGetter;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.other.FormatConverter;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.targets.RenderTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.vulkan.VK10.*;

@Mixin(value = RenderTarget.class, remap = false)
public abstract class MixinRenderTarget implements IRenderTargetVkGetter {
	private static final Logger LOGGER = LoggerFactory.getLogger(MixinRenderTarget.class);
	@Shadow @Final private PixelFormat format;
@Shadow @Final private InternalTextureFormat internalFormat;

@Shadow protected abstract void setupTexture(int i, int i1, int i2, boolean b);

@Unique private VRef<VGImage> vgMainTexture;
@Unique private VRef<VGImage> vgAltTexture;

@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_genTextures([I)V"))
    private void redirectGen(int[] textures) {

    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/targets/RenderTarget;setupTexture(IIIZ)V", ordinal = 0))
    private void redirectMain(RenderTarget instance, int id, int width, int height, boolean allowsLinear) {
        setupTextures(width, height, allowsLinear);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/targets/RenderTarget;setupTexture(IIIZ)V", ordinal = 1))
    private void redirectAlt(RenderTarget instance, int id, int width, int height, boolean allowsLinear) {}

    @Redirect(method = "setupTexture", at = @At(value = "INVOKE",target = "Lnet/irisshaders/iris/targets/RenderTarget;resizeTexture(III)V"))
    private void redirectResize(RenderTarget instance, int t, int w, int h) {}

    /**
     * Gets the main texture ID.
     * @author Cortex
     * @reason Intercept for Vulkanite
     * @return the main texture ID
     */
    @Overwrite
    public int getMainTexture() {
        return vgMainTexture.get().glId;
    }

    /**
     * Gets the alternate texture ID.
     * @author Cortex
     * @reason Intercept for Vulkanite
     * @return the alternate texture ID
     */
    @Overwrite
    public int getAltTexture() {
        return vgAltTexture.get().glId;
    }

    /**
     * Resizes the render target to the specified dimensions.
     * @author Cortex
     * @reason Intercept for Vulkanite
     * @param width the new width
     * @param height the new height
     */
    @Overwrite
    public void resize(int width, int height) {
        glFinish();
        setupTextures(width, height, !internalFormat.getPixelFormat().isInteger());
    }

    private void setupTextures(int width, int height, boolean allowsLinear) {
        var ctx = Vulkanite.INSTANCE.getCtx();

        int glfmt = internalFormat.getGlFormat();
        glfmt = (glfmt == GL_RGBA) ? GL_RGBA8 : glfmt;

        int vkfmt = FormatConverter.getVkFormatFromGl(internalFormat);

        // DLSS PROPER ARCHITECTURE:
        // - Output buffer (colortex0): FULL resolution - Iris composites this to screen
        // - G-buffers (colortex1-5): SCALED resolution - Ray tracing reads these
        // - Other buffers: FULL resolution - Not used by ray tracing
        //
        // This saves memory and bandwidth for G-buffers while keeping the output
        // at full resolution for proper Iris compositing.
        //
        // NOTE: ResolutionScaleManager.update() should be called from the main render loop
        // (VulkanPipeline.render()) to avoid race conditions. We only read the cached values here.
        ResolutionScaleManager scaleManager = ResolutionScaleManager.getInstance();

        int targetWidth;
        int targetHeight;
        int renderTargetIndex = IRenderTargetVkGetter.getCurrentIndex();
        boolean isGBuffer = (renderTargetIndex >= 1 && renderTargetIndex <= 5);
        boolean isOutputBuffer = (renderTargetIndex == 0);
        boolean allowScaledGBuffer = false;

        if (isGBuffer && allowScaledGBuffer && scaleManager.isScalingActive()) {
            // Vulkanite-owned deferred path may opt into scaled G-buffers for memory/bandwidth savings.
            targetWidth = scaleManager.getRenderWidth();
            targetHeight = scaleManager.getRenderHeight();
        } else {
            // Output buffer and other buffers at full resolution
            targetWidth = Math.max(8, width & ~7);
            targetHeight = Math.max(8, height & ~7);
        }

        // DIAGNOSTIC: Log render target creation dimensions
        LOGGER.debug("[RenderTarget] Creating: {}x{} (input={}x{}, format={}, idx={}, isGBuffer={}, isOutput={}, dlssScaling={}, allowScaledGBuffer={})",
                targetWidth, targetHeight, width, height, internalFormat.name(),
                renderTargetIndex, isGBuffer, isOutputBuffer, scaleManager.isScalingActive(), allowScaledGBuffer);

        int usage = VK_IMAGE_USAGE_STORAGE_BIT
                | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        vgMainTexture = ctx.memory.createSharedImage(targetWidth, targetHeight, 1, vkfmt, glfmt, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vgAltTexture = ctx.memory.createSharedImage(targetWidth, targetHeight, 1, vkfmt, glfmt, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vgMainTexture.get().setDebugUtilsObjectName("RenderTarget Main (index=" + renderTargetIndex + ")");
        vgAltTexture.get().setDebugUtilsObjectName("RenderTarget Alt (index=" + renderTargetIndex + ")");
        Vulkanite.INSTANCE.getCtx().cmd.executeWait(cmdbuf -> {
            cmdbuf.encodeImageTransition(new VRef<>(vgMainTexture.get()), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            cmdbuf.encodeImageTransition(new VRef<>(vgAltTexture.get()), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
        });

        setupTexture(getMainTexture(), targetWidth, targetHeight, allowsLinear);
        setupTexture(getAltTexture(), targetWidth, targetHeight, allowsLinear);
    }

    @Redirect(method = "destroy", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_deleteTextures([I)V"))
    private void redirectResize(int[] textures) {
//        glFinish();
        //TODO: block the gpu fully before deleting and resizing the textures
        vgMainTexture = null;
        vgAltTexture = null;
    }

    public VRef<VGImage> getMain() {
        return vgMainTexture.addRef();
    }

    public VRef<VGImage> getAlt() {
        return vgAltTexture.addRef();
    }
}
