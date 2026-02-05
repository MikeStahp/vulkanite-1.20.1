package me.cortex.vulkanite.lib.memory;

import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.util.vma.Vma.vmaFreeMemory;
import static org.lwjgl.vulkan.VK10.vkDestroyImage;

public class SharedImageAllocation extends ImageAllocation {
    private final VkDevice device;
    private final boolean dedicated;

    protected SharedImageAllocation(VkDevice device, long allocator, long image, long allocation, VmaAllocationInfo info, boolean dedicated) {
        super(allocator, image, allocation, info);
        this.device = device;
        this.dedicated = dedicated;
    }

    @Override
    protected void free() {
        vkDestroyImage(device, image, null);
        vmaFreeMemory(allocator, allocation);
        if (ai != null) ai.free();
    }

    public boolean isDedicated() {
        return dedicated;
    }
}
