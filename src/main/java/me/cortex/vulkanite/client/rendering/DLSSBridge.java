package me.cortex.vulkanite.client.rendering;

import com.sun.jna.Library;
import com.sun.jna.Native;

import me.cortex.vulkanite.client.rendering.util.NativeLibraryLoader;

/**
 * JNA Bridge to the native DLSS/DLSSD library.
 * 
 * This interface provides Java bindings to the native C++ DLSS bridge library,
 * supporting both standard DLSS upscaling and DLSSD Ray Reconstruction.
 */
public interface DLSSBridge extends Library {

    // ========================================================================
    // NGX Result Codes
    // ========================================================================
    
    /** Success */
    int NGX_RESULT_SUCCESS = 1;

    // ========================================================================
    // NGX Extension Query Functions
    // ========================================================================

    int getNGXInstanceExtensionCount();

    String getNGXInstanceExtension(int index);

    int getNGXDeviceExtensionCount(long instance, long physicalDevice);

    String getNGXDeviceExtension(long instance, long physicalDevice, int index);

    /**
     * Initializes the NGX SDK (Internal function, usually called by initDLSS/initDLSSD).
     * 
     * @param vkInstance       Vulkan instance pointer
     * @param vkPhysicalDevice Vulkan physical device pointer
     * @param vkDevice         Vulkan logical device pointer
     * @param dlssPath         Path to the folder containing DLSS binaries (optional)
     * @return 1 on success, 0 on failure
     */
    int initializeNGX(long vkInstance, long vkPhysicalDevice, long vkDevice, String dlssPath);

    // ========================================================================
    // Standard DLSS Functions (Upscaling + Anti-Aliasing)
    // ========================================================================

        /**
         * Initializes the DLSS feature for a specific resolution.
         * 
         * @param vkInstance       Vulkan instance pointer
         * @param vkPhysicalDevice Vulkan physical device pointer
         * @param vkDevice         Vulkan logical device pointer
         * @param width            Render width
         * @param height           Render height
         * @param outWidth         Output width
         * @param outHeight        Output height
         * @return 1 on success, 0 on failure
         */
        int initDLSS(long vkInstance, long vkPhysicalDevice, long vkDevice, int width, int height, int outWidth,
                        int outHeight);

        /**
         * Cleans up the DLSS feature and releases resources.
         * 
         * @param vkDevice Vulkan logical device pointer
         */
        void destroyDLSS(long vkDevice);

        /**
         * Evaluates the DLSS feature for a single frame.
         * 
         * @param vkCommandBuffer        Vulkan command buffer pointer where the DLSS
         *                               commands will be recorded
         * @param colorImageView         Vulkan image view pointer for the
         *                               noisy/jittered color input
         * @param colorImage             Vulkan image pointer
         * @param depthImageView         Vulkan image view pointer for the depth buffer
         * @param depthImage             Vulkan image pointer
         * @param motionVectorsImageView Vulkan image view pointer for the motion
         *                               vectors
         * @param motionVectorsImage     Vulkan image pointer
         * @param outputImageView        Vulkan image view pointer for the
         *                               anti-aliased/upscaled output
         * @param outputImage            Vulkan image pointer
         * @param jitterX                Jitter offset X in sub-pixel space (-0.5 to
         *                               0.5)
         * @param jitterY                Jitter offset Y in sub-pixel space (-0.5 to
         *                               0.5)
         * @return 1 on success, 0 on failure
         */
        int evaluateDLSS(long vkCommandBuffer,
                        long colorImageView, long colorImage, int colorFormat,
                        long depthImageView, long depthImage, int depthFormat,
                        long motionVectorsImageView, long motionVectorsImage, int motionVectorsFormat,
                        long outputImageView, long outputImage, int outputFormat,
                        float jitterX, float jitterY);

        // ========================================================================
        // DLSSD (Ray Reconstruction) Functions
        // ========================================================================

        /**
         * Initializes the DLSSD (Ray Reconstruction) feature.
         * 
         * DLSSD uses AI to denoise ray-traced images, replacing traditional denoisers
         * with a neural network trained on ray-tracing noise patterns.
         * 
         * @param vkInstance       Vulkan instance pointer
         * @param vkPhysicalDevice Vulkan physical device pointer
         * @param vkDevice         Vulkan logical device pointer
         * @param width            Render width (internal resolution)
         * @param height           Render height (internal resolution)
         * @param outWidth         Output width (target resolution)
         * @param outHeight        Output height (target resolution)
         * @param denoiseMode      Denoise mode: 0=Off, 1=DLUnified (Ray Reconstruction)
         * @param roughnessMode    Roughness mode: 0=Unpacked (separate texture),
         *                         1=Packed (in normals.w)
         * @param depthType        Depth type: 0=Linear, 1=HW Depth
         * @return 1 on success, 0 on failure
         */
        int initDLSSD(
                        long vkInstance,
                        long vkPhysicalDevice,
                        long vkDevice,
                        int width, int height,
                        int outWidth, int outHeight,
                        int denoiseMode,
                        int roughnessMode,
                        int depthType,
                        int perfQualityValue);

