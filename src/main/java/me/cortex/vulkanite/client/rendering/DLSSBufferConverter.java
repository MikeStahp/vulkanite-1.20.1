package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Utility class for converting buffer formats to DLSSD-compatible formats.
 * 
 * DLSSD requires all color inputs to be R16G16B16A16_SFLOAT format.
 * This class provides on-the-fly format conversion using Vulkan blit/copy operations.
 * 
 * Supported conversions:
 * - R8G8B8A8_UNORM -> R16G16B16A16_SFLOAT (UNORM to SFLOAT)
 * - R16G16_SFLOAT -> R16G16B16A16_SFLOAT (2-channel to 4-channel expansion)
 */
public class DLSSBufferConverter {
    
    private final VContext context;
    
    // Cache of converted images to avoid recreating them every frame
    // Key: original image ID + dimensions
    private final Map<String, VRef<VImage>> conversionCache = new HashMap<>();
    
    public DLSSBufferConverter(VContext context) {
        this.context = context;
    }
    
    /**
     * Convert an image to R16G16B16A16_SFLOAT format if needed.
     * 
     * @param cmd Command buffer for recording conversion commands
     * @param sourceImage The source image to convert
     * @param name Name for debugging/caching purposes
     * @return The converted image in R16G16B16A16_SFLOAT format, or the original if already compatible
     */
    public VRef<VImage> convertToRGBA16F(VCmdBuff cmd, VRef<VImage> sourceImage, String name) {
        if (sourceImage == null || sourceImage.get() == null) {
            return null;
        }
        
        VImage src = sourceImage.get();
        int srcFormat = src.format;
        int srcWidth = src.width;
        int srcHeight = src.height;
        
        // Check if already in correct format
        if (srcFormat == VK_FORMAT_R16G16B16A16_SFLOAT) {
            return sourceImage;
        }
        
        // Create cache key
        String cacheKey = name + "_" + srcWidth + "x" + srcHeight + "_" + srcFormat;
        
        // Check cache for existing converted image
        VRef<VImage> cachedImage = conversionCache.get(cacheKey);
        if (cachedImage != null && cachedImage.get() != null) {
            // Reuse the cached image - perform blit from source
            blitImage(cmd, sourceImage, cachedImage, srcWidth, srcHeight);
            return cachedImage;
        }
        
        // Create new image in RGBA16F format
        VRef<VImage> convertedImage = createRGBA16FImage(srcWidth, srcHeight, name);
        if (convertedImage == null) {
            System.err.println("[DLSSBufferConverter] Failed to create converted image for " + name);
            return sourceImage; // Return original as fallback
        }
        
        // Perform the conversion
        blitImage(cmd, sourceImage, convertedImage, srcWidth, srcHeight);
        
        // Cache the converted image
        conversionCache.put(cacheKey, convertedImage);
        
        System.out.println("[DLSSBufferConverter] Converted " + name + " from format " + 
            formatToString(srcFormat) + " to RGBA16F (" + srcWidth + "x" + srcHeight + ")");
        
        return convertedImage;
    }
    
    /**
     * Convert an image view to R16G16B16A16_SFLOAT format if needed.
     * Extracts the underlying image, converts it, and returns the converted image.
     * 
     * @param cmd Command buffer for recording conversion commands
     * @param sourceView The source image view to convert
     * @param name Name for debugging/caching purposes
     * @return The converted image in R16G16B16A16_SFLOAT format
     */
    public VRef<VImage> convertViewToRGBA16F(VCmdBuff cmd, VRef<VImageView> sourceView, String name) {
        if (sourceView == null || sourceView.get() == null) {
            return null;
        }
        
        VImageView view = sourceView.get();
        if (view.image == null) {
            System.err.println("[DLSSBufferConverter] View has no underlying image: " + name);
            return null;
        }
        
        return convertToRGBA16F(cmd, view.image, name);
    }
    
