package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.memory.AccelerationStructurePool;
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
import java.util.Optional;
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
    private static final long INFO_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SLOW_BATCH_LOG_NANOS =
            Long.getLong("vulkanite.blasSlowBatchLogMs", 80L) * 1_000_000L;
    private static final boolean COMPACT_BLAS = BLASBuildPolicy.compactStaticTerrainBlas();
    private static final int BUILD_TIMESTAMP_START = 0;
    private static final int BUILD_TIMESTAMP_END = 1;

    // Maximum batch size to prevent long GPU submissions and query-pool pressure.
    // Enqueuers should split larger bursts before they reach the processor.
    public static final int MAX_BATCH_SIZE = configuredMaxBatchSize();

    private final VContext context;
    private final int asyncQueue;
    private final VRef<VQueryPool> queryPool;
    private final VRef<VQueryPool> timestampQueryPool;
    private final VRef<VComputePipeline> gpuVertexDecodePipeline;
    private final BLASQueryBuilder queryBuilder;
    private final BLASCompactor compactor;
    private final AccelerationStructurePool accelerationStructurePool;
    private final Consumer<BLASBatchResult> resultConsumer;
    
    private long totalBatchesProcessed = 0;
    private long lastInfoLogNanos;
    
    public BLASBatchProcessor(
                VContext context,
                int asyncQueue,
                VRef<VQueryPool> queryPool,
                VRef<VComputePipeline> gpuVertexDecodePipeline,
                AccelerationStructurePool accelerationStructurePool,
                Consumer<BLASBatchResult> resultConsumer) {
        this.context = context;
        this.asyncQueue = asyncQueue;
        this.queryPool = queryPool;
        this.timestampQueryPool = VQueryPool.create(context.device, 2, VK_QUERY_TYPE_TIMESTAMP);
        this.gpuVertexDecodePipeline = gpuVertexDecodePipeline;
        this.queryBuilder = new BLASQueryBuilder(queryPool);
        this.accelerationStructurePool = accelerationStructurePool;
        this.resultConsumer = resultConsumer;
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
        
        // Validate batch size to prevent memory exhaustion.
        if (jobs.size() > MAX_BATCH_SIZE) {
            throw new IllegalStateException("BLAS batch size " + jobs.size()
                    + " exceeds MAX_BATCH_SIZE " + MAX_BATCH_SIZE);
        }
        
        totalBatchesProcessed++;
        int batchNumber = (int) totalBatchesProcessed;

        long batchStartTime = System.nanoTime();
        LOGGER.debug("[BLAS Batch #{}] Starting processing with {} jobs (max allowed: {}, terrainPolicy={})",
                batchNumber, jobs.size(), MAX_BATCH_SIZE,
                BLASBuildPolicy.describeStaticTerrainPolicy(COMPACT_BLAS));
        
        // Allocate buffers for build info
        var buildInfos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(jobs.size(), stack);
        PointerBuffer buildRanges = stack.mallocPointer(jobs.size());
        LongBuffer pAccelerationStructures = stack.mallocLong(jobs.size());
        int proceduralBuildCount = (int) jobs.stream().filter(job -> job.proceduralInput().isPresent()).count();
        var proceduralBuildInfos = proceduralBuildCount == 0
                ? null
                : VkAccelerationStructureBuildGeometryInfoKHR.calloc(proceduralBuildCount, stack);
        PointerBuffer proceduralBuildRanges = proceduralBuildCount == 0
                ? null
                : stack.mallocPointer(proceduralBuildCount);
        
        var accelerationStructures = new ArrayList<VRef<VAccelerationStructure>>(jobs.size());
        var proceduralBuilds = new ArrayList<ProceduralBLASBuild>(jobs.size());
        for (int i = 0; i < jobs.size(); i++) {
            proceduralBuilds.add(null);
        }
        
        // Create command buffer for geometry processing and build
        var uploadBuildCmdRef = singleUsePoolWorker.createCommandBuffer();
        var uploadBuildCmd = uploadBuildCmdRef.get();
        uploadBuildCmd.bindCompute(gpuVertexDecodePipeline);
        
        // Process geometry for all jobs
        var geometryProcessor = new BLASGeometryProcessor(
                context, buildCtx, uploadBuildCmd, accelerationStructurePool, COMPACT_BLAS);
        long geometryProcessStartTime = System.nanoTime();
        
        for (int i = 0; i < jobs.size(); i++) {
            var job = jobs.get(i);
            LOGGER.trace("[BLAS Batch #{}] Processing job {} with {} geometries",
                batchNumber, i, job.geometries().size());
            geometryProcessor.processJob(job, i, buildInfos, buildRanges, pAccelerationStructures, accelerationStructures);
            if (job.proceduralInput().isPresent()) {
                proceduralBuilds.set(i, geometryProcessor.processProceduralJob(
                        job, i, proceduralBuildInfos, proceduralBuildRanges));
            }
        }
        geometryProcessor.flushBuildInputBarriers(stack);
        
        long geometryProcessTime = (System.nanoTime() - geometryProcessStartTime) / 1_000_000;
        LOGGER.debug("[BLAS Batch #{}] Geometry processing completed in {} ms", batchNumber, geometryProcessTime);
        
        // Prepare for build
        buildInfos.rewind();
        buildRanges.rewind();
        pAccelerationStructures.rewind();
        if (proceduralBuildInfos != null) {
            proceduralBuildInfos.rewind();
            proceduralBuildRanges.rewind();
        }
        
        // Build acceleration structures
        LOGGER.debug("[BLAS Batch #{}] Building {} acceleration structures", batchNumber, jobs.size());
        long buildStartTime = System.nanoTime();
        uploadBuildCmd.resetQueryPool(timestampQueryPool, BUILD_TIMESTAMP_START, 2);
        uploadBuildCmd.writeTimestamp(timestampQueryPool, BUILD_TIMESTAMP_START, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        vkCmdBuildAccelerationStructuresKHR(uploadBuildCmd.buffer(), buildInfos, buildRanges);
        if (proceduralBuildInfos != null) {
            vkCmdBuildAccelerationStructuresKHR(
                    uploadBuildCmd.buffer(), proceduralBuildInfos, proceduralBuildRanges);
        }
        
        // Add memory barrier for AS build synchronization
        encodeASBuildMemoryBarrier(uploadBuildCmd, stack);
        uploadBuildCmd.writeTimestamp(timestampQueryPool, BUILD_TIMESTAMP_END,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
        
        if (COMPACT_BLAS) {
            // Query pool operations
            queryBuilder.resetQueryPool(uploadBuildCmd, jobs.size());
            queryBuilder.writeASProperties(uploadBuildCmd, pAccelerationStructures, stack);
            queryBuilder.encodeQueryPoolHostVisibilityBarrier(uploadBuildCmd, stack);
        }
        
        // Submit and wait for build to complete
        LOGGER.debug("[BLAS Batch #{}] Submitting build command", batchNumber);
        long submitStartTime = System.nanoTime();
        CompletableFuture<Long> buildExecutionFuture = context.cmd.enqueueSubmission(asyncQueue, uploadBuildCmdRef);
        long submitTime = System.nanoTime() - submitStartTime;
        long buildExecution;
        long buildGpuNanos;
        try {
            long waitStartTime = System.nanoTime();
            buildExecution = buildExecutionFuture.get();
            long waitTime = System.nanoTime() - waitStartTime;
            long buildTime = System.nanoTime() - buildStartTime;
            buildGpuNanos = readBuildGpuNanos();
            LOGGER.debug("[BLAS Batch #{}] Build completed (execution={}) enqueue={} ms, submissionWait={} ms, buildStage={} ms, gpuBuild={} ms",
                    batchNumber, buildExecution, formatMillis(submitTime), formatMillis(waitTime),
                    formatMillis(buildTime), formatMillis(buildGpuNanos));
        } catch (Exception e) {
            LOGGER.error("[BLAS Batch #{}] Failed while waiting for build submission", batchNumber, e);
            discardProceduralBuilds(proceduralBuilds);
            throw new RuntimeException(e);
        }
        
        uploadBuildCmdRef.close();
        
        if (COMPACT_BLAS) {
            // Read and validate compacted sizes
            long queryReadStartTime = System.nanoTime();
            long[] compactedSizes;
            try {
                compactedSizes = queryBuilder.readCompactedSizes(jobs.size());
                long queryReadTime = (System.nanoTime() - queryReadStartTime) / 1_000_000;
                LOGGER.debug("[BLAS Batch #{}] Query read completed in {} ms", batchNumber, queryReadTime);

                // Validate compacted sizes
                queryBuilder.validateCompactedSizes(compactedSizes, batchNumber);
            } catch (Exception e) {
                LOGGER.error("[BLAS Batch #{}] Error reading query pool results", batchNumber, e);
                throw e;
            }

            // Compact and publish results (compactor handles closing source AS)
            compactor.compactAndPublish(jobs, accelerationStructures, proceduralBuilds, compactedSizes,
                singleUsePoolWorker, stack, priorExecutions, batchNumber);
        } else {
            publishBuiltResults(jobs, accelerationStructures, proceduralBuilds,
                    buildExecution, priorExecutions, batchNumber);
        }

        long totalBatchNanos = System.nanoTime() - batchStartTime;
        long now = System.nanoTime();
        if (totalBatchNanos >= SLOW_BATCH_LOG_NANOS
                && now - lastInfoLogNanos >= INFO_LOG_INTERVAL_NANOS) {
            lastInfoLogNanos = now;
            LOGGER.info("[BLAS Batch #{}] Completed {} jobs in {} ms total, gpuBuild={} ms",
                    batchNumber, jobs.size(), formatMillis(totalBatchNanos), formatMillis(buildGpuNanos));
        } else {
            LOGGER.debug("[BLAS Batch #{}] Completed {} jobs in {} ms total, gpuBuild={} ms",
                    batchNumber, jobs.size(), formatMillis(totalBatchNanos), formatMillis(buildGpuNanos));
        }
    }

    private static String formatMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static int configuredMaxBatchSize() {
        return Math.max(1, Math.min(16, Integer.getInteger("vulkanite.blasBatchSize", 8)));
    }

    private long readBuildGpuNanos() {
        long[] timestamps = timestampQueryPool.get().getResultsLong(
                BUILD_TIMESTAMP_START, 2, VK_QUERY_RESULT_WAIT_BIT);
        return timestampDeltaNanos(timestamps[0], timestamps[1]);
    }

    private long timestampDeltaNanos(long start, long end) {
        if (end <= start) {
            return 0L;
        }
        return Math.round((end - start) * (double) context.properties.timestampPeriodNanos);
    }

    private void publishBuiltResults(
            List<BLASBuildJob> jobs,
            List<VRef<VAccelerationStructure>> accelerationStructures,
            List<ProceduralBLASBuild> proceduralBuilds,
            long buildExecution,
            Deque<Long> priorExecutions,
            int batchNumber) {
        if (jobs.size() != accelerationStructures.size()) {
            throw new IllegalStateException("Array size mismatch: jobs=" + jobs.size()
                    + ", accelerationStructures=" + accelerationStructures.size());
        }

        List<BLASBuildResult> results = new ArrayList<>(jobs.size());
        for (int i = 0; i < jobs.size(); i++) {
            results.add(createBuildResult(
                    accelerationStructures.get(i), jobs.get(i), proceduralBuilds.get(i)));
        }

        resultConsumer.accept(new BLASBatchResult(results, buildExecution));
        LOGGER.debug("[BLAS Batch #{}] Published {} uncompacted results, enqueueToPublish avg={} ms, max={} ms",
                batchNumber, results.size(), formatMillis(averageEnqueueToPublishNanos(results)),
                formatMillis(maxEnqueueToPublishNanos(results)));

        priorExecutions.add(buildExecution);
        if (priorExecutions.size() > 3) {
            long prior = priorExecutions.poll();
            LOGGER.debug("[BLAS Batch #{}] Waiting for prior execution {}", batchNumber, prior);
            context.cmd.hostWaitForExecution(asyncQueue, prior);
        }
    }

    static BLASBuildResult createBuildResult(
            VRef<VAccelerationStructure> triangleStructure,
            BLASBuildJob job,
            ProceduralBLASBuild proceduralBuild) {
        Optional<VRef<ProceduralBLAS>> procedural = Optional.empty();
        if (job.proceduralDisposition() == ProceduralBLASDisposition.REPLACE) {
            if (proceduralBuild == null) {
                throw new IllegalStateException("Missing procedural build for REPLACE result");
            }
            procedural = Optional.of(proceduralBuild.complete());
        } else if (proceduralBuild != null) {
            throw new IllegalStateException("Unexpected procedural build for " + job.proceduralDisposition());
        }
        return new BLASBuildResult(
                triangleStructure, job.data(), procedural, job.proceduralDisposition());
    }

    static void discardProceduralBuilds(List<ProceduralBLASBuild> builds) {
        for (ProceduralBLASBuild build : builds) {
            if (build != null) {
                build.discard();
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

    public void cleanup() {
        compactor.cleanup();
        timestampQueryPool.close();
    }
}
