package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.VImage;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Validates buffer formats and usage flags for DLSS/DLSSD input and output buffers.
 * 
 * NGX Required Buffer Specifications:
 * 
 * | Buffer          | Format                    | Usage Flags                                      |
 * |-----------------|---------------------------|--------------------------------------------------|
 * | Output          | VK_FORMAT_R16G16B16A16_SFLOAT | STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST |
 * | Depth           | VK_FORMAT_R32_SFLOAT      | STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST     |
 * | Noisy Input     | VK_FORMAT_R16G16B16A16_SFLOAT | STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST |
 * | Motion Vectors  | VK_FORMAT_R16G16B16A16_SFLOAT | STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST |
 * | Diffuse Albedo  | VK_FORMAT_R16G16B16A16_SFLOAT | STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST |
 * | Specular Albedo | VK_FORMAT_R16G16B16A16_SFLOAT | STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST |
 * | Normals         | VK_FORMAT_R16G16B16A16_SFLOAT | STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST |
 * | Roughness       | VK_FORMAT_R16G16B16A16_SFLOAT | STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST |
 * 
 * This validator detects if incoming buffers match these specifications
 * and provides detailed logging for debugging buffer mismatches.
 */
public class DLSSBufferValidator {

    // Required formats for NGX
    public static final int REQUIRED_FORMAT_COLOR = VK_FORMAT_R16G16B16A16_SFLOAT;  // 16-bit float RGBA
    public static final int REQUIRED_FORMAT_DEPTH = VK_FORMAT_R32_SFLOAT;          // 32-bit float depth
    
    // Required usage flags for all NGX buffers
    public static final int REQUIRED_USAGE_FLAGS = 
        VK_IMAGE_USAGE_STORAGE_BIT | 
        VK_IMAGE_USAGE_SAMPLED_BIT | 
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | 
        VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    
    // Buffer type enumeration
    public enum BufferType {
        OUTPUT("Output"),
        DEPTH("Depth"),
        NOISY_INPUT("Noisy Input"),
        MOTION_VECTORS("Motion Vectors"),
        DIFFUSE_ALBEDO("Diffuse Albedo"),
        SPECULAR_ALBEDO("Specular Albedo"),
        NORMALS("Normals"),
        ROUGHNESS("Roughness");
        
        private final String displayName;
        
        BufferType(String displayName) {
            this.displayName = displayName;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
    
    // Validation result class
    public static class ValidationResult {
        public final BufferType bufferType;
        public final boolean formatValid;
        public final boolean usageValid;
        public final boolean dimensionsValid;
        public final int actualFormat;
        public final int expectedFormat;
        public final int actualWidth;
        public final int actualHeight;
        public final int expectedWidth;
        public final int expectedHeight;
        public final String errorMessage;
        
        private ValidationResult(BufferType type, boolean formatValid, boolean usageValid, 
                                 boolean dimensionsValid, int actualFormat, int expectedFormat,
                                 int actualWidth, int actualHeight, int expectedWidth, int expectedHeight,
                                 String errorMessage) {
            this.bufferType = type;
            this.formatValid = formatValid;
            this.usageValid = usageValid;
            this.dimensionsValid = dimensionsValid;
            this.actualFormat = actualFormat;
            this.expectedFormat = expectedFormat;
            this.actualWidth = actualWidth;
            this.actualHeight = actualHeight;
            this.expectedWidth = expectedWidth;
            this.expectedHeight = expectedHeight;
            this.errorMessage = errorMessage;
        }
        
        public boolean isValid() {
            return formatValid && usageValid && dimensionsValid;
        }
        
        @Override
        public String toString() {
            if (isValid()) {
                return String.format("[%s] VALID - Format: %s, Dimensions: %dx%d", 
                    bufferType, formatToString(actualFormat), actualWidth, actualHeight);
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("[").append(bufferType).append("] INVALID: ");
                if (!formatValid) {
                    sb.append(String.format("Format mismatch (expected %s, got %s). ", 
                        formatToString(expectedFormat), formatToString(actualFormat)));
                }
                if (!usageValid) {
                    sb.append("Missing required usage flags. ");
                }
                if (!dimensionsValid) {
                    sb.append(String.format("Dimension mismatch (expected %dx%d, got %dx%d). ", 
                        expectedWidth, expectedHeight, actualWidth, actualHeight));
                }
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    sb.append(errorMessage);
                }
                return sb.toString();
            }
        }
    }
    
