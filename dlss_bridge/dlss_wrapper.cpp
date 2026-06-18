/**
 * @file dlss_wrapper.cpp
 * @brief Modern C++ bridge between Java (JNA) and NVIDIA NGX SDK for DLSS/DLSSD.
 *
 * This file implements a clean, modern C++ interface for DLSS (Deep Learning Super Sampling)
 * and DLSSD (Ray Reconstruction) functionality, wrapping the NVIDIA NGX SDK.
 *
 * @section architecture Architecture
 * - Java Layer: DLSSBridge.java (JNA interface)
 * - Native Layer: This file (Modern C++17 wrapper around NGX SDK)
 * - NVIDIA NGX: Official DLSS/DLSSD SDK
 *
 * @section features Supported Features
 * - Standard DLSS upscaling (Quality, Balanced, Performance, Ultra Performance modes)
 * - DLSSD Ray Reconstruction (AI-powered denoising for ray-traced images)
 * - Automatic jitter validation and clamping
 * - Proper image layout transitions for Vulkan
 * - Thread-safe operations with modern synchronization
 *
 * @section thread_safety Thread Safety
 * All public functions are thread-safe via internal mutex locking.
 * The DLSSBridge class uses a singleton pattern with proper lifecycle management.
 *
 * @see DLSSBridge.java
 * @see DLSSRayReconstruction.java
 * @see DLSSDProcessor.java
 */

// ============================================================================
// INCLUDES
// ============================================================================

#ifndef NV_WINDOWS
#define NV_WINDOWS 1
#endif

#include <vulkan/vulkan.h>

// FIX: Define NOMINMAX before including windows.h to prevent min/max macro conflicts
#ifndef NOMINMAX
#define NOMINMAX
#endif

// FIX: Define WIN32_LEAN_AND_MEAN to reduce Windows header bloat
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <windows.h>

extern "C" IMAGE_DOS_HEADER __ImageBase;

// FIX: Undefine Windows macros that conflict with our enum values
#ifdef ERROR
#undef ERROR
#endif
#ifdef FATAL
#undef FATAL
#endif
#include <iostream>
#include <fstream>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <vector>
#include <map>
#include <atomic>
#include <memory>
#include <optional>
#include <functional>
#include <chrono>
#include <iomanip>
#include <sstream>
#include <cstdint>
#include <algorithm>

// NGX SDK Headers
#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"

// DLSSD (Ray Reconstruction) headers
#include "nvsdk_ngx_defs_dlssd.h"
#include "nvsdk_ngx_params_dlssd.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"

// ============================================================================
// CONSTANTS
// ============================================================================

namespace Constants {
    // Jitter Range: Per NVIDIA DLSS documentation, jitter offsets should be in
    // pixel units with a typical range of [-0.5, 0.5] for sub-pixel jittering
    constexpr float JITTER_RANGE_MIN = -0.5f;
    constexpr float JITTER_RANGE_MAX = 0.5f;

    // Dimension Alignment: DLSS requires dimensions to be aligned to multiples of 8
    // for optimal performance and to avoid visual artifacts
    constexpr int DIMENSION_ALIGNMENT = 8;
    constexpr int DIMENSION_MIN = 8;

    // Motion Vector Scale:
    // Motion vectors are generated at render resolution in pixel space and map
    // the current pixel center to its previous-frame position. Match Radiance's
    // NGX contract: the shader writes pixel-space vectors and NGX consumes them
    // without an additional axis flip.
    constexpr float MV_SCALE_X = 1.0f;
    constexpr float MV_SCALE_Y = 1.0f;

    // NGX ProjectID must be GUID-like for CUSTOM engines or Init_with_ProjectID
    // fails with InvalidParameter. Keep this stable so NGX cache/settings remain stable.
    constexpr unsigned int NGX_APP_ID = 231313132;
    constexpr const char* NGX_PROJECT_ID = "b7f2a618-3df1-4e45-9f13-8a3d7d6216ce";
    constexpr const char* NGX_ENGINE_VERSION = "1.0.0";

    // Feature Flags:
    // - Motion vectors are generated at render resolution, so MVLowRes is part
    //   of the NGX contract for both standard DLSS and DLSS-RR.
    // - DLSS-RR's Vulkan sample uses IsHDR | MVLowRes for feature creation.
    constexpr unsigned int DLSS_FEATURE_FLAGS =
        NVSDK_NGX_DLSS_Feature_Flags_IsHDR |
        NVSDK_NGX_DLSS_Feature_Flags_MVLowRes |
        NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
    constexpr unsigned int DLSSD_FEATURE_FLAGS =
        NVSDK_NGX_DLSS_Feature_Flags_IsHDR |
        NVSDK_NGX_DLSS_Feature_Flags_MVLowRes;

    // Logging Configuration
    constexpr int LOG_THROTTLE_INTERVAL = 300;  // Log every N frames
    constexpr int MAX_CREATION_FAILURES = 3;    // Max failures before disabling feature

    // Pre-exposure values for HDR rendering
    constexpr float PRE_EXPOSURE_DEFAULT = 1.0f;
    constexpr float EXPOSURE_SCALE_DEFAULT = 1.0f;
}

static NVSDK_NGX_FeatureDiscoveryInfo GetFeatureDiscoveryInfo(NVSDK_NGX_Feature feature);

// ============================================================================
// ERROR HANDLING
// ============================================================================

/**
 * @brief Error categories for classification of DLSS operations.
 */
enum class ErrorCategory : uint8_t {
    None = 0,
    Initialization,
    Evaluation,
    Parameter,
    Resource,
    Feature,
    ThreadSafety,
    Internal
};

/**
 * @brief Converts ErrorCategory to string representation.
 */
[[nodiscard]] constexpr const char* to_string(ErrorCategory cat) noexcept {
    switch (cat) {
        case ErrorCategory::None:           return "None";
        case ErrorCategory::Initialization: return "Initialization";
        case ErrorCategory::Evaluation:     return "Evaluation";
        case ErrorCategory::Parameter:      return "Parameter";
        case ErrorCategory::Resource:       return "Resource";
        case ErrorCategory::Feature:        return "Feature";
        case ErrorCategory::ThreadSafety:   return "ThreadSafety";
        case ErrorCategory::Internal:       return "Internal";
        default:                            return "Unknown";
    }
}

/**
 * @brief Rich error information with context.
 */
struct ErrorInfo {
    ErrorCategory category = ErrorCategory::None;
    NVSDK_NGX_Result ngxResult = NVSDK_NGX_Result_Success;
    std::string message;
    std::string context;
    std::string file;
    int line = 0;

    ErrorInfo() = default;

    ErrorInfo(ErrorCategory cat, NVSDK_NGX_Result res, std::string_view msg,
              std::string_view ctx, std::string_view f, int l)
        : category(cat), ngxResult(res), message(msg), context(ctx), file(f), line(l) {}

    [[nodiscard]] bool isOk() const noexcept {
        return category == ErrorCategory::None && ngxResult == NVSDK_NGX_Result_Success;
    }

    [[nodiscard]] std::string format() const {
        std::ostringstream oss;
        oss << "[" << to_string(category) << "] " << message;
        if (ngxResult != NVSDK_NGX_Result_Success) {
            oss << " (NGX: " << getNGXErrorString(ngxResult) << ")";
        }
        if (!file.empty()) {
            oss << " [" << file << ":" << line << "]";
        }
        return oss.str();
    }

    // Public static helper for NGX error strings
    static const char* getNGXErrorString(NVSDK_NGX_Result res) noexcept;
    static std::string formatNGXResult(NVSDK_NGX_Result res);
};

// Forward declaration for error string helper
const char* ErrorInfo::getNGXErrorString(NVSDK_NGX_Result res) noexcept {
    switch (res) {
        case NVSDK_NGX_Result_Success:                   return "Success";
        case NVSDK_NGX_Result_FAIL_FeatureNotSupported:  return "FeatureNotSupported";
        case NVSDK_NGX_Result_FAIL_PlatformError:        return "PlatformError";
        case NVSDK_NGX_Result_FAIL_FeatureAlreadyExists: return "FeatureAlreadyExists";
        case NVSDK_NGX_Result_FAIL_FeatureNotFound:      return "FeatureNotFound";
        case NVSDK_NGX_Result_FAIL_InvalidParameter:     return "InvalidParameter";
        case NVSDK_NGX_Result_FAIL_ScratchBufferTooSmall:return "ScratchBufferTooSmall";
        case NVSDK_NGX_Result_FAIL_NotInitialized:       return "NotInitialized";
        case NVSDK_NGX_Result_FAIL_UnsupportedInputFormat:return "UnsupportedInputFormat";
        case NVSDK_NGX_Result_FAIL_RWFlagMissing:        return "RWFlagMissing";
        case NVSDK_NGX_Result_FAIL_MissingInput:         return "MissingInput";
        case NVSDK_NGX_Result_FAIL_UnableToInitializeFeature: return "UnableToInitializeFeature";
        case NVSDK_NGX_Result_FAIL_OutOfDate:            return "OutOfDate";
        case NVSDK_NGX_Result_FAIL_OutOfGPUMemory:       return "OutOfGPUMemory";
        case NVSDK_NGX_Result_FAIL_UnsupportedFormat:    return "UnsupportedFormat";
        case NVSDK_NGX_Result_FAIL_UnableToWriteToAppDataPath: return "UnableToWriteToAppDataPath";
        case NVSDK_NGX_Result_FAIL_UnsupportedParameter: return "UnsupportedParameter";
        case NVSDK_NGX_Result_FAIL_Denied:               return "Denied";
        case NVSDK_NGX_Result_FAIL_NotImplemented:       return "NotImplemented";
        default:                                         return (res < 0) ? "Failure" : "Unknown";
    }
}

/**
 * @brief Result type for fallible operations.
 * @tparam T The value type on success
 */
template<typename T>
class Result {
public:
    Result(T value) : m_value(std::move(value)), m_error() {}
    Result(ErrorInfo error) : m_error(std::move(error)) {}

    [[nodiscard]] bool isOk() const noexcept { return m_error.isOk(); }
    [[nodiscard]] bool isError() const noexcept { return !isOk(); }

    [[nodiscard]] T& value() & { return m_value; }
    [[nodiscard]] const T& value() const& { return m_value; }
    [[nodiscard]] T&& value() && { return std::move(m_value); }

    [[nodiscard]] const ErrorInfo& error() const noexcept { return m_error; }

    [[nodiscard]] T valueOr(T defaultValue) const {
        return isOk() ? m_value : defaultValue;
    }

    template<typename F>
    [[nodiscard]] auto map(F&& f) && -> Result<decltype(f(std::move(m_value)))> {
        using U = decltype(f(std::move(m_value)));
        if (isOk()) {
            return Result<U>(f(std::move(m_value)));
        }
        return Result<U>(m_error);
    }

private:
    T m_value;
    ErrorInfo m_error;
};

// Specialization for void result
template<>
class Result<void> {
public:
    Result() : m_error() {}
    Result(ErrorInfo error) : m_error(std::move(error)) {}

    [[nodiscard]] bool isOk() const noexcept { return m_error.isOk(); }
    [[nodiscard]] bool isError() const noexcept { return !isOk(); }
    [[nodiscard]] const ErrorInfo& error() const noexcept { return m_error; }

private:
    ErrorInfo m_error;
};

// Helper macros for error creation
#define DLSS_ERROR(cat, res, msg, ctx) \
    ErrorInfo(ErrorCategory::cat, res, msg, ctx, __FILE__, __LINE__)

#define DLSS_SUCCESS() ErrorInfo()

// ============================================================================
// LOGGING SYSTEM
// ============================================================================

/**
 * @brief Log severity levels.
 */
