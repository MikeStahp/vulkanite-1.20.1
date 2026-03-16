package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;

import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import static org.lwjgl.vulkan.VK10.*;

/**
 * DLSS Ray Reconstruction Integration for Vulkanite RT
 * 
 * This class provides integration with NVIDIA's DLSS 3.5 Ray Reconstruction
 * API.
 * DLSS RR uses AI-powered denoising and temporal reprojection to reconstruct
 * high-quality images from noisy ray-traced input.
 * 
 * Key Features:
 * - AI-powered denoising using Tensor Cores
 * - Temporal reprojection for stable images
 * - Support for multiple quality presets
 * - Graceful fallback for non-RTX GPUs
 * 
 * Architecture:
 * 1. Input Buffers:
 * - Noisy ray-traced output (from ReSTIR GI)
 * - Motion vectors (screen-space pixel movement)
 * - Depth buffer (for spatial reconstruction)
 * - Normal buffer (world-space surface normals)
 * - Diffuse Albedo (surface diffuse color)
 * - Specular Albedo (F0 reflectance)
 * - Roughness (surface roughness, can be packed in normals.w)
 * 
 * 2. DLSS RR Processing:
 * - Temporal reprojection using motion vectors
 * - AI denoising using trained neural network
 * - Spatial reconstruction for detail preservation
 * 
 * 3. Output:
 * - Denoised, reconstructed image
 * - Optional upscaling to target resolution
 * 
 * @see <a href="https://developer.nvidia.com/dlss-ray-reconstruction">NVIDIA
 *      DLSS Ray Reconstruction</a>
 */
public class DLSSRayReconstruction {
    private static final int RESET_WARMUP_FRAMES = 5;
    private final VContext context;

    // DLSS state
    private boolean initialized;
    private boolean isSupported;
    private boolean isDLSSDSupported;
    private DLSSQualityPreset qualityPreset;

    // Mode selection
    private boolean useRayReconstruction; // true = DLSSD, false = standard DLSS

    // Input buffers (standard DLSS)
    private VRef<VImage> noisyInputImage; // Noisy ray-traced input
    private VRef<VImageView> noisyInputView;
    private VRef<VImage> depthImage; // Depth buffer
    private VRef<VImageView> depthView;
    private VRef<VImage> motionVectorImage; // Motion vectors
    private VRef<VImageView> motionVectorView;

    // G-buffer inputs (DLSSD Ray Reconstruction)
    private VRef<VImage> diffuseAlbedoImage; // Diffuse albedo (RGB surface color)
    private VRef<VImageView> diffuseAlbedoView;
    private VRef<VImage> specularAlbedoImage; // Specular albedo (F0 reflectance)
    private VRef<VImageView> specularAlbedoView;
    private VRef<VImage> normalsImage; // World-space normals (roughness in .w if packed)
    private VRef<VImageView> normalsView;
    private VRef<VImage> roughnessImage; // Roughness (only if unpacked mode)
    private VRef<VImageView> roughnessView;

    // Output buffer
    private VRef<VImage> outputImage;
    private VRef<VImageView> outputView;

    // Viewport dimensions
    private int renderWidth;
    private int renderHeight;
    private int outputWidth;
    private int outputHeight;

    // Frame tracking
    private int frameIndex;
    private boolean reset;
    private int resetCountdown = RESET_WARMUP_FRAMES; // Warmup frames to clear artifacts
    private float lastFrameTimeDelta;
    private boolean internalInputsInitialized;
    private boolean outputLayoutInitialized;

    // Performance metrics
    private long lastProcessingTimeNs;

    // Roughness mode
    private boolean roughnessPacked = true; // true = roughness in normals.w

    /**
     * DLSS Quality Presets
     * Defines the rendering resolution relative to output resolution
     */
    public enum DLSSQualityPreset {
        NATIVE(1.0f, DLSSBridge.QUALITY_NATIVE),
        QUALITY(0.667f, DLSSBridge.QUALITY_QUALITY),
        BALANCED(0.583f, DLSSBridge.QUALITY_BALANCED),
        PERFORMANCE(0.5f, DLSSBridge.QUALITY_PERFORMANCE),
        ULTRA_PERFORMANCE(0.333f, DLSSBridge.QUALITY_ULTRA_PERFORMANCE);

