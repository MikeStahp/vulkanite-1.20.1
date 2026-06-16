package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.client.config.DLSSConfig;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VImage;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Validator for DLSSD (Ray Reconstruction) parameters.
 * 
 * <p>This class validates all parameters passed to DLSSD evaluation and logs
 * them to a separate log file for debugging and troubleshooting.</p>
 * 
 * <h2>Parameters Validated</h2>
 * <ul>
 *   <li>Resolution: Input render dimensions vs output dimensions</li>
 *   <li>Quality mode: Valid NGX quality preset values</li>
 *   <li>Image formats: Color, depth, motion vectors, G-buffer formats</li>
 *   <li>Jitter offsets: Range validation [-0.5, 0.5]</li>
 *   <li>Depth type: Linear vs Hardware depth</li>
 *   <li>Roughness mode: Packed vs Unpacked</li>
 *   <li>Temporal state: Reset flag and history validity</li>
 * </ul>
 * 
 * <h2>Log File Location</h2>
 * <p>Logs are written to: run/logs/dlssd_validator.log</p>
 */
public class DLSSDParameterValidator {
    
    private static final String LOG_FILE_NAME = "dlssd_validator.log";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    // Vulkan format constants for validation
    private static final int[] VALID_COLOR_FORMATS = {
        VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_FORMAT_R32G32B32A32_SFLOAT,
        VK_FORMAT_R8G8B8A8_UNORM,
        VK_FORMAT_B8G8R8A8_UNORM
    };
    
    private static final int[] VALID_DEPTH_FORMATS = {
        VK_FORMAT_R32_SFLOAT,
        VK_FORMAT_D32_SFLOAT,
        VK_FORMAT_R16_SFLOAT
    };
    
    private static final int[] VALID_MV_FORMATS = {
        VK_FORMAT_R16G16_SFLOAT,
        VK_FORMAT_R32G32_SFLOAT
    };
    
    private static final int[] VALID_NORMAL_FORMATS = {
        VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_FORMAT_R8G8B8A8_UNORM,
        1000361004 // VK_FORMAT_R10G10B10A2_UNORM_PACK32 (not in VK10)
    };
    
    private static final int[] VALID_ALBEDO_FORMATS = {
        VK_FORMAT_R8G8B8A8_UNORM,
        VK_FORMAT_R16G16B16A16_SFLOAT
    };
    
    private static DLSSDParameterValidator INSTANCE;
    private final Path logFilePath;
    private final List<String> pendingLogs = new ArrayList<>();
    private boolean logInitialized = false;
    
    // Validation state
    private int frameCount = 0;
    private int validationErrors = 0;
    private int validationWarnings = 0;
    