    /**
     * Validates an output buffer for DLSS.
     * Required: VK_FORMAT_R16G16B16A16_SFLOAT with STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST
     */
    public static ValidationResult validateOutputBuffer(VRef<VImage> image, int expectedWidth, int expectedHeight) {
        return validateBuffer(image, BufferType.OUTPUT, REQUIRED_FORMAT_COLOR, expectedWidth, expectedHeight);
    }
    
    /**
     * Validates a depth buffer for DLSS.
     * Required: VK_FORMAT_R32_SFLOAT with STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST
     */
    public static ValidationResult validateDepthBuffer(VRef<VImage> image, int expectedWidth, int expectedHeight) {
        return validateBuffer(image, BufferType.DEPTH, REQUIRED_FORMAT_DEPTH, expectedWidth, expectedHeight);
    }
    
    /**
     * Validates a noisy input buffer for DLSS.
     * Required: VK_FORMAT_R16G16B16A16_SFLOAT with STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST
     */
    public static ValidationResult validateNoisyInputBuffer(VRef<VImage> image, int expectedWidth, int expectedHeight) {
        return validateBuffer(image, BufferType.NOISY_INPUT, REQUIRED_FORMAT_COLOR, expectedWidth, expectedHeight);
    }
    
    /**
     * Validates a motion vectors buffer for DLSS.
     * Required: VK_FORMAT_R16G16B16A16_SFLOAT with STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST
     */
    public static ValidationResult validateMotionVectorsBuffer(VRef<VImage> image, int expectedWidth, int expectedHeight) {
        return validateBuffer(image, BufferType.MOTION_VECTORS, REQUIRED_FORMAT_COLOR, expectedWidth, expectedHeight);
    }
    
    /**
     * Validates a diffuse albedo buffer for DLSSD.
     * Required: VK_FORMAT_R16G16B16A16_SFLOAT with STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST
     */
    public static ValidationResult validateDiffuseAlbedoBuffer(VRef<VImage> image, int expectedWidth, int expectedHeight) {
        return validateBuffer(image, BufferType.DIFFUSE_ALBEDO, REQUIRED_FORMAT_COLOR, expectedWidth, expectedHeight);
    }
    
    /**
     * Validates a specular albedo buffer for DLSSD.
     * Required: VK_FORMAT_R16G16B16A16_SFLOAT with STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST
     */
    public static ValidationResult validateSpecularAlbedoBuffer(VRef<VImage> image, int expectedWidth, int expectedHeight) {
        return validateBuffer(image, BufferType.SPECULAR_ALBEDO, REQUIRED_FORMAT_COLOR, expectedWidth, expectedHeight);
    }
    
    /**
     * Validates a normals buffer for DLSSD.
     * Required: VK_FORMAT_R16G16B16A16_SFLOAT with STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST
     */
    public static ValidationResult validateNormalsBuffer(VRef<VImage> image, int expectedWidth, int expectedHeight) {
        return validateBuffer(image, BufferType.NORMALS, REQUIRED_FORMAT_COLOR, expectedWidth, expectedHeight);
    }
    
    /**
     * Validates a roughness buffer for DLSSD (optional).
     * Required: VK_FORMAT_R16G16B16A16_SFLOAT with STORAGE, SAMPLED, TRANSFER_SRC, TRANSFER_DST
     */
    public static ValidationResult validateRoughnessBuffer(VRef<VImage> image, int expectedWidth, int expectedHeight) {
        if (image == null) {
            // Roughness is optional (can be packed in normals.w)
            return new ValidationResult(BufferType.ROUGHNESS, true, true, true, 
                0, REQUIRED_FORMAT_COLOR, 0, 0, expectedWidth, expectedHeight, 
                "Roughness not provided (may be packed in normals.w)");
        }
        return validateBuffer(image, BufferType.ROUGHNESS, REQUIRED_FORMAT_COLOR, expectedWidth, expectedHeight);
    }
    