        private final float scale;
        private final int nativeValue;

        DLSSQualityPreset(float scale, int nativeValue) {
            this.scale = scale;
            this.nativeValue = nativeValue;
        }

        public float getScale() {
            return scale;
        }

        public int getNativeValue() {
            return nativeValue;
        }
    }

    /**
     * DLSS RR Configuration
     */
    public static class DLSSConfig {
        public DLSSQualityPreset preset = DLSSQualityPreset.QUALITY;
        public boolean enableRayReconstruction = true;
        public boolean enableUpscaling = false;
        public boolean enableAutoExposure = false;
        public boolean roughnessPacked = true; // roughness in normals.w

        public DLSSConfig() {
        }
    }

    public DLSSRayReconstruction(VContext context) {
        this.context = context;
        this.initialized = false;
        this.isSupported = checkDLSSSupport();
        this.isDLSSDSupported = checkDLSSDSupport();
        this.useRayReconstruction = this.isDLSSDSupported;
        this.qualityPreset = DLSSQualityPreset.NATIVE;
        this.renderWidth = 1920;
        this.renderHeight = 1080;
        this.outputWidth = 1920;
        this.outputHeight = 1080;
        this.frameIndex = 0;
        this.reset = true;
        this.resetCountdown = RESET_WARMUP_FRAMES;
        this.lastFrameTimeDelta = 16.67f; // ~60fps default
        this.lastProcessingTimeNs = 0;
        this.internalInputsInitialized = false;
        this.outputLayoutInitialized = false;

        if (isDLSSDSupported) {
            System.out.println("[Vulkanite] DLSS Ray Reconstruction (DLSSD) Library Loaded");
        } else if (isSupported) {
            System.out.println("[Vulkanite] Standard DLSS Library Loaded (Ray Reconstruction NOT available)");
        } else {
            System.out.println("[Vulkanite] DLSS Library NOT Loaded - will use fallback");
        }
    }

    /**
     * Check if DLSS is supported on the current GPU
     * DLSS requires NVIDIA RTX GPU (Tensor Cores)
     */
    private boolean checkDLSSSupport() {
        return DLSSLoader.getInstance() != null;
    }

    /**
     * Check if DLSSD (Ray Reconstruction) is available
     */
    private boolean checkDLSSDSupport() {
        if (DLSSLoader.getInstance() == null) {
            return false;
        }
        // Will be properly checked after initialization
        return true;
    }