enum class LogLevel : uint8_t {
    TRACE = 0,
    DEBUG = 1,
    INFO  = 2,
    WARN  = 3,
    ERROR = 4,
    FATAL = 5
};

/**
 * @brief Converts LogLevel to string representation.
 */
[[nodiscard]] constexpr const char* to_string(LogLevel level) noexcept {
    switch (level) {
        case LogLevel::TRACE: return "TRACE";
        case LogLevel::DEBUG: return "DEBUG";
        case LogLevel::INFO:  return "INFO";
        case LogLevel::WARN:  return "WARN";
        case LogLevel::ERROR: return "ERROR";
        case LogLevel::FATAL: return "FATAL";
        default:              return "UNKNOWN";
    }
}

/**
 * @brief Thread-safe logging system with severity levels.
 */
class Logger {
public:
    static Logger& instance() {
        static Logger inst;
        return inst;
    }

    void setLevel(LogLevel level) {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_level = level;
    }

    void setLogFile(const std::string& path) {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_logFile.is_open()) {
            m_logFile.close();
        }
        m_logFile.open(path, std::ios::out | std::ios::trunc);
        m_logPath = path;
    }

    void log(LogLevel level, std::string_view message) {
        if (level < m_level.load(std::memory_order_relaxed)) {
            return;
        }

        std::lock_guard<std::mutex> lock(m_mutex);

        // Get timestamp
        auto now = std::chrono::system_clock::now();
        auto time = std::chrono::system_clock::to_time_t(now);
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            now.time_since_epoch()) % 1000;

        std::ostringstream oss;
        oss << "[" << to_string(level) << "] "
            << std::put_time(std::localtime(&time), "%Y-%m-%d %H:%M:%S")
            << "." << std::setfill('0') << std::setw(3) << ms.count()
            << " - " << message;

        std::string logLine = oss.str();

        // Write to file
        if (m_logFile.is_open()) {
            m_logFile << logLine << std::endl;
            m_logFile.flush();
        }

        // Also output to console
        std::cout << "[DLSS Bridge] " << logLine << std::endl;
    }

    void trace(std::string_view msg) { log(LogLevel::TRACE, msg); }
    void debug(std::string_view msg) { log(LogLevel::DEBUG, msg); }
    void info(std::string_view msg)  { log(LogLevel::INFO, msg); }
    void warn(std::string_view msg)  { log(LogLevel::WARN, msg); }
    void error(std::string_view msg) { log(LogLevel::ERROR, msg); }
    void fatal(std::string_view msg) { log(LogLevel::FATAL, msg); }

    // Conditional logging for throttled messages
    [[nodiscard]] bool shouldLogThrottled(std::atomic<int>& counter) {
        return (++counter) % Constants::LOG_THROTTLE_INTERVAL == 1;
    }

private:
    Logger() {
        m_logFile.open("dlss_bridge_debug.log", std::ios::out | std::ios::trunc);
    }

    ~Logger() {
        if (m_logFile.is_open()) {
            m_logFile.close();
        }
    }

    std::mutex m_mutex;
    std::ofstream m_logFile;
    std::string m_logPath;
    std::atomic<LogLevel> m_level{LogLevel::DEBUG};
};

// Convenience macros for conditional logging (zero overhead when disabled)
#ifdef DLSS_DISABLE_DEBUG_LOGGING
    #define DLSS_LOG_TRACE(msg) ((void)0)
    #define DLSS_LOG_DEBUG(msg) ((void)0)
#else
    #define DLSS_LOG_TRACE(msg) Logger::instance().trace(msg)
    #define DLSS_LOG_DEBUG(msg) Logger::instance().debug(msg)
#endif

#define DLSS_LOG_INFO(msg)  Logger::instance().info(msg)
#define DLSS_LOG_WARN(msg)  Logger::instance().warn(msg)
#define DLSS_LOG_ERROR(msg) Logger::instance().error(msg)
#define DLSS_LOG_FATAL(msg) Logger::instance().fatal(msg)

static void NVSDK_CONV NGXLogCallback(
    const char* message,
    NVSDK_NGX_Logging_Level loggingLevel,
    NVSDK_NGX_Feature sourceComponent
) {
    std::string msg = std::string("[NGX source=") +
        std::to_string(static_cast<int>(sourceComponent)) + "] " +
        (message ? message : "<null>");

    if (loggingLevel == NVSDK_NGX_LOGGING_LEVEL_VERBOSE) {
        DLSS_LOG_DEBUG(msg);
    } else {
        DLSS_LOG_INFO(msg);
    }
}

// ============================================================================
// VULKAN HELPERS
// ============================================================================

namespace VulkanHelpers {

/**
 * @brief Determines the aspect mask for a given Vulkan format.
 * @param format The Vulkan format
 * @return The appropriate aspect mask flags
 */
[[nodiscard]] VkImageAspectFlags getAspectMask(VkFormat format) noexcept {
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

/**
 * @brief Transitions a Vulkan image between layouts with proper pipeline barriers.
 *
 * @param cmd Command buffer to record the barrier into
 * @param image The image to transition
 * @param oldLayout Current layout of the image
 * @param newLayout Target layout for the image
 * @param srcStage Source pipeline stage flags
 * @param dstStage Destination pipeline stage flags
 * @param srcAccess Source access flags
 * @param dstAccess Destination access flags
 * @param aspectMask Image aspect mask (default: COLOR)
 * @param baseMipLevel First mip level to transition (default: 0)
 * @param mipCount Number of mip levels to transition (default: 1)
 */
void transitionImageLayout(
    VkCommandBuffer cmd,
    VkImage image,
    VkImageLayout oldLayout,
    VkImageLayout newLayout,
    VkPipelineStageFlags srcStage,
    VkPipelineStageFlags dstStage,
    VkAccessFlags srcAccess,
    VkAccessFlags dstAccess,
    VkImageAspectFlags aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
    uint32_t baseMipLevel = 0,
    uint32_t mipCount = 1
) {
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = aspectMask;
    barrier.subresourceRange.baseMipLevel = baseMipLevel;
    barrier.subresourceRange.levelCount = mipCount;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;

    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

/**
 * @brief Converts wide string to narrow string using Windows API.
 * @param wstr Wide string to convert
 * @return Narrow string (UTF-8)
 */
[[nodiscard]] std::string wideToNarrow(const std::wstring& wstr) {
    if (wstr.empty()) return std::string();
    int size = WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(),
        static_cast<int>(wstr.length()), nullptr, 0, nullptr, nullptr);
    if (size <= 0) return std::string();
    std::string result(size, 0);
    WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(),
        static_cast<int>(wstr.length()), &result[0], size, nullptr, nullptr);
    return result;
}

/**
 * @brief Returns the folder containing this native bridge DLL.
 */
[[nodiscard]] std::wstring getNativeModuleDirectory() {
    wchar_t path[MAX_PATH] = {};
    DWORD length = GetModuleFileNameW(reinterpret_cast<HMODULE>(&__ImageBase), path, MAX_PATH);

    if (length == 0 || length >= MAX_PATH) {
        length = GetCurrentDirectoryW(MAX_PATH, path);
        if (length == 0 || length >= MAX_PATH) {
            return L".";
        }
        return std::wstring(path, length);
    }

    std::wstring modulePath(path, length);
    const size_t slash = modulePath.find_last_of(L"\\/");
    if (slash == std::wstring::npos) {
        return L".";
    }

    return modulePath.substr(0, slash);
}

/**
 * @brief Aligns a dimension value to the required alignment.
 * @param value The value to align
 * @param alignment The alignment (must be power of 2)
 * @return The aligned value
 */
[[nodiscard]] constexpr int alignDimension(int value, int alignment) noexcept {
    return value & ~(alignment - 1);
}

/**
 * @brief Validates that dimensions meet DLSS requirements.
 * @param width Width to validate
 * @param height Height to validate
 * @return true if dimensions are valid
 */
[[nodiscard]] bool validateDimensions(int width, int height) noexcept {
    return width >= Constants::DIMENSION_MIN &&
           height >= Constants::DIMENSION_MIN &&
           width % Constants::DIMENSION_ALIGNMENT == 0 &&
           height % Constants::DIMENSION_ALIGNMENT == 0;
}

} // namespace VulkanHelpers

// ============================================================================
// JITTER VALIDATION
// ============================================================================

namespace JitterValidation {

/**
 * @brief Validates and clamps jitter offsets to the valid DLSS range.
 *
 * DLSS expects jitter offsets in pixel units with range [-0.5, 0.5].
 * This function validates the input and clamps out-of-range values,
 * logging a warning when clamping occurs.
 *
 * @param jitterX Reference to X jitter offset (modified in-place if needed)
 * @param jitterY Reference to Y jitter offset (modified in-place if needed)
 * @param context Context string for logging
 */
void validateAndClamp(float& jitterX, float& jitterY, std::string_view context) {
    auto clampValue = [](float& value, float min, float max, std::string_view axis,
                         std::string_view ctx) {
        if (value < min || value > max) {
            float original = value;
            value = std::max(min, std::min(max, value));
            std::ostringstream oss;
            oss << "WARNING: " << ctx << " - Jitter" << axis << "=" << original
                << " clamped to " << value << " (range ["
                << Constants::JITTER_RANGE_MIN << ", " << Constants::JITTER_RANGE_MAX << "])";
            DLSS_LOG_WARN(oss.str());
        }
    };

    clampValue(jitterX, Constants::JITTER_RANGE_MIN, Constants::JITTER_RANGE_MAX, "X", context);
    clampValue(jitterY, Constants::JITTER_RANGE_MIN, Constants::JITTER_RANGE_MAX, "Y", context);
}

/**
 * @brief Checks if jitter values are within valid range without modifying them.
 * @param jitterX X jitter offset
 * @param jitterY Y jitter offset
 * @return true if both values are in valid range
 */
[[nodiscard]] bool isValid(float jitterX, float jitterY) noexcept {
    return jitterX >= Constants::JITTER_RANGE_MIN &&
           jitterX <= Constants::JITTER_RANGE_MAX &&
           jitterY >= Constants::JITTER_RANGE_MIN &&
           jitterY <= Constants::JITTER_RANGE_MAX;
}

} // namespace JitterValidation

// ============================================================================
// NGX LIFECYCLE MANAGER
// ============================================================================

/**
 * @brief Manages NGX SDK initialization and shutdown lifecycle.
 */
class NGXLifecycleManager {
public:
    struct InitParams {
        VkInstance instance;
        VkPhysicalDevice physicalDevice;
        VkDevice device;
        std::string dlssPath;
    };

