package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import net.minecraft.client.MinecraftClient;

import static org.lwjgl.vulkan.VK10.*;

import me.cortex.vulkanite.client.config.DLSSConfig;

/**
 * Integrates DLSSD (Ray Reconstruction) into the Vulkanite render pipeline.
 * 
 * This class manages the DLSSD processing step that occurs after ray tracing,
 * denoising the noisy ray-traced output using AI-powered reconstruction.
 * 
 * Usage flow:
 * 1. Initialize with VContext and output dimensions
 * 2. Call processFrame() after ray tracing to denoise the output
 * 3. The denoised output can then be composited with the final frame
 */
public class DLSSDProcessor {
    private final VContext context;
    private final DLSSRayReconstruction dlssd;
    private boolean initialized = false;
    private int lastWidth = 0;
    private int lastHeight = 0;

    // Configuration
    private DLSSRayReconstruction.DLSSConfig config;
    private boolean enabled = true;

    public DLSSDProcessor(VContext context) {
        this.context = context;
        this.dlssd = new DLSSRayReconstruction(context);
        this.config = new DLSSRayReconstruction.DLSSConfig();

        // Load settings from user configuration
        updateFromUserConfig();
    }

    /**
     * Update internal configuration from the user's DLSSConfig file.
     */
    public void updateFromUserConfig() {
        try {
            DLSSConfig userConfig = DLSSConfig.load();

            this.enabled = userConfig.isEnabled();

            // Map user config to internal config
            this.config.enableRayReconstruction = userConfig.isRayReconstructionEnabled();

            // Map quality preset
            this.config.preset = userConfig.getQualityPreset();

            // For now, we force roughnessPacked to true as it depends on the shader pack
            // Ideally this should be configurable or detected from the shader pack
            this.config.roughnessPacked = true;

            // Force re-initialization if settings changed
            this.initialized = false;

            System.out.println("[DLSSDProcessor] Updated configuration from file: Enabled=" + enabled +
                    ", RR=" + config.enableRayReconstruction +
                    ", Preset=" + config.preset);
        } catch (Exception e) {
            System.err.println("[DLSSDProcessor] Failed to load user config: " + e.getMessage());
            e.printStackTrace();
            // Fallback defaults
            this.config.preset = DLSSRayReconstruction.DLSSQualityPreset.NATIVE;
            this.config.enableRayReconstruction = true;
            this.config.roughnessPacked = true;
        }
    }

    /**
     * Initialize or reinitialize DLSSD for the given output dimensions.
     * This should be called when the framebuffer size changes.
     * 
     * @param width  Output width
     * @param height Output height
     * @return true if initialization succeeded
     */
    public boolean initialize(int width, int height) {
        // Respect user configuration - do not auto-enable if explicitly disabled
        if (!enabled) {
            System.out.println("[DLSSDProcessor] Disabled by configuration, skipping initialization");
            return false;
        }

        // Ensure dimensions are multiple of 8 to match DLSS requirements
        // This prevents off-by-one errors (e.g., 1009 height) which cause NGX InvalidParameter
        width = width & ~7;
        height = height & ~7;

        // Skip if already initialized with same dimensions
        if (initialized && lastWidth == width && lastHeight == height) {
            return true;
        }

        // Check if DLSSD is supported
        if (!dlssd.isDLSSSupported()) {
            System.out.println("[DLSSDProcessor] DLSS not supported, processor disabled");
            enabled = false;
            return false;
        }

        System.out.println("[DLSSDProcessor] Initializing for " + width + "x" + height);

        boolean success = dlssd.initialize(config, width, height);

        if (success) {
            initialized = true;
            lastWidth = width;
            lastHeight = height;
            System.out.println("[DLSSDProcessor] Initialized successfully. Ray Reconstruction: " +
                    dlssd.isRayReconstructionEnabled());
        } else {
            System.err.println("[DLSSDProcessor] Initialization failed!");
            initialized = false;
        }

        return success;
    }