    /**
     * Initialize DLSS Ray Reconstruction
     * 
     * @param config       DLSS configuration
     * @param outputWidth  Target output width
     * @param outputHeight Target output height
     * @return true if initialization succeeded
     */
    public boolean initialize(DLSSConfig config, int outputWidth, int outputHeight) {
        if (!isSupported) {
            System.out.println("[Vulkanite] DLSS not supported, skipping initialization");
            return false;
        }

        this.qualityPreset = config.preset;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.roughnessPacked = config.roughnessPacked;
        this.useRayReconstruction = config.enableRayReconstruction && isDLSSDSupported;

        // Cleanup before re-initializing
        cleanup();

        // Initialize NGX first to query optimal settings
        boolean ngxInitialized = false;
        DLSSBridge bridge = DLSSLoader.getInstance();
        if (bridge != null) {
            String dlssPath = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toAbsolutePath()
                    .toString();
            int res = bridge.initializeNGX(
                    context.instance.address(),
                    context.physicalDevice.address(),
                    context.device.address(),
                    dlssPath);
            if (res == 1) {
                ngxInitialized = true;
            } else {
                System.err.println("[Vulkanite] Failed to initialize NGX SDK. Error code: " + Integer.toHexString(res));
                // Don't return false yet, we might fallback? No, without NGX we can't do
                // anything.
                // But wait, standard DLSS might work if DLSSD failed? No, InitializeNGX is for
                // everything.
                return false;
            }
        }

        // Calculate render resolution
        // FIX: Ensure dimensions are EVEN (multiple of 2). Odd dimensions (like 1009)
        // often cause InvalidParameter (bad00005) in NGX feature creation.
        // We also force 1:1 resolution (Native DLAA mode) for hybrid rendering compatibility.
        this.renderWidth = outputWidth & ~7;
        this.renderHeight = outputHeight & ~7;
        this.outputWidth = this.renderWidth;
        this.outputHeight = this.renderHeight;

        String mode = useRayReconstruction ? "DLSSD (Ray Reconstruction)" : "DLSS (Standard)";
        System.out.println("[Vulkanite] " + mode + " initialized: " +
                renderWidth + "x" + renderHeight + " -> " +
                outputWidth + "x" + outputHeight +
                " (preset: " + qualityPreset + ")");

        // Create input/output buffers
        createBuffers();

        // Initialize DLSS in C++ bridge
        if (ngxInitialized) {
            int result;

            if (useRayReconstruction) {
                // Initialize DLSSD (Ray Reconstruction)
                System.out.println("[Vulkanite] Attempting to initialize DLSSD (Ray Reconstruction)...");
                result = bridge.initDLSSD(
                        context.instance.address(),
                        context.physicalDevice.address(),
                        context.device.address(),
                        renderWidth, renderHeight,
                        renderWidth, renderHeight,
                        DLSSBridge.DENOISE_MODE_DLUNIFIED,
                        roughnessPacked ? DLSSBridge.ROUGHNESS_MODE_PACKED : DLSSBridge.ROUGHNESS_MODE_UNPACKED,
                        // FIX: Use DEPTH_TYPE_LINEAR since ray0.rgen writes linear depth (distance from
                        // camera).
                        // HW depth expects [0,1] or [1,0] ranges, which completely breaks with linear
                        // distance.
                        DLSSBridge.DEPTH_TYPE_LINEAR,
                        (this.renderWidth == this.outputWidth && this.renderHeight == this.outputHeight) ? DLSSBridge.QUALITY_NATIVE : qualityPreset.getNativeValue());

                if (result != 1) {
                    System.err.println("[Vulkanite] DLSSD Native Initialization failed with code: "
                            + Integer.toHexString(result) + ", falling back to standard DLSS");

                    // [FIX] Ensure we clean up any partial state before retrying standard DLSS
                    // This helps if the native side left NGX in a weird state
                    bridge.destroyDLSS(context.device.address());

                    useRayReconstruction = false;
                    isDLSSDSupported = false;
                    // Try standard DLSS as fallback
                    result = bridge.initDLSS(
                            context.instance.address(),
                            context.physicalDevice.address(),
                            context.device.address(),
                            renderWidth, renderHeight,
                            renderWidth, renderHeight);
                }
            } else {
                // Initialize standard DLSS
                result = bridge.initDLSS(
                        context.instance.address(),
                        context.physicalDevice.address(),
                        context.device.address(),
                        renderWidth, renderHeight,
                        renderWidth, renderHeight);
            }

            if (result != 1) {
                System.err.println(
                        "[Vulkanite] DLSS Native Initialization failed! Error code: " + Integer.toHexString(result));
                isSupported = false;
                return false;
            }
        } else {
            System.err.println("[Vulkanite] DLSS Bridge not available!");
            isSupported = false;
            return false;
        }

        this.initialized = true;
        this.reset = true;
        this.resetCountdown = RESET_WARMUP_FRAMES;
        this.internalInputsInitialized = false;
        this.outputLayoutInitialized = false;

        return true;
    }