    [[nodiscard]] Result<void> initialize(const InitParams& params) {
        std::lock_guard<std::mutex> lock(m_mutex);

        if (m_initialized) {
            // Check if re-initialization with same parameters
            if (m_instance == params.instance &&
                m_physicalDevice == params.physicalDevice &&
                m_device == params.device) {
                return DLSS_SUCCESS();
            }
            // Need to reinitialize with different device
            shutdownInternal();
        }

        if (params.instance == VK_NULL_HANDLE ||
            params.physicalDevice == VK_NULL_HANDLE ||
            params.device == VK_NULL_HANDLE) {
            return DLSS_ERROR(Initialization, NVSDK_NGX_Result_FAIL_InvalidParameter,
                            "Null Vulkan handles provided", "initialize");
        }

        DLSS_LOG_INFO("Initializing NGX...");

        // Prepare path info
        std::wstring wDlssPath;
        std::wstring wNativeModulePath;
        const wchar_t* paths[] = { nullptr, nullptr };
        NVSDK_NGX_PathListInfo pathListInfo = {};
        NVSDK_NGX_FeatureCommonInfo featureInfo = {};
        featureInfo.LoggingInfo.LoggingCallback = NGXLogCallback;
        featureInfo.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_VERBOSE;
        featureInfo.LoggingInfo.DisableOtherLoggingSinks = false;
        const NVSDK_NGX_FeatureCommonInfo* pFeatureInfo = nullptr;

        if (!params.dlssPath.empty()) {
            wDlssPath.resize(params.dlssPath.length() + 1);
            size_t convertedChars = 0;
            errno_t err = mbstowcs_s(&convertedChars, &wDlssPath[0],
                wDlssPath.length() + 1, params.dlssPath.c_str(), _TRUNCATE);

            if (err == 0) {
                paths[0] = wDlssPath.c_str();
                pathListInfo.Path = paths;
                pathListInfo.Length = 1;
                featureInfo.PathListInfo = pathListInfo;
                pFeatureInfo = &featureInfo;
                DLSS_LOG_INFO("Using custom DLSS search path: " +
                              VulkanHelpers::wideToNarrow(wDlssPath));
            } else {
                DLSS_LOG_WARN("Failed to convert DLSS path to wstring. Using native module directory.");
            }
        }

        if (pFeatureInfo == nullptr) {
            wNativeModulePath = VulkanHelpers::getNativeModuleDirectory();
            paths[0] = wNativeModulePath.c_str();
            pathListInfo.Path = paths;
            pathListInfo.Length = 1;
            featureInfo.PathListInfo = pathListInfo;
            pFeatureInfo = &featureInfo;
            DLSS_LOG_INFO("Using native module directory for DLSS search: " +
                          VulkanHelpers::wideToNarrow(wNativeModulePath));
        }

        // Determine application data path
        std::wstring appDataPath;
        if (!params.dlssPath.empty()) {
            appDataPath = wDlssPath;
        } else {
            wchar_t tempPath[MAX_PATH];
            GetTempPathW(MAX_PATH, tempPath);
            appDataPath = tempPath;
            DLSS_LOG_INFO("Using temp directory for NGX data: " +
                         VulkanHelpers::wideToNarrow(appDataPath));
        }

        DLSS_LOG_INFO("ApplicationDataPath: " + VulkanHelpers::wideToNarrow(appDataPath));

        // Get function pointers
        PFN_vkGetInstanceProcAddr gipa = vkGetInstanceProcAddr;
        PFN_vkGetDeviceProcAddr gdpa = vkGetDeviceProcAddr;

        // Prefer ProjectID initialization for custom engines. The generic AppID
        // can initialize NGX but still leave DLSS/RR capability bits disabled.
        NVSDK_NGX_Result res = NVSDK_NGX_VULKAN_Init_with_ProjectID(
            Constants::NGX_PROJECT_ID,
            NVSDK_NGX_ENGINE_TYPE_CUSTOM,
            Constants::NGX_ENGINE_VERSION,
            appDataPath.c_str(),
            params.instance,
            params.physicalDevice,
            params.device,
            gipa,
            gdpa,
            pFeatureInfo,
            NVSDK_NGX_Version_API
        );

        if (NVSDK_NGX_FAILED(res)) {
            DLSS_LOG_WARN("NVSDK_NGX_VULKAN_Init_with_ProjectID failed: " +
                         ErrorInfo::formatNGXResult(res));

            // Fallback to AppID method for older NGX runtimes.
            res = NVSDK_NGX_VULKAN_Init(
                Constants::NGX_APP_ID,
                appDataPath.c_str(),
                params.instance,
                params.physicalDevice,
                params.device,
                gipa,
                gdpa,
                pFeatureInfo,
                NVSDK_NGX_Version_API
            );

            if (NVSDK_NGX_FAILED(res)) {
                return DLSS_ERROR(Initialization, res,
                                "NGX initialization failed (both ProjectID and AppID)",
                                "initialize");
            }
        }

        // Get capability parameters
        res = NVSDK_NGX_VULKAN_GetCapabilityParameters(&m_parameters);
        if (NVSDK_NGX_FAILED(res)) {
            NVSDK_NGX_VULKAN_Shutdown1(params.device);
            return DLSS_ERROR(Initialization, res,
                            "Failed to get capability parameters", "initialize");
        }

        m_instance = params.instance;
        m_physicalDevice = params.physicalDevice;
        m_device = params.device;
        m_initialized = true;

        logFeatureRequirements(NVSDK_NGX_Feature_RayReconstruction, "Ray Reconstruction");
        logFeatureRequirements(NVSDK_NGX_Feature_SuperSampling, "DLSS Super Resolution");

        logCapabilityInfo();

        DLSS_LOG_INFO("NGX Initialized Successfully");
        return DLSS_SUCCESS();
    }

    void shutdown() {
        std::lock_guard<std::mutex> lock(m_mutex);
        shutdownInternal();
    }

    [[nodiscard]] bool isInitialized() const noexcept { return m_initialized; }
    [[nodiscard]] VkDevice getDevice() const noexcept { return m_device; }
    [[nodiscard]] NVSDK_NGX_Parameter* getParameters() const noexcept { return m_parameters; }

    [[nodiscard]] bool isDLSSDAvailable() const {
        if (!m_initialized || !m_parameters) return false;
        int available = 0;
        NVSDK_NGX_Parameter_GetI(m_parameters,
            NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &available);
        return available != 0;
    }

    [[nodiscard]] bool isDLSSAvailable() const {
        if (!m_initialized || !m_parameters) return false;
        int available = 0;
        NVSDK_NGX_Parameter_GetI(m_parameters,
            NVSDK_NGX_Parameter_SuperSampling_Available, &available);
        return available != 0;
    }

private:
    NGXLifecycleManager() = default;
    // Java drives NGX teardown while the VkDevice is still valid. Static
    // destructor shutdown can run during JVM/DLL unload, which is too late.
    ~NGXLifecycleManager() = default;

    void shutdownInternal() {
        if (m_parameters) {
            NVSDK_NGX_VULKAN_DestroyParameters(m_parameters);
            m_parameters = nullptr;
        }
        if (m_initialized && m_device) {
            NVSDK_NGX_VULKAN_Shutdown1(m_device);
            m_initialized = false;
            m_instance = VK_NULL_HANDLE;
            m_physicalDevice = VK_NULL_HANDLE;
            m_device = VK_NULL_HANDLE;
        }
    }

    void logCapabilityInfo() {
        if (!m_parameters) return;

        DLSS_LOG_INFO("=== NGX Capability Check ===");

        // DLSSD availability
        int rrAvailable = 0;
        NVSDK_NGX_Result res = NVSDK_NGX_Parameter_GetI(m_parameters,
            NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &rrAvailable);
        if (NVSDK_NGX_SUCCEED(res)) {
            DLSS_LOG_INFO(std::string(" - Ray Reconstruction Available: ") +
                         (rrAvailable ? "YES" : "NO"));

            if (rrAvailable) {
                int needsUpdate = 0;
                NVSDK_NGX_Parameter_GetI(m_parameters,
                    NVSDK_NGX_Parameter_SuperSamplingDenoising_NeedsUpdatedDriver, &needsUpdate);
                DLSS_LOG_INFO(std::string(" - Needs Updated Driver: ") +
                             (needsUpdate ? "YES" : "NO"));

                unsigned int majorVer = 0, minorVer = 0;
                NVSDK_NGX_Parameter_GetUI(m_parameters,
                    NVSDK_NGX_Parameter_SuperSamplingDenoising_MinDriverVersionMajor, &majorVer);
                NVSDK_NGX_Parameter_GetUI(m_parameters,
                    NVSDK_NGX_Parameter_SuperSamplingDenoising_MinDriverVersionMinor, &minorVer);
                DLSS_LOG_INFO(" - Min Driver Version: " + std::to_string(majorVer) +
                             "." + std::to_string(minorVer));
            } else {
                DLSS_LOG_WARN("Ray Reconstruction is NOT available on this system!");
            }
        }

        // DLSS availability
        int dlssAvailable = 0;
        res = NVSDK_NGX_Parameter_GetI(m_parameters,
            NVSDK_NGX_Parameter_SuperSampling_Available, &dlssAvailable);
        if (NVSDK_NGX_SUCCEED(res)) {
            DLSS_LOG_INFO(std::string(" - DLSS Super Resolution Available: ") +
                         (dlssAvailable ? "YES" : "NO"));
        }
    }

    void logFeatureRequirements(NVSDK_NGX_Feature feature, const char* label) {
        NVSDK_NGX_FeatureDiscoveryInfo featureInfo = GetFeatureDiscoveryInfo(feature);
        NVSDK_NGX_FeatureRequirement requirements = {};
        NVSDK_NGX_Result res = NVSDK_NGX_VULKAN_GetFeatureRequirements(
            m_instance, m_physicalDevice, &featureInfo, &requirements);

        if (NVSDK_NGX_FAILED(res)) {
            DLSS_LOG_WARN(std::string(label) + " requirements query failed: " +
                         ErrorInfo::formatNGXResult(res));
            return;
        }

        DLSS_LOG_INFO(std::string(" - ") + label +
                     " requirements support mask: " +
                     std::to_string(static_cast<int>(requirements.FeatureSupported)));
        if (requirements.FeatureSupported != NVSDK_NGX_FeatureSupportResult_Supported) {
            if (requirements.FeatureSupported & NVSDK_NGX_FeatureSupportResult_CheckNotPresent) {
                DLSS_LOG_WARN(std::string("   ") + label + ": NGX check not present");
            }
            if (requirements.FeatureSupported & NVSDK_NGX_FeatureSupportResult_DriverVersionUnsupported) {
                DLSS_LOG_WARN(std::string("   ") + label + ": driver version unsupported");
            }
            if (requirements.FeatureSupported & NVSDK_NGX_FeatureSupportResult_AdapterUnsupported) {
                DLSS_LOG_WARN(std::string("   ") + label + ": adapter unsupported");
            }
            if (requirements.FeatureSupported & NVSDK_NGX_FeatureSupportResult_OSVersionBelowMinimumSupported) {
                DLSS_LOG_WARN(std::string("   ") + label + ": OS version below minimum");
            }
            if (requirements.FeatureSupported & NVSDK_NGX_FeatureSupportResult_NotImplemented) {
                DLSS_LOG_WARN(std::string("   ") + label + ": not implemented by this NGX runtime");
            }
        }
    }

    std::mutex m_mutex;
    bool m_initialized = false;
    VkInstance m_instance = VK_NULL_HANDLE;
    VkPhysicalDevice m_physicalDevice = VK_NULL_HANDLE;
    VkDevice m_device = VK_NULL_HANDLE;
    NVSDK_NGX_Parameter* m_parameters = nullptr;

    friend class DLSSBridge;
};

// ============================================================================
// DLSS FEATURE MANAGER
// ============================================================================

/**
 * @brief Manages standard DLSS feature lifecycle and evaluation.
 */
class DLSSFeatureManager {
public:
    struct CreateParams {
        int renderWidth;
        int renderHeight;
        int outputWidth;
        int outputHeight;
        NVSDK_NGX_PerfQuality_Value quality;
    };

