package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.PoolLinearAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/**
 * Manages buffer allocators for BLAS build operations.
 * Handles allocation and reset of build buffers, scratch buffers, and AS buffers.
 */
public class BLASMemoryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BLASMemoryManager.class);
    
    // Buffer allocator sizes - 64MB for build buffers, optimized for large batches
    private static final long BUILD_BUFFER_SIZE = 0x400_0000L;  // 64MB
    private static final long SCRATCH_BUFFER_SIZE = 0x400_0000L; // 64MB
    private static final long AS_BUFFER_SIZE = 0x400_0000L;      // 64MB
    private static final boolean COMPACT_BLAS = BLASBuildPolicy.compactStaticTerrainBlas();
    
    private final VContext context;
    private PoolLinearAllocator buildBufferAllocator;
    private PoolLinearAllocator scratchAllocator;
    private PoolLinearAllocator initialASBufferAllocator;
    
    public BLASMemoryManager(VContext context) {
        this.context = context;
        initializeAllocators();
    }
    
    /**
     * Initializes or reinitializes all buffer allocators.
     */
    public void initializeAllocators() {
        LOGGER.info("[BLAS Memory] Initializing buffer allocators (64MB each)");
        
        // Build buffer: storage + AS build input + shader device address
        buildBufferAllocator = new PoolLinearAllocator(context, 
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, 
            BUILD_BUFFER_SIZE, 16);
        
        // Scratch buffer: shader device address + storage (for temporary build data)
        scratchAllocator = new PoolLinearAllocator(context,
            VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            SCRATCH_BUFFER_SIZE, 256);
        
        // Initial AS buffer is only needed when a later compaction copy will
        // move the built structure into the persistent AS pool.
        if (COMPACT_BLAS) {
            initialASBufferAllocator = new PoolLinearAllocator(context,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                AS_BUFFER_SIZE, 256);
        }
        
        LOGGER.info("[BLAS Memory] Buffer allocators initialized");
    }
    
    /**
     * Resets all allocators for reuse. Call this between batches to reclaim memory.
     */
    public void reset() {
        if (buildBufferAllocator != null) buildBufferAllocator.reset();
        if (scratchAllocator != null) scratchAllocator.reset();
        if (initialASBufferAllocator != null) initialASBufferAllocator.reset();
        LOGGER.debug("[BLAS Memory] Allocators reset for next batch");
    }
    
    /**
     * Gets the build buffer allocator.
     */
    public PoolLinearAllocator getBuildBufferAllocator() {
        return buildBufferAllocator;
    }
    
    /**
     * Gets the scratch buffer allocator.
     */
    public PoolLinearAllocator getScratchAllocator() {
        return scratchAllocator;
    }
    
    /**
     * Gets the initial AS buffer allocator.
     */
    public PoolLinearAllocator getInitialASBufferAllocator() {
        return initialASBufferAllocator;
    }
    
    /**
     * Creates a BLASBuildContext for a batch of jobs.
     */
    public BLASBuildWorker.BLASBuildContext createBuildContext(
            java.util.List<BLASBuildJob> jobs,
            org.lwjgl.system.MemoryStack stack) {
        return new BLASBuildWorker.BLASBuildContext(
            jobs, stack, buildBufferAllocator, scratchAllocator, initialASBufferAllocator);
    }
    
    /**
     * Cleans up all allocators. Call when shutting down.
     */
    public void cleanup() {
        LOGGER.info("[BLAS Memory] Cleaning up buffer allocators");
        if (buildBufferAllocator != null) {
            buildBufferAllocator.close();
            buildBufferAllocator = null;
        }
        if (scratchAllocator != null) {
            scratchAllocator.close();
            scratchAllocator = null;
        }
        if (initialASBufferAllocator != null) {
            initialASBufferAllocator.close();
            initialASBufferAllocator = null;
        }
    }
}
