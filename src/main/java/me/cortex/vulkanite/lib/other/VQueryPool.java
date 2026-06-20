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
    private static final long SLOW_QUERY_LOG_NANOS =
            Long.getLong("vulkanite.querySlowLogMs", 10L) * 1_000_000L;
    
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
        long readStartTime = System.nanoTime();
        // Use heap allocation instead of stack allocation to prevent MemoryStack overflow
        // when dealing with large query counts in BLAS operations
        LongBuffer results = MemoryUtil.memAllocLong(count);
        try {
            // Try to get results - this will wait if VK_QUERY_RESULT_WAIT_BIT is set
            int result = vkGetQueryPoolResults(device, pool, start, count, results, Long.BYTES,
                    VK_QUERY_RESULT_64_BIT | flags);

            long readDurationNanos = System.nanoTime() - readStartTime;
            double readDurationMs = readDurationNanos / 1_000_000.0;

            if (result != VK_SUCCESS && result != VK_NOT_READY) {
                LOGGER.error("Query pool read FAILED after {} ms: result=0x{} ({})",
                        readDurationMs, Integer.toHexString(result), VUtil.getResultName(result));
                _CHECK_(result);
            } else if (result == VK_NOT_READY) {
                LOGGER.warn("Query pool results NOT READY after {} ms (start={}, count={})",
                        readDurationMs, start, count);
                // Still check it to throw appropriate exception
                _CHECK_(result);
            } else if (readDurationNanos >= SLOW_QUERY_LOG_NANOS) {
                LOGGER.debug("Query pool read completed in {} ms (start={}, count={}, flags=0x{})",
                        readDurationMs, start, count, Integer.toHexString(flags));
            } else if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Query pool read completed in {} ms (start={}, count={}, flags=0x{})",
                        readDurationMs, start, count, Integer.toHexString(flags));
            }

            var res = new long[count];
            results.rewind();
            results.get(res);
            return res;
        } catch (Exception e) {
            double readDurationMs = (System.nanoTime() - readStartTime) / 1_000_000.0;
            LOGGER.error("Exception reading query pool results after {} ms (start={}, count={}, flags=0x{})",
                    readDurationMs, start, count, Integer.toHexString(flags), e);
            throw e;
        } finally {
            // Always free the heap-allocated buffer to prevent memory leaks
            MemoryUtil.memFree(results);
        }
    }

    public long[] getResultsLongIfAvailable(int start, int count) {
        long readStartTime = System.nanoTime();
        LongBuffer results = MemoryUtil.memAllocLong(count * 2);
        try {
            int result = vkGetQueryPoolResults(device, pool, start, count, results,
                    Long.BYTES * 2L,
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (result == VK_NOT_READY) {
                return null;
            }
            if (result != VK_SUCCESS) {
                LOGGER.error("Query pool availability read FAILED after {} ms: result=0x{} ({})",
                        (System.nanoTime() - readStartTime) / 1_000_000.0,
                        Integer.toHexString(result), VUtil.getResultName(result));
                _CHECK_(result);
            }

            long[] values = new long[count];
            for (int i = 0; i < count; i++) {
                long value = results.get(i * 2);
                long available = results.get(i * 2 + 1);
                if (available == 0L) {
                    return null;
                }
                values[i] = value;
            }
            return values;
        } finally {
            MemoryUtil.memFree(results);
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