    [[nodiscard]] Result<void> configure(const CreateParams& params) {
        std::lock_guard<std::mutex> lock(m_mutex);

        // Validate dimensions
        if (params.renderWidth == 0 || params.renderHeight == 0 ||
            params.outputWidth == 0 || params.outputHeight == 0) {
            return DLSS_ERROR(Parameter, NVSDK_NGX_Result_FAIL_InvalidParameter,
                            "Invalid dimensions (0 detected)", "configure");
        }

        // Align dimensions
        m_renderWidth = VulkanHelpers::alignDimension(params.renderWidth, Constants::DIMENSION_ALIGNMENT);
        m_renderHeight = VulkanHelpers::alignDimension(params.renderHeight, Constants::DIMENSION_ALIGNMENT);
        m_outputWidth = VulkanHelpers::alignDimension(params.outputWidth, Constants::DIMENSION_ALIGNMENT);
        m_outputHeight = VulkanHelpers::alignDimension(params.outputHeight, Constants::DIMENSION_ALIGNMENT);
        m_quality = params.quality;

        // Check for dimension/quality changes
        bool dimensionsChanged = (m_lastRenderWidth != m_renderWidth ||
                                  m_lastRenderHeight != m_renderHeight ||
                                  m_lastOutputWidth != m_outputWidth ||
                                  m_lastOutputHeight != m_outputHeight);
        bool qualityChanged = (m_lastQuality != m_quality);

        m_lastRenderWidth = m_renderWidth;
        m_lastRenderHeight = m_renderHeight;
        m_lastOutputWidth = m_outputWidth;
        m_lastOutputHeight = m_outputHeight;
        m_lastQuality = m_quality;

        m_needsReset = dimensionsChanged || qualityChanged;
        if (m_needsReset) {
            DLSS_LOG_DEBUG("DLSS dimensions/quality changed, reset flag set");
        }

        m_featurePending = true;
        m_creationFailures = 0;

        DLSS_LOG_INFO("DLSS configured. Render: " + std::to_string(m_renderWidth) +
                     "x" + std::to_string(m_renderHeight));
        return DLSS_SUCCESS();
    }

    void destroy() {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_feature) {
            NVSDK_NGX_VULKAN_ReleaseFeature(m_feature);
            m_feature = nullptr;
        }
        m_featurePending = false;
    }

    [[nodiscard]] NVSDK_NGX_Result createFeature(
        VkDevice device,
        VkCommandBuffer cmdBuffer,
        NVSDK_NGX_Parameter* parameters
    ) {
        std::lock_guard<std::mutex> lock(m_mutex);

        if (!m_featurePending) {
            return NVSDK_NGX_Result_Success;
        }

        DLSS_LOG_INFO("=== CreateDLSSFeature ===");

        // Validate dimensions
        if (m_renderWidth == 0 || m_renderHeight == 0 ||
            m_outputWidth == 0 || m_outputHeight == 0) {
            DLSS_LOG_ERROR("Invalid DLSS dimensions (0 detected)");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }

        // Warn about alignment
        if (m_renderWidth % Constants::DIMENSION_ALIGNMENT != 0 ||
            m_renderHeight % Constants::DIMENSION_ALIGNMENT != 0) {
            DLSS_LOG_WARN("Render dimensions not aligned to " +
                         std::to_string(Constants::DIMENSION_ALIGNMENT));
        }

        NVSDK_NGX_DLSS_Create_Params createParams = {};
        createParams.Feature.InWidth = m_renderWidth;
        createParams.Feature.InHeight = m_renderHeight;
        createParams.Feature.InTargetWidth = m_outputWidth;
        createParams.Feature.InTargetHeight = m_outputHeight;
        createParams.Feature.InPerfQualityValue = m_quality;
        createParams.InFeatureCreateFlags = Constants::DLSS_FEATURE_FLAGS;

        DLSS_LOG_INFO("Creating DLSS Feature: In(" +
                     std::to_string(m_renderWidth) + "x" + std::to_string(m_renderHeight) +
                     ") Out(" + std::to_string(m_outputWidth) + "x" +
                     std::to_string(m_outputHeight) + ")");

        if (!parameters) {
            DLSS_LOG_ERROR("Parameters is null during DLSS creation");
            return NVSDK_NGX_Result_FAIL_NotInitialized;
        }

        NVSDK_NGX_Result res = NGX_VULKAN_CREATE_DLSS_EXT1(
            device, cmdBuffer, 1, 1, &m_feature, parameters, &createParams);

        if (NVSDK_NGX_FAILED(res)) {
            DLSS_LOG_ERROR("Failed to create DLSS Feature: " +
                          ErrorInfo::formatNGXResult(res));
            return res;
        }

        m_featurePending = false;
        DLSS_LOG_INFO("DLSS Feature Created Successfully");
        return NVSDK_NGX_Result_Success;
    }

    [[nodiscard]] NVSDK_NGX_Handle* getFeature() const noexcept { return m_feature; }
    [[nodiscard]] NVSDK_NGX_Handle** getFeaturePtr() noexcept { return &m_feature; }
    [[nodiscard]] bool isPending() const noexcept { return m_featurePending; }
    [[nodiscard]] int getRenderWidth() const noexcept { return m_renderWidth; }
    [[nodiscard]] int getRenderHeight() const noexcept { return m_renderHeight; }
    [[nodiscard]] int getOutputWidth() const noexcept { return m_outputWidth; }
    [[nodiscard]] int getOutputHeight() const noexcept { return m_outputHeight; }
    [[nodiscard]] bool needsReset() const noexcept { return m_needsReset; }
    void clearReset() noexcept { m_needsReset = false; }
    void incrementFailures() noexcept { m_creationFailures++; }
    [[nodiscard]] int getFailures() const noexcept { return m_creationFailures; }
    void clearPending() noexcept { m_featurePending = false; }

private:
    std::mutex m_mutex;
    NVSDK_NGX_Handle* m_feature = nullptr;
    bool m_featurePending = false;
    int m_creationFailures = 0;

    int m_renderWidth = 0;
    int m_renderHeight = 0;
    int m_outputWidth = 0;
    int m_outputHeight = 0;
    NVSDK_NGX_PerfQuality_Value m_quality = NVSDK_NGX_PerfQuality_Value_Balanced;

    // Change tracking
    int m_lastRenderWidth = 0;
    int m_lastRenderHeight = 0;
    int m_lastOutputWidth = 0;
    int m_lastOutputHeight = 0;
    NVSDK_NGX_PerfQuality_Value m_lastQuality = NVSDK_NGX_PerfQuality_Value_MaxQuality;
    bool m_needsReset = false;
};

// ============================================================================
// DLSSD FEATURE MANAGER
// ============================================================================

/**
 * @brief Manages DLSSD (Ray Reconstruction) feature lifecycle and evaluation.
 */
class DLSSDFeatureManager {
public:
    struct CreateParams {
        int renderWidth;
        int renderHeight;
        int outputWidth;
        int outputHeight;
        NVSDK_NGX_DLSS_Denoise_Mode denoiseMode;
        NVSDK_NGX_DLSS_Roughness_Mode roughnessMode;
        NVSDK_NGX_DLSS_Depth_Type depthType;
        NVSDK_NGX_PerfQuality_Value quality;
    };

    [[nodiscard]] Result<void> configure(const CreateParams& params) {
        std::lock_guard<std::mutex> lock(m_mutex);

        // Validate dimensions
        if (params.renderWidth == 0 || params.renderHeight == 0 ||
            params.outputWidth == 0 || params.outputHeight == 0) {
            return DLSS_ERROR(Parameter, NVSDK_NGX_Result_FAIL_InvalidParameter,
                            "Invalid dimensions (0 detected)", "configure");
        }

        m_renderWidth = VulkanHelpers::alignDimension(params.renderWidth, Constants::DIMENSION_ALIGNMENT);
        m_renderHeight = VulkanHelpers::alignDimension(params.renderHeight, Constants::DIMENSION_ALIGNMENT);
        m_outputWidth = VulkanHelpers::alignDimension(params.outputWidth, Constants::DIMENSION_ALIGNMENT);
        m_outputHeight = VulkanHelpers::alignDimension(params.outputHeight, Constants::DIMENSION_ALIGNMENT);
        m_denoiseMode = params.denoiseMode;
        m_roughnessMode = params.roughnessMode;
        m_depthType = params.depthType;
        m_quality = params.quality;

        // Check for changes
        bool dimensionsChanged = (m_lastRenderWidth != m_renderWidth ||
                                  m_lastRenderHeight != m_renderHeight ||
                                  m_lastOutputWidth != m_outputWidth ||
                                  m_lastOutputHeight != m_outputHeight);
        bool qualityChanged = (m_lastQuality != m_quality);

        m_lastRenderWidth = m_renderWidth;
        m_lastRenderHeight = m_renderHeight;
        m_lastOutputWidth = m_outputWidth;
        m_lastOutputHeight = m_outputHeight;
        m_lastQuality = m_quality;

        m_needsReset = dimensionsChanged || qualityChanged;
        if (m_needsReset) {
            m_outputLayoutInitialized = false;
            DLSS_LOG_DEBUG("DLSSD dimensions/quality changed, reset flag set");
        }

        m_featurePending = true;
        m_creationFailures = 0;

        DLSS_LOG_INFO("DLSSD configured. Render: " + std::to_string(m_renderWidth) +
                     "x" + std::to_string(m_renderHeight));
        return DLSS_SUCCESS();
    }