    /**
     * Create DLSS input/output buffers
     */
    private void createBuffers() {
        // Create noisy input buffer (ray-traced output)
        noisyInputImage = context.memory.createImage2D(
                renderWidth, renderHeight, 1,
                VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        noisyInputView = VImageView.create(context, noisyInputImage);

        // Create depth buffer (Linear Depth)
        // Reference implementation uses R32_SFLOAT for maximum precision and compatibility with NGX
        // R16_SFLOAT is technically supported but some driver versions are picky with linear depth formats
        depthImage = context.memory.createImage2D(
                renderWidth, renderHeight, 1,
                VK_FORMAT_R32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        depthView = VImageView.create(context, depthImage);

        // Create motion vector buffer
        motionVectorImage = context.memory.createImage2D(
                renderWidth, renderHeight, 1,
                VK_FORMAT_R16G16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        motionVectorView = VImageView.create(context, motionVectorImage);

        // Create G-buffer inputs for DLSSD (Ray Reconstruction)
        if (useRayReconstruction) {
            // Diffuse Albedo (RGB surface color)
            // NGX DLSSD expects HDR (floating point) input, or UNORM if flagged correctly.
            // But to be safe and match Ray Tracing pipeline, we use R16G16B16A16_SFLOAT.
            diffuseAlbedoImage = context.memory.createImage2D(
                    renderWidth, renderHeight, 1,
                    VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            diffuseAlbedoView = VImageView.create(context, diffuseAlbedoImage);

            // Specular Albedo (F0 reflectance)
            specularAlbedoImage = context.memory.createImage2D(
                    renderWidth, renderHeight, 1,
                    VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            specularAlbedoView = VImageView.create(context, specularAlbedoImage);

            // Normals (world-space, roughness in .w if packed mode)
            // MUST be floating point for high precision
            normalsImage = context.memory.createImage2D(
                    renderWidth, renderHeight, 1,
                    VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            normalsView = VImageView.create(context, normalsImage);

            // Roughness (only if unpacked mode)
            if (!roughnessPacked) {
                roughnessImage = context.memory.createImage2D(
                        renderWidth, renderHeight, 1,
                        VK_FORMAT_R8_UNORM,
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                roughnessView = VImageView.create(context, roughnessImage);
            }
        }

        // Create output buffer (matching the R16G16B16A16_SFLOAT format expected by NGX
        // for SDR/HDR)
        outputImage = context.memory.createImage2D(
                outputWidth, outputHeight, 1,
                VK_FORMAT_R16G16B16A16_SFLOAT, // NGX strictly requires HDR float formats, we will blit to match output
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        outputView = VImageView.create(context, outputImage);
    }

    private void transitionImage(VCmdBuff cmd, VRef<VImage> image, int oldLayout, int newLayout) {
        if (image == null) {
            return;
        }
        cmd.encodeImageTransition(image, oldLayout, newLayout, VK_IMAGE_ASPECT_COLOR_BIT, 1);
    }

    @SafeVarargs
    private final void transitionImages(VCmdBuff cmd, int oldLayout, int newLayout, VRef<VImage>... images) {
        for (VRef<VImage> image : images) {
            transitionImage(cmd, image, oldLayout, newLayout);
        }
    }

    private void blitImage(VCmdBuff cmd, VRef<VImage> source, VRef<VImage> target) {
        if (source == null || target == null) {
            return;
        }
        cmd.blitImage(source, target, renderWidth, renderHeight, VK_FILTER_NEAREST);
    }

    private void transitionOutputForEvaluation(VCmdBuff cmd) {
        if (!outputLayoutInitialized) {
            cmd.encodeImageTransition(outputImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT,
                    1);
            outputLayoutInitialized = true;
        }
    }

    @SafeVarargs
    private final void transitionInternalInputsForWrite(VCmdBuff cmd, VRef<VImage>... images) {
        transitionImages(cmd, internalInputsInitialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, images);
        internalInputsInitialized = true;
    }

    private float toFrameTimeMs(float deltaTime) {
        return Math.max(1.0f, Math.min(100.0f, deltaTime * 1000.0f));
    }

    private void advanceResetState() {
        if (resetCountdown > 0) {
            resetCountdown--;
            reset = true;
        } else {
            reset = false;
        }
    }

    /**
     * Process a frame through DLSS (standard mode)
     * 
     * @param cmd           Vulkan command buffer for recording DLSS evaluation
     * @param noisyInput    Noisy ray-traced input image
     * @param motionVectors Motion vector image
     * @param depth         Depth image
     * @param deltaTime     Time delta between frames in seconds
     * @return Denoised output image
     */
    public VRef<VImage> processFrame(VCmdBuff cmd, VRef<VImage> noisyInput,
            VRef<VImage> motionVectors, VRef<VImage> depth,
            float deltaTime) {
        DLSSBridge bridge = DLSSLoader.getInstance();
        if (!initialized || !isSupported || bridge == null) {
            System.err.println("[Vulkanite DLSS] processFrame: DLSS not available, returning original input");
            return noisyInput;
        }

        long startTime = System.nanoTime();
        frameIndex++;
        lastFrameTimeDelta = toFrameTimeMs(deltaTime);

        // =================================================================================================
        // BLIT CONVERSION: Copy external UNORM/SFLOAT textures into exactly formatted DLSS buffers
        // =================================================================================================

        transitionImages(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, noisyInput, depth, motionVectors);
        transitionInternalInputsForWrite(cmd, noisyInputImage, depthImage, motionVectorImage);

        blitImage(cmd, noisyInput, noisyInputImage);
        blitImage(cmd, depth, depthImage);
        blitImage(cmd, motionVectors, motionVectorImage);

        transitionImages(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, noisyInputImage, depthImage,
                motionVectorImage);
        transitionImages(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, noisyInput, depth, motionVectors);

        cmd.encodeMemoryBarrier();
        transitionOutputForEvaluation(cmd);

        // Jitter: JitterManager returns Halton subpixel offset in [-0.5, 0.5] pixels.
        // DLSS expects pixel-space jitter (not NDC, not UV).
        float subpixelJitterX = JitterManager.getJitterX();
        float subpixelJitterY = JitterManager.getJitterY();

        int result = bridge.evaluateDLSS(
                cmd.bufferAddress(),
                noisyInputView.get().view, noisyInputImage.get().image(), noisyInputImage.get().format,
                depthView.get().view, depthImage.get().image(), depthImage.get().format,
                motionVectorView.get().view, motionVectorImage.get().image(), motionVectorImage.get().format,
                outputView.get().view, outputImage.get().image(), outputImage.get().format,
                subpixelJitterX,
                subpixelJitterY);

        cmd.encodeMemoryBarrier();

        if (result != 1) {
            System.err.println("[Vulkanite DLSS] evaluateDLSS failed! Error code: " + Integer.toHexString(result));
            return noisyInput;
        }

        lastProcessingTimeNs = System.nanoTime() - startTime;
        advanceResetState();

        return outputImage;
    }

    /**
     * Process a frame through DLSSD (Ray Reconstruction)
     * 
     * This method requires G-buffer inputs for AI-powered denoising.
     * 
     * @param cmd            Vulkan command buffer
     * @param noisyInput     Noisy ray-traced color input
     * @param motionVectors  Motion vectors image
     * @param depth          Depth image
     * @param diffuseAlbedo  Diffuse albedo (RGB surface color)
     * @param specularAlbedo Specular albedo (F0 reflectance)
     * @param normals        World-space normals (roughness in .w if packed)
     * @param roughness      Roughness image (only if unpacked mode, can be null)
     * @param deltaTime      Time delta between frames in seconds
     * @return Denoised output image
     */
    public VRef<VImage> processFrameDLSSD(
            VCmdBuff cmd,
            VRef<VImage> noisyInput,
            VRef<VImage> motionVectors,
            VRef<VImage> depth,
            VRef<VImage> diffuseAlbedo,
            VRef<VImage> specularAlbedo,
            VRef<VImage> normals,
            VRef<VImage> roughness,
            float deltaTime) {

        DLSSBridge bridge = DLSSLoader.getInstance();
        if (!initialized || !isSupported || bridge == null) {
            System.err.println("[Vulkanite DLSSD] processFrameDLSSD: DLSS not available, returning original input");
            return noisyInput;
        }

        // If DLSSD is not available, fall back to standard DLSS
        if (!useRayReconstruction) {
            System.out.println("[Vulkanite DLSSD] Falling back to standard DLSS");
            return processFrame(cmd, noisyInput, motionVectors, depth, deltaTime);
        }

        long startTime = System.nanoTime();
        frameIndex++;
        lastFrameTimeDelta = toFrameTimeMs(deltaTime);

        VRef<VImageView> extRoughnessView = (roughness != null) ? VImageView.create(context, roughness) : null;

        try {
            // =================================================================================================
            // BLIT CONVERSION: Copy external UNORM/SFLOAT textures into exactly formatted DLSS buffers
            // This is required because DLSSD (IsHDR=true) expects strictly formatted floating point inputs.
            // =================================================================================================
            
            transitionImages(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, noisyInput, depth, motionVectors,
                    diffuseAlbedo, specularAlbedo, normals);
            transitionInternalInputsForWrite(cmd, noisyInputImage, depthImage, motionVectorImage, diffuseAlbedoImage,
                    specularAlbedoImage, normalsImage);

            // 3. Perform Blit (Copy + Format Conversion)
            // Vulkan vkCmdBlitImage handles format conversion (UNORM -> SFLOAT) automatically
            blitImage(cmd, noisyInput, noisyInputImage);
            blitImage(cmd, depth, depthImage);
            blitImage(cmd, motionVectors, motionVectorImage);
            blitImage(cmd, diffuseAlbedo, diffuseAlbedoImage);
            blitImage(cmd, specularAlbedo, specularAlbedoImage);
            blitImage(cmd, normals, normalsImage);

            transitionImages(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, noisyInputImage, depthImage,
                    motionVectorImage, diffuseAlbedoImage, specularAlbedoImage, normalsImage);

            transitionImages(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, noisyInput, depth, motionVectors,
                    diffuseAlbedo, specularAlbedo, normals);
            
            // Ensure writes are visible
            cmd.encodeMemoryBarrier();

            // Output: first frame must transition from UNDEFINED, subsequent frames stay GENERAL.
            transitionOutputForEvaluation(cmd);

            // Jitter: pixel-space Halton offset, [-0.5, 0.5] pixels.
            float subpixelJitterX = JitterManager.getJitterX();
            float subpixelJitterY = JitterManager.getJitterY();

            // Throttle per-frame logging to avoid flooding stdout
            boolean shouldLogFrame = (frameIndex % 300 == 1) || (reset && resetCountdown == RESET_WARMUP_FRAMES);
            
            // Call DLSSD evaluation with explicit formatted SFLOAT internal image views
            int result = bridge.evaluateDLSSD(
                    cmd.bufferAddress(),
                    // Standard inputs
                    noisyInputView.get().view, noisyInputImage.get().image(), noisyInputImage.get().format,
                    depthView.get().view, depthImage.get().image(), depthImage.get().format,
                    motionVectorView.get().view, motionVectorImage.get().image(), motionVectorImage.get().format,
                    // G-buffer inputs
                    diffuseAlbedoView.get().view, diffuseAlbedoImage.get().image(), diffuseAlbedoImage.get().format,
                    specularAlbedoView.get().view, specularAlbedoImage.get().image(), specularAlbedoImage.get().format,
                    normalsView.get().view, normalsImage.get().image(), normalsImage.get().format,
                    // Roughness (0 if not used)
                    (roughnessPacked || roughness == null) ? 0 : extRoughnessView.get().view,
                    (roughnessPacked || roughness == null) ? 0 : roughness.get().image(),
                    (roughnessPacked || roughness == null) ? 0 : roughness.get().format,
                    // Output
                    outputView.get().view, outputImage.get().image(), outputImage.get().format,
                    // Parameters
                    subpixelJitterX, subpixelJitterY,
                    reset ? 1 : 0,
                    lastFrameTimeDelta);
                    
            if (shouldLogFrame) {
                System.out.println("[Vulkanite DLSSD] evaluateDLSSD returned: " + result + " (frame " + frameIndex + ", reset=" + reset + ")");
            }

            // After NGX evaluate, add a memory barrier to ensure NGX writes are visible.
            cmd.encodeMemoryBarrier();

            if (result != 1) {
                System.err.println("[Vulkanite DLSSD] evaluateDLSSD failed with code: " + Integer.toHexString(result)
                        + "! Check native DLL is rebuilt.");
                return noisyInput; // Returning noisy input prevents rendering black
            }

            lastProcessingTimeNs = System.nanoTime() - startTime;
            advanceResetState();

            return outputImage;
        } finally {
            if (extRoughnessView != null) {
                extRoughnessView.close();
            }
        }
    }

    /**
     * Reset DLSS temporal state
     * Call this when the camera teleports or scene changes dramatically
     */
    public void resetTemporalState() {
        this.reset = true;
        this.resetCountdown = RESET_WARMUP_FRAMES;
        this.frameIndex = 0;
        this.internalInputsInitialized = false;
        this.outputLayoutInitialized = false;
    }

    /**
     * Check if DLSS is supported
     */
    public boolean isDLSSSupported() {
        return isSupported;
    }

    /**
     * Check if DLSSD (Ray Reconstruction) is supported and enabled
     */
    public boolean isRayReconstructionEnabled() {
        return useRayReconstruction && isDLSSDSupported;
    }

    /**
     * Check if DLSSD (Ray Reconstruction) is available on this system
     */
    public boolean isDLSSDAvailable() {
        return isDLSSDSupported;
    }

    /**
     * Check if DLSS is initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get current quality preset
     */
    public DLSSQualityPreset getQualityPreset() {
        return qualityPreset;
    }

    /**
     * Set quality preset (will require reinitialization)
     */
    public void setQualityPreset(DLSSQualityPreset preset) {
        if (this.qualityPreset != preset) {
            this.qualityPreset = preset;
            this.reset = true;
            this.resetCountdown = RESET_WARMUP_FRAMES;
        }
    }

    /**
     * Get render width (internal resolution)
     */
    public int getRenderWidth() {
        return renderWidth;
    }

    /**
     * Get render height (internal resolution)
     */
    public int getRenderHeight() {
        return renderHeight;
    }

    /**
     * Get output width (target resolution)
     */
    public int getOutputWidth() {
        return outputWidth;
    }

    /**
     * Get output height (target resolution)
     */
    public int getOutputHeight() {
        return outputHeight;
    }

    /**
     * Get last frame processing time in nanoseconds
     */
    public long getLastProcessingTimeNs() {
        return lastProcessingTimeNs;
    }

    /**
     * Get noisy input image view
     */
    public VRef<VImageView> getNoisyInputView() {
        return noisyInputView;
    }

    /**
     * Get output image view
     */
    public VRef<VImageView> getOutputView() {
        return outputView;
    }

    /**
     * Get diffuse albedo image view (for DLSSD)
     */
    public VRef<VImageView> getDiffuseAlbedoView() {
        return diffuseAlbedoView;
    }

    /**
     * Get specular albedo image view (for DLSSD)
     */
    public VRef<VImageView> getSpecularAlbedoView() {
        return specularAlbedoView;
    }

    /**
     * Get normals image view (for DLSSD)
     */
    public VRef<VImageView> getNormalsView() {
        return normalsView;
    }

    /**
     * Get roughness image view (for DLSSD, only if unpacked mode)
     */
    public VRef<VImageView> getRoughnessView() {
        return roughnessView;
    }

    /**
     * Get the internal depth buffer image.
     * This is a properly formatted depth buffer for DLSSD (D32_SFLOAT format).
     * Use this instead of world position for DLSSD depth input.
     */
    public VRef<VImage> getDepthImage() {
        return depthImage;
    }

    /**
     * Check if roughness is packed in normals.w
     */
    public boolean isRoughnessPacked() {
        return roughnessPacked;
    }

    /**
     * Cleanup DLSS resources
     */
    public void cleanup() {
        if (!initialized)
            return;

        DLSSBridge bridge = DLSSLoader.getInstance();
        if (bridge != null) {
            if (useRayReconstruction) {
                bridge.destroyDLSSD(context.device.address());
            } else {
                bridge.destroyDLSS(context.device.address());
            }
        }

        // Close all image views and images
        if (noisyInputView != null)
            noisyInputView.close();
        if (noisyInputImage != null)
            noisyInputImage.close();
        if (depthView != null)
            depthView.close();
        if (depthImage != null)
            depthImage.close();
        if (motionVectorView != null)
            motionVectorView.close();
        if (motionVectorImage != null)
            motionVectorImage.close();

        // DLSSD G-buffer resources
        if (diffuseAlbedoView != null)
            diffuseAlbedoView.close();
        if (diffuseAlbedoImage != null)
            diffuseAlbedoImage.close();
        if (specularAlbedoView != null)
            specularAlbedoView.close();
        if (specularAlbedoImage != null)
            specularAlbedoImage.close();
        if (normalsView != null)
            normalsView.close();
        if (normalsImage != null)
            normalsImage.close();
        if (roughnessView != null)
            roughnessView.close();
        if (roughnessImage != null)
            roughnessImage.close();

        if (outputView != null)
            outputView.close();
        if (outputImage != null)
            outputImage.close();

        initialized = false;
        internalInputsInitialized = false;
        outputLayoutInitialized = false;
    }
}
