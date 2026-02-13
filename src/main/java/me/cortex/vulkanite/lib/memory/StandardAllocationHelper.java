package me.cortex.vulkanite.lib.memory;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

public class StandardAllocationHelper extends BaseAllocationHelper {

    public StandardAllocationHelper(VkDevice device, long allocator, boolean hasDeviceAddresses) {
        super(device, allocator, hasDeviceAddresses);
    }

    @Override
    public BufferAllocation allocBuffer(long pool, VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo, long alignment) {
        if (bufferCreateInfo.size() == 0) {
            throw new RuntimeException("Buffer size must be > 0");
        }

        try (var stack = stackPush()) {
            LongBuffer pb = stack.mallocLong(1);
            PointerBuffer pa = stack.mallocPointer(1);
            VmaAllocationInfo vai = VmaAllocationInfo.calloc();

            if (pool != 0) allocationCreateInfo.pool(pool);

            _CHECK_(vmaCreateBufferWithAlignment(allocator, bufferCreateInfo, allocationCreateInfo, alignment, pb, pa, vai),
                    "Failed to allocate buffer");

            boolean requestAddress = (bufferCreateInfo.usage() & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0;
            return new BufferAllocation(device, allocator, pb.get(0), pa.get(0), vai, hasDeviceAddresses, requestAddress);
        }
    }

    @Override
    public ImageAllocation allocImage(long pool, VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
        testModifyFormatSupport(device, imageCreateInfo);
        try (var stack = stackPush()) {
            LongBuffer pi = stack.mallocLong(1);
            PointerBuffer pa = stack.mallocPointer(1);
            VmaAllocationInfo vai = VmaAllocationInfo.calloc();

            if (pool != 0) allocationCreateInfo.pool(pool);

            _CHECK_(vmaCreateImage(allocator, imageCreateInfo, allocationCreateInfo, pi, pa, vai),
                    "Failed to allocate image");

            return new ImageAllocation(allocator, pi.get(0), pa.get(0), vai);
        }
    }
}