    void destroy() {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_feature) {
            NVSDK_NGX_VULKAN_ReleaseFeature(m_feature);
            m_feature = nullptr;
        }
        m_featurePending = false;
        m_outputLayoutInitialized = false;
    }

    [[nodiscard]] NVSDK_NGX_Result createFeature(
        VkDevice device,
        VkCommandBuffer cmdBuffer,
        NVSDK_NGX_Parameter* parameters,
        NVSDK_NGX_Handle** fallbackDLSSFeature
    ) {
        std::lock_guard<std::mutex> lock(m_mutex);

        if (!m_featurePending) {
            return NVSDK_NGX_Result_Success;
        }

        DLSS_LOG_INFO("=== CreateDLSSDFeature ===");

        // Validate dimensions
        if (m_renderWidth == 0 || m_renderHeight == 0 ||
            m_outputWidth == 0 || m_outputHeight == 0) {
            DLSS_LOG_ERROR("Invalid DLSSD dimensions (0 detected)");
            m_featurePending = false;
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }

        bool tryDLSSD = true;

        // Check for native resolution (not supported by DLSSD)
        if (m_renderWidth == m_outputWidth && m_renderHeight == m_outputHeight) {
            DLSS_LOG_WARN("Native resolution not supported by DLSSD. Will use standard DLSS (DLAA).");
            tryDLSSD = false;
        }

        // Log availability, but do not hard-gate feature creation on it. Some NGX
        // runtimes report the capability bit conservatively while CreateFeature
        // is still the authoritative result for this Vulkan device.
        int rrAvailable = 0;
        NVSDK_NGX_Parameter_GetI(parameters,
            NVSDK_NGX_Parameter_SuperSamplingDenoising_Available, &rrAvailable);
        if (!rrAvailable) {
            DLSS_LOG_WARN("Ray Reconstruction capability bit is false; attempting DLSSD feature creation anyway.");
        }

        NVSDK_NGX_Result res = NVSDK_NGX_Result_FAIL_FeatureNotSupported;

        if (tryDLSSD) {
            NVSDK_NGX_DLSSD_Create_Params createParams = {};
            createParams.InWidth = m_renderWidth;
            createParams.InHeight = m_renderHeight;
            createParams.InTargetWidth = m_outputWidth;
            createParams.InTargetHeight = m_outputHeight;
            createParams.InDenoiseMode = m_denoiseMode;
            createParams.InRoughnessMode = m_roughnessMode;
            createParams.InUseHWDepth = m_depthType;
            createParams.InPerfQualityValue = m_quality;
            createParams.InFeatureCreateFlags = Constants::DLSSD_FEATURE_FLAGS;
            createParams.InEnableOutputSubrects = false;

            DLSS_LOG_INFO("Creating DLSSD Feature:");
            DLSS_LOG_INFO(" - Input: " + std::to_string(m_renderWidth) + "x" +
                         std::to_string(m_renderHeight));
            DLSS_LOG_INFO(" - Output: " + std::to_string(m_outputWidth) + "x" +
                         std::to_string(m_outputHeight));
            DLSS_LOG_INFO(std::string(" - DepthType: ") +
                         (m_depthType == NVSDK_NGX_DLSS_Depth_Type_Linear ? "LINEAR" : "HW"));
            DLSS_LOG_INFO(std::string(" - RoughnessMode: ") +
                         (m_roughnessMode == NVSDK_NGX_DLSS_Roughness_Mode_Packed ? "Packed" : "Unpacked"));
            DLSS_LOG_INFO(" - FeatureFlags: " + std::to_string(createParams.InFeatureCreateFlags));

            NVSDK_NGX_Parameter_SetUI(parameters,
                NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Quality,
                NVSDK_NGX_RayReconstruction_Hint_Render_Preset_Default);
            NVSDK_NGX_Parameter_SetUI(parameters,
                NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraQuality,
                NVSDK_NGX_RayReconstruction_Hint_Render_Preset_Default);
            NVSDK_NGX_Parameter_SetUI(parameters,
                NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Balanced,
                NVSDK_NGX_RayReconstruction_Hint_Render_Preset_Default);
            NVSDK_NGX_Parameter_SetUI(parameters,
                NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Performance,
                NVSDK_NGX_RayReconstruction_Hint_Render_Preset_Default);
            NVSDK_NGX_Parameter_SetUI(parameters,
                NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraPerformance,
                NVSDK_NGX_RayReconstruction_Hint_Render_Preset_Default);

            // Set RTXValue for compatibility
            NVSDK_NGX_Parameter_SetI(parameters, NVSDK_NGX_Parameter_RTXValue, false);

            res = NGX_VULKAN_CREATE_DLSSD_EXT1(
                device, cmdBuffer, 1, 1, &m_feature, parameters, &createParams);
        }

        if (NVSDK_NGX_FAILED(res)) {
            if (tryDLSSD) {
                DLSS_LOG_WARN("DLSSD creation failed: " +
                             ErrorInfo::formatNGXResult(res) +
                             ". Attempting standard DLSS fallback...");
            }

            // Fallback to standard DLSS
            NVSDK_NGX_DLSS_Create_Params dlssParams = {};
            dlssParams.Feature.InWidth = m_renderWidth;
            dlssParams.Feature.InHeight = m_renderHeight;
            dlssParams.Feature.InTargetWidth = m_outputWidth;
            dlssParams.Feature.InTargetHeight = m_outputHeight;
            dlssParams.Feature.InPerfQualityValue = m_quality;
            dlssParams.InFeatureCreateFlags = Constants::DLSS_FEATURE_FLAGS;

            NVSDK_NGX_Result fallbackRes = NGX_VULKAN_CREATE_DLSS_EXT1(
                device, cmdBuffer, 1, 1, fallbackDLSSFeature, parameters, &dlssParams);

            if (NVSDK_NGX_SUCCEED(fallbackRes)) {
                DLSS_LOG_INFO("FALLBACK SUCCESS: Created standard DLSS instead of DLSSD");
                m_featurePending = false;
                return NVSDK_NGX_Result_Success;
            } else {
                DLSS_LOG_ERROR("FALLBACK FAILED: " +
                              ErrorInfo::formatNGXResult(fallbackRes));
            }
            m_featurePending = false;
            return res;
        }

        m_featurePending = false;
        DLSS_LOG_INFO("DLSSD Feature Created Successfully");
        return NVSDK_NGX_Result_Success;
    }

    [[nodiscard]] NVSDK_NGX_Handle* getFeature() const noexcept { return m_feature; }
    [[nodiscard]] bool isPending() const noexcept { return m_featurePending; }
    [[nodiscard]] int getRenderWidth() const noexcept { return m_renderWidth; }
    [[nodiscard]] int getRenderHeight() const noexcept { return m_renderHeight; }
    [[nodiscard]] int getOutputWidth() const noexcept { return m_outputWidth; }
    [[nodiscard]] int getOutputHeight() const noexcept { return m_outputHeight; }
    [[nodiscard]] NVSDK_NGX_DLSS_Roughness_Mode getRoughnessMode() const noexcept {
        return m_roughnessMode;
    }
    [[nodiscard]] bool needsReset() const noexcept { return m_needsReset; }
    void clearReset() noexcept { m_needsReset = false; }
    void incrementFailures() noexcept { m_creationFailures++; }
    [[nodiscard]] int getFailures() const noexcept { return m_creationFailures; }
    void clearPending() noexcept { m_featurePending = false; }
    [[nodiscard]] bool isOutputLayoutInitialized() const noexcept {
        return m_outputLayoutInitialized;
    }
    void setOutputLayoutInitialized() noexcept { m_outputLayoutInitialized = true; }

private:
    std::mutex m_mutex;
    NVSDK_NGX_Handle* m_feature = nullptr;
    bool m_featurePending = false;
    int m_creationFailures = 0;

    int m_renderWidth = 0;
    int m_renderHeight = 0;
    int m_outputWidth = 0;
    int m_outputHeight = 0;
    NVSDK_NGX_DLSS_Denoise_Mode m_denoiseMode = NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
    NVSDK_NGX_DLSS_Roughness_Mode m_roughnessMode = NVSDK_NGX_DLSS_Roughness_Mode_Packed;
    NVSDK_NGX_DLSS_Depth_Type m_depthType = NVSDK_NGX_DLSS_Depth_Type_Linear;
    NVSDK_NGX_PerfQuality_Value m_quality = NVSDK_NGX_PerfQuality_Value_Balanced;

    // Change tracking
    int m_lastRenderWidth = 0;
    int m_lastRenderHeight = 0;
    int m_lastOutputWidth = 0;
    int m_lastOutputHeight = 0;
    NVSDK_NGX_PerfQuality_Value m_lastQuality = NVSDK_NGX_PerfQuality_Value_MaxQuality;
    bool m_needsReset = false;
    bool m_outputLayoutInitialized = false;
};

// ============================================================================
// DLSS BRIDGE (COORDINATOR)
// ============================================================================

