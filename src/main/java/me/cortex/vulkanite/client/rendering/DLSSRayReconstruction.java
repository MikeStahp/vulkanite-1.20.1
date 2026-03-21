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

    // Input buffers (standard DLSS) - No longer created internally, but kept for compatibility
    private VRef<VImage> noisyInputImage; // Noisy ray-traced input
    private VRef<VImageView> noisyInputView;
    private VRef<VImage> depthImage; // Depth buffer
    private VRef<VImageView> depthView;
    private VRef<VImage> motionVectorImage; // Motion vectors
    private VRef<VImageView> motionVectorView;

    // G-buffer inputs (DLSSD Ray Reconstruction) - No longer created internally, but kept for compatibility
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
        //
        // DIAGNOSTIC: Log the quality preset scale factor to verify if it should be applied
        // For Quality mode (0.667 scale), render resolution should be ~66.67% of output.
        // Example: 848x480 output -> ~565x320 render (Quality mode)
        // Currently: Forces 1:1 resolution (Native DLAA mode) for hybrid rendering compatibility.
        //
        // TODO: To enable lower-resolution G-buffer rendering:
        // 1. Apply qualityPreset.getScale() to calculate renderWidth/renderHeight
        // 2. Ensure Iris G-buffers are created at this lower resolution
        // 3. Pass correct render dimensions to DLSSD
        float scaleX = qualityPreset.getScale();
        float scale = scaleX; // Scale factor from quality preset (e.g., 0.667 for Quality)
        
        // DIAGNOSTIC: Log what resolution we WOULD be rendering at if scale was applied
        int scaledWidth = (int)(outputWidth * scale) & ~7;
        int scaledHeight = (int)(outputHeight * scale) & ~7;
        System.out.println("[DIAG-DLSS] Quality preset: " + qualityPreset + " (scale=" + scale + ")");
        System.out.println("[DIAG-DLSS] Output resolution: " + outputWidth + "x" + outputHeight);
        System.out.println("[DIAG-DLSS] Scaled render resolution WOULD BE: " + scaledWidth + "x" + scaledHeight);
        System.out.println("[DIAG-DLSS] ACTUAL render resolution (forced 1:1): " + (outputWidth & ~7) + "x" + (outputHeight & ~7));
        
        // Currently forcing 1:1 resolution (Native DLAA mode) for hybrid rendering compatibility
        this.renderWidth = outputWidth & ~7;
        this.renderHeight = outputHeight & ~7;
        this.outputWidth = this.renderWidth;
        this.outputHeight = this.renderHeight;
       
        String mode = useRayReconstruction ? "DLSSD (Ray Reconstruction)" : "DLSS (Standard)";
        System.out.println("[Vulkanite] " + mode + " initialized: " +
        	renderWidth + "x" + renderHeight + " -> " +
        	outputWidth + "x" + outputHeight +
        	" (preset: " + qualityPreset + ", scale applied: NO - using 1:1 native mode)");

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
     *
     * NGX Required Formats:
     * - Output: VK_FORMAT_R16G16B16A16_SFLOAT (STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST)
     * - Depth: VK_FORMAT_R32_SFLOAT (STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST)
     * - Noisy Input: VK_FORMAT_R16G16B16A16_SFLOAT (STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST)
     * - Motion Vectors: VK_FORMAT_R16G16B16A16_SFLOAT (STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST)
     * - Diffuse Albedo: VK_FORMAT_R16G16B16A16_SFLOAT (STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST)
     * - Specular Albedo: VK_FORMAT_R16G16B16A16_SFLOAT (STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST)
     * - Normals: VK_FORMAT_R16G16B16A16_SFLOAT (STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST)
     * - Roughness (optional): VK_FORMAT_R16G16B16A16_SFLOAT (STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST)
     */
    private void createBuffers() {
        // Common usage flags for all NGX buffers
        int ngxUsageFlags = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                           VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        
        // Create output buffer (matching the R16G16B16A16_SFLOAT format expected by NGX)
        outputImage = context.memory.createImage2D(
            outputWidth, outputHeight, 1,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            ngxUsageFlags,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        outputView = VImageView.create(context, outputImage);

        // Create internal depth buffer for linear depth (R32_SFLOAT format).
        // The ray tracing shader writes linear depth to this image (binding 14),
        // and DLSS reads it for temporal denoising / ray reconstruction.
        depthImage = context.memory.createImage2D(
            renderWidth, renderHeight, 1,
            VK_FORMAT_R32_SFLOAT,
            ngxUsageFlags,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        depthView = VImageView.create(context, depthImage);

        // Create noisy input buffer (R16G16B16A16_SFLOAT format)
        // This receives the noisy ray-traced output from the ray tracing pass
        noisyInputImage = context.memory.createImage2D(
            renderWidth, renderHeight, 1,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            ngxUsageFlags,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        noisyInputView = VImageView.create(context, noisyInputImage);

        // Create motion vectors buffer (R16G16B16A16_SFLOAT format)
        // Contains screen-space pixel motion for temporal reprojection
        motionVectorImage = context.memory.createImage2D(
            renderWidth, renderHeight, 1,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            ngxUsageFlags,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        motionVectorView = VImageView.create(context, motionVectorImage);

        // Create diffuse albedo buffer (R16G16B16A16_SFLOAT format)
        // RGB surface diffuse color for DLSSD Ray Reconstruction
        diffuseAlbedoImage = context.memory.createImage2D(
            renderWidth, renderHeight, 1,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            ngxUsageFlags,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        diffuseAlbedoView = VImageView.create(context, diffuseAlbedoImage);

        // Create specular albedo buffer (R16G16B16A16_SFLOAT format)
        // F0 reflectance for DLSSD Ray Reconstruction
        specularAlbedoImage = context.memory.createImage2D(
            renderWidth, renderHeight, 1,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            ngxUsageFlags,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        specularAlbedoView = VImageView.create(context, specularAlbedoImage);

        // Create normals buffer (R16G16B16A16_SFLOAT format)
        // World-space surface normals (roughness packed in .w if enabled)
        normalsImage = context.memory.createImage2D(
            renderWidth, renderHeight, 1,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            ngxUsageFlags,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        normalsView = VImageView.create(context, normalsImage);

        // Create roughness buffer (R16G16B16A16_SFLOAT format) - only if unpacked mode
        // This is optional - only used when roughness is NOT packed in normals.w
        if (!roughnessPacked) {
            roughnessImage = context.memory.createImage2D(
                renderWidth, renderHeight, 1,
                VK_FORMAT_R16G16B16A16_SFLOAT,
                ngxUsageFlags,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            roughnessView = VImageView.create(context, roughnessImage);
        }
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
        reset = resetCountdown > 0;
        if (resetCountdown > 0) {
            resetCountdown--;
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
        // ZERO-COPY PATH: Use external textures directly
        // =================================================================================================

        VRef<VImageView> extNoisyInputView = VImageView.create(context, noisyInput);
        VRef<VImageView> extDepthView = VImageView.create(context, depth);
        VRef<VImageView> extMotionVectorsView = VImageView.create(context, motionVectors);

        try {
            transitionImages(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, noisyInput, depth, motionVectors);

            cmd.encodeMemoryBarrier();
            transitionOutputForEvaluation(cmd);

 // NVIDIA DLSS REQUIREMENT: Pass jitter offsets to DLSS for internal compensation.
 // Motion vectors do NOT include jitter - they represent pure geometric motion.
 // DLSS uses InJitterOffsetX/Y to handle jitter compensation internally.
 // The jitter values are in pixel space [-0.5, 0.5] as required by DLSS.
 float subpixelJitterX = JitterManager.getJitterX();
 float subpixelJitterY = JitterManager.getJitterY();
           
            int result = bridge.evaluateDLSS(
                    cmd.bufferAddress(),
                    extNoisyInputView.get().view, noisyInput.get().image(), noisyInput.get().format,
                    extDepthView.get().view, depth.get().image(), depth.get().format,
                    extMotionVectorsView.get().view, motionVectors.get().image(), motionVectors.get().format,
                    outputView.get().view, outputImage.get().image(), outputImage.get().format,
                    subpixelJitterX,
                    subpixelJitterY);

            cmd.encodeMemoryBarrier();

            transitionImages(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, noisyInput, depth, motionVectors);

            if (result != 1) {
                System.err.println("[Vulkanite DLSS] evaluateDLSS failed! Error code: " + Integer.toHexString(result));
                return noisyInput;
            }

            lastProcessingTimeNs = System.nanoTime() - startTime;
            advanceResetState();

            return outputImage;
        } finally {
            if (extNoisyInputView != null) extNoisyInputView.close();
            if (extDepthView != null) extDepthView.close();
            if (extMotionVectorsView != null) extMotionVectorsView.close();
        }
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
            System.err.println("[Vulkanite DLSSD] processFrameDLSSD: not available (init=" + initialized + ", supported=" + isSupported + ", bridge=" + (bridge != null) + ")");
            return noisyInput;
        }

        // Validate required inputs
        if (diffuseAlbedo == null || specularAlbedo == null || normals == null) {
            System.err.println("[Vulkanite DLSSD] Missing G-buffer inputs: diffAlb=" + (diffuseAlbedo != null) +
                ", specAlb=" + (specularAlbedo != null) + ", normals=" + (normals != null) + " - falling back to standard DLSS");
            return processFrame(cmd, noisyInput, motionVectors, depth, deltaTime);
        }

        // If DLSSD is not available, fall back to standard DLSS
        if (!useRayReconstruction) {
            System.out.println("[Vulkanite DLSSD] useRayReconstruction=false, falling back to standard DLSS");
            return processFrame(cmd, noisyInput, motionVectors, depth, deltaTime);
        }
        
        System.out.println("[Vulkanite DLSSD] Proceeding with DLSSD evaluation (frame " + frameIndex + ")");

        long startTime = System.nanoTime();
        frameIndex++;
        lastFrameTimeDelta = toFrameTimeMs(deltaTime);

        VRef<VImageView> extNoisyInputView = VImageView.create(context, noisyInput);
        VRef<VImageView> extDepthView = VImageView.create(context, depth);
        VRef<VImageView> extMotionVectorsView = VImageView.create(context, motionVectors);
        VRef<VImageView> extDiffuseAlbedoView = VImageView.create(context, diffuseAlbedo);
        VRef<VImageView> extSpecularAlbedoView = VImageView.create(context, specularAlbedo);
        VRef<VImageView> extNormalsView = VImageView.create(context, normals);
        VRef<VImageView> extRoughnessView = (roughness != null) ? VImageView.create(context, roughness) : null;

        try {
            // =================================================================================================
            // ZERO-COPY PATH: Use external textures directly
            // =================================================================================================
            
            transitionImages(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, noisyInput, depth, motionVectors,
                    diffuseAlbedo, specularAlbedo, normals);
            if (roughness != null) {
                transitionImages(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, roughness);
            }
            
            // Ensure writes are visible
            cmd.encodeMemoryBarrier();

            // Output: first frame must transition from UNDEFINED, subsequent frames stay GENERAL.
            transitionOutputForEvaluation(cmd);

 // NVIDIA DLSSD REQUIREMENT: Pass jitter offsets to DLSSD for internal compensation.
 // Motion vectors do NOT include jitter - they represent pure geometric motion.
 // DLSSD uses InJitterOffsetX/Y to handle jitter compensation internally.
 // The jitter values are in pixel space [-0.5, 0.5] as required by DLSSD.
 float subpixelJitterX = JitterManager.getJitterX();
 float subpixelJitterY = JitterManager.getJitterY();

        // Throttle per-frame logging to avoid flooding stdout
        boolean shouldLogFrame = (frameIndex % 300 == 1) || (reset && resetCountdown == RESET_WARMUP_FRAMES);

        // Call DLSSD evaluation with explicit formatted SFLOAT internal image views
        int result = bridge.evaluateDLSSD(
            cmd.bufferAddress(),
            // Standard inputs
            extNoisyInputView.get().view, noisyInput.get().image(), noisyInput.get().format,
            extDepthView.get().view, depth.get().image(), depth.get().format,
            extMotionVectorsView.get().view, motionVectors.get().image(), motionVectors.get().format,
            // G-buffer inputs
            extDiffuseAlbedoView.get().view, diffuseAlbedo.get().image(), diffuseAlbedo.get().format,
            extSpecularAlbedoView.get().view, specularAlbedo.get().image(), specularAlbedo.get().format,
            extNormalsView.get().view, normals.get().image(), normals.get().format,
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

            transitionImages(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, noisyInput, depth, motionVectors,
                    diffuseAlbedo, specularAlbedo, normals);
            if (roughness != null) {
                transitionImages(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, roughness);
            }

            if (result != 1) {
                System.err.println("[Vulkanite DLSSD] evaluateDLSSD failed with code: " + Integer.toHexString(result)
                        + "! Check native DLL is rebuilt.");
                return noisyInput; // Returning noisy input prevents rendering black
            }

            lastProcessingTimeNs = System.nanoTime() - startTime;
            advanceResetState();

            return outputImage;
        } finally {
            if (extNoisyInputView != null) extNoisyInputView.close();
            if (extDepthView != null) extDepthView.close();
            if (extMotionVectorsView != null) extMotionVectorsView.close();
            if (extDiffuseAlbedoView != null) extDiffuseAlbedoView.close();
            if (extSpecularAlbedoView != null) extSpecularAlbedoView.close();
            if (extNormalsView != null) extNormalsView.close();
            if (extRoughnessView != null) extRoughnessView.close();
        }
    }

    /**
     * Reset DLSS temporal state.
     *
     * NVIDIA DLSS REQUIREMENT: Reset flag must be set to 1 on camera cuts/scene changes.
     * This ensures DLSS discards invalid history and prevents ghosting artifacts.
     *
     * Call this when:
     * - Camera teleports
     * - Scene changes dramatically
     * - Dimension change (e.g., Nether portal)
     * - Any situation where temporal history is invalid
     */
    public void resetTemporalState() {
    	this.reset = true;
    	this.resetCountdown = RESET_WARMUP_FRAMES;
    	this.frameIndex = 0;
    	this.internalInputsInitialized = false;
    	this.outputLayoutInitialized = false;
    	// Also reset jitter state to ensure clean temporal history
    	JitterManager.reset();
    	System.out.println("[DLSSRayReconstruction] Temporal state reset - history invalidated");
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
     * Get noisy input image (internal buffer)
     */
    public VRef<VImage> getNoisyInputImage() {
        return noisyInputImage;
    }

    /**
     * Get motion vector image (internal buffer)
     */
    public VRef<VImage> getMotionVectorImage() {
        return motionVectorImage;
    }

    /**
     * Get diffuse albedo image (internal buffer for DLSSD)
     */
    public VRef<VImage> getDiffuseAlbedoImage() {
        return diffuseAlbedoImage;
    }

    /**
     * Get specular albedo image (internal buffer for DLSSD)
     */
    public VRef<VImage> getSpecularAlbedoImage() {
        return specularAlbedoImage;
    }

    /**
     * Get normals image (internal buffer for DLSSD)
     */
    public VRef<VImage> getNormalsImage() {
        return normalsImage;
    }

    /**
     * Get roughness image (internal buffer for DLSSD, only if unpacked mode)
     */
    public VRef<VImage> getRoughnessImage() {
        return roughnessImage;
    }

    /**
     * Get output image
     */
    public VRef<VImage> getOutputImage() {
        return outputImage;
    }

    /**
     * Get motion vector image view
     */
    public VRef<VImageView> getMotionVectorView() {
        return motionVectorView;
    }

    /**
     * Get depth image view
     */
    public VRef<VImageView> getDepthView() {
        return depthView;
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

        // Close all image views and images (internal buffers now created in createBuffers())
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

        // DLSSD G-buffer resources (internal buffers)
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
