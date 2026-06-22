package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
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
 * sidecars; DLSS consumes those and produces processed HDR.</p>
 */
final class RtxFrameImages {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtxFrameImages.class);

    @SuppressWarnings("unchecked")
    private final VRef<VImage>[] reservoirs = new VRef[2];
    @SuppressWarnings("unchecked")
    private final VRef<VImageView>[] reservoirViews = new VRef[2];
    @SuppressWarnings("unchecked")
    private final VRef<VImage>[] specularHistory = new VRef[2];
    @SuppressWarnings("unchecked")
    private final VRef<VImageView>[] specularHistoryViews = new VRef[2];
    @SuppressWarnings("unchecked")
    private final VRef<VImage>[] specularSurfaceHistory = new VRef[2];
    @SuppressWarnings("unchecked")
    private final VRef<VImageView>[] specularSurfaceHistoryViews = new VRef[2];

    private VRef<VImage> radiance;
    private VRef<VImageView> radianceView;
    private VRef<VImage> diffuseAlbedoMetallic;
    private VRef<VImageView> diffuseAlbedoMetallicView;
    private VRef<VImage> specularAlbedo;
    private VRef<VImageView> specularAlbedoView;
    private VRef<VImage> normalRoughness;
    private VRef<VImageView> normalRoughnessView;
    private VRef<VImage> motionVector;
    private VRef<VImageView> motionVectorView;
    private VRef<VImage> linearDepth;
    private VRef<VImageView> linearDepthView;
    private VRef<VImage> specularHitDepth;
    private VRef<VImageView> specularHitDepthView;
    private VRef<VImage> firstHitDepth;
    private VRef<VImageView> firstHitDepthView;
    private VRef<VImage> blocklightDetail;
    private VRef<VImageView> blocklightDetailView;

    private VRef<VImage> processed;
    private VRef<VImageView> processedView;

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
            reservoirViews[i] = VImageView.create(ctx, reservoirs[i]);
            specularHistory[i] = createStorageImage(ctx, renderWidth, renderHeight,
                    VK_FORMAT_R16G16B16A16_SFLOAT, "Specular transport history " + i);
            specularHistoryViews[i] = VImageView.create(ctx, specularHistory[i]);
            specularSurfaceHistory[i] = createStorageImage(ctx, renderWidth, renderHeight,
                    VK_FORMAT_R16G16B16A16_SFLOAT, "Specular surface history " + i);
            specularSurfaceHistoryViews[i] = VImageView.create(ctx, specularSurfaceHistory[i]);
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
        createDlssViews(ctx);

        LOGGER.info("Allocated Radiance-style RTX frame images: render={}x{}, output={}x{}",
                renderWidth, renderHeight, outputWidth, outputHeight);
    }

    private void createDlssViews(VContext ctx) {
        radianceView = VImageView.create(ctx, radiance);
        diffuseAlbedoMetallicView = VImageView.create(ctx, diffuseAlbedoMetallic);
        specularAlbedoView = VImageView.create(ctx, specularAlbedo);
        normalRoughnessView = VImageView.create(ctx, normalRoughness);
        motionVectorView = VImageView.create(ctx, motionVector);
        linearDepthView = VImageView.create(ctx, linearDepth);
        specularHitDepthView = VImageView.create(ctx, specularHitDepth);
        firstHitDepthView = VImageView.create(ctx, firstHitDepth);
        blocklightDetailView = VImageView.create(ctx, blocklightDetail);
        processedView = VImageView.create(ctx, processed);
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

    private List<VRef<VImage>> allImages() {
        return List.of(
                reservoirs[0],
                reservoirs[1],
                specularHistory[0],
                specularHistory[1],
                specularSurfaceHistory[0],
                specularSurfaceHistory[1],
                radiance,
                diffuseAlbedoMetallic,
                specularAlbedo,
                normalRoughness,
                motionVector,
                linearDepth,
                specularHitDepth,
                firstHitDepth,
                blocklightDetail,
                processed);
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

    VRef<VImage> currentSpecularHistory(int frameIndex) {
        return specularHistory[frameIndex & 1];
    }

    VRef<VImage> previousSpecularHistory(int frameIndex) {
        return specularHistory[(frameIndex + 1) & 1];
    }

    VRef<VImage> currentSpecularSurfaceHistory(int frameIndex) {
        return specularSurfaceHistory[frameIndex & 1];
    }

    VRef<VImage> previousSpecularSurfaceHistory(int frameIndex) {
        return specularSurfaceHistory[(frameIndex + 1) & 1];
    }

    VRef<VImage> radiance() {
        return radiance;
    }

    VRef<VImageView> radianceView() {
        return radianceView;
    }

    VRef<VImage> diffuseAlbedoMetallic() {
        return diffuseAlbedoMetallic;
    }

    VRef<VImageView> diffuseAlbedoMetallicView() {
        return diffuseAlbedoMetallicView;
    }

    VRef<VImage> specularAlbedo() {
        return specularAlbedo;
    }

    VRef<VImageView> specularAlbedoView() {
        return specularAlbedoView;
    }

    VRef<VImage> normalRoughness() {
        return normalRoughness;
    }

    VRef<VImageView> normalRoughnessView() {
        return normalRoughnessView;
    }

    VRef<VImage> motionVector() {
        return motionVector;
    }

    VRef<VImageView> motionVectorView() {
        return motionVectorView;
    }

    VRef<VImage> linearDepth() {
        return linearDepth;
    }

    VRef<VImageView> linearDepthView() {
        return linearDepthView;
    }

    VRef<VImage> specularHitDepth() {
        return specularHitDepth;
    }

    VRef<VImageView> specularHitDepthView() {
        return specularHitDepthView;
    }

    VRef<VImage> firstHitDepth() {
        return firstHitDepth;
    }

    VRef<VImage> blocklightDetail() {
        return blocklightDetail;
    }

    StorageViews storageViews(int frameIndex) {
        int current = frameIndex & 1;
        int previous = (frameIndex + 1) & 1;
        return new StorageViews(
                reservoirViews[current],
                reservoirViews[previous],
                specularHistoryViews[previous],
                specularSurfaceHistoryViews[previous],
                specularHistoryViews[current],
                specularSurfaceHistoryViews[current],
                radianceView,
                motionVectorView,
                linearDepthView,
                diffuseAlbedoMetallicView,
                specularAlbedoView,
                normalRoughnessView,
                specularHitDepthView,
                firstHitDepthView,
                blocklightDetailView);
    }

    record StorageViews(
            VRef<VImageView> currentReservoir,
            VRef<VImageView> previousReservoir,
            VRef<VImageView> previousSpecularHistory,
            VRef<VImageView> previousSpecularSurfaceHistory,
            VRef<VImageView> currentSpecularHistory,
            VRef<VImageView> currentSpecularSurfaceHistory,
            VRef<VImageView> radiance,
            VRef<VImageView> motionVector,
            VRef<VImageView> linearDepth,
            VRef<VImageView> diffuseAlbedoMetallic,
            VRef<VImageView> specularAlbedo,
            VRef<VImageView> normalRoughness,
            VRef<VImageView> specularHitDepth,
            VRef<VImageView> firstHitDepth,
            VRef<VImageView> blocklightDetail) {
    }

    VRef<VImage> processed() {
        return processed;
    }

    VRef<VImageView> processedView() {
        return processedView;
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
        closeAllViews(reservoirViews);
        closeAllViews(specularHistoryViews);
        closeAllViews(specularSurfaceHistoryViews);
        radianceView = closeView(radianceView);
        diffuseAlbedoMetallicView = closeView(diffuseAlbedoMetallicView);
        specularAlbedoView = closeView(specularAlbedoView);
        normalRoughnessView = closeView(normalRoughnessView);
        motionVectorView = closeView(motionVectorView);
        linearDepthView = closeView(linearDepthView);
        specularHitDepthView = closeView(specularHitDepthView);
        firstHitDepthView = closeView(firstHitDepthView);
        blocklightDetailView = closeView(blocklightDetailView);
        processedView = closeView(processedView);

        closeAll(reservoirs);
        closeAll(specularHistory);
        closeAll(specularSurfaceHistory);
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

    private static void closeAllViews(VRef<VImageView>[] views) {
        for (int i = 0; i < views.length; i++) {
            views[i] = closeView(views[i]);
        }
    }

    private static VRef<VImageView> closeView(VRef<VImageView> view) {
        if (view != null) {
            view.close();
        }
        return null;
    }
}
