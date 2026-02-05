package me.cortex.vulkanite.lib.memory;

import org.lwjgl.util.vma.VmaAllocationInfo;

import static org.lwjgl.util.vma.Vma.vmaDestroyImage;

public class ImageAllocation extends Allocation {
    protected final long allocator;
    public final long image;

    protected ImageAllocation(long allocator, long image, long allocation, VmaAllocationInfo info) {
        super(allocation, info);
        this.allocator = allocator;
        this.image = image;
    }

    @Override
    protected void free() {
        vmaDestroyImage(allocator, image, allocation);
        super.free();
    }
}
