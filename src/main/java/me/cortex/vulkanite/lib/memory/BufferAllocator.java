package me.cortex.vulkanite.lib.memory;

import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;

public interface BufferAllocator {
    BufferAllocation allocBuffer(long pool, VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo, long alignment);
}