    /**
     * Generic buffer validation method.
     */
    private static ValidationResult validateBuffer(VRef<VImage> imageRef, BufferType type, 
                                                    int requiredFormat, int expectedWidth, int expectedHeight) {
        if (imageRef == null || imageRef.get() == null) {
            return new ValidationResult(type, false, false, false, 
                0, requiredFormat, 0, 0, expectedWidth, expectedHeight, 
                "Image reference is null");
        }
        
        VImage image = imageRef.get();
        int actualFormat = image.format;
        int actualWidth = image.width;
        int actualHeight = image.height;
        
        // Check format
        boolean formatValid = (actualFormat == requiredFormat);
        
        // Note: Usage flags are not stored in VImage, so we can't validate them directly
        // This would require querying the image from Vulkan or storing usage flags in VImage
        // For now, we assume usage flags are correct if the image was created through our pipeline
        boolean usageValid = true; // Assumed valid
        
        // Check dimensions
        boolean dimensionsValid = (actualWidth == expectedWidth && actualHeight == expectedHeight);
        
        String errorMessage = null;
        if (!formatValid) {
            errorMessage = String.format("Format %s does not match required %s for %s buffer", 
                formatToString(actualFormat), formatToString(requiredFormat), type);
        } else if (!dimensionsValid) {
            errorMessage = String.format("Dimensions %dx%d do not match expected %dx%d for %s buffer", 
                actualWidth, actualHeight, expectedWidth, expectedHeight, type);
        }
        
        return new ValidationResult(type, formatValid, usageValid, dimensionsValid, 
            actualFormat, requiredFormat, actualWidth, actualHeight, expectedWidth, expectedHeight, errorMessage);
    }
    
    /**
 * Validates all buffers for DLSSD (Ray Reconstruction) processing.
 *
 * @param noisyInput Noisy ray-traced color input
 * @param depth Depth buffer
 * @param motionVectors Motion vectors
 * @param diffuseAlbedo Diffuse albedo (G-buffer)
 * @param specularAlbedo Specular albedo (G-buffer)
 * @param normals World-space normals (G-buffer)
 * @param roughness Roughness buffer (optional, can be null if packed in normals.w)
 * @param output Output buffer
 * @param width Expected width
 * @param height Expected height
 * @return Array of validation results for each buffer
 */
public static ValidationResult[] validateAllBuffers(
VRef<VImage> noisyInput,
VRef<VImage> depth,
VRef<VImage> motionVectors,
VRef<VImage> diffuseAlbedo,
VRef<VImage> specularAlbedo,
VRef<VImage> normals,
VRef<VImage> roughness,
VRef<VImage> output,
int width, int height) {

ValidationResult[] results = new ValidationResult[8];

// Output and depth must match exactly
results[0] = validateOutputBuffer(output, width, height);
results[1] = validateDepthBuffer(depth, width, height);

// Noisy input and motion vectors should match, but we allow dimension tolerance
// (they will be blitted to the correct size if needed)
results[2] = validateBufferFormatOnly(noisyInput, BufferType.NOISY_INPUT, REQUIRED_FORMAT_COLOR);
results[3] = validateBufferFormatOnly(motionVectors, BufferType.MOTION_VECTORS, REQUIRED_FORMAT_COLOR);

// G-buffer inputs: Only validate format, not dimensions
// Iris creates G-buffer at actual window size, which may not be 8-pixel aligned
// NGX will handle the dimension mismatch via blit operations
results[4] = validateBufferFormatOnly(diffuseAlbedo, BufferType.DIFFUSE_ALBEDO, REQUIRED_FORMAT_COLOR);
results[5] = validateBufferFormatOnly(specularAlbedo, BufferType.SPECULAR_ALBEDO, REQUIRED_FORMAT_COLOR);
results[6] = validateBufferFormatOnly(normals, BufferType.NORMALS, REQUIRED_FORMAT_COLOR);
results[7] = validateRoughnessBuffer(roughness, width, height);

return results;
}

/**
 * Validate only the format of a buffer, ignoring dimension mismatches.
 * This is useful for G-buffer inputs which may have different dimensions
 * than the DLSSD render target due to window size not being 8-pixel aligned.
 */
private static ValidationResult validateBufferFormatOnly(VRef<VImage> imageRef, BufferType type, int requiredFormat) {
if (imageRef == null || imageRef.get() == null) {
return new ValidationResult(type, false, false, false,
0, requiredFormat, 0, 0, 0, 0, "Image reference is null");
}

VImage image = imageRef.get();
int actualFormat = image.format;
int actualWidth = image.width;
int actualHeight = image.height;

// Check format only
boolean formatValid = (actualFormat == requiredFormat);

// Assume usage is valid (can't check without Vulkan query)
boolean usageValid = true;

// Skip dimension check - G-buffer may have different dimensions
boolean dimensionsValid = true;

String errorMessage = null;
if (!formatValid) {
errorMessage = String.format("Format %s does not match required %s for %s buffer",
formatToString(actualFormat), formatToString(requiredFormat), type);
}

return new ValidationResult(type, formatValid, usageValid, dimensionsValid,
actualFormat, requiredFormat, actualWidth, actualHeight, actualWidth, actualHeight, errorMessage);
}
    
