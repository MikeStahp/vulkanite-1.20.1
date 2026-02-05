package me.cortex.vulkanite.lib.memory;

import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.util.vma.Vma.vmaFreeMemory;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;

public class SharedBufferAllocation extends BufferAllocation {
    private final boolean dedicated;

    protected SharedBufferAllocation(VkDevice device, long allocator, long buffer, long allocation, VmaAllocationInfo info, boolean hasDeviceAddresses, boolean requestAddress, boolean dedicated) {
        super(device, allocator, buffer, allocation, info, hasDeviceAddresses, requestAddress);
        this.dedicated = dedicated;
    }

    @Override
    protected void free() {
        // In shared/manual dedicated allocations, sometimes it's necessary to destroy the Vulkan object
        // and free VMA memory separately.
        vkDestroyBuffer(device, buffer, null);
        vmaFreeMemory(allocator, allocation);

        // Call free from Allocation just to clean up the info struct
        if (ai != null) ai.free();
    }

    public boolean isDedicated() {
        return dedicated;
    }
}
