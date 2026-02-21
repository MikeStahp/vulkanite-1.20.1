package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.other.VQueryPool;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Orchestrates BLAS batch processing.
 * Coordinates geometry processing, build operations, query operations, and compaction.
 */
public class BLASBatchProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(BLASBatchProcessor.class);

    // Maximum batch size to prevent memory exhaustion
    // Batches larger than this will be truncated and logged
    private static final int MAX_BATCH_SIZE = 16;

    private final VContext context;
    private final int asyncQueue;
    private final VRef<VQueryPool> queryPool;
    private final VRef<VComputePipeline> gpuVertexDecodePipeline;
    private final BLASQueryBuilder queryBuilder;
    private final BLASCompactor compactor;
    
    private long totalBatchesProcessed = 0;
    
    public BLASBatchProcessor(
                VContext context,
                int asyncQueue,
                VRef<VQueryPool> queryPool,
                VRef<VComputePipeline> gpuVertexDecodePipeline,
                me.cortex.vulkanite.lib.memory.AccelerationStructurePool accelerationStructurePool,
                Consumer<BLASBatchResult> resultConsumer) {
        this.context = context;
        this.asyncQueue = asyncQueue;
        this.queryPool = queryPool;
        this.gpuVertexDecodePipeline = gpuVertexDecodePipeline;
        this.queryBuilder = new BLASQueryBuilder(queryPool);
        this.compactor = new BLASCompactor(context, asyncQueue, accelerationStructurePool, resultConsumer);
    }
    
    /**
     * Processes a batch of BLAS build jobs.
     * 
     * @param buildCtx The build context containing jobs and allocators
     * @param singleUsePoolWorker The command pool for creating command buffers
     * @param priorExecutions Deque for tracking prior executions for synchronization
     * @param stack Memory stack for Vulkan struct allocation
     */
    public void processBatch(
            BLASBuildWorker.BLASBuildContext buildCtx,
            VCommandPool singleUsePoolWorker,
            Deque<Long> priorExecutions,
            MemoryStack stack) {

        var jobs = buildCtx.jobs;
        
        // Validate and potentially truncate batch size to prevent memory exhaustion
        if (jobs.size() > MAX_BATCH_SIZE) {
            LOGGER.warn("[BLAS Batch #{}] Batch size {} exceeds MAX_BATCH_SIZE ({}), truncating to {} jobs. " +
                    "Consider reducing BLASBuildWorker.collectJobs() limit.",
                    totalBatchesProcessed + 1, jobs.size(), MAX_BATCH_SIZE, MAX_BATCH_SIZE);
        }
        
        totalBatchesProcessed++;
        int batchNumber = (int) totalBatchesProcessed;

        long batchStartTime = System.nanoTime();
        LOGGER.info("[BLAS Batch #{}] Starting processing with {} jobs (max allowed: {})",
                batchNumber, jobs.size(), MAX_BATCH_SIZE);
        
        // Allocate buffers for build info
        var buildInfos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(jobs.size(), stack);
        PointerBuffer buildRanges = stack.mallocPointer(jobs.size());
        LongBuffer pAccelerationStructures = stack.mallocLong(jobs.size());
        
        var accelerationStructures = new ArrayList<VRef<VAccelerationStructure>>(jobs.size());
        
        // Create command buffer for geometry processing and build
        var uploadBuildCmdRef = singleUsePoolWorker.createCommandBuffer();
        var uploadBuildCmd = uploadBuildCmdRef.get();
        uploadBuildCmd.bindCompute(gpuVertexDecodePipeline);
        
        // Process geometry for all jobs
        var geometryProcessor = new BLASGeometryProcessor(context, buildCtx, uploadBuildCmd);
        long geometryProcessStartTime = System.nanoTime();
        
        for (int i = 0; i < jobs.size(); i++) {
            var job = jobs.get(i);
            LOGGER.debug("[BLAS Batch #{}] Processing job {} with {} geometries", 
                batchNumber, i, job.geometries().size());
            geometryProcessor.processJob(job, i, buildInfos, buildRanges, pAccelerationStructures, accelerationStructures);
        }
        
        long geometryProcessTime = (System.nanoTime() - geometryProcessStartTime) / 1_000_000;
        LOGGER.info("[BLAS Batch #{}] Geometry processing completed in {} ms", batchNumber, geometryProcessTime);
        
        // Prepare for build
        buildInfos.rewind();
        buildRanges.rewind();
        pAccelerationStructures.rewind();
        
        // Build acceleration structures
        LOGGER.info("[BLAS Batch #{}] Building {} acceleration structures", batchNumber, jobs.size());
        long buildStartTime = System.nanoTime();
        vkCmdBuildAccelerationStructuresKHR(uploadBuildCmd.buffer(), buildInfos, buildRanges);
        
        // Add memory barrier for AS build synchronization
        encodeASBuildMemoryBarrier(uploadBuildCmd, stack);
        
        // Query pool operations
        queryBuilder.resetQueryPool(uploadBuildCmd, jobs.size());
        queryBuilder.writeASProperties(uploadBuildCmd, pAccelerationStructures, stack);
        queryBuilder.encodeQueryPoolHostVisibilityBarrier(uploadBuildCmd, stack);
        
        // Submit and wait for build to complete
        LOGGER.info("[BLAS Batch #{}] Submitting build command", batchNumber);
        CompletableFuture<Long> buildExecutionFuture = context.cmd.enqueueSubmission(asyncQueue, uploadBuildCmdRef);
        
        Long buildExecution;
        try {
            buildExecution = buildExecutionFuture.get();
            long buildTime = (System.nanoTime() - buildStartTime) / 1_000_000;
            LOGGER.debug("[BLAS Batch #{}] Build completed in {} ms (execution={})", batchNumber, buildTime, buildExecution);
        } catch (Exception e) {
            LOGGER.error("[BLAS Batch #{}] Error waiting for build completion", batchNumber, e);
            throw new RuntimeException("Failed to complete BLAS build submission", e);
        }
        
        uploadBuildCmdRef.close();
        
        // Read and validate compacted sizes
        long queryReadStartTime = System.nanoTime();
        long[] compactedSizes;
        try {
            compactedSizes = queryBuilder.readCompactedSizes(jobs.size());
            long queryReadTime = (System.nanoTime() - queryReadStartTime) / 1_000_000;
            LOGGER.info("[BLAS Batch #{}] Query read completed in {} ms", batchNumber, queryReadTime);
            
            // Validate compacted sizes
            queryBuilder.validateCompactedSizes(compactedSizes, batchNumber);
        } catch (Exception e) {
            LOGGER.error("[BLAS Batch #{}] Error reading query pool results", batchNumber, e);
            throw e;
        }

        // Compact and publish results (compactor handles closing source AS)
        compactor.compactAndPublish(jobs, accelerationStructures, compactedSizes,
            singleUsePoolWorker, stack, priorExecutions, batchNumber);

        long totalBatchTime = (System.nanoTime() - batchStartTime) / 1_000_000;
        LOGGER.info("[BLAS Batch #{}] Completed in {} ms total", batchNumber, totalBatchTime);
    }
    
    /**
     * Encodes a memory barrier specifically for acceleration structure build synchronization.
     * This ensures that AS build writes are visible before reading query pool results.
     */
    private void encodeASBuildMemoryBarrier(VCmdBuff cmd, MemoryStack stack) {
        var barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
            .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        
        vkCmdPipelineBarrier(cmd.buffer(),
            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            0, barrier, null, null);
        
        LOGGER.debug("[BLAS Batch] AS build memory barrier encoded");
    }
    
    /**
     * Gets the total number of batches processed.
     */
    public long getTotalBatchesProcessed() {
        return totalBatchesProcessed;
    }
}
