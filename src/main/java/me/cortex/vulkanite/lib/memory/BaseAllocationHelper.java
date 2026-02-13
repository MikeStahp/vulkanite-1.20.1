package me.cortex.vulkanite.lib.memory;

import me.cortex.vulkanite.client.Vulkanite;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.HashMap;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

public abstract class BaseAllocationHelper implements BufferAllocator, ImageAllocator {
    protected final VkDevice device;
    protected final long allocator;
    protected final boolean hasDeviceAddresses;

    protected final HashMap<ImageFormatQuery, ImageFormatQueryResult> formatSupportCache = new HashMap<>();

    public BaseAllocationHelper(VkDevice device, long allocator, boolean hasDeviceAddresses) {
        this.device = device;
        this.allocator = allocator;
        this.hasDeviceAddresses = hasDeviceAddresses;
    }

    // Format support caching logic
    protected boolean testModifyFormatSupport(VkDevice device, VkImageCreateInfo imageCreateInfo) {
        var query = new ImageFormatQuery(imageCreateInfo.format(), imageCreateInfo.imageType(),
                imageCreateInfo.tiling(), imageCreateInfo.usage(), imageCreateInfo.flags());

        if (formatSupportCache.containsKey(query)) {
            var res = formatSupportCache.get(query);
            if (res.supported()) {
                imageCreateInfo.format(res.updatedParams().format());
                imageCreateInfo.imageType(res.updatedParams().imageType());
                imageCreateInfo.tiling(res.updatedParams().tiling());
                imageCreateInfo.usage(res.updatedParams().usage());
                imageCreateInfo.flags(res.updatedParams().flags());
            }
            return res.supported();
        }

        try (var stack = stackPush()) {
            var pImageFormatProperties = VkImageFormatProperties.callocStack(stack);
            var result = vkGetPhysicalDeviceImageFormatProperties(device.getPhysicalDevice(), imageCreateInfo.format(),
                    imageCreateInfo.imageType(), imageCreateInfo.tiling(), imageCreateInfo.usage(),
                    imageCreateInfo.flags(), pImageFormatProperties);

            if (result == VK_SUCCESS) {
                formatSupportCache.put(query, new ImageFormatQueryResult(true, query));
                return true;
            } else if (result != VK_ERROR_FORMAT_NOT_SUPPORTED) {
                throw new RuntimeException("Failed to get image format properties: " + result);
            }

            // Fallback logic
            // 1. Remove Storage bit
            if ((imageCreateInfo.usage() & VK_IMAGE_USAGE_STORAGE_BIT) != 0) {
                imageCreateInfo.usage(imageCreateInfo.usage() & ~VK_IMAGE_USAGE_STORAGE_BIT);
                if (testModifyFormatSupport(device, imageCreateInfo)) {
                    System.err.println("WARNING: Storage image usage removed from " + imageCreateInfo.format() + " (not supported)");
                    return true;
                }
            }

            // 2. Tiling Optimal -> Linear
            if (imageCreateInfo.tiling() == VK_IMAGE_TILING_OPTIMAL) {
                imageCreateInfo.tiling(VK_IMAGE_TILING_LINEAR);
                if (testModifyFormatSupport(device, imageCreateInfo)) {
                    System.err.println("WARNING: TILING_OPTIMAL changed to TILING_LINEAR for " + imageCreateInfo.format());
                    return true;
                }
            }
        }

        formatSupportCache.put(query, new ImageFormatQueryResult(false, null));
        return false;
    }

    public String dumpJson(long allocator, boolean detailed) {
        try (var stack = stackPush()) {
            PointerBuffer pb = stack.callocPointer(1);
            vmaBuildStatsString(allocator, pb, detailed);
            String result = org.lwjgl.system.MemoryUtil.memUTF8(pb.get(0));
            nvmaFreeStatsString(allocator, pb.get(0));
            return result;
        }
    }
}