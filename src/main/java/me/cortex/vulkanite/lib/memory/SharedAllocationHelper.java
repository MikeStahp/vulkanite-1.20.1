package me.cortex.vulkanite.lib.memory;

import me.cortex.vulkanite.client.Vulkanite;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

public class SharedAllocationHelper extends BaseAllocationHelper {
    private final long sharedBlockSize;

    public SharedAllocationHelper(VkDevice device, long allocator, boolean hasDeviceAddresses, long sharedBlockSize) {
        super(device, allocator, hasDeviceAddresses);
        this.sharedBlockSize = sharedBlockSize;
    }

    public SharedBufferAllocation allocShared(VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo,
                                              long sharedPool, long sharedDedicatedPool) {
        try (var stack = stackPush()) {
            LongBuffer pb = stack.callocLong(1);
            _CHECK_(vkCreateBuffer(device, bufferCreateInfo, null, pb), "Failed to create VkBuffer");
            long buffer = pb.get(0);

            var memReq = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, memReq);
            allocationCreateInfo.memoryTypeBits(memReq.memoryTypeBits());

            boolean dedicated = isDedicatedBuffer(stack, buffer, memReq.size(), sharedBlockSize);

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

    public SharedImageAllocation allocShared(VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo,
                                             long sharedPool, long sharedDedicatedPool) {
        try (var stack = stackPush()) {
            LongBuffer pb = stack.callocLong(1);
            _CHECK_(vkCreateImage(device, imageCreateInfo, null, pb), "Failed to create VkImage");
            long image = pb.get(0);

            var memReq = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, memReq);

            boolean dedicated = isDedicatedImage(stack, image, memReq.size(), sharedBlockSize);

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

    private boolean isDedicatedBuffer(MemoryStack stack, long buffer, long size, long sharedBlockSize) {
        if (size > sharedBlockSize) return true;

        var dedicatedMemReq = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
        var memReq2 = VkMemoryRequirements2.calloc(stack).sType$Default().pNext(dedicatedMemReq.address());

        vkGetBufferMemoryRequirements2(device, VkBufferMemoryRequirementsInfo2.calloc(stack).sType$Default().buffer(buffer), memReq2);

        return dedicatedMemReq.prefersDedicatedAllocation() || dedicatedMemReq.requiresDedicatedAllocation();
    }

    private boolean isDedicatedImage(MemoryStack stack, long image, long size, long sharedBlockSize) {
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

    @Override
    public BufferAllocation allocBuffer(long pool, VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo, long alignment) {
        // SharedAllocationHelper uses a different approach for buffer allocation
        throw new UnsupportedOperationException("Use allocShared for shared buffer allocation");
    }

    @Override
    public ImageAllocation allocImage(long pool, VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
        // SharedAllocationHelper uses a different approach for image allocation
        throw new UnsupportedOperationException("Use allocShared for shared image allocation");
    }
}