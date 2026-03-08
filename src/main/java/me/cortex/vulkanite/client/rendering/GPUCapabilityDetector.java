package me.cortex.vulkanite.client.rendering;

import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU Capability Detector for Upscaling Features
 *
 * This class detects GPU capabilities at runtime to determine
 * which upscaling/denoising method is available:
 * - DLSS Ray Reconstruction (NVIDIA RTX GPUs with Tensor Cores)
 * - FSR (All GPUs - AMD, NVIDIA, Intel)
 * - Basic temporal accumulation (Fallback)
 *
 * Detection Logic:
 * 1. Check for NVIDIA RTX GPU (DLSS support)
 * 2. Check for Vulkan extensions required for DLSS
 * 3. FSR is always available (software-based)
 *
 * Note: The user can manually select the denoiser in the settings,
 * but this class provides information about hardware capabilities.
 */
public class GPUCapabilityDetector {

    private final GPUType gpuType;
    private final boolean supportsDLSS;
    private final boolean supportsFSR;
    private final boolean supportsRayTracing;
    private final boolean supportsTensorCores;
    private final String gpuName;
    private final int vendorId;

    /**
     * GPU Type enumeration
     */
    public enum GPUType {
        NVIDIA,
        AMD,
        INTEL,
        UNKNOWN
    }

    public GPUCapabilityDetector(VkPhysicalDeviceProperties deviceProps) {
        this.gpuName = deviceProps.deviceNameString();
        this.vendorId = deviceProps.vendorID();

        // Detect GPU type from vendor ID
        this.gpuType = detectGPUType(vendorId);

        // Detect capabilities
        this.supportsRayTracing = checkRayTracingSupport(deviceProps);
        this.supportsTensorCores = checkTensorCoreSupport();
        this.supportsDLSS = checkDLSSSupport();
        this.supportsFSR = checkFSRSupport();

        printCapabilityReport();
    }

    /**
     * Detect GPU type from vendor ID
     */
    private GPUType detectGPUType(int vendorId) {
        // PCI Vendor IDs
        // NVIDIA: 0x10DE
        // AMD: 0x1002
        // Intel: 0x8086
        switch (vendorId) {
            case 0x10DE:
                return GPUType.NVIDIA;
            case 0x1002:
                return GPUType.AMD;
            case 0x8086:
                return GPUType.INTEL;
            default:
                return GPUType.UNKNOWN;
        }
    }

    /**
     * Check for ray tracing support
     */
    private boolean checkRayTracingSupport(VkPhysicalDeviceProperties deviceProps) {
        // Check for ray tracing pipeline properties
        // In a full implementation, this would query VK_KHR_ray_tracing_pipeline
        return true; // Assume ray tracing support if Vulkan pipeline is active
    }

    /**
     * Check for Tensor Core support (required for DLSS)
     * Tensor Cores are only available on NVIDIA RTX GPUs
     */
    private boolean checkTensorCoreSupport() {
        if (gpuType != GPUType.NVIDIA) {
            return false;
        }

        // Check for RTX GPU in name
        // RTX GPUs have "RTX" in their name
        boolean isRTX = gpuName.contains("RTX");

        // Also check for specific RTX series
        // RTX 20xx, 30xx, 40xx series
        boolean isRTXSeries = gpuName.matches(".*RTX\\s*[234]\\d{3}.*");

        return isRTX || isRTXSeries;
    }

    /**
     * Check for DLSS support
     * DLSS requires:
     * - NVIDIA RTX GPU (Tensor Cores)
     * - VK_NV_low_latency2 extension (optional, for Reflex)
     * - NVIDIA Streamline SDK
     */
    private boolean checkDLSSSupport() {
        // DLSS requires Tensor Cores (RTX GPU)
        if (!supportsTensorCores) {
            return false;
        }

        // In a full implementation, this would also check for:
        // - NVIDIA Streamline SDK availability
        // - DLSS DLL presence
        // - Required Vulkan extensions

        // For now, return true if RTX GPU is detected
        return true;
    }

    /**
     * Check for FSR support
     * FSR works on all GPUs that support Vulkan
     */
    private boolean checkFSRSupport() {
        // FSR is a spatial algorithm that works on all GPUs
        // No special hardware required
        return true;
    }

    /**
     * Get the recommended upscaling method based on hardware
     * Note: User can override this selection in settings
     */
    public UpscalingMethod getRecommendedUpscalingMethod() {
        if (supportsDLSS) {
            return UpscalingMethod.DLSS;
        } else if (supportsFSR) {
            return UpscalingMethod.FSR;
        } else {
            return UpscalingMethod.BASIC;
        }
    }

    /**
     * Check if a specific upscaling method is supported by hardware
     */
    public boolean isMethodSupported(UpscalingMethod method) {
        switch (method) {
            case DLSS:
                return supportsDLSS;
            case FSR:
                return supportsFSR;
            case BASIC:
            default:
                return true; // Basic is always available
        }
    }

    /**
     * Print capability report
     */
    private void printCapabilityReport() {
        System.out.println("===========================================");
        System.out.println("[Vulkanite] GPU Capability Report");
        System.out.println("===========================================");
        System.out.println("GPU Name: " + gpuName);
        System.out.println("GPU Type: " + gpuType);
        System.out.println("Vendor ID: 0x" + Integer.toHexString(vendorId));
        System.out.println("-------------------------------------------");
        System.out.println("Ray Tracing Support: " + supportsRayTracing);
        System.out.println("Tensor Core Support: " + supportsTensorCores);
        System.out.println("DLSS Support: " + supportsDLSS);
        System.out.println("FSR Support: " + supportsFSR);
        System.out.println("-------------------------------------------");
        System.out.println("Recommended Upscaler: " + getRecommendedUpscalingMethod());
        System.out.println("===========================================");
    }

    // Getters

    public GPUType getGPUType() {
        return gpuType;
    }

    public boolean supportsDLSS() {
        return supportsDLSS;
    }

    public boolean supportsFSR() {
        return supportsFSR;
    }

    public boolean supportsRayTracing() {
        return supportsRayTracing;
    }

    public boolean supportsTensorCores() {
        return supportsTensorCores;
    }

    public String getGPUName() {
        return gpuName;
    }

    public int getVendorId() {
        return vendorId;
    }

    /**
     * Upscaling Method enumeration
     */
    public enum UpscalingMethod {
        DLSS,  // NVIDIA DLSS Ray Reconstruction
        FSR,   // AMD FidelityFX Super Resolution
        BASIC  // Basic temporal accumulation (fallback)
    }
}