        /**
         * Cleans up the DLSSD feature and releases resources.
         * 
         * @param vkDevice Vulkan logical device pointer
         */
        void destroyDLSSD(long vkDevice);

    /**
     * Evaluates DLSSD (Ray Reconstruction) for a single frame.
     *
     * This function takes the noisy ray-traced output along with G-buffer data
     * and produces a denoised, high-quality image using AI.
     *
     * @param vkCommandBuffer Vulkan command buffer pointer
     * @param colorImageView Noisy ray-traced color input image view
     * @param colorImage Noisy ray-traced color input image
     * @param depthImageView Depth buffer image view
     * @param depthImage Depth buffer image
     * @param mvImageView Motion vectors image view
     * @param mvImage Motion vectors image
     * @param diffuseAlbedoImageView Diffuse albedo (RGB surface color) image view
     * @param diffuseAlbedoImage Diffuse albedo image
     * @param specularAlbedoImageView Specular albedo (F0 reflectance) image view
     * @param specularAlbedoImage Specular albedo image
     * @param normalsImageView World-space normals image view (roughness in
     * .w if packed)
     * @param normalsImage Normals image
     * @param roughnessImageView Roughness image view (only if unpacked mode)
     * @param roughnessImage Roughness image
     * @param outputImageView Denoised output image view
     * @param outputImage Denoised output image
     * @param jitterX Jitter offset X in sub-pixel space (-0.5 to
     * 0.5)
     * @param jitterY Jitter offset Y in sub-pixel space (-0.5 to
     * 0.5)
     * @param reset Set to 1 when scene changes completely (new
     * level, teleport, etc.)
     * @param frameTimeDeltaMs Frame time in milliseconds for temporal
     * stability
     * @return 1 on success, 0 on failure
     */
    int evaluateDLSSD(
        long vkCommandBuffer,
        // Standard inputs
        long colorImageView, long colorImage, int colorFormat,
        long depthImageView, long depthImage, int depthFormat,
        long mvImageView, long mvImage, int mvFormat,
        // G-buffer inputs for Ray Reconstruction
        long diffuseAlbedoImageView, long diffuseAlbedoImage, int diffuseAlbedoFormat,
        long specularAlbedoImageView, long specularAlbedoImage, int specularAlbedoFormat,
        long normalsImageView, long normalsImage, int normalsFormat,
        long roughnessImageView, long roughnessImage, int roughnessFormat,
        // Output
        long outputImageView, long outputImage, int outputFormat,
        // Parameters
        float jitterX, float jitterY,
        int reset,
        float frameTimeDeltaMs);

        /**
         * Check if DLSSD (Ray Reconstruction) is available on this system.
         * 
         * @return 1 if available, 0 if not
         */
        int isDLSSDAvailable();

        /**
         * Get the required render resolution for a given output resolution and quality
         * preset.
         * 
         * @param outWidth        Output/target width
         * @param outHeight       Output/target height
         * @param qualityPreset   Quality preset: 0=Native, 1=Quality, 2=Balanced,
         *                        3=Performance, 4=UltraPerformance
         * @param outRenderWidth  Array to receive calculated render width
         * @param outRenderHeight Array to receive calculated render height
         */
        void getDLSSDRenderResolution(
                        int outWidth, int outHeight,
                        int qualityPreset,
                        int[] outRenderWidth, int[] outRenderHeight);

        // ========================================================================
        // Constants for DLSSD parameters
        // ========================================================================

        /** Denoise mode: Ray Reconstruction disabled */
        int DENOISE_MODE_OFF = 0;
        /** Denoise mode: DL Unified (Ray Reconstruction enabled) */
        int DENOISE_MODE_DLUNIFIED = 1;

        /** Roughness mode: Separate roughness texture */
        int ROUGHNESS_MODE_UNPACKED = 0;
        /** Roughness mode: Roughness packed in normals.w */
        int ROUGHNESS_MODE_PACKED = 1;

        /** Depth type: Linear depth (distance from camera) */
        int DEPTH_TYPE_LINEAR = 0;
        /** Depth type: Hardware depth (standard OpenGL non-linear: 0.0=near, 1.0=far) */
        int DEPTH_TYPE_HW = 1;

        /** Quality preset: Native resolution (no upscaling) */
        int QUALITY_NATIVE = 0;
        /** Quality preset: Quality (66.7% resolution) */
        int QUALITY_QUALITY = 1;
        /** Quality preset: Balanced (58.3% resolution) */
        int QUALITY_BALANCED = 2;
        /** Quality preset: Performance (50% resolution) */
        int QUALITY_PERFORMANCE = 3;
        /** Quality preset: Ultra Performance (33.3% resolution) */
        int QUALITY_ULTRA_PERFORMANCE = 4;
}