    /**
     * Process a frame through DLSSD (Ray Reconstruction).
     * 
     * This method takes the noisy ray-traced output and G-buffer data,
     * then processes it through DLSSD for AI-powered denoising.
     * 
     * @param cmd           Command buffer to record DLSSD commands
     * @param noisyOutput   Noisy ray-traced color output
     * @param motionVectors Screen-space motion vectors
     * @param depth         Depth buffer
     * @param gbufferViews  G-buffer views from Iris:
     *                      [0] = colortex1 (Albedo)
     *                      [1] = colortex2 (Material)
     *                      [2] = colortex3 (Normals)
     *                      [3] = colortex4 (Position)
     *                      [4] = colortex5 (Extra/Specular)
     * @param deltaTime     Frame delta time in seconds
     * @return Denoised output image, or the original noisy input if DLSSD is not
     *         available
     */
    public VRef<VImage> processFrame(
            VCmdBuff cmd,
            VRef<VImage> noisyOutput,
            VRef<VImage> motionVectors,
            VRef<VImage> depth,
            VRef<VImageView>[] gbufferViews,
            float deltaTime) {

        if (!initialized || !enabled) {
            return noisyOutput;
        }

        // Extract G-buffer inputs for DLSSD
        GBufferDLSSDAdapter.DLSSDGBufferInputs gbufferInputs = GBufferDLSSDAdapter.extractDLSSDInputs(gbufferViews,
                config.roughnessPacked);

        // Validate inputs
        if (!GBufferDLSSDAdapter.validateInputs(gbufferInputs)) {
            System.err.println("[DLSSDProcessor] Invalid G-buffer inputs, falling back to standard DLSS");
            // Fall back to standard DLSS without G-buffer
            return dlssd.processFrame(cmd, noisyOutput, motionVectors, depth, deltaTime);
        }

        // Get image references from views for DLSSD processing
        // Note: We need to extract the underlying images from the views
        VRef<VImage> diffuseAlbedo = extractImageFromView(gbufferInputs.diffuseAlbedoView);
        VRef<VImage> specularAlbedo = extractImageFromView(gbufferInputs.specularAlbedoView);
        VRef<VImage> normals = extractImageFromView(gbufferInputs.normalsView);
        VRef<VImage> roughness = config.roughnessPacked ? null : extractImageFromView(gbufferInputs.roughnessView);

        // Process through DLSSD
        VRef<VImage> denoisedRef = dlssd.processFrameDLSSD(
                cmd,
                noisyOutput,
                motionVectors,
                depth,
                diffuseAlbedo,
                specularAlbedo,
                normals,
                roughness,
                deltaTime);

        // If DLSSD failed (e.g. native DLL error but isSupported returned true
        // previously),
        // we must fallback to the noisy output to avoid rendering black screens.
        if (denoisedRef == null || denoisedRef.get() == null) {
            System.err.println("[DLSSDProcessor] DLSSD evaluate failed, falling back to noisy output.");
            return noisyOutput;
        }

        return denoisedRef;
    }

    /**
     * Extract the underlying VImage from a VImageView VRef.
     * This is a helper method to get the image for DLSSD processing.
     */
    private VRef<VImage> extractImageFromView(VRef<VImageView> viewRef) {
        if (viewRef == null || viewRef.get() == null || viewRef.get().image == null) {
            return null;
        }
        // The VImageView has an 'image' field that references the underlying image
        return viewRef.get().image.addRef();
    }

    /**
     * Check if DLSSD is initialized and ready.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Check if DLSSD (Ray Reconstruction) is enabled and available.
     */
    public boolean isRayReconstructionEnabled() {
        return enabled && initialized && dlssd.isRayReconstructionEnabled();
    }

    /**
     * Check if DLSSD is supported on this system.
     */
    public boolean isSupported() {
        return dlssd.isDLSSSupported();
    }

    /**
     * Get the internal depth buffer image.
     * This is a properly formatted depth buffer for DLSSD (D32_SFLOAT format).
     * Use this instead of world position for DLSSD depth input.
     */
    public VRef<VImage> getDepthImage() {
        return dlssd.getDepthImage();
    }

    /**
     * Enable or disable DLSSD processing.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            reset();
        }
    }

    /**
     * Get current configuration.
     */
    public DLSSRayReconstruction.DLSSConfig getConfig() {
        return config;
    }

    /**
     * Set configuration. Will require reinitialization.
     */
    public void setConfig(DLSSRayReconstruction.DLSSConfig config) {
        this.config = config;
        this.initialized = false; // Force reinitialization
    }

    /**
     * Set quality preset.
     */
    public void setQualityPreset(DLSSRayReconstruction.DLSSQualityPreset preset) {
        if (this.config.preset != preset) {
            this.config.preset = preset;
            dlssd.setQualityPreset(preset);
        }
    }

    /**
     * Reset temporal state (call on scene changes, teleports, etc.)
     */
    public void resetTemporalState() {
        dlssd.resetTemporalState();
    }

    /**
     * Reset the processor (call when resizing or disabling)
     */
    public void reset() {
        if (initialized) {
            dlssd.cleanup();
            initialized = false;
            lastWidth = 0;
            lastHeight = 0;
        }
    }

    /**
     * Cleanup resources.
     */
    public void cleanup() {
        reset();
    }

    /**
     * Get the internal render resolution for the given output resolution.
     * Useful for configuring the ray tracer to render at the correct resolution.
     * 
     * @param outputWidth  Target output width
     * @param outputHeight Target output height
     * @return Array containing [renderWidth, renderHeight]
     */
    public int[] getRenderResolution(int outputWidth, int outputHeight) {
        float scale = config.preset.getScale();
        return new int[] {
                (int) (outputWidth * scale),
                (int) (outputHeight * scale)
        };
    }

    /**
     * Get the last processing time in nanoseconds.
     */
    public long getLastProcessingTimeNs() {
        return dlssd.getLastProcessingTimeNs();
    }

    /**
     * Auto-initialize based on current Minecraft window size.
     */
    public boolean autoInitialize() {
        MinecraftClient mc = MinecraftClient.getInstance();
        int width = mc.getWindow().getFramebufferWidth();
        int height = mc.getWindow().getFramebufferHeight();
        return initialize(width, height);
    }
}