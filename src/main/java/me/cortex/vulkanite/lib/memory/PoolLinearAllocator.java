package me.cortex.vulkanite.lib.memory;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;

import java.util.Stack;

import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;

public class PoolLinearAllocator {
    private final int usage;
    private final int properties;
    private final int vmaFlags;
    private final long poolSize;
    private final long alignment;
    private final long alignmentMask;

    private VRef<VBuffer> buffer;
    private long currentOffset;
    
    // Pool of reusable buffers for better memory management
    private final Stack<VRef<VBuffer>> bufferPool = new Stack<>();
    private int maxPoolSize = 3; // Limit pool size to prevent excessive memory usage

    private final VContext ctx;

    public record BufferRegion(VRef<VBuffer> buffer, long offset, long size, long deviceAddress) {
    }

    public PoolLinearAllocator(VContext ctx, int usage, long poolSize, long alignment) {
        this(ctx, usage, poolSize, alignment, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
    }

    public PoolLinearAllocator(VContext ctx, int usage, long poolSize, long alignment, int properties, int vmaFlags) {
        this.ctx = ctx;
        this.usage = usage;
        this.poolSize = poolSize;
        this.alignment = alignment;
        this.properties = properties;
        this.vmaFlags = vmaFlags;
        this.alignmentMask = ~(alignment - 1);

        this.currentOffset = 0;

        newBuffer(poolSize);
    }
    
    /**
     * Creates a new PoolLinearAllocator with a specified maximum pool size.
     *
     * @param ctx The Vulkan context
     * @param usage The buffer usage flags
     * @param poolSize The initial pool size
     * @param alignment The alignment requirement
     * @param maxPoolSize The maximum number of buffers to keep in the pool
     */
    public PoolLinearAllocator(VContext ctx, int usage, long poolSize, long alignment, int maxPoolSize) {
        this(ctx, usage, poolSize, alignment, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
        this.maxPoolSize = maxPoolSize;
    }

    private void newBuffer(long size) {
        // Try to reuse a buffer from the pool first
        VRef<VBuffer> newBuffer = null;
        if (!bufferPool.isEmpty() && bufferPool.peek().get().size() >= size) {
            newBuffer = bufferPool.pop();
        } else {
            newBuffer = ctx.memory.createBuffer(size, usage, properties, alignment, 0);
        }

        // Return the current buffer to the pool if it exists
        if (buffer != null) {
            if (bufferPool.size() < maxPoolSize) {
                bufferPool.push(buffer);
            } else {
                buffer.close();
            }
        }

        buffer = newBuffer;
        currentOffset = 0;
    }
    
    /**
     * Clears the buffer pool, closing all pooled buffers.
     * Should be called when the allocator is no longer needed.
     */
    public void clearPool() {
        while (!bufferPool.isEmpty()) {
            bufferPool.pop().close();
        }
    }

    public BufferRegion allocate(long size) {
        // Check if buffer is null (e.g., after reset) or if we need a new buffer
        if (buffer == null || currentOffset + size > poolSize) {
            newBuffer(Long.max(poolSize, size));
        }

        if (buffer == null) {
            throw new IllegalStateException("Buffer is null after newBuffer call - size requested: " + size + ", poolSize: " + poolSize);
        }
        
        long deviceAddress = buffer.get().hasDeviceAddress() ? buffer.get().deviceAddress() + currentOffset : 0;
        BufferRegion region = new BufferRegion(buffer, currentOffset, size, deviceAddress);
        currentOffset = (currentOffset + size + alignment - 1) & alignmentMask;

        return region;
    }

    public void reset() {
        // Return the current buffer to the pool instead of closing it
        if (buffer != null) {
            if (bufferPool.size() < maxPoolSize) {
                bufferPool.push(buffer);
            } else {
                buffer.close();
            }
            buffer = null;
        }
        currentOffset = 0;
    }
}
