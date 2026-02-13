package me.cortex.vulkanite.lib.memory;

import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;

public interface AllocationStrategy {
    BufferAllocation allocBuffer(long pool, VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo, long alignment);
    ImageAllocation allocImage(long pool, VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo);
}