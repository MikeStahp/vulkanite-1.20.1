package me.cortex.vulkanite.client.rendering;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Native bridge for DLSS/DLSSD functionality.
 *
 * <p>This class provides JNA bindings to the native DLSS bridge library
 * (dlss_bridge/vulkanite_dlss_bridge.dll) which wraps the NVIDIA NGX SDK.</p>
 *
 * <h2>Native Bridge Location</h2>
 * <ul>
 * <li>C++ Implementation: dlss_bridge/dlss_wrapper.cpp</li>
 * <li>Headers: dlss_bridge/Include/nvsdk_ngx_helpers_dlssd_vk.h</li>
 * </ul>
 *
 * <h2>Key NGX Functions Used</h2>
 * <ul>
 * <li>NGX_VULKAN_CREATE_DLSSD_EXT1 - Create DLSSD feature</li>
 * <li>NGX_VULKAN_EVALUATE_DLSSD_EXT - Evaluate DLSSD frame</li>
 * <li>NVSDK_NGX_VULKAN_ReleaseFeature - Release feature</li>
 * </ul>
 *
 * <h2>DLSSD Evaluation Parameters (NVSDK_NGX_VK_DLSSD_Eval_Params)</h2>
 * <ul>
 * <li>pInColor - Noisy ray-traced color input</li>
 * <li>pInDepth - Linear depth buffer</li>
 * <li>pInMotionVectors - Screen-space motion vectors</li>
 * <li>pInDiffuseAlbedo - Diffuse albedo (G-buffer)</li>
 * <li>pInSpecularAlbedo - Specular albedo/F0 (G-buffer)</li>
 * <li>pInNormals - World-space normals encoded from [-1, 1] to [0, 1], with roughness in .w</li>
 * <li>pInRoughness - Separate roughness buffer (if unpacked)</li>
 * <li>InJitterOffsetX/Y - Subpixel jitter in pixel space [-0.5, 0.5]</li>
 * <li>InReset - Set to 1 on scene changes to reset temporal history</li>
 * <li>InFrameTimeDeltaInMsec - Frame time for temporal stability</li>
 * </ul>
 *
 * @see DLSSDProcessor
 * @see DLSSConfig
 */
