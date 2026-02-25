package me.cortex.vulkanite.lib.memory;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkDevice;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.util.vma.Vma.vmaFlushAllocation;
import static org.lwjgl.util.vma.Vma.vmaMapMemory;
import static org.lwjgl.util.vma.Vma.vmaUnmapMemory;
import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

public class BufferAllocation extends Allocation {
    protected final long allocator;
    protected final VkDevice device;
    public final long buffer;
    public final long deviceAddress;
    public final int memoryProperties;
    public final int vmaFlags;

    protected BufferAllocation(VkDevice device, long allocator, long buffer, long allocation, VmaAllocationInfo info, boolean hasDeviceAddresses, boolean requestAddress, int memoryProperties, int vmaFlags) {
        super(allocation, info);
        this.device = device;
        this.allocator = allocator;
        this.buffer = buffer;
        this.memoryProperties = memoryProperties;
        this.vmaFlags = vmaFlags;

        if (hasDeviceAddresses && requestAddress) {
            try (MemoryStack stack = stackPush()) {
                this.deviceAddress = vkGetBufferDeviceAddress(device, VkBufferDeviceAddressInfo
                        .calloc(stack)
                        .sType$Default()
                        .buffer(buffer));
            }
        } else {
            this.deviceAddress = -1;
        }
    }

    // Constructor without properties/flags for backward compatibility if needed, or update call sites
    protected BufferAllocation(VkDevice device, long allocator, long buffer, long allocation, VmaAllocationInfo info, boolean hasDeviceAddresses, boolean requestAddress) {
        this(device, allocator, buffer, allocation, info, hasDeviceAddresses, requestAddress, 0, 0);
    }

    @Override
    protected void free() {
        vmaDestroyBuffer(allocator, buffer, allocation);
        super.free();
    }

    public VkDevice getDevice() {
        return device;
    }

    public long map() {
        try (var stack = stackPush()) {
            PointerBuffer res = stack.callocPointer(1);
            _CHECK_(vmaMapMemory(allocator, allocation, res), "Failed to map memory");
            return res.get(0);
        }
    }

    public void unmap() {
        vmaUnmapMemory(allocator, allocation);
    }

    public void flush(long offset, long size) {
        vmaFlushAllocation(allocator, allocation, offset, size);
    }
}
