#include <vulkan/vulkan.h>
#include <windows.h> // For GetTempPathW
#include <iostream>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>
#include <map>

#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"

// DLSSD (Ray Reconstruction) headers
#include "nvsdk_ngx_defs_dlssd.h"
#include "nvsdk_ngx_params_dlssd.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"

// --- Logging ---
static std::ofstream g_LogFile;
static std::mutex g_LogMutex;

void OpenLog() {
    std::lock_guard<std::mutex> lock(g_LogMutex);
    if (!g_LogFile.is_open()) {
        g_LogFile.open("dlss_bridge_debug.log", std::ios::out | std::ios::trunc);
    }
}

void Log(const std::string& msg) {
    OpenLog();
    std::lock_guard<std::mutex> lock(g_LogMutex);
    if (g_LogFile.is_open()) {
        g_LogFile << msg << std::endl;
        g_LogFile.flush();
    }
    // Also print to stdout for console visibility
    std::cout << "[DLSS Bridge] " << msg << std::endl;
}

// Helper function to translate NVSDK_NGX_Result to string
const char* getNGXErrorString(NVSDK_NGX_Result res) {
    switch (res) {
        case NVSDK_NGX_Result_Success: return "Success";
        case NVSDK_NGX_Result_FAIL_FeatureNotSupported: return "FeatureNotSupported";
        case NVSDK_NGX_Result_FAIL_PlatformError: return "PlatformError";
        case NVSDK_NGX_Result_FAIL_FeatureAlreadyExists: return "FeatureAlreadyExists";
        case NVSDK_NGX_Result_FAIL_FeatureNotFound: return "FeatureNotFound";
        case NVSDK_NGX_Result_FAIL_InvalidParameter: return "InvalidParameter";
        case NVSDK_NGX_Result_FAIL_ScratchBufferTooSmall: return "ScratchBufferTooSmall";
        case NVSDK_NGX_Result_FAIL_NotInitialized: return "NotInitialized";
        case NVSDK_NGX_Result_FAIL_UnsupportedInputFormat: return "UnsupportedInputFormat";
        case NVSDK_NGX_Result_FAIL_RWFlagMissing: return "RWFlagMissing";
        case NVSDK_NGX_Result_FAIL_MissingInput: return "MissingInput";
        case NVSDK_NGX_Result_FAIL_UnableToInitializeFeature: return "UnableToInitializeFeature";
        case NVSDK_NGX_Result_FAIL_OutOfDate: return "OutOfDate";
        case NVSDK_NGX_Result_FAIL_OutOfGPUMemory: return "OutOfGPUMemory";
        case NVSDK_NGX_Result_FAIL_UnsupportedFormat: return "UnsupportedFormat";
        default: return (res < 0) ? "Failure (Unknown)" : "Unknown";
    }
}

// Helper to determine aspect mask from format
VkImageAspectFlags GetAspectMask(VkFormat format) {
    switch (format) {
        case VK_FORMAT_D16_UNORM:
        case VK_FORMAT_X8_D24_UNORM_PACK32:
        case VK_FORMAT_D32_SFLOAT:
            return VK_IMAGE_ASPECT_DEPTH_BIT;
        case VK_FORMAT_S8_UINT:
            return VK_IMAGE_ASPECT_STENCIL_BIT;
        case VK_FORMAT_D16_UNORM_S8_UINT:
        case VK_FORMAT_D24_UNORM_S8_UINT:
        case VK_FORMAT_D32_SFLOAT_S8_UINT:
            return VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
        default:
            return VK_IMAGE_ASPECT_COLOR_BIT;
    }
}

// --- DLSS Bridge Class ---

class DLSSBridge {
public:
    static DLSSBridge& Get() {
        static DLSSBridge instance;
        return instance;
    }

    // Prevent copying
    DLSSBridge(const DLSSBridge&) = delete;
    DLSSBridge& operator=(const DLSSBridge&) = delete;

    // --- Initialization / Shutdown ---

