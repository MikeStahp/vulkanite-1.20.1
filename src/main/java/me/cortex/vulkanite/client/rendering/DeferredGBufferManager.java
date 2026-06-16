package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.*;

import net.minecraft.client.MinecraftClient;

/**
 * Manages G-Buffer images for deferred rendering.
 *
 * <p>This class creates and manages the G-buffer images used by the deferred
 * lighting pipeline. The G-buffer consists of multiple render targets that
 * store geometry and material information for each pixel.</p>
 *
 * <h2>G-Buffer Layout</h2>
 * <ul>
 * <li><b>colortex0</b>: Albedo color (RGBA16F)</li>
 * <li><b>colortex1</b>: F0 reflectance + Roughness (RGBA16F)</li>
 * <li><b>colortex2</b>: World normal (RGBA16F)</li>
 * <li><b>colortex3</b>: World position + Metallic (RGBA32F)</li>
 * <li><b>colortex4</b>: Light data (Blocklight, Skylight, AO, Emission) (RGBA16F)</li>
 * <li><b>colortex5</b>: Shadow coordinates (RGBA16F)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * DeferredGBufferManager gbuffer = new DeferredGBufferManager(context);
 * gbuffer.initialize(width, height);
 *
 * // Get image views for rendering
 * VRef<VImageView> albedoView = gbuffer.getAlbedoView();
 *
 * // Resize when window changes
 * gbuffer.resize(newWidth, newHeight);
 * }</pre>
 */
