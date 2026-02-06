package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.other.FormatConverter;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Mixin for GlImage to create shared Vulkan storage images for custom images
 * defined in shaders.properties (like ReSTIR reservoirs).
 */
@Mixin(value = GlImage.class, remap = false)
public abstract class MixinGlImage implements IVGImage {
    @Unique
    private VRef<VGImage> sharedImage;

    @Shadow
    public abstract String getName();

    @Shadow
    public abstract int getId();

    @Shadow
    public abstract int getWidth();

    @Shadow
    public abstract int getHeight();

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_genTexture()I"))
    private static int redirectGen() {
        return -1;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onConstructed(String name, int width, int height, InternalTextureFormat internalFormat, CallbackInfo ci) {
        int vkFormat = FormatConverter.getVkFormatFromGl(internalFormat);

        // Create shared image with storage usage for compute/raytracing read/write
        sharedImage = Vulkanite.INSTANCE.getCtx().memory
                .createSharedImage(
                        2, // 2D image
                        width,
                        height,
                        1, // depth
                        1, // mip levels
                        vkFormat,
                        internalFormat.getGlFormat(),
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        sharedImage.get().setDebugUtilsObjectName("GlImage_" + name);

        Vulkanite.INSTANCE.getCtx().cmd.executeWait(cmdbuf -> {
            cmdbuf.encodeImageTransition(new VRef<>(sharedImage.get()), VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
        });
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void onDestroy(CallbackInfo ci) {
        if (sharedImage != null) {
            sharedImage.close();
            sharedImage = null;
        }
    }

    @Override
    public VRef<VGImage> getVGImage() {
        return sharedImage == null ? null : sharedImage.addRef();
    }

    @Override
    public void setVGImage(VRef<VGImage> image) {
        if (sharedImage != null) {
            sharedImage.close();
        }
        sharedImage = image;
    }
}
