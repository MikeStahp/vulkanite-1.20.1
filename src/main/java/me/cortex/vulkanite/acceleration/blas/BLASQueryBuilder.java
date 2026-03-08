package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.other.VQueryPool;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Handles query pool operations for BLAS builds.
 * Manages query pool writes, memory barriers, and result reading.
 */
public class BLASQueryBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(BLASQueryBuilder.class);
    
    private final VRef<VQueryPool> queryPool;
    
    public BLASQueryBuilder(VRef<VQueryPool> queryPool) {
        this.queryPool = queryPool;
    }
    
    /**
     * Resets the query pool for a new batch of queries.
     */
    public void resetQueryPool(VCmdBuff cmd, int queryCount) {
        LOGGER.debug("[BLAS Query] Resetting query pool for {} queries", queryCount);
        cmd.resetQueryPool(queryPool, 0, queryCount);
    }
    
    /**
     * Writes acceleration structure properties to the query pool.
     */
    public void writeASProperties(VCmdBuff cmd, LongBuffer pAccelerationStructures, MemoryStack stack) {
        LOGGER.debug("[BLAS Query] Writing AS properties to query pool");
        LOGGER.debug("[BLAS Query] pAccelerationStructures count: {}", pAccelerationStructures.remaining());
        
        for (int i = 0; i < pAccelerationStructures.remaining(); i++) {
            LOGGER.debug("[BLAS Query] AS[{}] handle: 0x{}", i, Long.toHexString(pAccelerationStructures.get(i)));
        }
        pAccelerationStructures.rewind();
        
        vkCmdWriteAccelerationStructuresPropertiesKHR(
            cmd.buffer(),
            pAccelerationStructures,
            VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
            queryPool.get().pool,
            0);
        
        LOGGER.debug("[BLAS Query] vkCmdWriteAccelerationStructuresPropertiesKHR completed");
    }
    
    /**
     * Encodes a memory barrier to ensure query pool writes are visible to the host.
     * This barrier must be placed after vkCmdWriteAccelerationStructuresPropertiesKHR
     * to ensure the query results are flushed from GPU caches and visible to CPU reads.
     */
    public void encodeQueryPoolHostVisibilityBarrier(VCmdBuff cmd, MemoryStack stack) {
        var barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
            // All prior GPU writes (including AS build and query write)
            .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_SHADER_WRITE_BIT)
            // Host read is the destination
            .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
        
        // Pipeline barrier from all commands to host stage
        // Using ALL_COMMANDS_BIT ensures query pool writes are included
        vkCmdPipelineBarrier(cmd.buffer(),
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_HOST_BIT,
            0, barrier, null, null);
        
        LOGGER.debug("[BLAS Query] Query pool host visibility barrier encoded: src=ALL_COMMANDS, dst=HOST_READ");
    }
    
    /**
     * Reads compacted sizes from the query pool.
     * Includes pre-read validation to prevent excessive memory allocation.
     *
     * @param queryCount Number of query results to read
     * @return Array of compacted sizes
     * @throws IllegalStateException if queryCount is excessive
     */
    public long[] readCompactedSizes(int queryCount) {
        // Pre-read validation: sanity check for excessive query counts
        // This prevents potential MemoryStack overflow from malformed batch sizes
        final int MAX_QUERY_COUNT = 1024; // Sanity limit to prevent memory exhaustion
        
        if (queryCount <= 0) {
            LOGGER.error("[BLAS Query] INVALID query count: {} (must be > 0)", queryCount);
            throw new IllegalStateException("Invalid query count: " + queryCount);
        }
        
        if (queryCount > MAX_QUERY_COUNT) {
            LOGGER.error("[BLAS Query] EXCESSIVE query count: {} (max allowed: {}). " +
                    "This may indicate a batch size control failure.", queryCount, MAX_QUERY_COUNT);
            throw new IllegalStateException("Excessive query count: " + queryCount +
                    ". Max allowed: " + MAX_QUERY_COUNT);
        }
        
        LOGGER.debug("[BLAS Query] Reading {} query results (validated, max: {})", queryCount, MAX_QUERY_COUNT);
        return queryPool.get().getResultsLong(queryCount);
    }
    
    /**
     * Validates compacted sizes for sanity.
     * @throws IllegalStateException if any size is invalid
     */
    public void validateCompactedSizes(long[] compactedSizes, int batchNumber) {
        LOGGER.debug("[BLAS Query] Validating {} compacted sizes for batch #{}", compactedSizes.length, batchNumber);
        
        long totalCompactedSize = 0;
        for (int i = 0; i < compactedSizes.length; i++) {
            long size = compactedSizes[i];
            totalCompactedSize += size;
            
            if (size <= 0) {
                LOGGER.error("[BLAS Query] Batch #{} AS[{}] INVALID compacted size: {} (must be > 0)", 
                    batchNumber, i, size);
                throw new IllegalStateException("Invalid compacted size for AS[" + i + "]: " + size);
            }
            
            // 500MB sanity check per structure
            if (size > 500_000_000) {
                LOGGER.error("[BLAS Query] Batch #{} AS[{}] EXCESSIVE compacted size: {} bytes", 
                    batchNumber, i, size);
                throw new IllegalStateException("Excessive compacted size for AS[" + i + "]: " + size);
            }
        }
        
        LOGGER.info("[BLAS Query] Batch #{} Total compacted size: {} bytes ({} MB)",
            batchNumber, totalCompactedSize, totalCompactedSize / (1024 * 1024));
    }
    
    /**
     * Gets the query pool reference.
     */
    public VRef<VQueryPool> getQueryPool() {
        return queryPool;
    }
}