    NVSDK_NGX_Result InitializeNGX(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device, const char* dlssPath) {
        std::lock_guard<std::mutex> lock(m_Mutex);

        if (m_Initialized) {
            bool sameDevice = (m_Instance == instance && m_PhysicalDevice == physicalDevice && m_Device == device);
            bool samePath = true;
            if (dlssPath != nullptr) {
                samePath = (m_DLSSPath == dlssPath);
            }
            
            if (sameDevice && samePath) {
                return NVSDK_NGX_Result_Success; // Already initialized with same device and path
            }
            
            Log("Re-initializing NGX...");
            ShutdownNGX_Internal();
        }

        if (instance == VK_NULL_HANDLE || physicalDevice == VK_NULL_HANDLE || device == VK_NULL_HANDLE) {
            Log("InitializeNGX called with null Vulkan handles!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }

        Log("Initializing NGX...");
        if (dlssPath) {
            Log("DLSS Path: " + std::string(dlssPath));
            m_DLSSPath = dlssPath;
        } else {
            m_DLSSPath.clear();
        }
        
        // Define function pointers
        PFN_vkGetInstanceProcAddr gipa = vkGetInstanceProcAddr;
        PFN_vkGetDeviceProcAddr gdpa = vkGetDeviceProcAddr;

        // Prepare path info for custom DLL location
        std::wstring wDlssPath;
        const wchar_t* paths[] = { nullptr, nullptr };
        NVSDK_NGX_PathListInfo pathListInfo = {};
        NVSDK_NGX_FeatureCommonInfo featureInfo = {};
        const NVSDK_NGX_FeatureCommonInfo* pFeatureInfo = nullptr;

        if (dlssPath != nullptr && strlen(dlssPath) > 0) {
            size_t len = strlen(dlssPath);
            wDlssPath.resize(len + 1);
            size_t convertedChars = 0;
            errno_t err = mbstowcs_s(&convertedChars, &wDlssPath[0], len + 1, dlssPath, _TRUNCATE);
            
            if (err == 0) {
                paths[0] = wDlssPath.c_str();
                pathListInfo.Path = paths;
                pathListInfo.Length = 1;
                featureInfo.PathListInfo = pathListInfo;
                pFeatureInfo = &featureInfo;
                Log("Using custom DLSS search path.");
            } else {
                Log("Failed to convert DLSS path to wstring. Ignoring custom path.");
            }
        } else {
            // FIX: Default to current working directory "." if no path provided
            // This is required because Minecraft runs as javaw.exe, so default search path is Java bin dir
            // But DLLs are in the game directory (CWD)
            Log("No custom DLSS path provided. Defaulting to CWD (.) for DLL search.");
            paths[0] = L"."; 
            pathListInfo.Path = paths;
            pathListInfo.Length = 1;
            featureInfo.PathListInfo = pathListInfo;
            pFeatureInfo = &featureInfo;
        }

        // Use ProjectID method which is more flexible for mods/non-commercial use
        // and allows passing a UUID string.
        // AppID 0 (standard Init) often lacks permissions for advanced features like DLSSD.
        // NOTE: We use "." for ApplicationDataPath to ensure logs/cache go to the CWD (usually game dir),
        // instead of the DLL path which might be read-only.
        Log("Calling NVSDK_NGX_VULKAN_Init_with_ProjectID...");
        Log(" - ProjectID: vulkanite-raytracing-mod");
        Log(" - EngineType: CUSTOM");
        Log(" - EngineVersion: 1.0.0");
    
        // CRITICAL FIX: NVSDK_NGX_VULKAN_Init_with_ProjectID requires a VALID, WRITABLE directory
        // for InApplicationDataPath. Using L"." (CWD) can fail if the directory is not writable
        // or if the path has issues. We need to use an absolute path.
        //
        // For Minecraft mods, the best location is the game directory (run/ folder).
        // The Java side passes this as dlssPath, so we use it if available.
        //
        // If dlssPath is not available, we fall back to creating a temp directory.
        std::wstring appDataPath;
        if (dlssPath != nullptr && strlen(dlssPath) > 0) {
            // Use the provided path (game directory)
            appDataPath = wDlssPath;
        } else {
            // Fall back to temp directory
            wchar_t tempPath[MAX_PATH];
            GetTempPathW(MAX_PATH, tempPath);
            appDataPath = tempPath;
            Log("Using temp directory for NGX data: " + std::string(appDataPath.begin(), appDataPath.end()));
        }
    
        // DLSSD/Ray Reconstruction requires proper initialization with ProjectID
        // Using a descriptive project ID that identifies this as a ray tracing mod
        NVSDK_NGX_Result res = NVSDK_NGX_VULKAN_Init_with_ProjectID(
            "vulkanite-raytracing-mod", // Unique project identifier
            NVSDK_NGX_ENGINE_TYPE_CUSTOM,
            "1.0.0",
            appDataPath.c_str(), // Path to store logs/cache (use game dir or temp)
            instance,
            physicalDevice,
            device,
            gipa, // Instance Proc Addr
            gdpa, // Device Proc Addr
            pFeatureInfo, // Ensure pFeatureInfo is passed, not nullptr!
            NVSDK_NGX_Version_API
        );
    
        if (NVSDK_NGX_FAILED(res)) {
            Log("NVSDK_NGX_VULKAN_Init_with_ProjectID failed: " + std::string(getNGXErrorString(res)) + " (code: " + std::to_string(res) + ")");
            Log("Trying fallback with standard AppID...");
    
            // Fallback: Try standard Init with generic AppID
            // NOTE: Standard AppID may not have DLSSD (Ray Reconstruction) permissions!
            // DLSSD requires a valid ProjectID registration with NVIDIA.
            res = NVSDK_NGX_VULKAN_Init(
                231313132, // AppID 231313132 = NVIDIA Generic/Test AppID
                appDataPath.c_str(),
                instance,
                physicalDevice,
                device,
                gipa,
                gdpa,
                pFeatureInfo,
                NVSDK_NGX_Version_API
            );
    
            if (NVSDK_NGX_FAILED(res)) {
                Log("NVSDK_NGX_VULKAN_Init (fallback) also failed: " + std::string(getNGXErrorString(res)) + " (code: " + std::to_string(res) + ")");
                return res;
            }
        }
    
        if (NVSDK_NGX_FAILED(res)) {
            Log("NVSDK_NGX_VULKAN_Init (both methods) failed: " + std::string(getNGXErrorString(res)));
            return res;
        }

        // Acquire capability parameters
        res = NVSDK_NGX_VULKAN_GetCapabilityParameters(&m_Parameters);
        if (NVSDK_NGX_FAILED(res)) {
            Log("Failed to get capability parameters: " + std::string(getNGXErrorString(res)));
            NVSDK_NGX_VULKAN_Shutdown1(device);
            return res;
        }

        m_Instance = instance;
        m_PhysicalDevice = physicalDevice;
        m_Device = device;
        m_Initialized = true;
        Log("NGX Initialized Successfully (Build ID: 2026-03-19-RR-FIX)");
        
        // Check and log DLSSD/Ray Reconstruction availability
        int rrAvailable = 0;
        res = NVSDK_NGX_Parameter_GetI(m_Parameters, NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &rrAvailable);
        if (NVSDK_NGX_SUCCEED(res)) {
            Log("=== DLSSD/Ray Reconstruction Capability Check ===");
            Log("  - Ray Reconstruction Available: " + std::string(rrAvailable ? "YES" : "NO"));
            
            if (rrAvailable) {
                // Log additional DLSSD info
                int needsUpdate = 0;
                NVSDK_NGX_Parameter_GetI(m_Parameters, NVSDK_NGX_Parameter_SuperSamplingDenoising_NeedsUpdatedDriver, &needsUpdate);
                Log("  - Needs Updated Driver: " + std::string(needsUpdate ? "YES" : "NO"));
                
                unsigned int majorVer = 0, minorVer = 0;
                NVSDK_NGX_Parameter_GetUI(m_Parameters, NVSDK_NGX_Parameter_SuperSamplingDenoising_MinDriverVersionMajor, &majorVer);
                NVSDK_NGX_Parameter_GetUI(m_Parameters, NVSDK_NGX_Parameter_SuperSamplingDenoising_MinDriverVersionMinor, &minorVer);
                Log("  - Min Driver Version: " + std::to_string(majorVer) + "." + std::to_string(minorVer));
            } else {
                Log("  WARNING: Ray Reconstruction is NOT available on this system!");
                Log("  Possible reasons: GPU not supported, driver too old, or DLSS DLLs missing/incompatible.");
            }
        } else {
            Log("Failed to query Ray Reconstruction availability: " + std::string(getNGXErrorString(res)));
        }
        
        // Also check standard DLSS availability
        int dlssAvailable = 0;
        res = NVSDK_NGX_Parameter_GetI(m_Parameters, NVSDK_NGX_Parameter_SuperSampling_Available, &dlssAvailable);
        if (NVSDK_NGX_SUCCEED(res)) {
            Log("  - DLSS Super Resolution Available: " + std::string(dlssAvailable ? "YES" : "NO"));
        }
        
        return NVSDK_NGX_Result_Success;
    }

    void ShutdownNGX() {
        std::lock_guard<std::mutex> lock(m_Mutex);
        ShutdownNGX_Internal();
    }

    // --- Standard DLSS ---

    NVSDK_NGX_Result InitDLSS(int renderWidth, int renderHeight, int outWidth, int outHeight, NVSDK_NGX_PerfQuality_Value quality) {
        std::lock_guard<std::mutex> lock(m_Mutex);
        if (!m_Initialized) {
            Log("InitDLSS called before NGX Initialization!");
            return NVSDK_NGX_Result_FAIL_NotInitialized;
        }

        if (m_DLSSFeature) {
            Log("DLSS Feature already exists. Destroying old feature.");
            NVSDK_NGX_VULKAN_ReleaseFeature(m_DLSSFeature);
            m_DLSSFeature = nullptr;
        }

        // Ensure dimensions are even (DLSS requirement often)
        m_DLSSRenderWidth = (renderWidth % 2 != 0) ? renderWidth - 1 : renderWidth;
        m_DLSSRenderHeight = (renderHeight % 2 != 0) ? renderHeight - 1 : renderHeight;
        m_DLSSOutWidth = (outWidth % 2 != 0) ? outWidth - 1 : outWidth;
        m_DLSSOutHeight = (outHeight % 2 != 0) ? outHeight - 1 : outHeight;
        m_DLSSQuality = quality;

        // Feature creation is deferred to the first Evaluate call because it requires a CommandBuffer
        m_DLSSFeaturePending = true;
        m_DLSSCreationFailures = 0;
        Log("DLSS Init configured. Feature creation deferred to first frame. Render: " + std::to_string(m_DLSSRenderWidth) + "x" + std::to_string(m_DLSSRenderHeight));
        return NVSDK_NGX_Result_Success;
    }

    void DestroyDLSS() {
        std::lock_guard<std::mutex> lock(m_Mutex);
        if (m_DLSSFeature) {
            NVSDK_NGX_VULKAN_ReleaseFeature(m_DLSSFeature);
            m_DLSSFeature = nullptr;
        }
        m_DLSSFeaturePending = false;
        // If DLSSD is also not active, we could shutdown NGX, but let's keep it alive for now to avoid overhead
    }

    // Internal non-locking version for use within DLSSD fallback
// NOTE: When called from DLSSD fallback, use DLSSD dimensions since the feature was created with those
NVSDK_NGX_Result EvaluateDLSS_Internal(VkCommandBuffer cmdBuffer,
    VkImageView color, VkImage colorImg, int colorFormat,
    VkImageView depth, VkImage depthImg, int depthFormat,
    VkImageView mv, VkImage mvImg, int mvFormat,
    VkImageView output, VkImage outputImg, int outputFormat,
    float jitterX, float jitterY) {
// NOTE: This is called from within EvaluateDLSSD which already holds the lock
// DO NOT acquire the lock here!

if (!m_Initialized) return NVSDK_NGX_Result_FAIL_NotInitialized;

// Create feature if pending
if (m_DLSSFeaturePending) {
    NVSDK_NGX_Result res = CreateDLSSFeature(cmdBuffer);
    if (NVSDK_NGX_FAILED(res)) {
        m_DLSSCreationFailures++;
        if (m_DLSSCreationFailures >= 3) {
            m_DLSSFeaturePending = false;
            Log("DLSS Feature creation failed 3 times. Disabling DLSS for this session.");
        }
        return res;
    }
    m_DLSSFeaturePending = false;
}

if (!m_DLSSFeature) return NVSDK_NGX_Result_FAIL_FeatureNotFound;

// CRITICAL FIX: Use DLSSD dimensions if the DLSS feature was created as a fallback from DLSSD
// The fallback DLSS feature uses m_DLSSDRenderWidth/Height dimensions
int renderWidth = m_DLSSRenderWidth;
int renderHeight = m_DLSSRenderHeight;
int outWidth = m_DLSSOutWidth;
int outHeight = m_DLSSOutHeight;

// If DLSSD dimensions are set but standard DLSS dimensions are 0, use DLSSD dimensions
// This happens when the DLSS feature was created as a fallback from DLSSD creation failure
if (renderWidth == 0 || renderHeight == 0) {
    renderWidth = m_DLSSDRenderWidth;
    renderHeight = m_DLSSDRenderHeight;
    outWidth = m_DLSSDOutWidth;
    outHeight = m_DLSSDOutHeight;
    Log("EvaluateDLSS_Internal: Using DLSSD fallback dimensions " + std::to_string(renderWidth) + "x" + std::to_string(renderHeight));
}

// Prepare Resources
VkImageSubresourceRange subRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
VkImageSubresourceRange depthRange = { GetAspectMask((VkFormat)depthFormat), 0, 1, 0, 1 };

NVSDK_NGX_Resource_VK rColor = NVSDK_NGX_Create_ImageView_Resource_VK(color, colorImg, subRange, (VkFormat)colorFormat, renderWidth, renderHeight, false);
NVSDK_NGX_Resource_VK rDepth = NVSDK_NGX_Create_ImageView_Resource_VK(depth, depthImg, depthRange, (VkFormat)depthFormat, renderWidth, renderHeight, false);
NVSDK_NGX_Resource_VK rMV = NVSDK_NGX_Create_ImageView_Resource_VK(mv, mvImg, subRange, (VkFormat)mvFormat, renderWidth, renderHeight, false);
NVSDK_NGX_Resource_VK rOutput = NVSDK_NGX_Create_ImageView_Resource_VK(output, outputImg, subRange, (VkFormat)outputFormat, outWidth, outHeight, true);

NVSDK_NGX_VK_DLSS_Eval_Params evalParams = {};
evalParams.Feature.pInColor = &rColor;
evalParams.Feature.pInOutput = &rOutput;
evalParams.pInDepth = &rDepth;
evalParams.pInMotionVectors = &rMV;
evalParams.InJitterOffsetX = jitterX;
evalParams.InJitterOffsetY = jitterY;
evalParams.InRenderSubrectDimensions = { (unsigned int)renderWidth, (unsigned int)renderHeight };
// FIX: MVs are in pixel space, so scale must be 1.0 (not renderDims which would double-scale them)
evalParams.InMVScaleX = 1.0f;
evalParams.InMVScaleY = 1.0f;

NVSDK_NGX_Result res = NGX_VULKAN_EVALUATE_DLSS_EXT(cmdBuffer, m_DLSSFeature, m_Parameters, &evalParams);
if (NVSDK_NGX_FAILED(res)) {
    Log("EvaluateDLSS failed: " + std::string(getNGXErrorString(res)));
    return res;
}
Log("EvaluateDLSS succeeded");
return NVSDK_NGX_Result_Success;
}

    // Public version that acquires the lock
    NVSDK_NGX_Result EvaluateDLSS(VkCommandBuffer cmdBuffer,
        VkImageView color, VkImage colorImg, int colorFormat,
        VkImageView depth, VkImage depthImg, int depthFormat,
        VkImageView mv, VkImage mvImg, int mvFormat,
        VkImageView output, VkImage outputImg, int outputFormat,
        float jitterX, float jitterY) {
        std::lock_guard<std::mutex> lock(m_Mutex);
        return EvaluateDLSS_Internal(cmdBuffer, color, colorImg, colorFormat,
                                    depth, depthImg, depthFormat,
                                    mv, mvImg, mvFormat,
                                    output, outputImg, outputFormat,
                                    jitterX, jitterY);
    }

    // --- DLSS Ray Reconstruction (DLSSD) ---

    NVSDK_NGX_Result InitDLSSD(int renderWidth, int renderHeight, int outWidth, int outHeight, 
                   int denoiseMode, int roughnessMode, int depthType, int quality) {
        std::lock_guard<std::mutex> lock(m_Mutex);
        if (!m_Initialized) {
            Log("InitDLSSD called before NGX Initialization!");
            return NVSDK_NGX_Result_FAIL_NotInitialized;
        }

        // Check availability
        int rrAvailable = 0;
        NVSDK_NGX_Parameter_GetI(m_Parameters, NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &rrAvailable);
        Log("Ray Reconstruction (DLSSD) availability: " + std::to_string(rrAvailable));
        if (!rrAvailable) {
            Log("Ray Reconstruction (DLSSD) not available on this system.");
            return NVSDK_NGX_Result_FAIL_FeatureNotSupported;
        }

        if (m_DLSSDFeature) {
            NVSDK_NGX_VULKAN_ReleaseFeature(m_DLSSDFeature);
            m_DLSSDFeature = nullptr;
        }

        m_DLSSDRenderWidth = renderWidth;
        m_DLSSDRenderHeight = renderHeight;
        m_DLSSDOutWidth = outWidth;
        m_DLSSDOutHeight = outHeight;
        m_DLSSDDenoiseMode = (NVSDK_NGX_DLSS_Denoise_Mode)denoiseMode;
        m_DLSSDRoughnessMode = (NVSDK_NGX_DLSS_Roughness_Mode)roughnessMode;
        m_DLSSDDepthType = (NVSDK_NGX_DLSS_Depth_Type)depthType;
        m_DLSSDQuality = MapQuality(quality);

        m_DLSSDFeaturePending = true;
        m_DLSSDCreationFailures = 0;
        Log("DLSSD Init configured. Feature creation deferred.");
        return NVSDK_NGX_Result_Success;
    }

    void DestroyDLSSD() {
        std::lock_guard<std::mutex> lock(m_Mutex);
        if (m_DLSSDFeature) {
            NVSDK_NGX_VULKAN_ReleaseFeature(m_DLSSDFeature);
            m_DLSSDFeature = nullptr;
        }
        m_DLSSDFeaturePending = false;
    }

    NVSDK_NGX_Result EvaluateDLSSD(VkCommandBuffer cmdBuffer,
        VkImageView color, VkImage colorImg, int colorFormat,
        VkImageView depth, VkImage depthImg, int depthFormat,
        VkImageView mv, VkImage mvImg, int mvFormat,
        VkImageView diffAlb, VkImage diffAlbImg, int diffAlbFormat,
        VkImageView specAlb, VkImage specAlbImg, int specAlbFormat,
        VkImageView normals, VkImage normalsImg, int normalsFormat,
        VkImageView rough, VkImage roughImg, int roughFormat,
        VkImageView output, VkImage outputImg, int outputFormat,
        float jitterX, float jitterY, int reset, float dt) {

        std::lock_guard<std::mutex> lock(m_Mutex);
        if (!m_Initialized) return NVSDK_NGX_Result_FAIL_NotInitialized;

        // DIAGNOSTIC: Throttle logging to every 300 frames to avoid huge log files
        static int dlssdEvalCount = 0;
        dlssdEvalCount++;
        bool shouldLog = (dlssdEvalCount % 300 == 1) || reset;
        if (shouldLog) {
            Log("=== EvaluateDLSSD Diagnostics (frame " + std::to_string(dlssdEvalCount) + ") ===");
            Log("Render dims: " + std::to_string(m_DLSSDRenderWidth) + "x" + std::to_string(m_DLSSDRenderHeight));
            Log("Jitter: (" + std::to_string(jitterX) + ", " + std::to_string(jitterY) + ")");
            Log("Reset: " + std::to_string(reset) + ", dt: " + std::to_string(dt) + "ms");
        }
        
        // VALIDATE: Check for null handles
        if (color == 0 || colorImg == 0) {
            Log("ERROR: Color image/view is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (depth == 0 || depthImg == 0) {
            Log("ERROR: Depth image/view is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (mv == 0 || mvImg == 0) {
            Log("ERROR: Motion vector image/view is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (diffAlb == 0 || diffAlbImg == 0) {
            Log("ERROR: Diffuse albedo image/view is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (specAlb == 0 || specAlbImg == 0) {
            Log("ERROR: Specular albedo image/view is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (normals == 0 || normalsImg == 0) {
            Log("ERROR: Normals image/view is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (output == 0 || outputImg == 0) {
            Log("ERROR: Output image/view is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }

        if (m_DLSSDFeaturePending) {
            Log("Creating DLSSD feature (pending)...");
            NVSDK_NGX_Result res = CreateDLSSDFeature(cmdBuffer);
            if (NVSDK_NGX_FAILED(res)) {
                m_DLSSDCreationFailures++;
                if (m_DLSSDCreationFailures >= 3) {
                    m_DLSSDFeaturePending = false;
                    Log("DLSSD Feature creation failed 3 times. Disabling DLSSD for this session.");
                }
                return res;
            }
            m_DLSSDFeaturePending = false;
            Log("DLSSD feature created successfully, forcing reset=1 for first frame");
            reset = 1; // FORCE reset on first frame after creation
        }
        
        // CRITICAL FIX: If DLSSD feature is not available but standard DLSS is, use that instead
        // This handles the case where CreateDLSSDFeature fell back to standard DLSS
        if (!m_DLSSDFeature) {
            if (m_DLSSFeature) {
                Log("DLSSD feature not available, using standard DLSS fallback for evaluation");
                // Use standard DLSS evaluation with available inputs
                // Note: Standard DLSS only uses color, depth, motion vectors, and output
                // IMPORTANT: Use EvaluateDLSS_Internal to avoid deadlock (we already hold the lock)
                return EvaluateDLSS_Internal(cmdBuffer, color, colorImg, colorFormat,
                                   depth, depthImg, depthFormat,
                                   mv, mvImg, mvFormat,
                                   output, outputImg, outputFormat,
                                   jitterX, jitterY);
            }
            return NVSDK_NGX_Result_FAIL_FeatureNotFound;
        }

        // Prepare Resources
        // NOTE: DLSSD requires all input resources to have the same dimensions as the render resolution.
        // The G-buffer images from Iris may have different dimensions, so we use the stored render dimensions.
        
        // CRITICAL: Ensure we use the ALIGNED dimensions for creating resource views
        // If we use the raw image dimensions from Java (e.g. 1009), NGX will crash or return InvalidParameter
        // because they don't match the feature dimensions (1008).
        
        VkImageSubresourceRange subRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        VkImageSubresourceRange depthRange = { GetAspectMask((VkFormat)depthFormat), 0, 1, 0, 1 };
    
        // Log diagnostic if image dimensions seem off
        // Log("Eval dimensions: Render=" + std::to_string(m_DLSSDRenderWidth) + "x" + std::to_string(m_DLSSDRenderHeight));
    
        NVSDK_NGX_Resource_VK rColor = NVSDK_NGX_Create_ImageView_Resource_VK(color, colorImg, subRange, (VkFormat)colorFormat, m_DLSSDRenderWidth, m_DLSSDRenderHeight, false);
        NVSDK_NGX_Resource_VK rDepth = NVSDK_NGX_Create_ImageView_Resource_VK(depth, depthImg, depthRange, (VkFormat)depthFormat, m_DLSSDRenderWidth, m_DLSSDRenderHeight, false);
        NVSDK_NGX_Resource_VK rMV = NVSDK_NGX_Create_ImageView_Resource_VK(mv, mvImg, subRange, (VkFormat)mvFormat, m_DLSSDRenderWidth, m_DLSSDRenderHeight, false);
        NVSDK_NGX_Resource_VK rDiffAlb = NVSDK_NGX_Create_ImageView_Resource_VK(diffAlb, diffAlbImg, subRange, (VkFormat)diffAlbFormat, m_DLSSDRenderWidth, m_DLSSDRenderHeight, false);
        NVSDK_NGX_Resource_VK rSpecAlb = NVSDK_NGX_Create_ImageView_Resource_VK(specAlb, specAlbImg, subRange, (VkFormat)specAlbFormat, m_DLSSDRenderWidth, m_DLSSDRenderHeight, false);
        NVSDK_NGX_Resource_VK rNormals = NVSDK_NGX_Create_ImageView_Resource_VK(normals, normalsImg, subRange, (VkFormat)normalsFormat, m_DLSSDRenderWidth, m_DLSSDRenderHeight, false);
        
        // Output might be different size if upscaling, use Output dimensions
        NVSDK_NGX_Resource_VK rOutput = NVSDK_NGX_Create_ImageView_Resource_VK(output, outputImg, subRange, (VkFormat)outputFormat, m_DLSSDOutWidth, m_DLSSDOutHeight, true);

        NVSDK_NGX_Resource_VK* pRough = nullptr;
        NVSDK_NGX_Resource_VK rRough = {};
        if (rough && m_DLSSDRoughnessMode == NVSDK_NGX_DLSS_Roughness_Mode_Unpacked) {
            rRough = NVSDK_NGX_Create_ImageView_Resource_VK(rough, roughImg, subRange, (VkFormat)roughFormat, m_DLSSDRenderWidth, m_DLSSDRenderHeight, false);
            pRough = &rRough;
        }

        // CRITICAL: Initialize all DLSSD evaluate parameters properly
        // DLSSD requires ALL of these inputs for Ray Reconstruction to work correctly
        NVSDK_NGX_VK_DLSSD_Eval_Params evalParams = {};
        
        // === REQUIRED INPUTS ===
        // Noisy ray-traced color at render resolution
        evalParams.pInColor = &rColor;
        // Linear depth (length(worldPos) for ray-traced, or HW depth)
        evalParams.pInDepth = &rDepth;
        // Pixel-space motion vectors (2D offsets from prev frame to current)
        evalParams.pInMotionVectors = &rMV;
        // Output image at display resolution
        evalParams.pInOutput = &rOutput;
        // Diffuse albedo from G-buffer (for ray reconstruction denoising)
        evalParams.pInDiffuseAlbedo = &rDiffAlb;
        // Specular albedo from G-buffer (for ray reconstruction denoising)
        evalParams.pInSpecularAlbedo = &rSpecAlb;
        // World-space normals from G-buffer
        evalParams.pInNormals = &rNormals;
        // Roughness (optional if packed in normals.w)
        evalParams.pInRoughness = pRough;
        
        // === JITTER PARAMETERS ===
        // Sub-pixel jitter offsets in render pixel space
        // These should match the jitter used during ray tracing
        evalParams.InJitterOffsetX = jitterX;
        evalParams.InJitterOffsetY = jitterY;
        
        // === RESET FLAG ===
        // Set to 1 when scene changes completely (new level, camera cut, etc.)
        // This tells DLSSD to reset its temporal history
        evalParams.InReset = reset;
        
        // === MOTION VECTOR SCALING ===
        // CRITICAL: MVs are in PIXEL SPACE (not normalized [0,1])
        // Scale = 1.0 means MVs are already in pixel units
        // If MVs were normalized [0,1], scale would need to be render dimensions
        evalParams.InMVScaleX = 1.0f;
        evalParams.InMVScaleY = 1.0f;
        
        // === TIMING ===
        // Frame time delta in milliseconds - helps DLSSD determine denoising strength
        // based on object velocity from motion vectors
        evalParams.InFrameTimeDeltaInMsec = dt;
        
        // === RENDER SUBRECT ===
        // Dimensions of the render region (usually full render resolution)
        evalParams.InRenderSubrectDimensions = { (unsigned int)m_DLSSDRenderWidth, (unsigned int)m_DLSSDRenderHeight };
        
        // === OPTIONAL INPUTS (set to nullptr/0 if not used) ===
        // These are for advanced use cases and are not required for basic RR
        evalParams.pInTransparencyMask = nullptr;
        evalParams.pInExposureTexture = nullptr;
        evalParams.pInBiasCurrentColorMask = nullptr;
        evalParams.pInMotionVectors3D = nullptr;
        evalParams.pInIsParticleMask = nullptr;
        evalParams.pInAnimatedTextureMask = nullptr;
        evalParams.pInDepthHighRes = nullptr;
        evalParams.pInPositionViewSpace = nullptr;
        evalParams.pInRayTracingHitDistance = nullptr;
        evalParams.pInMotionVectorsReflections = nullptr;
        
        // === PRE-EXPOSURE (for HDR) ===
        // Set to 1.0 if not using manual exposure control
        evalParams.InPreExposure = 1.0f;
        evalParams.InExposureScale = 1.0f;
        
        // Log detailed info on first frame or when reset
        if (shouldLog || reset) {
            Log("=== DLSSD Evaluate Parameters ===");
            Log("  Jitter: (" + std::to_string(jitterX) + ", " + std::to_string(jitterY) + ")");
            Log("  Reset: " + std::to_string(reset));
            Log("  FrameTime: " + std::to_string(dt) + "ms");
            Log("  MVScale: (1.0, 1.0) - pixel space");
            Log("  RenderDims: " + std::to_string(m_DLSSDRenderWidth) + "x" + std::to_string(m_DLSSDRenderHeight));
            Log("  OutputDims: " + std::to_string(m_DLSSDOutWidth) + "x" + std::to_string(m_DLSSDOutHeight));
            Log("  RoughnessMode: " + std::string(m_DLSSDRoughnessMode == NVSDK_NGX_DLSS_Roughness_Mode_Packed ? "Packed" : "Unpacked"));
        }

        NVSDK_NGX_Result res = NGX_VULKAN_EVALUATE_DLSSD_EXT(cmdBuffer, m_DLSSDFeature, m_Parameters, &evalParams);
        if (NVSDK_NGX_FAILED(res)) {
            Log("EvaluateDLSSD FAILED: " + std::string(getNGXErrorString(res)) + " (code: " + std::to_string(res) + ")");
            // Log additional context for debugging
            Log("  This error occurred during DLSSD evaluation.");
            Log("  Check that all required G-buffer inputs are valid and correctly formatted.");
            return res;
        }
        if (shouldLog) {
            Log("EvaluateDLSSD succeeded (frame " + std::to_string(dlssdEvalCount) + ")");
        }
        return NVSDK_NGX_Result_Success;
    }

    void GetOptimalSettings(int width, int height, int quality, int* renderW, int* renderH) {
        // Try to use NGX if initialized
        bool usedNGX = false;
        if (m_Initialized && m_Parameters) {
            unsigned int optW, optH, maxW, maxH, minW, minH;
            float sharpness;
            NVSDK_NGX_Result res = NGX_DLSS_GET_OPTIMAL_SETTINGS(
                m_Parameters, width, height, MapQuality(quality),
                &optW, &optH, &maxW, &maxH, &minW, &minH, &sharpness);
            
            if (NVSDK_NGX_SUCCEED(res) && optW > 0 && optH > 0) {
                *renderW = (int)optW;
                *renderH = (int)optH;
                usedNGX = true;
            }
        }

        if (!usedNGX) {
            // Fallback to standard scaling
            float scale = 0.5f; // Performance default
            switch (quality) {
                case 0: scale = 1.0f; break;   // Native/DLAA
                case 1: scale = 0.6666667f; break; // Quality
                case 2: scale = 0.5833334f; break; // Balanced
                case 3: scale = 0.5f; break;       // Performance
                case 4: scale = 0.3333333f; break; // Ultra Performance
                default: scale = 0.5f; break;
            }
            *renderW = (int)(width * scale);
            *renderH = (int)(height * scale);
        }
    }

    bool IsDLSSDAvailable() {
        if (!m_Initialized || !m_Parameters) return false;
        int available = 0;
        NVSDK_NGX_Parameter_GetI(m_Parameters, NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &available);
        return (available != 0);
    }

private:
    DLSSBridge() = default;
    ~DLSSBridge() { ShutdownNGX_Internal(); }

    void ShutdownNGX_Internal() {
        if (m_DLSSFeature) {
            NVSDK_NGX_VULKAN_ReleaseFeature(m_DLSSFeature);
            m_DLSSFeature = nullptr;
        }
        if (m_DLSSDFeature) {
            NVSDK_NGX_VULKAN_ReleaseFeature(m_DLSSDFeature);
            m_DLSSDFeature = nullptr;
        }
        if (m_Parameters) {
            NVSDK_NGX_VULKAN_DestroyParameters(m_Parameters);
            m_Parameters = nullptr;
        }
        if (m_Initialized && m_Device) {
            NVSDK_NGX_VULKAN_Shutdown1(m_Device);
            m_Initialized = false;
            m_Instance = VK_NULL_HANDLE;
            m_PhysicalDevice = VK_NULL_HANDLE;
            m_Device = VK_NULL_HANDLE;
        }
    }

    NVSDK_NGX_PerfQuality_Value MapQuality(int q) {
        switch (q) {
            case 0: return NVSDK_NGX_PerfQuality_Value_DLAA;             // SDK: 5
            case 1: return NVSDK_NGX_PerfQuality_Value_MaxQuality;       // SDK: 2
            case 2: return NVSDK_NGX_PerfQuality_Value_Balanced;         // SDK: 1
            case 3: return NVSDK_NGX_PerfQuality_Value_MaxPerf;           // SDK: 0
            case 4: return NVSDK_NGX_PerfQuality_Value_UltraPerformance;  // SDK: 3
            default: return NVSDK_NGX_PerfQuality_Value_Balanced;
        }
    }

    NVSDK_NGX_Result CreateDLSSFeature(VkCommandBuffer cmdBuffer) {
        NVSDK_NGX_DLSS_Create_Params createParams = {};
        createParams.Feature.InWidth = m_DLSSRenderWidth;
        createParams.Feature.InHeight = m_DLSSRenderHeight;
        createParams.Feature.InTargetWidth = m_DLSSOutWidth;
        createParams.Feature.InTargetHeight = m_DLSSOutHeight;
        createParams.Feature.InPerfQualityValue = m_DLSSQuality;
        
        // FIX: Remove DepthInverted flag - OpenGL/Minecraft depth is NOT inverted
        // (0.0 = near, 1.0 = far)
        // Also remove MVLowRes - motion vectors are at native resolution
        // Keep IsHDR if input is HDR
        // IMPORTANT: DLSS requires IsHDR flag if the input format is floating point (R16F/R32F)
        createParams.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_IsHDR | 
                                            NVSDK_NGX_DLSS_Feature_Flags_DoSharpening;
        // createParams.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_None; // Start with minimal flags to isolate errors

        std::string dimLog = "Creating DLSS Feature: In(" + std::to_string(m_DLSSRenderWidth) + "x" + std::to_string(m_DLSSRenderHeight) + ") Out(" + std::to_string(m_DLSSOutWidth) + "x" + std::to_string(m_DLSSOutHeight) + ")";
        Log(dimLog);
        std::cout << "[DLSS Bridge] " << dimLog << std::endl; // Ensure console output

        // Validate dimensions
        if (m_DLSSRenderWidth == 0 || m_DLSSRenderHeight == 0 || m_DLSSOutWidth == 0 || m_DLSSOutHeight == 0) {
            Log("Invalid DLSS dimensions (0 detected). Aborting creation.");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }

        // IMPORTANT: We must pass the correct AppId if we initialized with ProjectID.
        // The SDK documentation suggests that for Init_with_ProjectID, feature creation might need specific handling or
        // that the ProjectID is sufficient. However, bad00007 (NotInitialized) during CreateFeature often means
        // the internal state of NGX doesn't match what CreateFeature expects (e.g. mismatched AppID/ProjectID context).
        // 
        // Since we used NVSDK_NGX_VULKAN_Init_with_ProjectID, we should verify if we need to pass the ProjectID structure
        // to CreateFeature, but the standard API doesn't take it.
        //
        // Another common cause for bad00007 is calling CreateFeature on a thread/context where NGX wasn't initialized,
        // OR if the initialization actually failed silently or was incomplete (e.g. missing feature permission).
        
        // Let's try to re-ensure initialization state or check parameters.
        if (!m_Parameters) {
             Log("CreateDLSSFeature: m_Parameters is null! This should not happen if Init succeeded.");
             return NVSDK_NGX_Result_FAIL_NotInitialized;
        }

        NVSDK_NGX_Result res = NGX_VULKAN_CREATE_DLSS_EXT1(m_Device, cmdBuffer, 1, 1, &m_DLSSFeature, m_Parameters, &createParams);
        if (NVSDK_NGX_FAILED(res)) {
            Log("Failed to create DLSS Feature: " + std::string(getNGXErrorString(res)) + " Code: " + std::to_string(res));
            return res;
        }
        Log("DLSS Feature Created Successfully.");
        return NVSDK_NGX_Result_Success;
    }

    NVSDK_NGX_Result CreateDLSSDFeature(VkCommandBuffer cmdBuffer) {
        Log("=== CreateDLSSDFeature ===");
        
        // Validate dimensions before creating
        if (m_DLSSDRenderWidth == 0 || m_DLSSDRenderHeight == 0 ||
            m_DLSSDOutWidth == 0 || m_DLSSDOutHeight == 0) {
            Log("ERROR: Invalid DLSSD dimensions (0 detected). Aborting creation.");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        
        // Ensure dimensions are even (DLSS requirement)
        if (m_DLSSDRenderWidth % 2 != 0 || m_DLSSDRenderHeight % 2 != 0 ||
            m_DLSSDOutWidth % 2 != 0 || m_DLSSDOutHeight % 2 != 0) {
            Log("WARNING: Dimensions are not even. This may cause issues.");
        }
        
        if (!m_Parameters) {
            Log("ERROR: m_Parameters is NULL during DLSSD Creation!");
            return NVSDK_NGX_Result_FAIL_NotInitialized;
        }
        
        // Check DLSSD availability before creating
        int rrAvailable = 0;
        NVSDK_NGX_Parameter_GetI(m_Parameters, NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &rrAvailable);
        if (!rrAvailable) {
            Log("ERROR: Ray Reconstruction (DLSSD) is NOT available on this system!");
            Log("Cannot create DLSSD feature. Falling back to standard DLSS may be required.");
            return NVSDK_NGX_Result_FAIL_FeatureNotSupported;
        }
        
        NVSDK_NGX_DLSSD_Create_Params createParams = {};
        createParams.InWidth = m_DLSSDRenderWidth;
        createParams.InHeight = m_DLSSDRenderHeight;
        createParams.InTargetWidth = m_DLSSDOutWidth;
        createParams.InTargetHeight = m_DLSSDOutHeight;
        createParams.InDenoiseMode = m_DLSSDDenoiseMode;
        createParams.InRoughnessMode = m_DLSSDRoughnessMode;

        // Use depth type provided during initialization
        // DEPTH_TYPE_LINEAR (0) = length(worldPos) from raytracer
        // DEPTH_TYPE_HW (1) = hardware depth buffer
        createParams.InUseHWDepth = m_DLSSDDepthType;
        createParams.InPerfQualityValue = m_DLSSDQuality;

        // CRITICAL: Feature flags for Ray Reconstruction
        // - IsHDR: Required for floating-point input formats (R16F/R32F) which we use for HDR rendering
        // - DoSharpening: Enable DLSS sharpening for better image quality
        // - NOTE: Do NOT use MVLowRes since our motion vectors are at full render resolution
        // - NOTE: Do NOT use DepthInverted since our depth is standard (0=near, 1=far for HW, or linear distance)
        createParams.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_IsHDR |
            NVSDK_NGX_DLSS_Feature_Flags_DoSharpening;
        
        // Output subrects disabled (we use full image)
        createParams.InEnableOutputSubrects = false;

        Log("Creating DLSSD Feature:");
        Log("  - Input Resolution: " + std::to_string(m_DLSSDRenderWidth) + "x" + std::to_string(m_DLSSDRenderHeight));
        Log("  - Output Resolution: " + std::to_string(m_DLSSDOutWidth) + "x" + std::to_string(m_DLSSDOutHeight));
        Log("  - DepthType: " + std::string(m_DLSSDDepthType == NVSDK_NGX_DLSS_Depth_Type_Linear ? "LINEAR (ray-traced)" : "HW (depth buffer)"));
        Log("  - DenoiseMode: " + std::string(m_DLSSDDenoiseMode == NVSDK_NGX_DLSS_Denoise_Mode_DLUnified ? "DLUnified" : "Off"));
        Log("  - RoughnessMode: " + std::string(m_DLSSDRoughnessMode == NVSDK_NGX_DLSS_Roughness_Mode_Packed ? "Packed (in normals.w)" : "Unpacked (separate texture)"));
        Log("  - Quality: " + std::to_string((int)m_DLSSDQuality));
        Log("  - Flags: 0x" + std::to_string(createParams.InFeatureCreateFlags) + " (IsHDR=1, DoSharpening=1)");

        NVSDK_NGX_Result res = NGX_VULKAN_CREATE_DLSSD_EXT1(m_Device, cmdBuffer, 1, 1, &m_DLSSDFeature, m_Parameters, &createParams);
        if (NVSDK_NGX_FAILED(res)) {
            Log("FAILED to create DLSSD Feature: " + std::string(getNGXErrorString(res)) + " (code: " + std::to_string(res) + ")");
            Log("Attempting fallback to standard DLSS Super Resolution...");
            
            // FALLBACK: Try creating standard DLSS feature instead
            NVSDK_NGX_DLSS_Create_Params dlssParams = {};
            dlssParams.Feature.InWidth = m_DLSSDRenderWidth;
            dlssParams.Feature.InHeight = m_DLSSDRenderHeight;
            dlssParams.Feature.InTargetWidth = m_DLSSDOutWidth;
            dlssParams.Feature.InTargetHeight = m_DLSSDOutHeight;
            dlssParams.Feature.InPerfQualityValue = m_DLSSDQuality;
            dlssParams.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_IsHDR |
                NVSDK_NGX_DLSS_Feature_Flags_DoSharpening;
            
            NVSDK_NGX_Result fallbackRes = NGX_VULKAN_CREATE_DLSS_EXT1(m_Device, cmdBuffer, 1, 1, &m_DLSSFeature, m_Parameters, &dlssParams);
            if (NVSDK_NGX_SUCCEED(fallbackRes)) {
                Log("FALLBACK SUCCESS: Created standard DLSS SR feature instead of DLSSD.");
                Log("Note: Ray Reconstruction will not be available, but upscaling will work.");
                m_DLSSDFeaturePending = false;  // Clear DLSSD pending flag
                m_DLSSFeaturePending = false;   // DLSS feature is now active
                return NVSDK_NGX_Result_Success;
            } else {
                Log("FALLBACK FAILED: Could not create standard DLSS either: " + std::string(getNGXErrorString(fallbackRes)));
            }
            return res;
        }
        Log("DLSSD Feature Created Successfully!");
        return NVSDK_NGX_Result_Success;
    }

    std::mutex m_Mutex;
    bool m_Initialized = false;
    VkInstance m_Instance = VK_NULL_HANDLE;
    VkPhysicalDevice m_PhysicalDevice = VK_NULL_HANDLE;
    VkDevice m_Device = VK_NULL_HANDLE;
    NVSDK_NGX_Parameter* m_Parameters = nullptr;
    std::string m_DLSSPath;

    // DLSS State
    NVSDK_NGX_Handle* m_DLSSFeature = nullptr;
    bool m_DLSSFeaturePending = false;
    int m_DLSSCreationFailures = 0;
    int m_DLSSRenderWidth = 0;
    int m_DLSSRenderHeight = 0;
    int m_DLSSOutWidth = 0;
    int m_DLSSOutHeight = 0;
    NVSDK_NGX_PerfQuality_Value m_DLSSQuality = NVSDK_NGX_PerfQuality_Value_Balanced;

    // DLSSD State
    NVSDK_NGX_Handle* m_DLSSDFeature = nullptr;
    bool m_DLSSDFeaturePending = false;
    int m_DLSSDCreationFailures = 0;
    int m_DLSSDRenderWidth = 0;
    int m_DLSSDRenderHeight = 0;
    int m_DLSSDOutWidth = 0;
    int m_DLSSDOutHeight = 0;
    NVSDK_NGX_DLSS_Denoise_Mode m_DLSSDDenoiseMode = NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
    NVSDK_NGX_DLSS_Roughness_Mode m_DLSSDRoughnessMode = NVSDK_NGX_DLSS_Roughness_Mode_Packed;
    // FIX: Default to Linear depth (raytraced output is linear depth)
    NVSDK_NGX_DLSS_Depth_Type m_DLSSDDepthType = NVSDK_NGX_DLSS_Depth_Type_Linear;
    NVSDK_NGX_PerfQuality_Value m_DLSSDQuality = NVSDK_NGX_PerfQuality_Value_Balanced;
};

// --- C Interface ---

extern "C" {

__declspec(dllexport) int initializeNGX(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device, const char* dlssPath) {
    return (int)DLSSBridge::Get().InitializeNGX(instance, physicalDevice, device, dlssPath);
}

__declspec(dllexport) int initDLSS(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device, int width, int height, int outWidth, int outHeight) {
    if (NVSDK_NGX_FAILED(DLSSBridge::Get().InitializeNGX(instance, physicalDevice, device, nullptr))) return (int)NVSDK_NGX_Result_FAIL_NotInitialized;
    
    // Determine quality based on resolution ratio if not provided explicitly.
    float ratio = (float)width / (float)outWidth;
    NVSDK_NGX_PerfQuality_Value quality = NVSDK_NGX_PerfQuality_Value_Balanced;
    if (ratio > 0.99f) quality = NVSDK_NGX_PerfQuality_Value_DLAA;
    else if (ratio > 0.66f) quality = NVSDK_NGX_PerfQuality_Value_MaxQuality;
    else if (ratio > 0.58f) quality = NVSDK_NGX_PerfQuality_Value_Balanced;
    else if (ratio > 0.49f) quality = NVSDK_NGX_PerfQuality_Value_MaxPerf;
    else quality = NVSDK_NGX_PerfQuality_Value_UltraPerformance;

    return (int)DLSSBridge::Get().InitDLSS(width, height, outWidth, outHeight, quality);
}

__declspec(dllexport) void destroyDLSS(VkDevice device) {
    DLSSBridge::Get().DestroyDLSS();
}

__declspec(dllexport) int evaluateDLSS(VkCommandBuffer cmdBuffer, 
    VkImageView colorImageView, VkImage colorImage, int colorFormat,
    VkImageView depthImageView, VkImage depthImage, int depthFormat,
    VkImageView mvImageView, VkImage mvImage, int mvFormat,
    VkImageView outputImageView, VkImage outputImage, int outputFormat,
    float jitterX, float jitterY) {
    return (int)DLSSBridge::Get().EvaluateDLSS(cmdBuffer, 
        colorImageView, colorImage, colorFormat,
        depthImageView, depthImage, depthFormat,
        mvImageView, mvImage, mvFormat,
        outputImageView, outputImage, outputFormat,
        jitterX, jitterY);
}

__declspec(dllexport) int initDLSSD(
  VkInstance instance,
  VkPhysicalDevice physicalDevice,
  VkDevice device,
  int width, int height,
  int outWidth, int outHeight,
  int denoiseMode,
  int roughnessMode,
  int depthType,
  int perfQualityValue) {
    NVSDK_NGX_Result res = DLSSBridge::Get().InitializeNGX(instance, physicalDevice, device, nullptr);
    if (NVSDK_NGX_FAILED(res)) return (int)res;
    return (int)DLSSBridge::Get().InitDLSSD(width, height, outWidth, outHeight, denoiseMode, roughnessMode, depthType, perfQualityValue);
}

__declspec(dllexport) void destroyDLSSD(VkDevice device) {
    DLSSBridge::Get().DestroyDLSSD();
}

__declspec(dllexport) int evaluateDLSSD(
    VkCommandBuffer cmdBuffer,
    VkImageView colorImageView, VkImage colorImage, int colorFormat,
    VkImageView depthImageView, VkImage depthImage, int depthFormat,
    VkImageView mvImageView, VkImage mvImage, int mvFormat,
    VkImageView diffuseAlbedoImageView, VkImage diffuseAlbedoImage, int diffuseAlbedoFormat,
    VkImageView specularAlbedoImageView, VkImage specularAlbedoImage, int specularAlbedoFormat,
    VkImageView normalsImageView, VkImage normalsImage, int normalsFormat,
    VkImageView roughnessImageView, VkImage roughnessImage, int roughnessFormat,
    VkImageView outputImageView, VkImage outputImage, int outputFormat,
    float jitterX, float jitterY,
    int reset, float frameTimeDeltaMs) {
    return (int)DLSSBridge::Get().EvaluateDLSSD(cmdBuffer,
        colorImageView, colorImage, colorFormat,
        depthImageView, depthImage, depthFormat,
        mvImageView, mvImage, mvFormat,
        diffuseAlbedoImageView, diffuseAlbedoImage, diffuseAlbedoFormat,
        specularAlbedoImageView, specularAlbedoImage, specularAlbedoFormat,
        normalsImageView, normalsImage, normalsFormat,
        roughnessImageView, roughnessImage, roughnessFormat,
        outputImageView, outputImage, outputFormat,
        jitterX, jitterY, reset, frameTimeDeltaMs);
}

__declspec(dllexport) int isDLSSDAvailable() {
    return DLSSBridge::Get().IsDLSSDAvailable() ? 1 : 0;
}

__declspec(dllexport) void getDLSSDRenderResolution(
    int outWidth, int outHeight,
    int qualityPreset,
    int* outRenderWidth, int* outRenderHeight) {
    DLSSBridge::Get().GetOptimalSettings(outWidth, outHeight, qualityPreset, outRenderWidth, outRenderHeight);
}

// Helper to tell NVIDIA where the DLLs are before starting
// Updated to use consistent project ID with initialization
NVSDK_NGX_FeatureDiscoveryInfo GetFeatureDiscoveryInfo() {
    static const wchar_t* paths[] = { L"." };
    static NVSDK_NGX_PathListInfo pathListInfo = { paths, 1 };
    static NVSDK_NGX_FeatureCommonInfo featureInfo = {};
    featureInfo.PathListInfo = pathListInfo;

    static NVSDK_NGX_FeatureDiscoveryInfo discoveryInfo = {};
    discoveryInfo.SDKVersion = NVSDK_NGX_Version_API;
    // Use RayReconstruction feature ID for DLSSD capability queries
    discoveryInfo.FeatureID = NVSDK_NGX_Feature_RayReconstruction;
    discoveryInfo.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
    discoveryInfo.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
    discoveryInfo.Identifier.v.ProjectDesc.EngineVersion = "1.0.0";
    // Use consistent project ID with initialization
    discoveryInfo.Identifier.v.ProjectDesc.ProjectId = "vulkanite-raytracing-mod";
    discoveryInfo.ApplicationDataPath = L".";
    discoveryInfo.FeatureInfo = &featureInfo;

    return discoveryInfo;
}

// Extended availability check with detailed info
__declspec(dllexport) int getDLSSDAvailabilityInfo(int* needsDriverUpdate, int* minDriverMajor, int* minDriverMinor) {
    if (!DLSSBridge::Get().IsDLSSDAvailable()) {
        if (needsDriverUpdate) *needsDriverUpdate = 0;
        if (minDriverMajor) *minDriverMajor = 0;
        if (minDriverMinor) *minDriverMinor = 0;
        return 0; // Not available
    }
    
    // Get additional info from NGX parameters
    NVSDK_NGX_Parameter* params = nullptr;
    // Note: We can't easily access m_Parameters from here, so we return basic info
    if (needsDriverUpdate) *needsDriverUpdate = 0;
    if (minDriverMajor) *minDriverMajor = 0;
    if (minDriverMinor) *minDriverMinor = 0;
    
    return 1; // Available
}

// Get detailed NGX error description
__declspec(dllexport) const char* getNGXErrorDescription(int errorCode) {
    return getNGXErrorString((NVSDK_NGX_Result)errorCode);
}

__declspec(dllexport) uint32_t getNGXInstanceExtensionCount() { 
    uint32_t count = 0; 
    VkExtensionProperties* props = nullptr; 
    NVSDK_NGX_FeatureDiscoveryInfo featureInfo = GetFeatureDiscoveryInfo(); 
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&featureInfo, &count, &props))) { 
        return count; 
    } 
    return 0; 
} 

__declspec(dllexport) const char* getNGXInstanceExtension(uint32_t index) { 
    uint32_t count = 0; 
    VkExtensionProperties* props = nullptr; 
    NVSDK_NGX_FeatureDiscoveryInfo featureInfo = GetFeatureDiscoveryInfo(); 
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&featureInfo, &count, &props))) { 
        if (index < count && props != nullptr) return props[index].extensionName; 
    } 
    return nullptr; 
} 

__declspec(dllexport) uint32_t getNGXDeviceExtensionCount(VkInstance instance, VkPhysicalDevice physicalDevice) { 
    uint32_t count = 0; 
    VkExtensionProperties* props = nullptr; 
    NVSDK_NGX_FeatureDiscoveryInfo featureInfo = GetFeatureDiscoveryInfo(); 
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(instance, physicalDevice, &featureInfo, &count, &props))) { 
        return count; 
    } 
    return 0; 
} 

__declspec(dllexport) const char* getNGXDeviceExtension(VkInstance instance, VkPhysicalDevice physicalDevice, uint32_t index) { 
    uint32_t count = 0; 
    VkExtensionProperties* props = nullptr; 
    NVSDK_NGX_FeatureDiscoveryInfo featureInfo = GetFeatureDiscoveryInfo(); 
    if (NVSDK_NGX_SUCCEED(NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(instance, physicalDevice, &featureInfo, &count, &props))) { 
        if (index < count && props != nullptr) return props[index].extensionName; 
    } 
    return nullptr; 
}

} // extern "C"
