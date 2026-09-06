package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.memory.AccelerationStructurePool;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.other.VQueryPool;
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
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WAIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;

/**
 * Handles acceleration structure compaction operations.
 * Manages compact AS creation, copy operations, and result publishing.
 */
public class BLASCompactor {
    private static final Logger LOGGER = LoggerFactory.getLogger(BLASCompactor.class);
    private static final long INFO_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SLOW_COMPACTION_LOG_NANOS =
            Long.getLong("vulkanite.blasSlowCompactionLogMs", 40L) * 1_000_000L;
    private static final int COMPACT_TIMESTAMP_START = 0;
    private static final int COMPACT_TIMESTAMP_END = 1;
    
    private final VContext context;
    private final int asyncQueue;
    private final AccelerationStructurePool accelerationStructurePool;
    private final Consumer<BLASBatchResult> resultConsumer;
    private final VRef<VQueryPool> timestampQueryPool;
    private long lastInfoLogNanos;
    
    public BLASCompactor(
            VContext context,
            int asyncQueue,
            AccelerationStructurePool accelerationStructurePool,
            Consumer<BLASBatchResult> resultConsumer) {
        this.context = context;
        this.asyncQueue = asyncQueue;
        this.accelerationStructurePool = accelerationStructurePool;
        this.resultConsumer = resultConsumer;
        this.timestampQueryPool = VQueryPool.create(context.device, 2, VK_QUERY_TYPE_TIMESTAMP);
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
            List<ShadowTriangleBLASBuild> shadowBuilds,
            List<ProceduralBLASBuild> proceduralBuilds,
            long[] compactedSizes,
            VCommandPool singleUsePoolWorker,
            MemoryStack stack,
            Deque<Long> priorExecutions,
            int batchNumber) {
        
        LOGGER.debug("[BLAS Compactor] Batch #{} Compacting {} structures", batchNumber, compactedSizes.length);
        
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
        boolean resultsTransferred = false;
        boolean waitInterrupted = false;
        try {
            long compactStartTime = System.nanoTime();
            cmdRef.get().resetQueryPool(timestampQueryPool, COMPACT_TIMESTAMP_START, 2);
            cmdRef.get().writeTimestamp(
                    timestampQueryPool, COMPACT_TIMESTAMP_START, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);

            for (int idx = 0; idx < compactedSizes.length; idx++) {
                LOGGER.debug("[BLAS Compactor] Batch #{} Creating compact AS[{}] with size {}",
                        batchNumber, idx, compactedSizes[idx]);

                VRef<VAccelerationStructure> compactStructure = accelerationStructurePool.createAcceleration(
                        compactedSizes[idx], VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                boolean compactStructureTransferred = false;
                try {
                    VRef<VAccelerationStructure> sourceStructure = accelerationStructures.get(idx);
                    LOGGER.debug("[BLAS Compactor] Batch #{} Copying AS[{}]: src=0x{}, dst=0x{}",
                            batchNumber, idx, Long.toHexString(sourceStructure.get().structure),
                            Long.toHexString(compactStructure.get().structure));

                    vkCmdCopyAccelerationStructureKHR(cmdRef.get().buffer(),
                            VkCopyAccelerationStructureInfoKHR.calloc(stack).sType$Default()
                                    .src(sourceStructure.get().structure)
                                    .dst(compactStructure.get().structure)
                                    .mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR));
                    cmdRef.get().addAccelerationStructureRef(sourceStructure);
                    cmdRef.get().addAccelerationStructureRef(compactStructure);

                    BLASBuildResult result = BLASBatchProcessor.createBuildResult(
                            compactStructure,
                            jobs.get(idx),
                            shadowBuilds.get(idx),
                            proceduralBuilds.get(idx));
                    try {
                        results.add(result);
                        compactStructureTransferred = true;
                    } catch (RuntimeException | Error failure) {
                        result.close();
                        throw failure;
                    }
                } finally {
                    if (!compactStructureTransferred) {
                        compactStructure.close();
                    }
                }
            }
            cmdRef.get().writeTimestamp(timestampQueryPool, COMPACT_TIMESTAMP_END,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);

            LOGGER.debug("[BLAS Compactor] Batch #{} Enqueueing compaction command", batchNumber);
            long submitStartTime = System.nanoTime();
            CompletableFuture<Long> blasExecutionFuture =
                    context.cmd.enqueueSubmission(asyncQueue, cmdRef);
            long submitTime = System.nanoTime() - submitStartTime;
            cmdRef.close();

            long blasExecution;
            try {
                long waitStartTime = System.nanoTime();
                while (true) {
                    try {
                        blasExecution = blasExecutionFuture.get();
                        break;
                    } catch (InterruptedException interrupted) {
                        // The compact source/destination structures are retained
                        // by the submitted command. Do not let worker shutdown
                        // reclaim them until that submission has completed.
                        waitInterrupted = true;
                    }
                }
                long waitTime = System.nanoTime() - waitStartTime;
                long compactTime = System.nanoTime() - compactStartTime;
                long compactGpuNanos = readCompactGpuNanos();
                long now = System.nanoTime();
                if (compactTime >= SLOW_COMPACTION_LOG_NANOS
                        && now - lastInfoLogNanos >= INFO_LOG_INTERVAL_NANOS) {
                    lastInfoLogNanos = now;
                    LOGGER.info("[BLAS Compactor] Batch #{} Compaction completed (execution={}) enqueue={} ms, submissionWait={} ms, compactStage={} ms, gpuCompact={} ms",
                            batchNumber, blasExecution, formatMillis(submitTime), formatMillis(waitTime),
                            formatMillis(compactTime), formatMillis(compactGpuNanos));
                } else {
                    LOGGER.debug("[BLAS Compactor] Batch #{} Compaction completed (execution={}) enqueue={} ms, submissionWait={} ms, compactStage={} ms, gpuCompact={} ms",
                            batchNumber, blasExecution, formatMillis(submitTime), formatMillis(waitTime),
                            formatMillis(compactTime), formatMillis(compactGpuNanos));
                }
            } catch (Exception e) {
                LOGGER.error("[BLAS Compactor] Batch #{} Failed while waiting for compaction submission",
                        batchNumber, e);
                throw new RuntimeException(e);
            }

            if (priorExecutions.size() >= 3) {
                long prior = priorExecutions.poll();
                LOGGER.debug("[BLAS Compactor] Batch #{} Waiting for prior execution {}", batchNumber, prior);
                context.cmd.hostWaitForExecution(asyncQueue, prior);
            }
            priorExecutions.add(blasExecution);

            try {
                LOGGER.debug("[BLAS Compactor] Batch #{} Publishing {} results, enqueueToPublish avg={} ms, max={} ms",
                        batchNumber, results.size(), formatMillis(averageEnqueueToPublishNanos(results)),
                        formatMillis(maxEnqueueToPublishNanos(results)));
                resultConsumer.accept(new BLASBatchResult(results, blasExecution));
                resultsTransferred = true;
            } catch (Exception e) {
                LOGGER.error("[BLAS Compactor] Batch #{} Error publishing results", batchNumber, e);
                throw e;
            }
        } finally {
            cmdRef.close();
            BLASBatchProcessor.closeAccelerationStructures(accelerationStructures);
            if (!resultsTransferred) {
                for (BLASBuildResult result : results) {
                    result.close();
                }
                BLASBatchProcessor.discardShadowBuilds(shadowBuilds);
                BLASBatchProcessor.discardProceduralBuilds(proceduralBuilds);
            }
            if (waitInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
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

    private long readCompactGpuNanos() {
        long[] timestamps = timestampQueryPool.get().getResultsLong(
                COMPACT_TIMESTAMP_START, 2, VK_QUERY_RESULT_WAIT_BIT);
        return timestampDeltaNanos(timestamps[0], timestamps[1]);
    }

    private long timestampDeltaNanos(long start, long end) {
        if (end <= start) {
            return 0L;
        }
        return Math.round((end - start) * (double) context.properties.timestampPeriodNanos);
    }

    public void cleanup() {
        timestampQueryPool.close();
    }
}