public class DeferredGBufferManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeferredGBufferManager.class);

    // Image format constants
    public static final int FORMAT_RGBA16F = VK_FORMAT_R16G16B16A16_SFLOAT;
    public static final int FORMAT_RGBA32F = VK_FORMAT_R32G32B32A32_SFLOAT;
    public static final int FORMAT_DEPTH = VK_FORMAT_D32_SFLOAT;

    private final VContext ctx;

    // G-Buffer images
    private VRef<VImage> albedoImage;        // colortex0
    private VRef<VImageView> albedoView;
    private VRef<VImage> materialImage;      // colortex1 (F0 + Roughness)
    private VRef<VImageView> materialView;
    private VRef<VImage> normalImage;        // colortex2
    private VRef<VImageView> normalView;
    private VRef<VImage> positionImage;      // colortex3 (World pos + Metallic)
    private VRef<VImageView> positionView;
    private VRef<VImage> lightDataImage;     // colortex4
    private VRef<VImageView> lightDataView;
    private VRef<VImage> shadowCoordImage;   // colortex5
    private VRef<VImageView> shadowCoordView;
    private VRef<VImage> depthImage;
    private VRef<VImageView> depthView;

    // Current dimensions
    private int currentWidth = 0;
    private int currentHeight = 0;

    /**
     * Container for all G-buffer image views.
     */
    public record GBufferViews(
            VRef<VImageView> albedoView,
            VRef<VImageView> materialView,
            VRef<VImageView> normalView,
            VRef<VImageView> positionView,
            VRef<VImageView> lightDataView,
            VRef<VImageView> shadowCoordView,
            VRef<VImageView> depthView
    ) {}

    /**
     * Creates a new G-buffer manager.
     * @param context Vulkan context
     */
    public DeferredGBufferManager(VContext context) {
        this.ctx = context;
        LOGGER.info("DeferredGBufferManager created");
    }

    /**
     * Initializes G-buffer images at the specified resolution.
     * When DLSS is active, uses render resolution instead of output resolution.
     * @param width Width in pixels (output resolution)
     * @param height Height in pixels (output resolution)
     */
    public void initialize(int width, int height) {
        // When DLSS is enabled, use render resolution for G-buffers
        ResolutionScaleManager scaleManager = ResolutionScaleManager.getInstance();
        
        // Ensure ResolutionScaleManager is updated with current window size
        MinecraftClient mc = MinecraftClient.getInstance();
        int outputWidth = mc.getWindow().getFramebufferWidth();
        int outputHeight = mc.getWindow().getFramebufferHeight();
        scaleManager.update(outputWidth, outputHeight);
        
        int actualWidth, actualHeight;
        if (scaleManager.isDLSSEnabled()) {
            // Use render resolution when DLSS is active
            actualWidth = scaleManager.getRenderWidth();
            actualHeight = scaleManager.getRenderHeight();
            LOGGER.info("DLSS active: Creating G-buffer at render resolution {}x{} (output: {}x{})",
                actualWidth, actualHeight, width, height);
        } else {
            // Use full resolution when DLSS is disabled
            actualWidth = width;
            actualHeight = height;
        }

        if (currentWidth == actualWidth && currentHeight == actualHeight && albedoImage != null) {
            return; // Already initialized at this size
        }

        LOGGER.info("Initializing G-buffer at {}x{}", actualWidth, actualHeight);
        currentWidth = actualWidth;
        currentHeight = actualHeight;

        // Clean up existing images if any
        cleanup();

        // Create G-buffer images
        createGBufferImages(actualWidth, actualHeight);

        LOGGER.info("G-buffer initialized successfully");
    }

    private void createGBufferImages(int width, int height) {
        // Albedo (colortex0) - RGBA16F
        albedoImage = ctx.memory.createImage2D(
                width, height, 1,
                FORMAT_RGBA16F,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        albedoImage.get().setDebugUtilsObjectName("GBuffer_Albedo");
        albedoView = VImageView.create(ctx, albedoImage);

        // Material (colortex1) - F0 + Roughness - RGBA16F
        materialImage = ctx.memory.createImage2D(
                width, height, 1,
                FORMAT_RGBA16F,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        materialImage.get().setDebugUtilsObjectName("GBuffer_Material");
        materialView = VImageView.create(ctx, materialImage);

        // Normal (colortex2) - RGBA16F
        normalImage = ctx.memory.createImage2D(
                width, height, 1,
                FORMAT_RGBA16F,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        normalImage.get().setDebugUtilsObjectName("GBuffer_Normal");
        normalView = VImageView.create(ctx, normalImage);

        // Position (colortex3) - World pos + Metallic - RGBA32F
        positionImage = ctx.memory.createImage2D(
                width, height, 1,
                FORMAT_RGBA32F,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        positionImage.get().setDebugUtilsObjectName("GBuffer_Position");
        positionView = VImageView.create(ctx, positionImage);

        // Light data (colortex4) - RGBA16F
        lightDataImage = ctx.memory.createImage2D(
                width, height, 1,
                FORMAT_RGBA16F,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        lightDataImage.get().setDebugUtilsObjectName("GBuffer_LightData");
        lightDataView = VImageView.create(ctx, lightDataImage);

        // Shadow coordinates (colortex5) - RGBA16F
        shadowCoordImage = ctx.memory.createImage2D(
                width, height, 1,
                FORMAT_RGBA16F,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        shadowCoordImage.get().setDebugUtilsObjectName("GBuffer_ShadowCoord");
        shadowCoordView = VImageView.create(ctx, shadowCoordImage);

        // Depth buffer - D32F
        depthImage = ctx.memory.createImage2D(
                width, height, 1,
                FORMAT_DEPTH,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        depthImage.get().setDebugUtilsObjectName("GBuffer_Depth");
        depthView = VImageView.create(ctx, depthImage);
    }

    /**
     * Resizes the G-buffer to new dimensions.
     * @param width New width in pixels
     * @param height New height in pixels
     */
    public void resize(int width, int height) {
        if (width == currentWidth && height == currentHeight) {
            return;
        }

        LOGGER.info("Resizing G-buffer from {}x{} to {}x{}", currentWidth, currentHeight, width, height);
        initialize(width, height);
    }

    /**
     * Gets all G-buffer image views.
     * @return GBufferViews containing all image views
     */
    public GBufferViews getViews() {
        return new GBufferViews(
                albedoView,
                materialView,
                normalView,
                positionView,
                lightDataView,
                shadowCoordView,
                depthView
        );
    }

    /**
     * Gets G-buffer views as an array for use with descriptor sets.
     * Order: [albedo, material, normal, position, lightData]
     * @return Array of G-buffer image views
     */
    @SuppressWarnings("unchecked")
    public VRef<VImageView>[] getViewsAsArray() {
        return new VRef[] {
                albedoView,
                materialView,
                normalView,
                positionView,
                lightDataView
        };
    }

    // Individual getters

    public VRef<VImage> getAlbedoImage() { return albedoImage; }
    public VRef<VImageView> getAlbedoView() { return albedoView; }

    public VRef<VImage> getMaterialImage() { return materialImage; }
    public VRef<VImageView> getMaterialView() { return materialView; }

    public VRef<VImage> getNormalImage() { return normalImage; }
    public VRef<VImageView> getNormalView() { return normalView; }

    public VRef<VImage> getPositionImage() { return positionImage; }
    public VRef<VImageView> getPositionView() { return positionView; }

    public VRef<VImage> getLightDataImage() { return lightDataImage; }
    public VRef<VImageView> getLightDataView() { return lightDataView; }

    public VRef<VImage> getShadowCoordImage() { return shadowCoordImage; }
    public VRef<VImageView> getShadowCoordView() { return shadowCoordView; }

    public VRef<VImage> getDepthImage() { return depthImage; }
    public VRef<VImageView> getDepthView() { return depthView; }

    public int getWidth() { return currentWidth; }
    public int getHeight() { return currentHeight; }

    /**
     * Checks if the G-buffer is initialized.
     * @return true if initialized
     */
    public boolean isInitialized() {
        return albedoImage != null;
    }

    /**
     * Cleans up all G-buffer resources.
     */
    public void cleanup() {
        LOGGER.info("Cleaning up G-buffer");

        // Close views first
        if (albedoView != null) { albedoView.close(); albedoView = null; }
        if (materialView != null) { materialView.close(); materialView = null; }
        if (normalView != null) { normalView.close(); normalView = null; }
        if (positionView != null) { positionView.close(); positionView = null; }
        if (lightDataView != null) { lightDataView.close(); lightDataView = null; }
        if (shadowCoordView != null) { shadowCoordView.close(); shadowCoordView = null; }
        if (depthView != null) { depthView.close(); depthView = null; }

        // Close images
        if (albedoImage != null) { albedoImage.close(); albedoImage = null; }
        if (materialImage != null) { materialImage.close(); materialImage = null; }
        if (normalImage != null) { normalImage.close(); normalImage = null; }
        if (positionImage != null) { positionImage.close(); positionImage = null; }
        if (lightDataImage != null) { lightDataImage.close(); lightDataImage = null; }
        if (shadowCoordImage != null) { shadowCoordImage.close(); shadowCoordImage = null; }
        if (depthImage != null) { depthImage.close(); depthImage = null; }
    }
}