/**
 * @brief Main coordinator class for DLSS/DLSSD operations.
 *
 * This class provides the main interface for DLSS operations, coordinating
 * between the NGX lifecycle, DLSS, and DLSSD feature managers.
 */
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

    NVSDK_NGX_Result InitializeNGX(VkInstance instance, VkPhysicalDevice physicalDevice,
                                   VkDevice device, const char* dlssPath) {
        NGXLifecycleManager::InitParams params = {};
        params.instance = instance;
        params.physicalDevice = physicalDevice;
        params.device = device;
        if (dlssPath) params.dlssPath = dlssPath;

        auto result = m_lifecycle.initialize(params);
        return result.isOk() ? NVSDK_NGX_Result_Success : result.error().ngxResult;
    }

    void ShutdownNGX() {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_dlssManager.destroy();
        m_dlssdManager.destroy();
        m_lifecycle.shutdown();
    }

    // --- Standard DLSS ---

    NVSDK_NGX_Result InitDLSS(int renderWidth, int renderHeight, int outWidth, int outHeight,
                              NVSDK_NGX_PerfQuality_Value quality) {
        if (!m_lifecycle.isInitialized()) {
            DLSS_LOG_ERROR("InitDLSS called before NGX Initialization!");
            return NVSDK_NGX_Result_FAIL_NotInitialized;
        }

        m_dlssManager.destroy();

        DLSSFeatureManager::CreateParams params = {};
        params.renderWidth = renderWidth;
        params.renderHeight = renderHeight;
        params.outputWidth = outWidth;
        params.outputHeight = outHeight;
        params.quality = quality;

        auto result = m_dlssManager.configure(params);
        return result.isOk() ? NVSDK_NGX_Result_Success : result.error().ngxResult;
    }

    void DestroyDLSS() {
        m_dlssManager.destroy();
    }

    NVSDK_NGX_Result EvaluateDLSS(
        VkCommandBuffer cmdBuffer,
        VkImageView color, VkImage colorImg, int colorFormat,
        VkImageView depth, VkImage depthImg, int depthFormat,
        VkImageView mv, VkImage mvImg, int mvFormat,
        VkImageView output, VkImage outputImg, int outputFormat,
        float jitterX, float jitterY
    ) {
        std::lock_guard<std::mutex> lock(m_mutex);

        if (!m_lifecycle.isInitialized()) {
            return NVSDK_NGX_Result_FAIL_NotInitialized;
        }

        // Create feature if pending
        if (m_dlssManager.isPending()) {
            NVSDK_NGX_Result res = m_dlssManager.createFeature(
                m_lifecycle.getDevice(), cmdBuffer, m_lifecycle.getParameters());
            if (NVSDK_NGX_FAILED(res)) {
                m_dlssManager.incrementFailures();
                if (m_dlssManager.getFailures() >= Constants::MAX_CREATION_FAILURES) {
                    m_dlssManager.clearPending();
                    DLSS_LOG_ERROR("DLSS Feature creation failed 3 times. Disabling.");
                }
                return res;
            }
        }

        if (!m_dlssManager.getFeature()) {
            return NVSDK_NGX_Result_FAIL_FeatureNotFound;
        }

        // Validate and clamp jitter
        float clampedJitterX = jitterX;
        float clampedJitterY = jitterY;
        JitterValidation::validateAndClamp(clampedJitterX, clampedJitterY, "EvaluateDLSS");

        // Prepare resources
        VkImageSubresourceRange subRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        VkImageSubresourceRange depthRange = {
            VulkanHelpers::getAspectMask(static_cast<VkFormat>(depthFormat)), 0, 1, 0, 1
        };

        int renderW = m_dlssManager.getRenderWidth();
        int renderH = m_dlssManager.getRenderHeight();
        int outW = m_dlssManager.getOutputWidth();
        int outH = m_dlssManager.getOutputHeight();

        NVSDK_NGX_Resource_VK rColor = NVSDK_NGX_Create_ImageView_Resource_VK(
            color, colorImg, subRange, static_cast<VkFormat>(colorFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rDepth = NVSDK_NGX_Create_ImageView_Resource_VK(
            depth, depthImg, depthRange, static_cast<VkFormat>(depthFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rMV = NVSDK_NGX_Create_ImageView_Resource_VK(
            mv, mvImg, subRange, static_cast<VkFormat>(mvFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rOutput = NVSDK_NGX_Create_ImageView_Resource_VK(
            output, outputImg, subRange, static_cast<VkFormat>(outputFormat), outW, outH, true);

        NVSDK_NGX_VK_DLSS_Eval_Params evalParams = {};
        evalParams.Feature.pInColor = &rColor;
        evalParams.Feature.pInOutput = &rOutput;
        evalParams.pInDepth = &rDepth;
        evalParams.pInMotionVectors = &rMV;
        evalParams.InJitterOffsetX = -clampedJitterX;
        evalParams.InJitterOffsetY = -clampedJitterY;
        evalParams.InRenderSubrectDimensions = {
            static_cast<unsigned int>(renderW),
            static_cast<unsigned int>(renderH)
        };
        evalParams.InMVScaleX = Constants::MV_SCALE_X;
        evalParams.InMVScaleY = Constants::MV_SCALE_Y;

        // Throttled logging
        static std::atomic<int> dlssEvalCount(0);
        if (Logger::instance().shouldLogThrottled(dlssEvalCount)) {
            DLSS_LOG_INFO("=== DLSS Evaluation (frame " + std::to_string(dlssEvalCount) + ") ===");
            DLSS_LOG_INFO(" Jitter: (" + std::to_string(clampedJitterX) + ", " +
                         std::to_string(clampedJitterY) + ")");
            DLSS_LOG_INFO(" RenderDims: " + std::to_string(renderW) + "x" + std::to_string(renderH));
            DLSS_LOG_INFO(" MVScale: (" + std::to_string(Constants::MV_SCALE_X) + ", " +
                         std::to_string(Constants::MV_SCALE_Y) + ")");
        }

        // Layout transitions
        VulkanHelpers::transitionImageLayout(cmdBuffer, colorImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, mvImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, depthImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VulkanHelpers::getAspectMask(static_cast<VkFormat>(depthFormat)));

        VulkanHelpers::transitionImageLayout(cmdBuffer, outputImg,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, VK_ACCESS_SHADER_WRITE_BIT);

        NVSDK_NGX_Result res = NGX_VULKAN_EVALUATE_DLSS_EXT(
            cmdBuffer, m_dlssManager.getFeature(), m_lifecycle.getParameters(), &evalParams);

        // Layout transitions (Restore)
        VulkanHelpers::transitionImageLayout(cmdBuffer, colorImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, mvImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, depthImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT, VulkanHelpers::getAspectMask(static_cast<VkFormat>(depthFormat)));


        if (NVSDK_NGX_FAILED(res)) {
            DLSS_LOG_ERROR("EvaluateDLSS failed: " +
                          ErrorInfo::formatNGXResult(res));
            return res;
        }

        return NVSDK_NGX_Result_Success;
    }

    // --- DLSSD (Ray Reconstruction) ---

    NVSDK_NGX_Result InitDLSSD(int renderWidth, int renderHeight, int outWidth, int outHeight,
                               int denoiseMode, int roughnessMode, int depthType, int quality) {
        if (!m_lifecycle.isInitialized()) {
            DLSS_LOG_ERROR("InitDLSSD called before NGX Initialization!");
            return NVSDK_NGX_Result_FAIL_NotInitialized;
        }

        // Do not reject DLSSD solely because the capability parameter is false.
        // Feature creation below will return the authoritative NGX result.
        if (!m_lifecycle.isDLSSDAvailable()) {
            DLSS_LOG_WARN("Ray Reconstruction capability bit is false during init; configuring DLSSD anyway.");
        }

        m_dlssdManager.destroy();

        DLSSDFeatureManager::CreateParams params = {};
        params.renderWidth = renderWidth;
        params.renderHeight = renderHeight;
        params.outputWidth = outWidth;
        params.outputHeight = outHeight;
        params.denoiseMode = static_cast<NVSDK_NGX_DLSS_Denoise_Mode>(denoiseMode);
        params.roughnessMode = static_cast<NVSDK_NGX_DLSS_Roughness_Mode>(roughnessMode);
        params.depthType = static_cast<NVSDK_NGX_DLSS_Depth_Type>(depthType);
        params.quality = mapQualityDLSSD(quality);

        auto result = m_dlssdManager.configure(params);
        return result.isOk() ? NVSDK_NGX_Result_Success : result.error().ngxResult;
    }

    void DestroyDLSSD() {
        m_dlssdManager.destroy();
    }

    NVSDK_NGX_Result EvaluateDLSSD(
        VkCommandBuffer cmdBuffer,
        VkImageView color, VkImage colorImg, int colorFormat,
        VkImageView depth, VkImage depthImg, int depthFormat,
        VkImageView mv, VkImage mvImg, int mvFormat,
        VkImageView diffAlb, VkImage diffAlbImg, int diffAlbFormat,
        VkImageView specAlb, VkImage specAlbImg, int specAlbFormat,
        VkImageView normals, VkImage normalsImg, int normalsFormat,
        VkImageView rough, VkImage roughImg, int roughFormat,
        VkImageView specHitDepth, VkImage specHitDepthImg, int specHitDepthFormat,
        VkImageView output, VkImage outputImg, int outputFormat,
        float jitterX, float jitterY, int reset, float dt,
        float* worldToViewMatrix, float* viewToClipMatrix
    ) {
        std::lock_guard<std::mutex> lock(m_mutex);

        if (!m_lifecycle.isInitialized()) {
            return NVSDK_NGX_Result_FAIL_NotInitialized;
        }

        // Throttled logging
        static std::atomic<int> dlssdEvalCount(0);
        bool shouldLog = Logger::instance().shouldLogThrottled(dlssdEvalCount) || reset;

        // Validate and clamp jitter
        float clampedJitterX = jitterX;
        float clampedJitterY = jitterY;
        JitterValidation::validateAndClamp(clampedJitterX, clampedJitterY, "EvaluateDLSSD");

        if (shouldLog) {
            DLSS_LOG_INFO("=== EvaluateDLSSD (frame " + std::to_string(dlssdEvalCount) + ") ===");
            DLSS_LOG_INFO(" Jitter: (" + std::to_string(clampedJitterX) + ", " +
                         std::to_string(clampedJitterY) + ")");
            DLSS_LOG_INFO(" Reset: " + std::to_string(reset) + ", dt: " + std::to_string(dt) + "ms");
        }

        // Validate null handles - images are required, views are optional (can use image directly)
        if (colorImg == 0) {
            DLSS_LOG_ERROR("Color image is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (depthImg == 0) {
            DLSS_LOG_ERROR("Depth image is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (mvImg == 0) {
            DLSS_LOG_ERROR("Motion vector image is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (diffAlbImg == 0) {
            DLSS_LOG_ERROR("Diffuse albedo image is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (specAlbImg == 0) {
            DLSS_LOG_ERROR("Specular albedo image is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (normalsImg == 0) {
            DLSS_LOG_ERROR("Normals image is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (specHitDepthImg == 0) {
            DLSS_LOG_ERROR("Specular hit depth image is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }
        if (outputImg == 0) {
            DLSS_LOG_ERROR("Output image is NULL!");
            return NVSDK_NGX_Result_FAIL_InvalidParameter;
        }

        // Create feature if pending
        if (m_dlssdManager.isPending()) {
            DLSS_LOG_INFO("Creating DLSSD feature (pending)...");
            NVSDK_NGX_Result res = m_dlssdManager.createFeature(
                m_lifecycle.getDevice(), cmdBuffer, m_lifecycle.getParameters(),
                m_dlssManager.getFeaturePtr());

            if (NVSDK_NGX_FAILED(res)) {
                m_dlssdManager.incrementFailures();
                if (m_dlssdManager.getFailures() >= Constants::MAX_CREATION_FAILURES) {
                    m_dlssdManager.clearPending();
                    DLSS_LOG_ERROR("DLSSD Feature creation failed 3 times. Disabling.");
                }
                return res;
            }

            // Force reset on first frame
            if (reset == 0) {
                DLSS_LOG_INFO("Forcing reset=1 for first frame after DLSSD feature creation");
                reset = 1;
            }
        }

        // Fallback to standard DLSS if DLSSD not available
        if (!m_dlssdManager.getFeature()) {
            if (m_dlssManager.getFeature()) {
                DLSS_LOG_WARN("DLSSD feature not available, using standard DLSS fallback");
                return EvaluateDLSS_Internal(cmdBuffer,
                    color, colorImg, colorFormat,
                    depth, depthImg, depthFormat,
                    mv, mvImg, mvFormat,
                    output, outputImg, outputFormat,
                    clampedJitterX, clampedJitterY);
            }
            DLSS_LOG_ERROR("Neither DLSSD nor DLSS feature available!");
            return NVSDK_NGX_Result_FAIL_FeatureNotFound;
        }

        // Prepare resources
        VkImageSubresourceRange subRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        VkImageSubresourceRange depthRange = {
            VulkanHelpers::getAspectMask(static_cast<VkFormat>(depthFormat)), 0, 1, 0, 1
        };

        int renderW = m_dlssdManager.getRenderWidth();
        int renderH = m_dlssdManager.getRenderHeight();
        int outW = m_dlssdManager.getOutputWidth();
        int outH = m_dlssdManager.getOutputHeight();

        // Create image views if not provided (view=0 means use image directly)
        // NGX SDK requires image views, so we create temporary views when needed
        VkImageView colorView = color;
        VkImageView depthView = depth;
        VkImageView mvView = mv;
        VkImageView outputView = output;

        // Note: When view is 0, we pass the image handle as both view and image
        // The NGX SDK's NVSDK_NGX_Create_ImageView_Resource_VK will handle this
        // by using the image directly if the view is VK_NULL_HANDLE (0)

        NVSDK_NGX_Resource_VK rColor = NVSDK_NGX_Create_ImageView_Resource_VK(
            colorView, colorImg, subRange, static_cast<VkFormat>(colorFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rDepth = NVSDK_NGX_Create_ImageView_Resource_VK(
            depthView, depthImg, depthRange, static_cast<VkFormat>(depthFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rMV = NVSDK_NGX_Create_ImageView_Resource_VK(
            mvView, mvImg, subRange, static_cast<VkFormat>(mvFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rDiffAlb = NVSDK_NGX_Create_ImageView_Resource_VK(
            diffAlb, diffAlbImg, subRange, static_cast<VkFormat>(diffAlbFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rSpecAlb = NVSDK_NGX_Create_ImageView_Resource_VK(
            specAlb, specAlbImg, subRange, static_cast<VkFormat>(specAlbFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rNormals = NVSDK_NGX_Create_ImageView_Resource_VK(
            normals, normalsImg, subRange, static_cast<VkFormat>(normalsFormat), renderW, renderH, false);
        VkImageSubresourceRange specHitDepthRange = {
            VulkanHelpers::getAspectMask(static_cast<VkFormat>(specHitDepthFormat)), 0, 1, 0, 1
        };
        NVSDK_NGX_Resource_VK rSpecHitDepth = NVSDK_NGX_Create_ImageView_Resource_VK(
            specHitDepth, specHitDepthImg, specHitDepthRange,
            static_cast<VkFormat>(specHitDepthFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rOutput = NVSDK_NGX_Create_ImageView_Resource_VK(
            outputView, outputImg, subRange, static_cast<VkFormat>(outputFormat), outW, outH, true);

        // Roughness is packed into normals.w by default. NVIDIA's Vulkan RR
        // sample still passes the normal/roughness resource as pInRoughness.
        NVSDK_NGX_Resource_VK* pRough = &rNormals;
        NVSDK_NGX_Resource_VK rRough = {};

        if (m_dlssdManager.getRoughnessMode() == NVSDK_NGX_DLSS_Roughness_Mode_Unpacked) {
            if (rough == 0 || roughImg == 0) {
                DLSS_LOG_ERROR("Roughness image/view is NULL but RoughnessMode=Unpacked!");
                return NVSDK_NGX_Result_FAIL_InvalidParameter;
            }
            rRough = NVSDK_NGX_Create_ImageView_Resource_VK(
                rough, roughImg, subRange, static_cast<VkFormat>(roughFormat), renderW, renderH, false);
            pRough = &rRough;
        }

        // Setup evaluation parameters
        NVSDK_NGX_VK_DLSSD_Eval_Params evalParams = {};
        evalParams.pInColor = &rColor;
        evalParams.pInDepth = &rDepth;
        evalParams.pInMotionVectors = &rMV;
        evalParams.pInOutput = &rOutput;
        evalParams.pInDiffuseAlbedo = &rDiffAlb;
        evalParams.pInSpecularAlbedo = &rSpecAlb;
        evalParams.pInNormals = &rNormals;
        evalParams.pInRoughness = pRough;
        evalParams.pInSpecularHitDistance = &rSpecHitDepth;
        evalParams.InJitterOffsetX = -clampedJitterX;
        evalParams.InJitterOffsetY = -clampedJitterY;
        evalParams.InReset = reset;
        evalParams.InMVScaleX = Constants::MV_SCALE_X;
        evalParams.InMVScaleY = Constants::MV_SCALE_Y;
        evalParams.InFrameTimeDeltaInMsec = dt;
        evalParams.InRenderSubrectDimensions = {
            static_cast<unsigned int>(renderW),
            static_cast<unsigned int>(renderH)
        };
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
        evalParams.InPreExposure = Constants::PRE_EXPOSURE_DEFAULT;
        evalParams.InExposureScale = Constants::EXPOSURE_SCALE_DEFAULT;
        evalParams.pInWorldToViewMatrix = worldToViewMatrix;
        evalParams.pInViewToClipMatrix = viewToClipMatrix;

        if (shouldLog) {
            DLSS_LOG_INFO("=== DLSSD Evaluate Parameters ===");
            DLSS_LOG_INFO(" RenderDims: " + std::to_string(renderW) + "x" + std::to_string(renderH));
            DLSS_LOG_INFO(" OutputDims: " + std::to_string(outW) + "x" + std::to_string(outH));
            DLSS_LOG_INFO(" MVScale: (" + std::to_string(Constants::MV_SCALE_X) + ", " +
                         std::to_string(Constants::MV_SCALE_Y) + ")");
            DLSS_LOG_INFO(std::string(" RoughnessMode: ") +
                         (m_dlssdManager.getRoughnessMode() == NVSDK_NGX_DLSS_Roughness_Mode_Packed ?
                          "Packed" : "Unpacked"));
        }

        // Layout transitions
        VulkanHelpers::transitionImageLayout(cmdBuffer, colorImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, mvImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, diffAlbImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, specAlbImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, normalsImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, depthImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VulkanHelpers::getAspectMask(static_cast<VkFormat>(depthFormat)));

        VulkanHelpers::transitionImageLayout(cmdBuffer, specHitDepthImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VulkanHelpers::getAspectMask(static_cast<VkFormat>(specHitDepthFormat)));

        if (roughImg != VK_NULL_HANDLE) {
            VulkanHelpers::transitionImageLayout(cmdBuffer, roughImg,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
        }

        // Output transition (track layout state)
        VkImageLayout outputOldLayout = m_dlssdManager.isOutputLayoutInitialized() ?
            VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
        VulkanHelpers::transitionImageLayout(cmdBuffer, outputImg,
            outputOldLayout, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        m_dlssdManager.setOutputLayoutInitialized();

        NVSDK_NGX_Result res = NGX_VULKAN_EVALUATE_DLSSD_EXT(
            cmdBuffer, m_dlssdManager.getFeature(), m_lifecycle.getParameters(), &evalParams);

        // Layout transitions (Restore)
        VulkanHelpers::transitionImageLayout(cmdBuffer, colorImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, mvImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, diffAlbImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, specAlbImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, normalsImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, depthImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT, VulkanHelpers::getAspectMask(static_cast<VkFormat>(depthFormat)));

        VulkanHelpers::transitionImageLayout(cmdBuffer, specHitDepthImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT, VulkanHelpers::getAspectMask(static_cast<VkFormat>(specHitDepthFormat)));

        if (roughImg != VK_NULL_HANDLE) {
            VulkanHelpers::transitionImageLayout(cmdBuffer, roughImg,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        }


        if (NVSDK_NGX_FAILED(res)) {
            DLSS_LOG_ERROR("EvaluateDLSSD FAILED: " +
                          ErrorInfo::formatNGXResult(res));
            return res;
        }

        if (shouldLog) {
            DLSS_LOG_INFO("EvaluateDLSSD succeeded (frame " + std::to_string(dlssdEvalCount) + ")");
        }

        return NVSDK_NGX_Result_Success;
    }

    // --- Utility Functions ---

    void GetOptimalSettings(int width, int height, int quality, int* renderW, int* renderH) {
        bool usedNGX = false;
        if (m_lifecycle.isInitialized() && m_lifecycle.getParameters()) {
            unsigned int optW, optH, maxW, maxH, minW, minH;
            float sharpness;
            NVSDK_NGX_Result res = NGX_DLSS_GET_OPTIMAL_SETTINGS(
                m_lifecycle.getParameters(), width, height, mapQuality(quality),
                &optW, &optH, &maxW, &maxH, &minW, &minH, &sharpness);

            if (NVSDK_NGX_SUCCEED(res) && optW > 0 && optH > 0) {
                *renderW = static_cast<int>(optW);
                *renderH = static_cast<int>(optH);
                usedNGX = true;
            }
        }

        if (!usedNGX) {
            // Fallback scaling
            float scale = 0.5f;
            switch (quality) {
                case 0: scale = 1.0f; break;        // DLAA
                case 1: scale = 0.6666667f; break; // Quality
                case 2: scale = 0.5833334f; break; // Balanced
                case 3: scale = 0.5f; break;       // Performance
                case 4: scale = 0.3333333f; break; // Ultra Performance
                default: break;
            }
            *renderW = static_cast<int>(width * scale);
            *renderH = static_cast<int>(height * scale);
        }
    }

    bool IsDLSSDAvailable() {
        return m_lifecycle.isDLSSDAvailable();
    }

private:
    DLSSBridge() = default;
    ~DLSSBridge() = default;

    // Internal evaluation without lock (for DLSSD fallback)
    NVSDK_NGX_Result EvaluateDLSS_Internal(
        VkCommandBuffer cmdBuffer,
        VkImageView color, VkImage colorImg, int colorFormat,
        VkImageView depth, VkImage depthImg, int depthFormat,
        VkImageView mv, VkImage mvImg, int mvFormat,
        VkImageView output, VkImage outputImg, int outputFormat,
        float jitterX, float jitterY
    ) {
        // This is called from within EvaluateDLSSD which already holds the lock
        if (!m_lifecycle.isInitialized()) {
            return NVSDK_NGX_Result_FAIL_NotInitialized;
        }

        if (!m_dlssManager.getFeature()) {
            return NVSDK_NGX_Result_FAIL_FeatureNotFound;
        }

        // Use DLSSD dimensions if DLSS dimensions are 0 (fallback case)
        int renderW = m_dlssManager.getRenderWidth();
        int renderH = m_dlssManager.getRenderHeight();
        int outW = m_dlssManager.getOutputWidth();
        int outH = m_dlssManager.getOutputHeight();

        if (renderW == 0 || renderH == 0) {
            renderW = m_dlssdManager.getRenderWidth();
            renderH = m_dlssdManager.getRenderHeight();
            outW = m_dlssdManager.getOutputWidth();
            outH = m_dlssdManager.getOutputHeight();
        }

        VkImageSubresourceRange subRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        VkImageSubresourceRange depthRange = {
            VulkanHelpers::getAspectMask(static_cast<VkFormat>(depthFormat)), 0, 1, 0, 1
        };

        NVSDK_NGX_Resource_VK rColor = NVSDK_NGX_Create_ImageView_Resource_VK(
            color, colorImg, subRange, static_cast<VkFormat>(colorFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rDepth = NVSDK_NGX_Create_ImageView_Resource_VK(
            depth, depthImg, depthRange, static_cast<VkFormat>(depthFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rMV = NVSDK_NGX_Create_ImageView_Resource_VK(
            mv, mvImg, subRange, static_cast<VkFormat>(mvFormat), renderW, renderH, false);
        NVSDK_NGX_Resource_VK rOutput = NVSDK_NGX_Create_ImageView_Resource_VK(
            output, outputImg, subRange, static_cast<VkFormat>(outputFormat), outW, outH, true);

        NVSDK_NGX_VK_DLSS_Eval_Params evalParams = {};
        evalParams.Feature.pInColor = &rColor;
        evalParams.Feature.pInOutput = &rOutput;
        evalParams.pInDepth = &rDepth;
        evalParams.pInMotionVectors = &rMV;
        evalParams.InJitterOffsetX = -jitterX;
        evalParams.InJitterOffsetY = -jitterY;
        evalParams.InRenderSubrectDimensions = {
            static_cast<unsigned int>(renderW),
            static_cast<unsigned int>(renderH)
        };
        evalParams.InMVScaleX = Constants::MV_SCALE_X;
        evalParams.InMVScaleY = Constants::MV_SCALE_Y;

        VulkanHelpers::transitionImageLayout(cmdBuffer, colorImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, mvImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, depthImg,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VulkanHelpers::getAspectMask(static_cast<VkFormat>(depthFormat)));

        VulkanHelpers::transitionImageLayout(cmdBuffer, outputImg,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, VK_ACCESS_SHADER_WRITE_BIT);

        NVSDK_NGX_Result res = NGX_VULKAN_EVALUATE_DLSS_EXT(
            cmdBuffer, m_dlssManager.getFeature(), m_lifecycle.getParameters(), &evalParams);

        // Layout transitions (Restore)
        VulkanHelpers::transitionImageLayout(cmdBuffer, colorImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, mvImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);

        VulkanHelpers::transitionImageLayout(cmdBuffer, depthImg,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT, VulkanHelpers::getAspectMask(static_cast<VkFormat>(depthFormat)));

        if (NVSDK_NGX_FAILED(res)) {
            DLSS_LOG_ERROR("EvaluateDLSS_Internal failed: " + std::to_string(res));
        }

        return res;
    }

    // Quality mapping functions
    [[nodiscard]] static NVSDK_NGX_PerfQuality_Value mapQuality(int q) noexcept {
        switch (q) {
            case 0: return NVSDK_NGX_PerfQuality_Value_MaxPerf;        // Performance
            case 1: return NVSDK_NGX_PerfQuality_Value_Balanced;      // Balanced
            case 2: return NVSDK_NGX_PerfQuality_Value_MaxQuality;    // Quality
            case 3: return NVSDK_NGX_PerfQuality_Value_UltraPerformance; // Ultra Performance
            case 4: return NVSDK_NGX_PerfQuality_Value_UltraQuality; // Ultra Quality
            case 5: return NVSDK_NGX_PerfQuality_Value_DLAA;          // DLAA
            default: return NVSDK_NGX_PerfQuality_Value_Balanced;
        }
    }

    [[nodiscard]] static NVSDK_NGX_PerfQuality_Value mapQualityDLSSD(int q) noexcept {
        // DLSSD does not support DLAA mode
        switch (q) {
            case 0: return NVSDK_NGX_PerfQuality_Value_MaxPerf;
            case 1: return NVSDK_NGX_PerfQuality_Value_Balanced;
            case 2: return NVSDK_NGX_PerfQuality_Value_MaxQuality;
            case 3: return NVSDK_NGX_PerfQuality_Value_UltraPerformance;
            case 4: return NVSDK_NGX_PerfQuality_Value_UltraQuality;
            case 5: return NVSDK_NGX_PerfQuality_Value_Balanced; // DLAA -> Balanced
            default: return NVSDK_NGX_PerfQuality_Value_Balanced;
        }
    }

    std::mutex m_mutex;
    NGXLifecycleManager m_lifecycle;
    DLSSFeatureManager m_dlssManager;
    DLSSDFeatureManager m_dlssdManager;
};

// ============================================================================
// C INTERFACE (JNA EXPORTS)
// ============================================================================

extern "C" {

__declspec(dllexport) int initializeNGX(
    VkInstance instance,
    VkPhysicalDevice physicalDevice,
    VkDevice device,
    const char* dlssPath
) {
    return static_cast<int>(DLSSBridge::Get().InitializeNGX(instance, physicalDevice, device, dlssPath));
}

__declspec(dllexport) int initDLSS(
    VkInstance instance,
    VkPhysicalDevice physicalDevice,
    VkDevice device,
    int width,
    int height,
    int outWidth,
    int outHeight
) {
    if (NVSDK_NGX_FAILED(DLSSBridge::Get().InitializeNGX(instance, physicalDevice, device, nullptr))) {
        return static_cast<int>(NVSDK_NGX_Result_FAIL_NotInitialized);
    }

    // Determine quality based on resolution ratio
    float ratio = static_cast<float>(width) / static_cast<float>(outWidth);
    NVSDK_NGX_PerfQuality_Value quality = NVSDK_NGX_PerfQuality_Value_Balanced;

    if (ratio > 0.99f) quality = NVSDK_NGX_PerfQuality_Value_DLAA;
    else if (ratio > 0.66f) quality = NVSDK_NGX_PerfQuality_Value_MaxQuality;
    else if (ratio > 0.58f) quality = NVSDK_NGX_PerfQuality_Value_Balanced;
    else if (ratio > 0.49f) quality = NVSDK_NGX_PerfQuality_Value_MaxPerf;
    else quality = NVSDK_NGX_PerfQuality_Value_UltraPerformance;

    return static_cast<int>(DLSSBridge::Get().InitDLSS(width, height, outWidth, outHeight, quality));
}

__declspec(dllexport) void destroyDLSS(VkDevice device) {
    DLSSBridge::Get().DestroyDLSS();
}

__declspec(dllexport) int evaluateDLSS(
    VkCommandBuffer cmdBuffer,
    VkImageView colorImageView,
    VkImage colorImage,
    int colorFormat,
    VkImageView depthImageView,
    VkImage depthImage,
    int depthFormat,
    VkImageView mvImageView,
    VkImage mvImage,
    int mvFormat,
    VkImageView outputImageView,
    VkImage outputImage,
    int outputFormat,
    float jitterX,
    float jitterY
) {
    return static_cast<int>(DLSSBridge::Get().EvaluateDLSS(
        cmdBuffer,
        colorImageView, colorImage, colorFormat,
        depthImageView, depthImage, depthFormat,
        mvImageView, mvImage, mvFormat,
        outputImageView, outputImage, outputFormat,
        jitterX, jitterY
    ));
}

__declspec(dllexport) int initDLSSD(
    VkInstance instance,
    VkPhysicalDevice physicalDevice,
    VkDevice device,
    int width,
    int height,
    int outWidth,
    int outHeight,
    int denoiseMode,
    int roughnessMode,
    int depthType,
    int perfQualityValue
) {
    NVSDK_NGX_Result res = DLSSBridge::Get().InitializeNGX(instance, physicalDevice, device, nullptr);
    if (NVSDK_NGX_FAILED(res)) {
        return static_cast<int>(res);
    }
    return static_cast<int>(DLSSBridge::Get().InitDLSSD(
        width, height, outWidth, outHeight,
        denoiseMode, roughnessMode, depthType, perfQualityValue
    ));
}

__declspec(dllexport) void destroyDLSSD(VkDevice device) {
    DLSSBridge::Get().DestroyDLSSD();
}

__declspec(dllexport) void shutdownNGX() {
    DLSSBridge::Get().ShutdownNGX();
}

__declspec(dllexport) int evaluateDLSSD(
    VkCommandBuffer cmdBuffer,
    VkImageView colorImageView,
    VkImage colorImage,
    int colorFormat,
    VkImageView depthImageView,
    VkImage depthImage,
    int depthFormat,
    VkImageView mvImageView,
    VkImage mvImage,
    int mvFormat,
    VkImageView diffuseAlbedoImageView,
    VkImage diffuseAlbedoImage,
    int diffuseAlbedoFormat,
    VkImageView specularAlbedoImageView,
    VkImage specularAlbedoImage,
    int specularAlbedoFormat,
    VkImageView normalsImageView,
    VkImage normalsImage,
    int normalsFormat,
    VkImageView roughnessImageView,
    VkImage roughnessImage,
    int roughnessFormat,
    VkImageView specularHitDepthImageView,
    VkImage specularHitDepthImage,
    int specularHitDepthFormat,
    VkImageView outputImageView,
    VkImage outputImage,
    int outputFormat,
    float jitterX,
    float jitterY,
    int reset,
    float frameTimeDeltaMs,
    float* worldToViewMatrix,
    float* viewToClipMatrix
) {
    return static_cast<int>(DLSSBridge::Get().EvaluateDLSSD(
        cmdBuffer,
        colorImageView, colorImage, colorFormat,
        depthImageView, depthImage, depthFormat,
        mvImageView, mvImage, mvFormat,
        diffuseAlbedoImageView, diffuseAlbedoImage, diffuseAlbedoFormat,
        specularAlbedoImageView, specularAlbedoImage, specularAlbedoFormat,
        normalsImageView, normalsImage, normalsFormat,
        roughnessImageView, roughnessImage, roughnessFormat,
        specularHitDepthImageView, specularHitDepthImage, specularHitDepthFormat,
        outputImageView, outputImage, outputFormat,
        jitterX, jitterY, reset, frameTimeDeltaMs,
        worldToViewMatrix, viewToClipMatrix
    ));
}

__declspec(dllexport) int isDLSSDAvailable() {
    return DLSSBridge::Get().IsDLSSDAvailable() ? 1 : 0;
}

__declspec(dllexport) void getDLSSDRenderResolution(
    int outWidth,
    int outHeight,
    int qualityPreset,
    int* outRenderWidth,
    int* outRenderHeight
) {
    DLSSBridge::Get().GetOptimalSettings(outWidth, outHeight, qualityPreset,
                                          outRenderWidth, outRenderHeight);
}

} // extern "C"

// Feature discovery info holder
class FeatureDiscoveryInfoHolder {
public:
    std::wstring searchPath = VulkanHelpers::getNativeModuleDirectory();
    const wchar_t* paths[1] = { searchPath.c_str() };
    NVSDK_NGX_PathListInfo pathListInfo = { paths, 1 };
    NVSDK_NGX_FeatureCommonInfo featureInfo = {};

    FeatureDiscoveryInfoHolder() {
        featureInfo.PathListInfo = pathListInfo;
    }
};

static NVSDK_NGX_FeatureDiscoveryInfo GetFeatureDiscoveryInfo(NVSDK_NGX_Feature feature) {
    static FeatureDiscoveryInfoHolder holder;
    NVSDK_NGX_FeatureDiscoveryInfo discoveryInfo = {};
    discoveryInfo.SDKVersion = NVSDK_NGX_Version_API;
    discoveryInfo.FeatureID = feature;
    discoveryInfo.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
    discoveryInfo.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
    discoveryInfo.Identifier.v.ProjectDesc.EngineVersion = Constants::NGX_ENGINE_VERSION;
    discoveryInfo.Identifier.v.ProjectDesc.ProjectId = Constants::NGX_PROJECT_ID;
    discoveryInfo.ApplicationDataPath = holder.searchPath.c_str();
    discoveryInfo.FeatureInfo = &holder.featureInfo;
    return discoveryInfo;
}

static void AddUniqueExtension(std::vector<std::string>& extensions, const char* extensionName) {
    if (extensionName == nullptr || extensionName[0] == '\0') {
        return;
    }
    if (std::find(extensions.begin(), extensions.end(), extensionName) == extensions.end()) {
        extensions.emplace_back(extensionName);
    }
}

std::string ErrorInfo::formatNGXResult(NVSDK_NGX_Result res) {
    std::ostringstream oss;
    oss << getNGXErrorString(res)
        << " (dec=" << static_cast<int>(res)
        << ", hex=0x" << std::hex << static_cast<uint32_t>(res) << ")";
    return oss.str();
}

static std::vector<std::string> CollectInstanceExtensions() {
    std::vector<std::string> extensions;
    const std::pair<NVSDK_NGX_Feature, const char*> features[] = {
        { NVSDK_NGX_Feature_RayReconstruction, "Ray Reconstruction" },
        { NVSDK_NGX_Feature_SuperSampling, "DLSS Super Resolution" }
    };

    for (const auto& feature : features) {
        uint32_t count = 0;
        VkExtensionProperties* props = nullptr;
        NVSDK_NGX_FeatureDiscoveryInfo featureInfo = GetFeatureDiscoveryInfo(feature.first);
        NVSDK_NGX_Result res = NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(
            &featureInfo, &count, &props);
        if (NVSDK_NGX_FAILED(res)) {
            DLSS_LOG_WARN(std::string("Instance extension query failed for ") +
                         feature.second + ": " + ErrorInfo::formatNGXResult(res));
            continue;
        }
        DLSS_LOG_INFO(std::string("NGX instance extensions for ") +
                     feature.second + ": " + std::to_string(count));
        for (uint32_t i = 0; i < count && props != nullptr; i++) {
            AddUniqueExtension(extensions, props[i].extensionName);
        }
    }

    return extensions;
}

static std::vector<std::string> CollectDeviceExtensions(VkInstance instance, VkPhysicalDevice physicalDevice) {
    std::vector<std::string> extensions;
    const std::pair<NVSDK_NGX_Feature, const char*> features[] = {
        { NVSDK_NGX_Feature_RayReconstruction, "Ray Reconstruction" },
        { NVSDK_NGX_Feature_SuperSampling, "DLSS Super Resolution" }
    };

    for (const auto& feature : features) {
        uint32_t count = 0;
        VkExtensionProperties* props = nullptr;
        NVSDK_NGX_FeatureDiscoveryInfo featureInfo = GetFeatureDiscoveryInfo(feature.first);
        NVSDK_NGX_Result res = NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(
            instance, physicalDevice, &featureInfo, &count, &props);
        if (NVSDK_NGX_FAILED(res)) {
            DLSS_LOG_WARN(std::string("Device extension query failed for ") +
                         feature.second + ": " + ErrorInfo::formatNGXResult(res));
            continue;
        }
        DLSS_LOG_INFO(std::string("NGX device extensions for ") +
                     feature.second + ": " + std::to_string(count));
        for (uint32_t i = 0; i < count && props != nullptr; i++) {
            AddUniqueExtension(extensions, props[i].extensionName);
        }
    }

    return extensions;
}

static std::vector<std::string> g_instanceExtensionCache;
static std::vector<std::string> g_deviceExtensionCache;

extern "C" {

__declspec(dllexport) int getDLSSDAvailabilityInfo(
    int* needsDriverUpdate,
    int* minDriverMajor,
    int* minDriverMinor
) {
    if (!DLSSBridge::Get().IsDLSSDAvailable()) {
        if (needsDriverUpdate) *needsDriverUpdate = 0;
        if (minDriverMajor) *minDriverMajor = 0;
        if (minDriverMinor) *minDriverMinor = 0;
        return 0;
    }

    if (needsDriverUpdate) *needsDriverUpdate = 0;
    if (minDriverMajor) *minDriverMajor = 0;
    if (minDriverMinor) *minDriverMinor = 0;
    return 1;
}

__declspec(dllexport) const char* getNGXErrorDescription(int errorCode) {
    return ErrorInfo::getNGXErrorString(static_cast<NVSDK_NGX_Result>(errorCode));
}

__declspec(dllexport) uint32_t getNGXInstanceExtensionCount() {
    g_instanceExtensionCache = CollectInstanceExtensions();
    return static_cast<uint32_t>(g_instanceExtensionCache.size());
}

__declspec(dllexport) const char* getNGXInstanceExtension(uint32_t index) {
    if (g_instanceExtensionCache.empty()) {
        g_instanceExtensionCache = CollectInstanceExtensions();
    }
    if (index < g_instanceExtensionCache.size()) {
        return g_instanceExtensionCache[index].c_str();
    }
    return nullptr;
}

__declspec(dllexport) uint32_t getNGXDeviceExtensionCount(
    VkInstance instance,
    VkPhysicalDevice physicalDevice
) {
    g_deviceExtensionCache = CollectDeviceExtensions(instance, physicalDevice);
    return static_cast<uint32_t>(g_deviceExtensionCache.size());
}

__declspec(dllexport) const char* getNGXDeviceExtension(
    VkInstance instance,
    VkPhysicalDevice physicalDevice,
    uint32_t index
) {
    if (g_deviceExtensionCache.empty()) {
        g_deviceExtensionCache = CollectDeviceExtensions(instance, physicalDevice);
    }
    if (index < g_deviceExtensionCache.size()) {
        return g_deviceExtensionCache[index].c_str();
    }
    return nullptr;
}

} // extern "C"
