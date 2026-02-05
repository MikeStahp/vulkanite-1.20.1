package me.cortex.vulkanite.lib.memory;

import me.cortex.vulkanite.lib.base.VObject;
import org.lwjgl.util.vma.VmaAllocationInfo;

public abstract class Allocation extends VObject {
    public final VmaAllocationInfo ai;
    public final long allocation;

    protected Allocation(long allocation, VmaAllocationInfo info) {
        this.ai = info;
        this.allocation = allocation;
    }

    @Override
    protected void free() {
        // Note: Actual VMA memory freeing must be handled
        // by the subclass calling vmaDestroyBuffer/Image, but we free the info here.
        if (ai != null) {
            ai.free();
        }
    }

    public long size() {
        return ai.size();
    }
}