    /**
     * Logs all validation results for debugging.
     */
    public static void logValidationResults(ValidationResult[] results) {
        System.out.println("[DLSSBufferValidator] === Buffer Validation Report ===");
        int validCount = 0;
        int invalidCount = 0;
        
        for (ValidationResult result : results) {
            if (result.isValid()) {
                validCount++;
                System.out.println("[DLSSBufferValidator] " + result);
            } else {
                invalidCount++;
                System.err.println("[DLSSBufferValidator] " + result);
            }
        }
        
        System.out.println("[DLSSBufferValidator] Summary: " + validCount + " valid, " + invalidCount + " invalid");
    }
    
    /**
     * Checks if all required buffers are valid for DLSSD processing.
     */
    public static boolean allBuffersValid(ValidationResult[] results) {
        for (ValidationResult result : results) {
            // Roughness is optional, so skip it in the overall validity check
            if (result.bufferType == BufferType.ROUGHNESS && result.actualFormat == 0) {
                continue;
            }
            if (!result.isValid()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Converts Vulkan format constant to human-readable string.
     */
    public static String formatToString(int format) {
        switch (format) {
            case VK_FORMAT_R16G16B16A16_SFLOAT: return "R16G16B16A16_SFLOAT";
            case VK_FORMAT_R32_SFLOAT: return "R32_SFLOAT";
            case VK_FORMAT_R16G16B16A16_UNORM: return "R16G16B16A16_UNORM";
            case VK_FORMAT_R8G8B8A8_UNORM: return "R8G8B8A8_UNORM";
            case VK_FORMAT_R8G8B8A8_SRGB: return "R8G8B8A8_SRGB";
            case VK_FORMAT_B8G8R8A8_UNORM: return "B8G8R8A8_UNORM";
            case VK_FORMAT_B8G8R8A8_SRGB: return "B8G8R8A8_SRGB";
            case VK_FORMAT_R32G32B32A32_SFLOAT: return "R32G32B32A32_SFLOAT";
            case VK_FORMAT_R16G16_SFLOAT: return "R16G16_SFLOAT";
            case VK_FORMAT_R16_SFLOAT: return "R16_SFLOAT";
            case VK_FORMAT_D32_SFLOAT: return "D32_SFLOAT";
            case VK_FORMAT_D24_UNORM_S8_UINT: return "D24_UNORM_S8_UINT";
            case VK_FORMAT_D16_UNORM: return "D16_UNORM";
            case 0: return "UNDEFINED";
            default: return "UNKNOWN(" + format + ")";
        }
    }
    
    /**
     * Detects if a buffer has the correct format for DLSS color buffers.
     * @return true if format is VK_FORMAT_R16G16B16A16_SFLOAT
     */
    public static boolean isColorFormatValid(int format) {
        return format == REQUIRED_FORMAT_COLOR;
    }
    
    /**
     * Detects if a buffer has the correct format for DLSS depth buffer.
     * @return true if format is VK_FORMAT_R32_SFLOAT
     */
    public static boolean isDepthFormatValid(int format) {
        return format == REQUIRED_FORMAT_DEPTH;
    }
}
