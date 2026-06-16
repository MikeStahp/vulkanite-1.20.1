package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VImage;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan-owned images used by the hybrid RTX path.
 *
 * <p>The DLSS-facing resources mirror Radiance's module contract: ray tracing
 * produces radiance, material guides, motion, linear depth, and hit-depth
 * sidecars; DLSS consumes those and produces processed HDR plus upscaled
 * sidecars.</p>
 */
final class RtxFrameImages {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtxFrameImages.class);

    @SuppressWarnings("unchecked")
    private final VRef<VImage>[] reservoirs = new VRef[2];

    private VRef<VImage> radiance;
    private VRef<VImage> diffuseAlbedoMetallic;
    private VRef<VImage> specularAlbedo;
    private VRef<VImage> normalRoughness;
    private VRef<VImage> motionVector;
    private VRef<VImage> linearDepth;
    private VRef<VImage> specularHitDepth;
    private VRef<VImage> firstHitDepth;
    private VRef<VImage> blocklightDetail;

    private VRef<VImage> processed;
    private VRef<VImage> upscaledDiffuseAlbedoMetallic;
    private VRef<VImage> upscaledFirstHitDepth;
    private VRef<VImage> upscaledMotionVector;
    private VRef<VImage> upscaledNormalRoughness;
    private VRef<VImage> upscaledBlocklightDetail;

    private int renderWidth;
    private int renderHeight;
    private int outputWidth;
    private int outputHeight;
    private boolean layoutsInitialized;

    void ensureAllocated(VContext ctx, int renderWidth, int renderHeight, int outputWidth, int outputHeight) {
        if (renderWidth <= 0 || renderHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("Invalid RTX frame image size render="
                    + renderWidth + "x" + renderHeight + ", output=" + outputWidth + "x" + outputHeight);
        }

        if (radiance != null
                && this.renderWidth == renderWidth
                && this.renderHeight == renderHeight
                && this.outputWidth == outputWidth
                && this.outputHeight == outputHeight) {
            return;
        }

        if (radiance != null) {
            ctx.cmd.waitQueueIdle(0);
            destroy();
        }

        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.layoutsInitialized = false;

        for (int i = 0; i < reservoirs.length; i++) {
            reservoirs[i] = createStorageImage(ctx, renderWidth, renderHeight,
                    VK_FORMAT_R32G32B32A32_SFLOAT, "RTX Reservoir " + i);
        }

        radiance = createStorageImage(ctx, renderWidth, renderHeight,
                VK_FORMAT_R16G16B16A16_SFLOAT, "Radiance DLSS Input Radiance");
        diffuseAlbedoMetallic = createStorageImage(ctx, renderWidth, renderHeight,
                VK_FORMAT_R8G8B8A8_UNORM, "Radiance DLSS Input DiffuseAlbedoMetallic");
        specularAlbedo = createStorageImage(ctx, renderWidth, renderHeight,
                VK_FORMAT_R8G8B8A8_UNORM, "Radiance DLSS Input SpecularAlbedo");
        normalRoughness = createStorageImage(ctx, renderWidth, renderHeight,
                VK_FORMAT_R16G16B16A16_SFLOAT, "Radiance DLSS Input NormalRoughness");
        motionVector = createStorageImage(ctx, renderWidth, renderHeight,
                VK_FORMAT_R16G16_SFLOAT, "Radiance DLSS Input MotionVector");
        linearDepth = createStorageImage(ctx, renderWidth, renderHeight,
                VK_FORMAT_R16_SFLOAT, "Radiance DLSS Input LinearDepth");
        specularHitDepth = createStorageImage(ctx, renderWidth, renderHeight,
                VK_FORMAT_R16_SFLOAT, "Radiance DLSS Input SpecularHitDepth");
        firstHitDepth = createStorageImage(ctx, renderWidth, renderHeight,
                VK_FORMAT_R16_SFLOAT, "Radiance DLSS Input FirstHitDepth");
        blocklightDetail = createStorageImage(ctx, renderWidth, renderHeight,
                VK_FORMAT_R16G16B16A16_SFLOAT, "Radiance Blocklight Detail");

        processed = createStorageImage(ctx, outputWidth, outputHeight,
                VK_FORMAT_R16G16B16A16_SFLOAT, "Radiance DLSS Output Processed");
        upscaledDiffuseAlbedoMetallic = createStorageImage(ctx, outputWidth, outputHeight,
                VK_FORMAT_R8G8B8A8_UNORM, "Radiance Upscaled DiffuseAlbedoMetallic");
        upscaledFirstHitDepth = createStorageImage(ctx, outputWidth, outputHeight,
                VK_FORMAT_R16_SFLOAT, "Radiance DLSS Output UpscaledFirstHitDepth");
        upscaledMotionVector = createStorageImage(ctx, outputWidth, outputHeight,
                VK_FORMAT_R16G16_SFLOAT, "Radiance DLSS Output UpscaledMotionVector");
        upscaledNormalRoughness = createStorageImage(ctx, outputWidth, outputHeight,
                VK_FORMAT_R16G16B16A16_SFLOAT, "Radiance DLSS Output UpscaledNormalRoughness");
        upscaledBlocklightDetail = createStorageImage(ctx, outputWidth, outputHeight,
                VK_FORMAT_R16G16B16A16_SFLOAT, "Radiance Upscaled Blocklight Detail");

        LOGGER.info("Allocated Radiance-style RTX frame images: render={}x{}, output={}x{}",
                renderWidth, renderHeight, outputWidth, outputHeight);
    }

    private static VRef<VImage> createStorageImage(VContext ctx, int width, int height, int format, String debugName) {
        VRef<VImage> image = ctx.memory.createImage2D(width, height, 1,
                format,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        image.get().setDebugUtilsObjectName(debugName);
        return image;
    }

    void initializeLayouts(VCmdBuff cmd) {
        if (layoutsInitialized) {
            return;
        }

        for (VRef<VImage> image : allImages()) {
            cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
            clearColorImage(cmd, image);
        }

        layoutsInitialized = true;
    }

    void upscaleSidecars(VCmdBuff cmd) {
        blit(cmd, diffuseAlbedoMetallic, upscaledDiffuseAlbedoMetallic, VK_FILTER_LINEAR);
        blit(cmd, firstHitDepth, upscaledFirstHitDepth, VK_FILTER_LINEAR);
        blit(cmd, motionVector, upscaledMotionVector, VK_FILTER_LINEAR);
        blit(cmd, normalRoughness, upscaledNormalRoughness, VK_FILTER_NEAREST);
        blit(cmd, blocklightDetail, upscaledBlocklightDetail, VK_FILTER_LINEAR);
    }

    private static void blit(VCmdBuff cmd, VRef<VImage> source, VRef<VImage> target, int filter) {
        if (source == null || target == null) {
            return;
        }
        cmd.encodeImageTransition(source, VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
        cmd.encodeImageTransition(target, VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
        cmd.blitImage(source, target,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                filter);
        cmd.encodeImageTransition(target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
        cmd.encodeImageTransition(source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
    }

    private List<VRef<VImage>> allImages() {
        return List.of(
                reservoirs[0],
                reservoirs[1],
                radiance,
                diffuseAlbedoMetallic,
                specularAlbedo,
                normalRoughness,
                motionVector,
                linearDepth,
                specularHitDepth,
                firstHitDepth,
                blocklightDetail,
                processed,
                upscaledDiffuseAlbedoMetallic,
                upscaledFirstHitDepth,
                upscaledMotionVector,
                upscaledNormalRoughness,
                upscaledBlocklightDetail);
    }

    private static void clearColorImage(VCmdBuff cmd, VRef<VImage> image) {
        try (var stack = stackPush()) {
            VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
            clearColor.float32(0, 0.0f);
            clearColor.float32(1, 0.0f);
            clearColor.float32(2, 0.0f);
            clearColor.float32(3, 0.0f);

            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
            range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            range.baseMipLevel(0);
            range.levelCount(1);
            range.baseArrayLayer(0);
            range.layerCount(1);

            vkCmdClearColorImage(cmd.buffer(), image.get().image(), VK_IMAGE_LAYOUT_GENERAL, clearColor, range);
        }
    }

    VRef<VImage> currentReservoir(int frameIndex) {
        return reservoirs[frameIndex & 1];
    }

    VRef<VImage> previousReservoir(int frameIndex) {
        return reservoirs[(frameIndex + 1) & 1];
    }

    VRef<VImage>[] reservoirs() {
        return reservoirs;
    }

    VRef<VImage> radiance() {
        return radiance;
    }

    VRef<VImage> diffuseAlbedoMetallic() {
        return diffuseAlbedoMetallic;
    }

    VRef<VImage> specularAlbedo() {
        return specularAlbedo;
    }

    VRef<VImage> normalRoughness() {
        return normalRoughness;
    }

    VRef<VImage> motionVector() {
        return motionVector;
    }

    VRef<VImage> motionVectors() {
        return motionVector;
    }

    VRef<VImage> linearDepth() {
        return linearDepth;
    }

    VRef<VImage> specularHitDepth() {
        return specularHitDepth;
    }

    VRef<VImage> firstHitDepth() {
        return firstHitDepth;
    }

    VRef<VImage> blocklightDetail() {
        return blocklightDetail;
    }

    VRef<VImage> processed() {
        return processed;
    }

    VRef<VImage> upscaledDiffuseAlbedoMetallic() {
        return upscaledDiffuseAlbedoMetallic;
    }

    VRef<VImage> upscaledFirstHitDepth() {
        return upscaledFirstHitDepth;
    }

    VRef<VImage> upscaledMotionVector() {
        return upscaledMotionVector;
    }

    VRef<VImage> upscaledNormalRoughness() {
        return upscaledNormalRoughness;
    }

    VRef<VImage> upscaledBlocklightDetail() {
        return upscaledBlocklightDetail;
    }

    VRef<VImage> noisyColor() {
        return radiance;
    }

    int renderWidth() {
        return renderWidth;
    }

    int renderHeight() {
        return renderHeight;
    }

    int outputWidth() {
        return outputWidth;
    }

    int outputHeight() {
        return outputHeight;
    }

    void destroy() {
        closeAll(reservoirs);
        radiance = close(radiance);
        diffuseAlbedoMetallic = close(diffuseAlbedoMetallic);
        specularAlbedo = close(specularAlbedo);
        normalRoughness = close(normalRoughness);
        motionVector = close(motionVector);
        linearDepth = close(linearDepth);
        specularHitDepth = close(specularHitDepth);
        firstHitDepth = close(firstHitDepth);
        blocklightDetail = close(blocklightDetail);
        processed = close(processed);
        upscaledDiffuseAlbedoMetallic = close(upscaledDiffuseAlbedoMetallic);
        upscaledFirstHitDepth = close(upscaledFirstHitDepth);
        upscaledMotionVector = close(upscaledMotionVector);
        upscaledNormalRoughness = close(upscaledNormalRoughness);
        upscaledBlocklightDetail = close(upscaledBlocklightDetail);
        renderWidth = 0;
        renderHeight = 0;
        outputWidth = 0;
        outputHeight = 0;
        layoutsInitialized = false;
    }

    private static void closeAll(VRef<VImage>[] images) {
        for (int i = 0; i < images.length; i++) {
            images[i] = close(images[i]);
        }
    }

    private static VRef<VImage> close(VRef<VImage> image) {
        if (image != null) {
            image.close();
        }
        return null;
    }
}
