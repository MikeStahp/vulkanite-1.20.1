package me.cortex.vulkanite.lib.other;

import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class VQueryPool extends VObject {
    private static final Logger LOGGER = LoggerFactory.getLogger(VQueryPool.class);
    
    public final long pool;
    private final VkDevice device;
    private final int queryCount;
    private final int queryType;
    
    private VQueryPool(VkDevice device, int count, int type) {
        this.queryCount = count;
        this.queryType = type;
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pQueryPool = stack.mallocLong(1);
            _CHECK_(vkCreateQueryPool(device,
                    VkQueryPoolCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .queryCount(count)
                            .queryType(type),
                    null, pQueryPool), "Failed to create query pool");
            pool = pQueryPool.get(0);
        }
    }

    public static VRef<VQueryPool> create(VkDevice device, int count, int type) {
        return new VRef<>(new VQueryPool(device, count, type));
    }

    public long[] getResultsLong(int count) {
        return getResultsLong(0, count, VK_QUERY_RESULT_WAIT_BIT);
    }

    public long[] getResultsLong(int start, int count, int flags) {
        LOGGER.info("=== VQueryPool.getResultsLong START ===");
        LOGGER.info("Reading {} query results from query pool (start={}, flags=0x{}, wait={})",
                count, start, Integer.toHexString(flags), (flags & VK_QUERY_RESULT_WAIT_BIT) != 0);
        LOGGER.info("Using heap allocation (MemoryUtil.memAllocLong) to avoid MemoryStack overflow");

        long readStartTime = System.nanoTime();
        // Use heap allocation instead of stack allocation to prevent MemoryStack overflow
        // when dealing with large query counts in BLAS operations
        LongBuffer results = MemoryUtil.memAllocLong(count);
        try {
            LOGGER.info("Heap LongBuffer allocated successfully, capacity: {}, remaining: {}", results.capacity(), results.remaining());

            // Try to get results - this will wait if VK_QUERY_RESULT_WAIT_BIT is set
            LOGGER.info("About to call vkGetQueryPoolResults with count={}, start={}", count, start);
            int result = vkGetQueryPoolResults(device, pool, start, count, results, Long.BYTES,
                    VK_QUERY_RESULT_64_BIT | flags);
            LOGGER.info("vkGetQueryPoolResults returned: 0x{} ({})", Integer.toHexString(result),
                    result == 0 ? "VK_SUCCESS" : result == VK_NOT_READY ? "VK_NOT_READY" : "ERROR");

            long readDuration = (System.nanoTime() - readStartTime) / 1_000_000;

            if (result != VK_SUCCESS && result != VK_NOT_READY) {
                LOGGER.error("Query pool read FAILED after {} ms: result=0x{} ({})",
                        readDuration, Integer.toHexString(result), VUtil.getResultName(result));
                _CHECK_(result);
            } else if (result == VK_NOT_READY) {
                LOGGER.warn("Query pool results NOT READY after {} ms (start={}, count={})",
                        readDuration, start, count);
                // Still check it to throw appropriate exception
                _CHECK_(result);
            } else {
                LOGGER.info("Query pool read SUCCESS after {} ms", readDuration);
            }

            LOGGER.info("About to create result array of size {} and copy from buffer", count);
            var res = new long[count];
            results.rewind();
            LOGGER.info("Buffer rewound, position: {}, remaining: {}", results.position(), results.remaining());
            results.get(res);
            LOGGER.info("Successfully copied {} elements to result array", res.length);
            LOGGER.info("=== VQueryPool.getResultsLong END ===");
            return res;
        } catch (Exception e) {
            long readDuration = (System.nanoTime() - readStartTime) / 1_000_000;
            LOGGER.error("=== VQueryPool.getResultsLong EXCEPTION ===");
            LOGGER.error("Exception reading query pool results after {} ms (start={}, count={}, flags=0x{})",
                    readDuration, start, count, Integer.toHexString(flags), e);
            LOGGER.error("=== VQueryPool.getResultsLong EXCEPTION END ===");
            throw e;
        } finally {
            // Always free the heap-allocated buffer to prevent memory leaks
            MemoryUtil.memFree(results);
            LOGGER.info("Heap LongBuffer freed in finally block");
        }
    }

    protected void free() {
        vkDestroyQueryPool(device, pool, null);
    }

    /**
     * AutoCloseable wrapper for heap-allocated LongBuffer.
     * Ensures proper cleanup of native memory allocated via MemoryUtil.memAllocLong().
     *
     * Usage pattern:
     * <pre>
     * try (HeapLongBuffer buffer = new HeapLongBuffer(MemoryUtil.memAllocLong(count))) {
     *     // use buffer.get()
     * } // automatically freed
     * </pre>
     */
    public static class HeapLongBuffer implements AutoCloseable {
        private final LongBuffer buffer;
        private boolean freed = false;

        public HeapLongBuffer(LongBuffer buffer) {
            this.buffer = buffer;
        }

        public LongBuffer get() {
            if (freed) {
                throw new IllegalStateException("Buffer has been freed");
            }
            return buffer;
        }

        @Override
        public void close() {
            if (!freed) {
                MemoryUtil.memFree(buffer);
                freed = true;
            }
        }

        public boolean isFreed() {
            return freed;
        }
    }
}
