package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.memory.AccelerationStructurePool;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCopyAccelerationStructureInfoKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR;

/**
 * Handles acceleration structure compaction operations.
 * Manages compact AS creation, copy operations, and result publishing.
 */
public class BLASCompactor {
    private static final Logger LOGGER = LoggerFactory.getLogger(BLASCompactor.class);
    
    private final VContext context;
    private final int asyncQueue;
    private final AccelerationStructurePool accelerationStructurePool;
    private final Consumer<BLASBatchResult> resultConsumer;
    
    public BLASCompactor(
            VContext context,
            int asyncQueue,
            AccelerationStructurePool accelerationStructurePool,
            Consumer<BLASBatchResult> resultConsumer) {
        this.context = context;
        this.asyncQueue = asyncQueue;
        this.accelerationStructurePool = accelerationStructurePool;
        this.resultConsumer = resultConsumer;
    }
    
    /**
     * Compacts acceleration structures and publishes results.
     * 
     * @param jobs The list of BLAS build jobs
     * @param accelerationStructures The list of source (fat) acceleration structures
     * @param compactedSizes The compacted sizes from query pool
     * @param singleUsePoolWorker The command pool for creating command buffers
     * @param stack Memory stack for Vulkan struct allocation
     * @param priorExecutions Deque for tracking prior executions for synchronization
     * @param batchNumber The batch number for logging
     */
    public void compactAndPublish(
            List<BLASBuildJob> jobs,
            List<VRef<VAccelerationStructure>> accelerationStructures,
            long[] compactedSizes,
            VCommandPool singleUsePoolWorker,
            MemoryStack stack,
            Deque<Long> priorExecutions,
            int batchNumber) {
        
        LOGGER.info("[BLAS Compactor] Batch #{} Compacting {} structures", batchNumber, compactedSizes.length);
        
        // Validate array sizes match
        if (compactedSizes.length != accelerationStructures.size()) {
            LOGGER.error("[BLAS Compactor] Batch #{} Array size mismatch: compactedSizes.length={} != accelerationStructures.size()={}",
                batchNumber, compactedSizes.length, accelerationStructures.size());
            throw new IllegalStateException("Array size mismatch: compactedSizes=" + compactedSizes.length +
                ", accelerationStructures=" + accelerationStructures.size());
        }
        if (compactedSizes.length != jobs.size()) {
            LOGGER.error("[BLAS Compactor] Batch #{} Array size mismatch: compactedSizes.length={} != jobs.size()={}",
                batchNumber, compactedSizes.length, jobs.size());
            throw new IllegalStateException("Array size mismatch: compactedSizes=" + compactedSizes.length +
                ", jobs=" + jobs.size());
        }
        
        List<BLASBuildResult> results = new ArrayList<>(jobs.size());
        var cmdRef = singleUsePoolWorker.createCommandBuffer();
        
        long compactStartTime = System.nanoTime();
        
        for (int idx = 0; idx < compactedSizes.length; idx++) {
            LOGGER.debug("[BLAS Compactor] Batch #{} Creating compact AS[{}] with size {}", 
                batchNumber, idx, compactedSizes[idx]);
            
            // Create compact acceleration structure
            var compact_as = accelerationStructurePool.createAcceleration(compactedSizes[idx],
                VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            
            // Get source (fat) acceleration structure
            var fat_as = accelerationStructures.get(idx);
            
            LOGGER.debug("[BLAS Compactor] Batch #{} Copying AS[{}]: src=0x{}, dst=0x{}",
                batchNumber, idx, Long.toHexString(fat_as.get().structure),
                Long.toHexString(compact_as.get().structure));
            
            // Copy and compact
            vkCmdCopyAccelerationStructureKHR(cmdRef.get().buffer(),
                VkCopyAccelerationStructureInfoKHR.calloc(stack).sType$Default()
                    .src(fat_as.get().structure)
                    .dst(compact_as.get().structure)
                    .mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR));
            
            // Add references and cleanup
            cmdRef.get().addAccelerationStructureRef(fat_as);
            cmdRef.get().addAccelerationStructureRef(compact_as);
            fat_as.close();
            
            // Create result
            var job = jobs.get(idx);
            results.add(new BLASBuildResult(compact_as, job.data()));
        }
        
        // Submit compaction command
        LOGGER.info("[BLAS Compactor] Batch #{} Enqueueing compaction command", batchNumber);
        long submitStartTime = System.nanoTime();
        CompletableFuture<Long> blasExecutionFuture = context.cmd.enqueueSubmission(asyncQueue, cmdRef);
        long submitTime = System.nanoTime() - submitStartTime;
        cmdRef.close();
        
        long blasExecution;
        try {
            long waitStartTime = System.nanoTime();
            blasExecution = blasExecutionFuture.get();
            long waitTime = System.nanoTime() - waitStartTime;
            long compactTime = System.nanoTime() - compactStartTime;
            LOGGER.info("[BLAS Compactor] Batch #{} Compaction completed (execution={}) enqueue={} ms, submissionWait={} ms, compactStage={} ms",
                    batchNumber, blasExecution, formatMillis(submitTime), formatMillis(waitTime),
                    formatMillis(compactTime));
        } catch (Exception e) {
            LOGGER.error("[BLAS Compactor] Batch #{} Failed while waiting for compaction submission", batchNumber, e);
            throw new RuntimeException(e);
        }
        
        // Publish results
        try {
            resultConsumer.accept(new BLASBatchResult(results, blasExecution));
            LOGGER.info("[BLAS Compactor] Batch #{} Published {} results, enqueueToPublish avg={} ms, max={} ms",
                    batchNumber, results.size(), formatMillis(averageEnqueueToPublishNanos(results)),
                    formatMillis(maxEnqueueToPublishNanos(results)));
        } catch (Exception e) {
            LOGGER.error("[BLAS Compactor] Batch #{} Error publishing results", batchNumber, e);
            throw e;
        }
        
        // Track execution and handle prior synchronization
        priorExecutions.add(blasExecution);
        if (priorExecutions.size() > 3) {
            long prior = priorExecutions.poll();
            LOGGER.debug("[BLAS Compactor] Batch #{} Waiting for prior execution {}", batchNumber, prior);
            context.cmd.hostWaitForExecution(asyncQueue, prior);
        }
        
        LOGGER.info("[BLAS Compactor] Batch #{} Compaction complete", batchNumber);
    }

    private static long averageEnqueueToPublishNanos(List<BLASBuildResult> results) {
        if (results.isEmpty()) {
            return 0L;
        }
        long now = System.nanoTime();
        long total = 0L;
        for (BLASBuildResult result : results) {
            total += Math.max(0L, now - result.data().enqueuedNanos());
        }
        return total / results.size();
    }

    private static long maxEnqueueToPublishNanos(List<BLASBuildResult> results) {
        long now = System.nanoTime();
        long max = 0L;
        for (BLASBuildResult result : results) {
            max = Math.max(max, Math.max(0L, now - result.data().enqueuedNanos()));
        }
        return max;
    }

    private static String formatMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}
