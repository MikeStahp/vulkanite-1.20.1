package me.cortex.vulkanite.lib.memory;

import me.cortex.vulkanite.client.Vulkanite;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

public class VmaAllocator {
    private final VkDevice device;
    private final long allocator;
    private final boolean hasDeviceAddresses;

    private long sharedPool;
    private long sharedDedicatedPool;
    private final long sharedBlockSize;

    private record ImageFormatQuery(int format, int imageType, int tiling, int usage, int flags) {}

    private record ImageFormatQueryResult(boolean supported, ImageFormatQuery updatedParams) {}

    private final HashMap<ImageFormatQuery, ImageFormatQueryResult> formatSupportCache = new HashMap<>();

    private VkExportMemoryAllocateInfo exportMemoryAllocateInfo;
    private VkExportMemoryAllocateInfo exportDedicatedMemoryAllocateInfo;

    public VmaAllocator(VkDevice device, boolean enableDeviceAddresses, long sharedBlockSize, int sharedHandleType) {
        this.device = device;
        this.hasDeviceAddresses = enableDeviceAddresses;
        this.sharedBlockSize = sharedBlockSize;

        try (var stack = stackPush()) {
            VmaAllocatorCreateInfo allocatorCreateInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .instance(device.getPhysicalDevice().getInstance())
                    .physicalDevice(device.getPhysicalDevice())
                    .device(device)
                    .pVulkanFunctions(VmaVulkanFunctions.calloc(stack)
                            .set(device.getPhysicalDevice().getInstance(), device))
                    .vulkanApiVersion(VK_API_VERSION_1_2)
                    .flags(enableDeviceAddresses ? VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT : 0);
            allocatorCreateInfo.flags(allocatorCreateInfo.flags() | VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT);

            PointerBuffer pAllocator = stack.pointers(0);
            _CHECK_(vmaCreateAllocator(allocatorCreateInfo, pAllocator), "Failed to create allocator");
            this.allocator = pAllocator.get(0);

            if (sharedHandleType != 0) {
                initSharedPools(stack, sharedHandleType);
            }
        }
    }

    private void initSharedPools(org.lwjgl.system.MemoryStack stack, int sharedHandleType) {
        // 1. Create Dedicated Pool
        var imageCreateInfo = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .extent(e -> e.set(512, 512, 1))
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                        | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

        var allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE)
                .requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        IntBuffer pMemoryTypeIndex = stack.callocInt(1);
        _CHECK_(vmaFindMemoryTypeIndexForImageInfo(allocator, imageCreateInfo, allocationCreateInfo, pMemoryTypeIndex),
                "Failed to find memory type index for shared dedicated pool");

        int sharedDedicatedPoolMemoryTypeIndex = pMemoryTypeIndex.get(0);

        this.exportDedicatedMemoryAllocateInfo = VkExportMemoryAllocateInfo.create()
                .sType$Default()
                .handleTypes(sharedHandleType);

        VmaPoolCreateInfo dedicatedPci = VmaPoolCreateInfo.calloc(stack)
                .memoryTypeIndex(sharedDedicatedPoolMemoryTypeIndex)
                .pMemoryAllocateNext(exportDedicatedMemoryAllocateInfo.address());

        PointerBuffer pb = stack.callocPointer(1);
        _CHECK_(vmaCreatePool(allocator, dedicatedPci, pb), "Failed to create sharedDedicatedPool");
        this.sharedDedicatedPool = pb.get(0);

        // 2. Create Generic Pool
        var bufferCreateInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(sharedBlockSize)
                .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