    private DLSSDParameterValidator() {
        // Create logs directory if it doesn't exist
        Path logsDir = Paths.get("run", "logs");
        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            // Fallback to current directory
            logsDir = Paths.get(".");
        }
        this.logFilePath = logsDir.resolve(LOG_FILE_NAME);
    }
    
    public static synchronized DLSSDParameterValidator getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new DLSSDParameterValidator();
        }
        return INSTANCE;
    }
    
    /**
     * Validation result container.
     */
    public static class ValidationResult {
        public final boolean valid;
        public final List<String> errors;
        public final List<String> warnings;
        
        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors;
            this.warnings = warnings;
        }
    }
    
    /**
     * Container for all DLSSD parameters to validate.
     */
    public static class DLSSDParams {
        // Resolution parameters
        public int renderWidth;
        public int renderHeight;
        public int outputWidth;
        public int outputHeight;
        
        // Quality and mode parameters
        public int qualityMode;
        public int roughnessMode;
        public int depthType;
        
        // Jitter parameters
        public float jitterX;
        public float jitterY;
        
        // Temporal parameters
        public int reset;
        public float deltaTimeMs;
        
        // Image parameters
        public long colorImage;
        public int colorFormat;
        public long depthImage;
        public int depthFormat;
        public long motionVectorsImage;
        public int motionVectorsFormat;
        public long outputImage;
        public int outputFormat;
        
        // G-buffer parameters
        public long diffuseAlbedoView;
        public int diffuseAlbedoFormat;
        public long specularAlbedoView;
        public int specularAlbedoFormat;
        public long normalsView;
        public int normalsFormat;
        public long roughnessView;
        public int roughnessFormat;
        
        // Feature handle
        public long featureHandle;
    }
    
    /**
     * Validate all DLSSD parameters before evaluation.
     * 
     * @param params The parameters to validate
     * @return ValidationResult containing errors and warnings
     */
    public ValidationResult validateAll(DLSSDParams params) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        frameCount++;
        
        // Log header for this validation
        logSeparator();
        logInfo(String.format("FRAME %d - DLSSD Parameter Validation", frameCount));
        logInfo(String.format("Timestamp: %s", LocalDateTime.now().format(TIMESTAMP_FORMAT)));
        logSeparator();
        
        // Validate resolution parameters
        validateResolution(params, errors, warnings);
        
        // Validate quality and mode parameters
        validateQualityMode(params, errors, warnings);
        validateDepthType(params, errors, warnings);
        validateRoughnessMode(params, errors, warnings);
        
        // Validate jitter parameters
        validateJitter(params, errors, warnings);
        
        // Validate temporal parameters
        validateTemporal(params, errors, warnings);
        
        // Validate image parameters
        validateImageFormats(params, errors, warnings);
        
        // Validate G-buffer parameters
        validateGBuffer(params, errors, warnings);
        
        // Validate feature handle
        validateFeatureHandle(params, errors, warnings);
        
        // Log summary
        logSeparator();
        if (errors.isEmpty() && warnings.isEmpty()) {
            logInfo("VALIDATION PASSED - All parameters valid");
        } else {
            if (!errors.isEmpty()) {
                logError(String.format("VALIDATION FAILED - %d error(s)", errors.size()));
                for (String error : errors) {
                    logError("  ERROR: " + error);
                }
                validationErrors += errors.size();
            }
            if (!warnings.isEmpty()) {
                logWarning(String.format("VALIDATION WARNINGS - %d warning(s)", warnings.size()));
                for (String warning : warnings) {
                    logWarning("  WARNING: " + warning);
                }
                validationWarnings += warnings.size();
            }
        }
        logSeparator();
        logNewLine();
        
        // Flush logs to file
        flushLogs();
        
        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }
    
    /**
     * Validate resolution parameters.
     */
    private void validateResolution(DLSSDParams params, List<String> errors, List<String> warnings) {
        logSection("RESOLUTION VALIDATION");
        
        // Log resolution values
        logInfo(String.format("  Render Resolution: %d x %d", params.renderWidth, params.renderHeight));
        logInfo(String.format("  Output Resolution: %d x %d", params.outputWidth, params.outputHeight));
        
        // Calculate and log scale factor
        float scaleX = (float) params.outputWidth / params.renderWidth;
        float scaleY = (float) params.outputHeight / params.renderHeight;
        logInfo(String.format("  Scale Factor: X=%.3f, Y=%.3f", scaleX, scaleY));
        
        // Validate minimum dimensions
        if (params.renderWidth < 8 || params.renderHeight < 8) {
            errors.add(String.format("Render dimensions too small: %dx%d (minimum 8x8)", 
                params.renderWidth, params.renderHeight));
        }
        if (params.outputWidth < 8 || params.outputHeight < 8) {
            errors.add(String.format("Output dimensions too small: %dx%d (minimum 8x8)", 
                params.outputWidth, params.outputHeight));
        }
        
        // Validate render <= output
        if (params.renderWidth > params.outputWidth || params.renderHeight > params.outputHeight) {
            errors.add(String.format("Render dimensions (%dx%d) larger than output (%dx%d) - DLSSD requires upscaling", 
                params.renderWidth, params.renderHeight, params.outputWidth, params.outputHeight));
        }
        
        // Check for native resolution (not supported by DLSSD)
        if (params.renderWidth == params.outputWidth && params.renderHeight == params.outputHeight) {
            errors.add("Native resolution mode (render == output) is not supported by DLSSD Ray Reconstruction");
        }
        
        // Validate alignment to 8 pixels
        if ((params.renderWidth & 7) != 0 || (params.renderHeight & 7) != 0) {
            warnings.add(String.format("Render dimensions not aligned to 8: %dx%d", 
                params.renderWidth, params.renderHeight));
        }
        if ((params.outputWidth & 7) != 0 || (params.outputHeight & 7) != 0) {
            warnings.add(String.format("Output dimensions not aligned to 8: %dx%d", 
                params.outputWidth, params.outputHeight));
        }
        
        // Check aspect ratio consistency
        float renderAspect = (float) params.renderWidth / params.renderHeight;
        float outputAspect = (float) params.outputWidth / params.outputHeight;
        if (Math.abs(renderAspect - outputAspect) > 0.01f) {
            warnings.add(String.format("Aspect ratio mismatch: render=%.3f, output=%.3f", 
                renderAspect, outputAspect));
        }
        
        // Validate against config
        DLSSConfig config = DLSSConfig.load();
        ResolutionScaleManager scaleManager = ResolutionScaleManager.getInstance();
        int expectedRenderW = scaleManager.getRenderWidth();
        int expectedRenderH = scaleManager.getRenderHeight();
        
        if (params.renderWidth != expectedRenderW || params.renderHeight != expectedRenderH) {
            warnings.add(String.format("Render dimensions mismatch with ResolutionScaleManager: got %dx%d, expected %dx%d", 
                params.renderWidth, params.renderHeight, expectedRenderW, expectedRenderH));
        }
    }
    
    	/**
	 * Validate quality mode parameter.
	 * Values based on NVSDK_NGX_PerfQuality_Value enum from nvsdk_ngx_defs.h:
	 * 0 = MaxPerf (Performance), 1 = Balanced, 2 = MaxQuality (Quality),
	 * 3 = UltraPerformance, 4 = UltraQuality, 5 = DLAA
	 */
	private void validateQualityMode(DLSSDParams params, List<String> errors, List<String> warnings) {
		logSection("QUALITY MODE VALIDATION");

		logInfo(String.format(" Quality Mode Value: %d", params.qualityMode));

		// Map quality mode to name (based on NVSDK_NGX_PerfQuality_Value)
		String qualityName;
		switch (params.qualityMode) {
			case 0: qualityName = "PERFORMANCE (MaxPerf, 0.5x)"; break;
			case 1: qualityName = "BALANCED (0.583x)"; break;
			case 2: qualityName = "QUALITY (MaxQuality, 0.667x)"; break;
			case 3: qualityName = "ULTRA_PERFORMANCE (0.333x)"; break;
			case 4: qualityName = "ULTRA_QUALITY (0.77x)"; break;
			case 5: qualityName = "DLAA (1.0x, no upscaling)"; break;
			default: qualityName = "UNKNOWN"; break;
		}
		logInfo(String.format(" Quality Mode Name: %s", qualityName));

		// Validate range (0-5 based on SDK)
		if (params.qualityMode < 0 || params.qualityMode > 5) {
			errors.add(String.format("Invalid quality mode: %d (valid range 0-5)", params.qualityMode));
		}

		// Check against config using getNgxValue() for correct mapping
		DLSSConfig config = DLSSConfig.load();
		int configQuality = config.getQualityPreset().getNgxValue();
		if (params.qualityMode != configQuality) {
			warnings.add(String.format("Quality mode mismatch with config: got %d, config has %d", params.qualityMode, configQuality));
		}

		// DLAA mode (5) doesn't perform upscaling
		if (params.qualityMode == 5) {
			warnings.add("Quality mode 5 (DLAA) performs no upscaling - ensure render and output resolutions differ if upscaling is expected");
		}
	}
    
    /**
     * Validate depth type parameter.
     */
    private void validateDepthType(DLSSDParams params, List<String> errors, List<String> warnings) {
        logSection("DEPTH TYPE VALIDATION");
        
        logInfo(String.format("  Depth Type Value: %d", params.depthType));
        
        String depthTypeName;
        switch (params.depthType) {
            case DLSSDProcessor.DEPTH_TYPE_LINEAR: 
                depthTypeName = "LINEAR (recommended for ray tracing)"; 
                break;
            case DLSSDProcessor.DEPTH_TYPE_HW: 
                depthTypeName = "HARDWARE (0-1 range)"; 
                break;
            default: 
                depthTypeName = "UNKNOWN"; 
                break;
        }
        logInfo(String.format("  Depth Type Name: %s", depthTypeName));
        
        // Validate range
        if (params.depthType < 0 || params.depthType > 1) {
            errors.add(String.format("Invalid depth type: %d (0=LINEAR, 1=HW)", params.depthType));
        }
        
        // DLSSD typically requires linear depth for ray tracing
        if (params.depthType != DLSSDProcessor.DEPTH_TYPE_LINEAR) {
            warnings.add("Non-linear depth type may cause issues with ray tracing workloads");
        }
    }
    
    /**
     * Validate roughness mode parameter.
     */
    private void validateRoughnessMode(DLSSDParams params, List<String> errors, List<String> warnings) {
        logSection("ROUGHNESS MODE VALIDATION");
        
        logInfo(String.format("  Roughness Mode Value: %d", params.roughnessMode));
        
        String roughnessModeName;
        switch (params.roughnessMode) {
            case DLSSDProcessor.ROUGHNESS_MODE_UNPACKED: 
                roughnessModeName = "UNPACKED (separate texture)"; 
                break;
            case DLSSDProcessor.ROUGHNESS_MODE_PACKED: 
                roughnessModeName = "PACKED (in normals.w)"; 
                break;
            default: 
                roughnessModeName = "UNKNOWN"; 
                break;
        }
        logInfo(String.format("  Roughness Mode Name: %s", roughnessModeName));
        
        // Validate range
        if (params.roughnessMode < 0 || params.roughnessMode > 1) {
            errors.add(String.format("Invalid roughness mode: %d (0=UNPACKED, 1=PACKED)", params.roughnessMode));
        }
        
        // If unpacked, roughness view should be provided
        if (params.roughnessMode == DLSSDProcessor.ROUGHNESS_MODE_UNPACKED && params.roughnessView == 0) {
            warnings.add("Roughness mode is UNPACKED but no roughness view provided");
        }
    }
    
    /**
     * Validate jitter parameters.
     */
    private void validateJitter(DLSSDParams params, List<String> errors, List<String> warnings) {
        logSection("JITTER VALIDATION");
        
        logInfo(String.format("  Jitter X: %.6f", params.jitterX));
        logInfo(String.format("  Jitter Y: %.6f", params.jitterY));
        
        // Validate range [-0.5, 0.5]
        if (params.jitterX < -0.5f || params.jitterX > 0.5f) {
            errors.add(String.format("Jitter X out of range: %.6f (valid range [-0.5, 0.5])", params.jitterX));
        }
        if (params.jitterY < -0.5f || params.jitterY > 0.5f) {
            errors.add(String.format("Jitter Y out of range: %.6f (valid range [-0.5, 0.5])", params.jitterY));
        }
        
        // Check if jitter is enabled in config
        DLSSConfig config = DLSSConfig.load();
        if (!config.isJitterEnabled() && (params.jitterX != 0 || params.jitterY != 0)) {
            warnings.add("Jitter values provided but jitter is disabled in config");
        }
        
        // Log jitter source
        float expectedJitterX = JitterManager.getJitterX();
        float expectedJitterY = JitterManager.getJitterY();
        logInfo(String.format("  Expected from JitterManager: X=%.6f, Y=%.6f", expectedJitterX, expectedJitterY));
        
        if (Math.abs(params.jitterX - expectedJitterX) > 0.001f || 
            Math.abs(params.jitterY - expectedJitterY) > 0.001f) {
            warnings.add(String.format("Jitter mismatch with JitterManager: got (%.6f, %.6f), expected (%.6f, %.6f)", 
                params.jitterX, params.jitterY, expectedJitterX, expectedJitterY));
        }
    }
    
    /**
     * Validate temporal parameters.
     */
    private void validateTemporal(DLSSDParams params, List<String> errors, List<String> warnings) {
        logSection("TEMPORAL VALIDATION");
        
        logInfo(String.format("  Reset Flag: %d", params.reset));
        logInfo(String.format("  Delta Time: %.3f ms", params.deltaTimeMs));
        
        // Validate reset flag
        if (params.reset < 0 || params.reset > 1) {
            errors.add(String.format("Invalid reset flag: %d (must be 0 or 1)", params.reset));
        }
        
        // Validate delta time
        if (params.deltaTimeMs < 0) {
            errors.add(String.format("Negative delta time: %.3f ms", params.deltaTimeMs));
        }
        if (params.deltaTimeMs > 1000) {
            warnings.add(String.format("Unusually high delta time: %.3f ms (>1 second)", params.deltaTimeMs));
        }
        
        // Log frame time in different units
        logInfo(String.format("  Delta Time: %.6f seconds", params.deltaTimeMs / 1000.0f));
        logInfo(String.format("  Estimated FPS: %.1f", 1000.0f / Math.max(0.001f, params.deltaTimeMs)));
    }
    
    /**
     * Validate image formats.
     */
    private void validateImageFormats(DLSSDParams params, List<String> errors, List<String> warnings) {
        logSection("IMAGE FORMAT VALIDATION");
        
        // Color image
        logInfo(String.format("  Color Image: 0x%X", params.colorImage));
        logInfo(String.format("  Color Format: %s (0x%X)", formatToString(params.colorFormat), params.colorFormat));
        if (!isValidFormat(params.colorFormat, VALID_COLOR_FORMATS)) {
            warnings.add(String.format("Unusual color format: %s", formatToString(params.colorFormat)));
        }
        if (params.colorImage == 0) {
            errors.add("Color image handle is null (0)");
        }
        
        // Depth image
        logInfo(String.format("  Depth Image: 0x%X", params.depthImage));
        logInfo(String.format("  Depth Format: %s (0x%X)", formatToString(params.depthFormat), params.depthFormat));
        if (!isValidFormat(params.depthFormat, VALID_DEPTH_FORMATS)) {
            warnings.add(String.format("Unusual depth format: %s (expected R32_SFLOAT for linear depth)", 
                formatToString(params.depthFormat)));
        }
        if (params.depthImage == 0) {
            errors.add("Depth image handle is null (0)");
        }
        
        // Motion vectors image
        logInfo(String.format("  Motion Vectors Image: 0x%X", params.motionVectorsImage));
        logInfo(String.format("  Motion Vectors Format: %s (0x%X)", formatToString(params.motionVectorsFormat), params.motionVectorsFormat));
        if (!isValidFormat(params.motionVectorsFormat, VALID_MV_FORMATS)) {
            warnings.add(String.format("Unusual motion vectors format: %s", formatToString(params.motionVectorsFormat)));
        }
        if (params.motionVectorsImage == 0) {
            errors.add("Motion vectors image handle is null (0)");
        }
        
        // Output image
        logInfo(String.format("  Output Image: 0x%X", params.outputImage));
        logInfo(String.format("  Output Format: %s (0x%X)", formatToString(params.outputFormat), params.outputFormat));
        if (!isValidFormat(params.outputFormat, VALID_COLOR_FORMATS)) {
            warnings.add(String.format("Unusual output format: %s", formatToString(params.outputFormat)));
        }
        if (params.outputImage == 0) {
            errors.add("Output image handle is null (0)");
        }
    }
    
    /**
     * Validate G-buffer parameters.
     */
    private void validateGBuffer(DLSSDParams params, List<String> errors, List<String> warnings) {
        logSection("G-BUFFER VALIDATION");
        
        // Diffuse albedo
        logInfo(String.format("  Diffuse Albedo View: 0x%X", params.diffuseAlbedoView));
        logInfo(String.format("  Diffuse Albedo Format: %s (0x%X)", formatToString(params.diffuseAlbedoFormat), params.diffuseAlbedoFormat));
        if (params.diffuseAlbedoView != 0 && !isValidFormat(params.diffuseAlbedoFormat, VALID_ALBEDO_FORMATS)) {
            warnings.add(String.format("Unusual diffuse albedo format: %s", formatToString(params.diffuseAlbedoFormat)));
        }
        if (params.diffuseAlbedoView == 0) {
            warnings.add("Diffuse albedo view is null (0) - DLSSD may produce lower quality");
        }
        
        // Specular albedo
        logInfo(String.format("  Specular Albedo View: 0x%X", params.specularAlbedoView));
        logInfo(String.format("  Specular Albedo Format: %s (0x%X)", formatToString(params.specularAlbedoFormat), params.specularAlbedoFormat));
        if (params.specularAlbedoView != 0 && !isValidFormat(params.specularAlbedoFormat, VALID_ALBEDO_FORMATS)) {
            warnings.add(String.format("Unusual specular albedo format: %s", formatToString(params.specularAlbedoFormat)));
        }
        
        // Normals
        logInfo(String.format("  Normals View: 0x%X", params.normalsView));
        logInfo(String.format("  Normals Format: %s (0x%X)", formatToString(params.normalsFormat), params.normalsFormat));
        if (params.normalsView != 0 && !isValidFormat(params.normalsFormat, VALID_NORMAL_FORMATS)) {
            warnings.add(String.format("Unusual normals format: %s", formatToString(params.normalsFormat)));
        }
        if (params.normalsView == 0) {
            warnings.add("Normals view is null (0) - DLSSD may produce lower quality");
        }
        
        // Roughness (only if unpacked mode)
        if (params.roughnessMode == DLSSDProcessor.ROUGHNESS_MODE_UNPACKED) {
            logInfo(String.format("  Roughness View: 0x%X", params.roughnessView));
            logInfo(String.format("  Roughness Format: %s (0x%X)", formatToString(params.roughnessFormat), params.roughnessFormat));
            if (params.roughnessView == 0) {
                errors.add("Roughness view is null (0) but roughness mode is UNPACKED");
            }
        } else {
            logInfo("  Roughness: Packed in normals.w");
        }
    }
    
    /**
     * Validate feature handle.
     */
    private void validateFeatureHandle(DLSSDParams params, List<String> errors, List<String> warnings) {
        logSection("FEATURE HANDLE VALIDATION");
        
        logInfo(String.format("  Feature Handle: 0x%X", params.featureHandle));
        
        if (params.featureHandle == 0) {
            errors.add("Feature handle is null (0) - DLSSD feature not initialized");
        }
    }
    
    // =====================================================================
    // LOGGING METHODS
    // =====================================================================
    
    private void logSeparator() {
        pendingLogs.add("================================================================================");
    }
    
    private void logSection(String title) {
        pendingLogs.add("");
        pendingLogs.add("--- " + title + " ---");
    }
    
    private void logInfo(String message) {
        pendingLogs.add("[INFO] " + message);
    }
    
    private void logWarning(String message) {
        pendingLogs.add("[WARN] " + message);
    }
    
    private void logError(String message) {
        pendingLogs.add("[ERROR] " + message);
    }
    
    private void logNewLine() {
        pendingLogs.add("");
    }
    
    private void flushLogs() {
        try (FileWriter writer = new FileWriter(logFilePath.toFile(), true)) {
            for (String line : pendingLogs) {
                writer.write(line + "\n");
            }
            pendingLogs.clear();
        } catch (IOException e) {
            System.err.println("[DLSSDParameterValidator] Failed to write log: " + e.getMessage());
        }
    }
    
    /**
     * Clear the log file and start fresh.
     */
    public void clearLog() {
        try {
            Files.deleteIfExists(logFilePath);
            logInitialized = false;
        } catch (IOException e) {
            System.err.println("[DLSSDParameterValidator] Failed to clear log: " + e.getMessage());
        }
    }
    
    /**
     * Log a summary of validation statistics.
     */
    public void logSummary() {
        logSeparator();
        logInfo("VALIDATION SUMMARY");
        logInfo(String.format("  Total Frames Validated: %d", frameCount));
        logInfo(String.format("  Total Errors: %d", validationErrors));
        logInfo(String.format("  Total Warnings: %d", validationWarnings));
        if (frameCount > 0) {
            logInfo(String.format("  Error Rate: %.2f%%", 100.0 * validationErrors / frameCount));
            logInfo(String.format("  Warning Rate: %.2f%%", 100.0 * validationWarnings / frameCount));
        }
        logSeparator();
        flushLogs();
    }
    
    // =====================================================================
    // UTILITY METHODS
    // =====================================================================
    
    private boolean isValidFormat(int format, int[] validFormats) {
        for (int valid : validFormats) {
            if (format == valid) return true;
        }
        return false;
    }
    
    private String formatToString(int format) {
        switch (format) {
            case VK_FORMAT_R8G8B8A8_UNORM: return "R8G8B8A8_UNORM";
            case VK_FORMAT_B8G8R8A8_UNORM: return "B8G8R8A8_UNORM";
            case VK_FORMAT_R16G16B16A16_SFLOAT: return "R16G16B16A16_SFLOAT";
            case VK_FORMAT_R32G32B32A32_SFLOAT: return "R32G32B32A32_SFLOAT";
            case VK_FORMAT_R16_SFLOAT: return "R16_SFLOAT";
            case VK_FORMAT_R32_SFLOAT: return "R32_SFLOAT";
            case VK_FORMAT_D32_SFLOAT: return "D32_SFLOAT";
            case VK_FORMAT_R16G16_SFLOAT: return "R16G16_SFLOAT";
            case VK_FORMAT_R32G32_SFLOAT: return "R32G32_SFLOAT";
            case 1000361004: return "R10G10B10A2_UNORM_PACK32";
            case VK_FORMAT_UNDEFINED: return "UNDEFINED";
            default: return String.format("UNKNOWN(0x%X)", format);
        }
    }
    
    /**
     * Get the log file path.
     */
    public Path getLogFilePath() {
        return logFilePath;
    }
    
    /**
     * Get total validation errors.
     */
    public int getValidationErrors() {
        return validationErrors;
    }
    
    /**
     * Get total validation warnings.
     */
    public int getValidationWarnings() {
        return validationWarnings;
    }
    
    /**
     * Get total frames validated.
     */
    public int getFrameCount() {
        return frameCount;
    }
}