public class DLSSBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(DLSSBridge.class);

    // DLSS depth type constants (from nvsdk_ngx_defs_dlssd.h)
    public static final int DEPTH_TYPE_LINEAR = 0;
    public static final int DEPTH_TYPE_HW = 1;

    // DLSS roughness mode constants
    public static final int ROUGHNESS_MODE_UNPACKED = 0;
    public static final int ROUGHNESS_MODE_PACKED = 1;

    // NGX quality/performance values - matches NVSDK_NGX_PerfQuality_Value enum from nvsdk_ngx_defs.h
    // These values are passed directly to the NGX SDK without remapping.
    // See plans/DLSSD_PARAMETERS_REFERENCE.md for documentation.
    public static final int NGX_PERF_QUALITY_MAX_PERF = 0;        // "Performance" - 0.5 (50%) scale
    public static final int NGX_PERF_QUALITY_BALANCED = 1;        // "Balanced" - 0.583 (58.3%) scale
    public static final int NGX_PERF_QUALITY_MAX_QUALITY = 2;     // "Quality" - 0.667 (66.67%) scale
    public static final int NGX_PERF_QUALITY_ULTRA_PERFORMANCE = 3; // "Ultra Performance" - 0.333 (33.3%) scale
    public static final int NGX_PERF_QUALITY_ULTRA_QUALITY = 4;   // "Ultra Quality" - ~0.77 (77%) scale
    public static final int NGX_PERF_QUALITY_DLAA = 5;            // "DLAA" - 1.0 (no upscaling, AA only)

    // Legacy aliases for backward compatibility
    /** @deprecated Use {@link #NGX_PERF_QUALITY_MAX_PERF} instead */
    public static final int NGX_PERF_QUALITY_PERFORMANCE = NGX_PERF_QUALITY_MAX_PERF;
    /** @deprecated Use {@link #NGX_PERF_QUALITY_MAX_QUALITY} instead */
    public static final int NGX_PERF_QUALITY_QUALITY = NGX_PERF_QUALITY_MAX_QUALITY;

    // Native library loaded flag
    private static boolean nativeLibraryLoaded = false;
    private static boolean nativeLibraryLoadAttempted = false;
    private static boolean ngxActive = false;

    // JNA interface to native library
    private interface NativeDLSS extends Library {
        // DLSSD availability check - maps to isDLSSDAvailable()
        int isDLSSDAvailable();

        // DLSSD initialization - maps to initDLSSD()
        int initDLSSD(
            long instance,
            long physicalDevice,
            long device,
            int width, int height,
            int outWidth, int outHeight,
            int denoiseMode,
            int roughnessMode,
            int depthType,
            int perfQualityValue);

        // DLSSD evaluation - maps to evaluateDLSSD()
        int evaluateDLSSD(
            long cmdBuffer,
            long colorImageView, long colorImage, int colorFormat,
            long depthImageView, long depthImage, int depthFormat,
            long mvImageView, long mvImage, int mvFormat,
            long diffuseAlbedoImageView, long diffuseAlbedoImage, int diffuseAlbedoFormat,
            long specularAlbedoImageView, long specularAlbedoImage, int specularAlbedoFormat,
            long normalsImageView, long normalsImage, int normalsFormat,
            long roughnessImageView, long roughnessImage, int roughnessFormat,
            long specularHitDepthImageView, long specularHitDepthImage, int specularHitDepthFormat,
            long outputImageView, long outputImage, int outputFormat,
            float jitterX, float jitterY,
            int reset, float frameTimeDeltaMs,
            float[] worldToViewMatrix, float[] viewToClipMatrix);

        // DLSSD destruction - maps to destroyDLSSD()
        void destroyDLSSD(long device);

        // NGX shutdown - maps to shutdownNGX()
        void shutdownNGX();

        // NGX initialization - maps to initializeNGX()
        int initializeNGX(long instance, long physicalDevice, long device, String dlssPath);

        // Standard DLSS initialization - maps to initDLSS()
        int initDLSS(long instance, long physicalDevice, long device, int width, int height, int outWidth, int outHeight);

        // Standard DLSS evaluation - maps to evaluateDLSS()
        int evaluateDLSS(
            long cmdBuffer,
            long colorImageView, long colorImage, int colorFormat,
            long depthImageView, long depthImage, int depthFormat,
            long mvImageView, long mvImage, int mvFormat,
            long outputImageView, long outputImage, int outputFormat,
            float jitterX, float jitterY);

        // Standard DLSS destruction - maps to destroyDLSS()
        void destroyDLSS(long device);

        // Get render resolution - maps to getDLSSDRenderResolution()
        void getDLSSDRenderResolution(int outWidth, int outHeight, int qualityPreset, int[] outRenderWidth, int[] outRenderHeight);

        // NGX Extension Discovery (must be called BEFORE Vulkan instance/device creation)
        int getNGXInstanceExtensionCount();
        String getNGXInstanceExtension(int index);
        int getNGXDeviceExtensionCount(long instance, long physicalDevice);
        String getNGXDeviceExtension(long instance, long physicalDevice, int index);
    }

    private static NativeDLSS nativeLib = null;

    static {
        loadNativeLibrary();
    }

    /**
     * Load the native DLSS bridge library.
     */
    private static void loadNativeLibrary() {
        if (nativeLibraryLoadAttempted) {
            return;
        }
        nativeLibraryLoadAttempted = true;

        try {
            // Try to load the native library using JNA
            nativeLib = Native.load("vulkanite_dlss_bridge", NativeDLSS.class);
            nativeLibraryLoaded = true;
            LOGGER.info("DLSS bridge native library loaded successfully via JNA");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("Failed to load DLSS bridge native library: {}", e.getMessage());
            LOGGER.info("DLSS functionality will be disabled");
            nativeLibraryLoaded = false;
        }
    }

    /**
     * Check if the native library is loaded.
     *
     * @return true if the native library was loaded successfully
     */
    public static boolean isNativeLibraryLoaded() {
        return nativeLibraryLoaded;
    }

    /**
     * Check if DLSSD (Ray Reconstruction) is supported.
     *
     * @return true if DLSSD is available
     */
    public static boolean isDLSSDSupported() {
        if (!nativeLibraryLoaded || nativeLib == null) {
            return false;
        }
        try {
            return nativeLib.isDLSSDAvailable() != 0;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("Native method not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Query NGX-required Vulkan instance extensions.
     * MUST be called BEFORE VkInstance creation so these extensions can be enabled.
     *
     * @return list of required extension names, empty if native lib unavailable
     */
    public static List<String> getRequiredInstanceExtensions() {
        List<String> extensions = new java.util.ArrayList<>();
        if (!nativeLibraryLoaded || nativeLib == null) {
            return extensions;
        }
        try {
            int count = nativeLib.getNGXInstanceExtensionCount();
            for (int i = 0; i < count; i++) {
                String ext = nativeLib.getNGXInstanceExtension(i);
                if (ext != null && !ext.isEmpty()) {
                    extensions.add(ext);
                    LOGGER.info("NGX requires instance extension: {}", ext);
                }
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("getNGXInstanceExtension not available: {}", e.getMessage());
        }
        return extensions;
    }

    /**
     * Query NGX-required Vulkan device extensions.
     * MUST be called AFTER physical device selection but BEFORE VkDevice creation.
     *
     * @param instance VkInstance handle
     * @param physicalDevice VkPhysicalDevice handle
     * @return list of required extension names, empty if native lib unavailable
     */
    public static List<String> getRequiredDeviceExtensions(long instance, long physicalDevice) {
        List<String> extensions = new java.util.ArrayList<>();
        if (!nativeLibraryLoaded || nativeLib == null) {
            return extensions;
        }
        try {
            int count = nativeLib.getNGXDeviceExtensionCount(instance, physicalDevice);
            for (int i = 0; i < count; i++) {
                String ext = nativeLib.getNGXDeviceExtension(instance, physicalDevice, i);
                if (ext != null && !ext.isEmpty()) {
                    extensions.add(ext);
                    LOGGER.info("NGX requires device extension: {}", ext);
                }
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("getNGXDeviceExtension not available: {}", e.getMessage());
        }
        return extensions;
    }

    /**
     * Create a DLSSD feature.
     *
     * @param instance The Vulkan instance handle
     * @param physicalDevice The Vulkan physical device handle
     * @param device The Vulkan device handle
     * @param renderWidth The render width
     * @param renderHeight The render height
     * @param outputWidth The output width
     * @param outputHeight The output height
     * @param qualityMode The quality mode (NGX_PERF_QUALITY_* value)
     * @param roughnessMode The roughness mode (ROUGHNESS_MODE_PACKED or UNPACKED)
     * @param depthType The depth type (DEPTH_TYPE_LINEAR or HW)
     * @return The feature handle, or 0 on failure
     */
    public static long createDLSSDFeature(
        long instance, long physicalDevice, long device,
        int renderWidth, int renderHeight,
        int outputWidth, int outputHeight,
        int qualityMode,
        int roughnessMode,
        int depthType) {

        if (!nativeLibraryLoaded || nativeLib == null) {
            LOGGER.warn("Cannot create DLSSD feature - native library not loaded");
            return 0;
        }

        try {
            // Note: The native initDLSSD returns an NGX result code, not a handle.
            // We use a non-zero value to indicate success since the native code
            // manages the feature internally as a singleton.
            int result = nativeLib.initDLSSD(
                instance, physicalDevice,
                device,
                renderWidth, renderHeight,
                outputWidth, outputHeight,
                1, // denoiseMode - Ray Reconstruction
                roughnessMode,
                depthType,
                qualityMode);

            if (result != 1) { // NVSDK_NGX_Result_Success = 1
                LOGGER.error("Native initDLSSD failed with error code: {}", result);
                return 0;
            }

            // Return a non-zero handle to indicate success
            // The actual feature is managed internally by the native bridge
            long handle = 1; // Use 1 as success indicator
            ngxActive = true;
            LOGGER.info("DLSSD feature created: handle={}, render={}x{}, output={}x{}",
                handle, renderWidth, renderHeight, outputWidth, outputHeight);
            return handle;

        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("Native method not available: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Evaluate DLSSD for a frame.
     *
     * @param commandBuffer The Vulkan command buffer
     * @param featureHandle The feature handle from createDLSSDFeature
     * @param colorImage The noisy color input image
     * @param colorFormat The color image format
     * @param depthView The depth image view (or 0 to use image directly)
     * @param depthImage The depth VkImage handle
     * @param depthFormat The depth image format
     * @param motionVectorsImage The motion vectors image
     * @param motionVectorsFormat The motion vectors format
     * @param outputView The output image view (or 0 to use image directly)
     * @param outputImage The output VkImage handle
     * @param outputFormat The output format
     * @param diffuseAlbedoView The diffuse albedo image view (or 0)
     * @param diffuseAlbedoImage The diffuse albedo image (required if view is 0)
     * @param diffuseAlbedoFormat The diffuse albedo format
     * @param specularAlbedoView The specular albedo image view (or 0)
     * @param specularAlbedoImage The specular albedo image (required if view is 0)
     * @param specularAlbedoFormat The specular albedo format
     * @param normalsView The normals image view (or 0)
     * @param normalsImage The normals image (required if view is 0)
     * @param normalsFormat The normals format
     * @param roughnessView The roughness image view (or 0, if packed in normals)
     * @param roughnessImage The roughness image (or 0 if packed in normals)
     * @param roughnessFormat The roughness format
     * @param jitterX The jitter X offset in pixel space
     * @param jitterY The jitter Y offset in pixel space
     * @param reset 1 to reset temporal history, 0 otherwise
     * @param deltaTimeMs The frame delta time in milliseconds
     * @param renderWidth The render width
     * @param renderHeight The render height
     * @return true if evaluation succeeded
     */
    public static boolean evaluateDLSSD(
        long commandBuffer,
        long featureHandle,
        long colorView, long colorImage, int colorFormat,
        long depthView, long depthImage, int depthFormat,
        long motionVectorsView, long motionVectorsImage, int motionVectorsFormat,
        long outputView, long outputImage, int outputFormat,
        long diffuseAlbedoView, long diffuseAlbedoImage, int diffuseAlbedoFormat,
        long specularAlbedoView, long specularAlbedoImage, int specularAlbedoFormat,
        long normalsView, long normalsImage, int normalsFormat,
        long roughnessView, long roughnessImage, int roughnessFormat,
        long specularHitDepthView, long specularHitDepthImage, int specularHitDepthFormat,
        float jitterX, float jitterY,
        int reset,
        float deltaTimeMs,
        float[] worldToViewMatrix,
        float[] viewToClipMatrix,
        int renderWidth, int renderHeight) {

        if (!nativeLibraryLoaded || nativeLib == null) {
            LOGGER.warn("Cannot evaluate DLSSD - native library not loaded");
            return false;
        }

        try {
            // Pass views and images to native code
            // Native expects (view, image, format) triplets for each resource
            int result = nativeLib.evaluateDLSSD(
                commandBuffer,
                colorView, colorImage, colorFormat,             // colorView, colorImage, colorFormat
                depthView, depthImage, depthFormat,      // depthView, depthImage, depthFormat
                motionVectorsView, motionVectorsImage, motionVectorsFormat, // mvView, mvImage, mvFormat
                diffuseAlbedoView, diffuseAlbedoImage, diffuseAlbedoFormat,
                specularAlbedoView, specularAlbedoImage, specularAlbedoFormat,
                normalsView, normalsImage, normalsFormat,
                roughnessView, roughnessImage, roughnessFormat,
                specularHitDepthView, specularHitDepthImage, specularHitDepthFormat,
                outputView, outputImage, outputFormat,   // outputView, outputImage, outputFormat
                jitterX, jitterY,
                reset, deltaTimeMs,
                worldToViewMatrix, viewToClipMatrix);

            return result == 1; // NVSDK_NGX_Result_Success = 1

        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("Native method not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Release a DLSSD feature.
     *
     * @param featureHandle The feature handle to release
     */
    public static void releaseDLSSDFeature(long featureHandle) {
        if (!nativeLibraryLoaded || nativeLib == null) {
            return;
        }
        try {
            nativeLib.destroyDLSSD(0); // device not used in current implementation
            LOGGER.info("DLSSD feature released: handle={}", featureHandle);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("Native method not available: {}", e.getMessage());
        }
    }

    /**
     * Shut down the NGX lifecycle after DLSS/DLSSD features have been released.
     */
    public static void shutdownNGX() {
        if (!nativeLibraryLoaded || nativeLib == null) {
            return;
        }
        if (!ngxActive) {
            return;
        }
        try {
            nativeLib.shutdownNGX();
            ngxActive = false;
            LOGGER.debug("NGX shutdown requested");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("NGX shutdown native method not available: {}", e.getMessage());
        }
    }

    /**
     * Initialize standard DLSS Super Resolution feature.
     * This is the fallback when DLSSD (Ray Reconstruction) is not available.
     *
     * @param instance VkInstance handle
     * @param physicalDevice VkPhysicalDevice handle
     * @param device VkDevice handle
     * @param renderWidth Render (input) width
     * @param renderHeight Render (input) height
     * @param outputWidth Output (display) width
     * @param outputHeight Output (display) height
     * @return true if initialization succeeded
     */
    public static boolean initStandardDLSS(
            long instance, long physicalDevice, long device,
            int renderWidth, int renderHeight,
            int outputWidth, int outputHeight) {
        if (!nativeLibraryLoaded || nativeLib == null) {
            LOGGER.warn("Cannot init standard DLSS - native library not loaded");
            return false;
        }
        try {
            int result = nativeLib.initDLSS(instance, physicalDevice, device,
                    renderWidth, renderHeight, outputWidth, outputHeight);
            if (result == 1) { // NVSDK_NGX_Result_Success = 1
                ngxActive = true;
                LOGGER.info("Standard DLSS initialized: render={}x{}, output={}x{}",
                        renderWidth, renderHeight, outputWidth, outputHeight);
                return true;
            } else {
                LOGGER.error("Standard DLSS init failed with code: {}", result);
                return false;
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("Standard DLSS native method not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Evaluate standard DLSS Super Resolution for a frame.
     * Standard DLSS performs temporal upscaling without ray reconstruction denoising.
     *
     * @param commandBuffer VkCommandBuffer handle
     * @param colorImage Noisy color input VkImage
     * @param colorFormat Color image Vulkan format
     * @param depthView Depth VkImageView (or 0)
     * @param depthImage Depth VkImage handle
     * @param depthFormat Depth Vulkan format
     * @param motionVectorsImage Motion vectors VkImage
     * @param motionVectorsFormat Motion vectors Vulkan format
     * @param outputView Output VkImageView (or 0)
     * @param outputImage Output VkImage handle
     * @param outputFormat Output Vulkan format
     * @param jitterX Jitter X offset in pixel space
     * @param jitterY Jitter Y offset in pixel space
     * @return true if evaluation succeeded
     */
    public static boolean evaluateStandardDLSS(
            long commandBuffer,
            long colorView, long colorImage, int colorFormat,
            long depthView, long depthImage, int depthFormat,
            long motionVectorsView, long motionVectorsImage, int motionVectorsFormat,
            long outputView, long outputImage, int outputFormat,
            float jitterX, float jitterY) {
        if (!nativeLibraryLoaded || nativeLib == null) {
            LOGGER.warn("Cannot evaluate standard DLSS - native library not loaded");
            return false;
        }
        try {
            int result = nativeLib.evaluateDLSS(
                commandBuffer,
                colorView, colorImage, colorFormat,           // colorView, colorImage, format
                depthView, depthImage, depthFormat,    // depthView, depthImage, format
                motionVectorsView, motionVectorsImage, motionVectorsFormat, // mvView, mvImage, format
                outputView, outputImage, outputFormat, // outputView, outputImage, format
                jitterX, jitterY);
            return result == 1; // NVSDK_NGX_Result_Success = 1
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("Standard DLSS evaluate native method not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Destroy standard DLSS feature.
     *
     * @param device VkDevice handle
     */
    public static void destroyStandardDLSS(long device) {
        if (!nativeLibraryLoaded || nativeLib == null) return;
        try {
            nativeLib.destroyDLSS(device);
            LOGGER.info("Standard DLSS destroyed");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("Standard DLSS destroy native method not available: {}", e.getMessage());
        }
    }
}
