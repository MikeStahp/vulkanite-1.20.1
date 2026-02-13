package me.cortex.vulkanite.lib.memory;

import me.cortex.vulkanite.client.Vulkanite;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

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

    private SharedAllocationHelper sharedAllocationHelper;
    private StandardAllocationHelper standardAllocationHelper;

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
            
            // Initialize helpers with the actual allocator
            this.standardAllocationHelper = new StandardAllocationHelper(device, allocator, hasDeviceAddresses);
            this.sharedAllocationHelper = new SharedAllocationHelper(device, allocator, hasDeviceAddresses, sharedBlockSize);

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
        return sharedAllocationHelper.allocShared(bufferCreateInfo, allocationCreateInfo, sharedPool, sharedDedicatedPool);
    }

    public BufferAllocation alloc(long pool, VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
        return alloc(pool, bufferCreateInfo, allocationCreateInfo, 0);
    }

    public BufferAllocation alloc(long pool, VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo, long alignment) {
       return standardAllocationHelper.allocBuffer(pool, bufferCreateInfo, allocationCreateInfo, alignment);
   }

   public SharedImageAllocation allocShared(VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
       return sharedAllocationHelper.allocShared(imageCreateInfo, allocationCreateInfo, sharedPool, sharedDedicatedPool);
   }

   public ImageAllocation alloc(long pool, VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
       return standardAllocationHelper.allocImage(pool, imageCreateInfo, allocationCreateInfo);
   }

   public String dumpJson(boolean detailed) {
       return standardAllocationHelper.dumpJson(allocator, detailed);
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