        var bufAllocCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_UNKNOWN)
                .requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        _CHECK_(vmaFindMemoryTypeIndexForBufferInfo(allocator, bufferCreateInfo, bufAllocCreateInfo, pMemoryTypeIndex),
                "Failed to find memory type index for shared pool");

        int sharedPoolMemoryTypeIndex = pMemoryTypeIndex.get(0);

        this.exportMemoryAllocateInfo = VkExportMemoryAllocateInfo.create()
                .sType$Default()
                .handleTypes(sharedHandleType);

        VmaPoolCreateInfo poolPci = VmaPoolCreateInfo.calloc(stack)
                .memoryTypeIndex(sharedPoolMemoryTypeIndex)
                .blockSize(sharedBlockSize)
                .pMemoryAllocateNext(exportMemoryAllocateInfo.address());

        _CHECK_(vmaCreatePool(allocator, poolPci, pb), "Failed to create sharedPool");
        this.sharedPool = pb.get(0);
    }

    // --- Allocation Methods ---

    public SharedBufferAllocation allocShared(VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
        try (var stack = stackPush()) {
            LongBuffer pb = stack.callocLong(1);
            _CHECK_(vkCreateBuffer(device, bufferCreateInfo, null, pb), "Failed to create VkBuffer");
            long buffer = pb.get(0);

            var memReq = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, memReq);
            allocationCreateInfo.memoryTypeBits(memReq.memoryTypeBits());

            boolean dedicated = isDedicatedBuffer(stack, buffer, memReq.size());

            if (dedicated) {
                allocationCreateInfo.flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);
                allocationCreateInfo.pool(sharedDedicatedPool);
            } else {
                allocationCreateInfo.pool(sharedPool);
            }

            VmaAllocationInfo vai = VmaAllocationInfo.calloc();
            PointerBuffer pAllocation = stack.mallocPointer(1);

            _CHECK_(vmaAllocateMemoryForBuffer(allocator, buffer, allocationCreateInfo, pAllocation, vai),
                    "Failed to allocate memory for buffer");

            long allocation = pAllocation.get(0);
            _CHECK_(vmaBindBufferMemory(allocator, allocation, buffer), "failed to bind buffer memory");

            boolean requestAddress = (bufferCreateInfo.usage() & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0;
            return new SharedBufferAllocation(device, allocator, buffer, allocation, vai, hasDeviceAddresses, requestAddress, dedicated);
        }
    }

    public BufferAllocation alloc(long pool, VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
        return alloc(pool, bufferCreateInfo, allocationCreateInfo, 0);
    }

    public BufferAllocation alloc(long pool, VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo, long alignment) {
        if (bufferCreateInfo.size() == 0) {
            throw new RuntimeException("Buffer size must be > 0");
        }

        try (var stack = stackPush()) {
            LongBuffer pb = stack.mallocLong(1);
            PointerBuffer pa = stack.mallocPointer(1);
            VmaAllocationInfo vai = VmaAllocationInfo.calloc();

            if (pool != 0) allocationCreateInfo.pool(pool);

            _CHECK_(vmaCreateBufferWithAlignment(allocator, bufferCreateInfo, allocationCreateInfo, alignment, pb, pa, vai),
                    "Failed to allocate buffer");

            boolean requestAddress = (bufferCreateInfo.usage() & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0;
            return new BufferAllocation(device, allocator, pb.get(0), pa.get(0), vai, hasDeviceAddresses, requestAddress);
        }
    }

    public SharedImageAllocation allocShared(VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
        testModifyFormatSupport(device, imageCreateInfo);
        try (var stack = stackPush()) {
            LongBuffer pb = stack.callocLong(1);
            _CHECK_(vkCreateImage(device, imageCreateInfo, null, pb), "Failed to create VkImage");
            long image = pb.get(0);

            var memReq = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, memReq);

            boolean dedicated = isDedicatedImage(stack, image, memReq.size());

            if (dedicated) {
                allocationCreateInfo.flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);
                allocationCreateInfo.pool(sharedDedicatedPool);
            } else {
                allocationCreateInfo.pool(sharedPool);
            }

            allocationCreateInfo.memoryTypeBits(memReq.memoryTypeBits());
            VmaAllocationInfo vai = VmaAllocationInfo.calloc();
            PointerBuffer pAllocation = stack.mallocPointer(1);

            _CHECK_(vmaAllocateMemoryForImage(allocator, image, allocationCreateInfo, pAllocation, vai),
                    "Failed to allocate memory for image");

            long allocation = pAllocation.get(0);
            _CHECK_(vmaBindImageMemory(allocator, allocation, image), "failed to bind image memory");

            return new SharedImageAllocation(device, allocator, image, allocation, vai, dedicated);
        }
    }

    public ImageAllocation alloc(long pool, VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
        testModifyFormatSupport(device, imageCreateInfo);
        try (var stack = stackPush()) {
            LongBuffer pi = stack.mallocLong(1);
            PointerBuffer pa = stack.mallocPointer(1);
            VmaAllocationInfo vai = VmaAllocationInfo.calloc();

            if (pool != 0) allocationCreateInfo.pool(pool);

            _CHECK_(vmaCreateImage(allocator, imageCreateInfo, allocationCreateInfo, pi, pa, vai),
                    "Failed to allocate image");

            return new ImageAllocation(allocator, pi.get(0), pa.get(0), vai);
        }
    }

    // --- Internal Helpers ---

    private boolean isDedicatedBuffer(org.lwjgl.system.MemoryStack stack, long buffer, long size) {
        if (size > sharedBlockSize) return true;

        var dedicatedMemReq = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
        var memReq2 = VkMemoryRequirements2.calloc(stack).sType$Default().pNext(dedicatedMemReq.address());

        vkGetBufferMemoryRequirements2(device, VkBufferMemoryRequirementsInfo2.calloc(stack).sType$Default().buffer(buffer), memReq2);

        return dedicatedMemReq.prefersDedicatedAllocation() || dedicatedMemReq.requiresDedicatedAllocation();
    }

    private boolean isDedicatedImage(org.lwjgl.system.MemoryStack stack, long image, long size) {
        // Zink Check: Use dependency injection or configuration instead of statics if possible
        if (Vulkanite.INSTANCE.IS_ZINK) return false;

        if (size > sharedBlockSize) return true;

        var dedicatedMemReq = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
        var memReq2 = VkMemoryRequirements2.calloc(stack).sType$Default().pNext(dedicatedMemReq.address());

        vkGetImageMemoryRequirements2(device, VkImageMemoryRequirementsInfo2.calloc(stack).sType$Default().image(image), memReq2);

        if (Vulkanite.INSTANCE.IS_ZINK) {
            if (dedicatedMemReq.requiresDedicatedAllocation()) {
                throw new RuntimeException("Zink does not support importing dedicated memory, however the Vulkan implementation demands it");
            }
            return false;
        }

        return dedicatedMemReq.prefersDedicatedAllocation() || dedicatedMemReq.requiresDedicatedAllocation();
    }

    // Format support caching logic
    boolean testModifyFormatSupport(VkDevice device, VkImageCreateInfo imageCreateInfo) {
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

    public String dumpJson(boolean detailed) {
        try (var stack = stackPush()) {
            PointerBuffer pb = stack.callocPointer(1);
            vmaBuildStatsString(allocator, pb, detailed);
            String result = MemoryUtil.memUTF8(pb.get(0));
            nvmaFreeStatsString(allocator, pb.get(0));
            return result;
        }
    }

    // Cleanup of allocator if needed to destroy the global instance
    public void destroy() {
        if (exportDedicatedMemoryAllocateInfo != null) exportDedicatedMemoryAllocateInfo.free();
        if (exportMemoryAllocateInfo != null) exportMemoryAllocateInfo.free();
        if (sharedDedicatedPool != 0) vmaDestroyPool(allocator, sharedDedicatedPool);
        if (sharedPool != 0) vmaDestroyPool(allocator, sharedPool);

        vmaDestroyAllocator(allocator);
    }
}
