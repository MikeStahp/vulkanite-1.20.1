package me.cortex.vulkanite.lib.memory;

import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;

public interface ImageAllocator {
    ImageAllocation allocImage(long pool, VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo);
}