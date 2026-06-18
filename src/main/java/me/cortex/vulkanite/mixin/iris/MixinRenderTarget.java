package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IRenderTargetVkGetter;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.FormatConverter;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.targets.RenderTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.vulkan.VK10.*;

@Mixin(value = RenderTarget.class, remap = false)
public abstract class MixinRenderTarget implements IRenderTargetVkGetter {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinRenderTarget.class);

    @Shadow
    @Final
    private InternalTextureFormat internalFormat;

    @Shadow
    protected abstract void setupTexture(int i, int i1, int i2, boolean b);

    @Unique
    private VRef<VGImage> vgMainTexture;

    @Unique
    private VRef<VGImage> vgAltTexture;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_genTextures([I)V"))
    private void redirectGen(int[] textures) {

    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/targets/RenderTarget;setupTexture(IIIZ)V", ordinal = 0))
    private void redirectMain(RenderTarget instance, int id, int width, int height, boolean allowsLinear) {
        setupTextures(width, height, allowsLinear);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/targets/RenderTarget;setupTexture(IIIZ)V", ordinal = 1))
    private void redirectAlt(RenderTarget instance, int id, int width, int height, boolean allowsLinear) {
    }

    @Redirect(method = "setupTexture", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/targets/RenderTarget;resizeTexture(III)V"))
    private void redirectResize(RenderTarget instance, int t, int w, int h) {
    }

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

        closeTextures();

        int renderTargetIndex = IRenderTargetVkGetter.getCurrentIndex();
        int targetWidth = Math.max(8, width & ~7);
        int targetHeight = Math.max(8, height & ~7);

        LOGGER.debug("Creating Iris render target {}x{} (input={}x{}, format={}, index={})",
                targetWidth, targetHeight, width, height, internalFormat.name(), renderTargetIndex);

        int usage = VK_IMAGE_USAGE_STORAGE_BIT
                | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        vgMainTexture = ctx.memory.createSharedImage(targetWidth, targetHeight, 1, vkfmt, glfmt, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vgAltTexture = ctx.memory.createSharedImage(targetWidth, targetHeight, 1, vkfmt, glfmt, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vgMainTexture.get().setDebugUtilsObjectName("RenderTarget Main (index=" + renderTargetIndex + ")");
        vgAltTexture.get().setDebugUtilsObjectName("RenderTarget Alt (index=" + renderTargetIndex + ")");
        try (VRef<VImage> main = new VRef<>(vgMainTexture.get());
                VRef<VImage> alt = new VRef<>(vgAltTexture.get())) {
            ctx.cmd.executeWait(cmdbuf -> {
                cmdbuf.encodeImageTransition(main, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                cmdbuf.encodeImageTransition(alt, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
            });
        }

        setupTexture(getMainTexture(), targetWidth, targetHeight, allowsLinear);
        setupTexture(getAltTexture(), targetWidth, targetHeight, allowsLinear);
    }

    @Redirect(method = "destroy", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_deleteTextures([I)V"))
    private void redirectResize(int[] textures) {
        closeTextures();
    }

    public VRef<VGImage> getMain() {
        return vgMainTexture.addRef();
    }

    public VRef<VGImage> getAlt() {
        return vgAltTexture.addRef();
    }

    @Unique
    private void closeTextures() {
        safeClose(vgMainTexture);
        safeClose(vgAltTexture);
        vgMainTexture = null;
        vgAltTexture = null;
    }

    @Unique
    private static void safeClose(VRef<?> ref) {
        if (ref == null) {
            return;
        }
        try {
            ref.close();
        } catch (NullPointerException ignored) {
            // The referenced image may already have been collected during reload.
        }
    }
}