    /**
     * Create a new image in R16G16B16A16_SFLOAT format.
     */
    private VRef<VImage> createRGBA16FImage(int width, int height, String name) {
        try {
            // Create image with DLSSD-required usage flags
            VRef<VImage> imageRef = context.memory.createImage2D(
                width,
                height,
                1, // mipLevels
                VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | 
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            
            if (imageRef != null && imageRef.get() != null) {
                imageRef.get().setDebugUtilsObjectName(name + "_RGBA16F");
            }
            
            return imageRef;
        } catch (Exception e) {
            System.err.println("[DLSSBufferConverter] Failed to create RGBA16F image: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Blit from source to destination image.
     * This handles format conversion automatically via Vulkan's blit operation.
     */
    private void blitImage(VCmdBuff cmd, VRef<VImage> srcRef, VRef<VImage> dstRef, int width, int height) {
        VkCommandBuffer cmdBuff = cmd.buffer();
        VImage src = srcRef.get();
        VImage dst = dstRef.get();
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Transition source to TRANSFER_SRC_OPTIMAL
            VkImageMemoryBarrier.Buffer srcBarrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(src.image())
                .subresourceRange(sr -> sr
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)
                );
            
            vkCmdPipelineBarrier(
                cmdBuff,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0,
                null, null, srcBarrier
            );
            
            // Transition destination to TRANSFER_DST_OPTIMAL
            VkImageMemoryBarrier.Buffer dstBarrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(dst.image())
                .subresourceRange(sr -> sr
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)
                );
            
            vkCmdPipelineBarrier(
                cmdBuff,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0,
                null, null, dstBarrier
            );
            
            // Perform blit (handles format conversion)
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcOffsets(0).x(0).y(0).z(0);
            blit.srcOffsets(1).x(width).y(height).z(1);
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.dstOffsets(0).x(0).y(0).z(0);
            blit.dstOffsets(1).x(width).y(height).z(1);
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            
            vkCmdBlitImage(
                cmdBuff,
                src.image(),
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dst.image(),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                blit,
                VK_FILTER_LINEAR
            );
            
            // Transition source back to SHADER_READ_ONLY
            VkImageMemoryBarrier.Buffer srcRestoreBarrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(src.image())
                .subresourceRange(sr -> sr
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)
                );
            
            vkCmdPipelineBarrier(
                cmdBuff,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0,
                null, null, srcRestoreBarrier
            );
            
            // Transition destination to SHADER_READ_ONLY (ready for DLSSD)
            VkImageMemoryBarrier.Buffer dstRestoreBarrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(dst.image())
                .subresourceRange(sr -> sr
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)
                );
            
            vkCmdPipelineBarrier(
                cmdBuff,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0,
                null, null, dstRestoreBarrier
            );
        }
    }
    
    /**
     * Convert format enum to human-readable string.
     */
    public static String formatToString(int format) {
        switch (format) {
            case VK_FORMAT_R8G8B8A8_UNORM: return "R8G8B8A8_UNORM";
            case VK_FORMAT_R16G16B16A16_SFLOAT: return "R16G16B16A16_SFLOAT";
            case VK_FORMAT_R16G16_SFLOAT: return "R16G16_SFLOAT";
            case VK_FORMAT_R32_SFLOAT: return "R32_SFLOAT";
            case VK_FORMAT_R32G32B32A32_SFLOAT: return "R32G32B32A32_SFLOAT";
            default: return "UNKNOWN(" + format + ")";
        }
    }
    
    /**
     * Check if a format is DLSSD-compatible for color buffers.
     */
    public static boolean isDLSSDColorFormat(int format) {
        return format == VK_FORMAT_R16G16B16A16_SFLOAT;
    }
    
    /**
     * Check if a format is DLSSD-compatible for depth buffers.
     */
    public static boolean isDLSSDDepthFormat(int format) {
        return format == VK_FORMAT_R32_SFLOAT;
    }
    
    /**
     * Clear the conversion cache. Call this when resizing or when images need to be recreated.
     */
    public void clearCache() {
        conversionCache.clear();
    }
    
    /**
     * Cleanup resources. Call this when the converter is no longer needed.
     */
    public void cleanup() {
        clearCache();
    }
}
