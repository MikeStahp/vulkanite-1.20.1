package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.base.VRegistry;
import me.cortex.vulkanite.lib.memory.AccelerationStructurePool;
import me.cortex.vulkanite.lib.memory.PoolLinearAllocator;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.other.VQueryPool;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/**
 * Worker thread that processes queued BLAS build jobs in batches.
 * Handles vertex decoding, acceleration structure building, and compaction.
 */
public class BLASBuildWorker implements Runnable {
    private final VContext context;
    private final int asyncQueue;
    private final Consumer<BLASBatchResult> resultConsumer;
    private final VRef<VQueryPool> queryPool;
    private final VRef<VComputePipeline> gpuVertexDecodePipeline;
    private final AccelerationStructurePool accelerationStructurePool;

    private final Semaphore awaitingJobBatches;
    private final ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs;

    public BLASBuildWorker(
            VContext context,
            int asyncQueue,
            Consumer<BLASBatchResult> resultConsumer,
            VRef<VQueryPool> queryPool,
            VRef<VComputePipeline> gpuVertexDecodePipeline,
            AccelerationStructurePool accelerationStructurePool,
            Semaphore awaitingJobBatches,
            ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs) {
        this.context = context;
        this.asyncQueue = asyncQueue;
        this.resultConsumer = resultConsumer;
        this.queryPool = queryPool;
        this.gpuVertexDecodePipeline = gpuVertexDecodePipeline;
        this.accelerationStructurePool = accelerationStructurePool;
        this.awaitingJobBatches = awaitingJobBatches;
        this.batchedJobs = batchedJobs;
    }

    @Override
    public void run() {
        MemoryStack bigStack = MemoryStack.create(20_000_000);

        var buildBufferAllocator = new PoolLinearAllocator(context, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, 0x200_0000L, 16);
        var scratchAllocator = new PoolLinearAllocator(context,
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                0x200_0000L, 256);
        var initialASBufferAllocator = new PoolLinearAllocator(context,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                0x200_0000L, 256);

        List<BLASBuildJob> jobs = new ArrayList<>();
        Deque<Long> priorExecutions = new ArrayDeque<>(3);

        while (true) {
            collectJobs(jobs);
            VRegistry.INSTANCE.threadLocalCollect();
            var sinlgeUsePoolWorker = context.cmd.getSingleUsePool();

            try (var stack = bigStack.push()) {
                var buildContext = new BLASBuildContext(jobs, stack, buildBufferAllocator, scratchAllocator,
                        initialASBufferAllocator);
                processBuildBatch(buildContext, sinlgeUsePoolWorker, priorExecutions);
            }
        }
    }

    private void collectJobs(List<BLASBuildJob> jobs) {
        jobs.clear();
        try {
            awaitingJobBatches.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        var batch = batchedJobs.poll();
        if (batch != null) {
            jobs.addAll(batch);
        }
        // Try to collect more without blocking
        while (jobs.size() < 32 && awaitingJobBatches.tryAcquire()) {
            batch = batchedJobs.poll();
            if (batch != null) {
                jobs.addAll(batch);
            }
        }
    }

    private void processBuildBatch(BLASBuildContext buildCtx,
            me.cortex.vulkanite.lib.cmd.VCommandPool sinlgeUsePoolWorker,
            Deque<Long> priorExecutions) {
        var jobs = buildCtx.jobs;
        var stack = buildCtx.stack;

        var buildInfos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(jobs.size(), stack);
        PointerBuffer buildRanges = stack.mallocPointer(jobs.size());
        LongBuffer pAccelerationStructures = stack.mallocLong(jobs.size());

        var accelerationStructures = new ArrayList<VRef<VAccelerationStructure>>(jobs.size());

        var uploadBuildCmdRef = sinlgeUsePoolWorker.createCommandBuffer();
        var uploadBuildCmd = uploadBuildCmdRef.get();
        uploadBuildCmd.bindCompute(gpuVertexDecodePipeline);

        var geometryProcessor = new BLASGeometryProcessor(context, buildCtx, uploadBuildCmd);

        for (int i = 0; i < jobs.size(); i++) {
            var job = jobs.get(i);
            geometryProcessor.processJob(job, i, buildInfos, buildRanges, pAccelerationStructures,
                    accelerationStructures);
        }

        buildInfos.rewind();
        buildRanges.rewind();
        pAccelerationStructures.rewind();

        // Build acceleration structures
        vkCmdBuildAccelerationStructuresKHR(uploadBuildCmd.buffer(), buildInfos, buildRanges);
        uploadBuildCmd.encodeMemoryBarrier();
        uploadBuildCmd.resetQueryPool(queryPool, 0, jobs.size());
        vkCmdWriteAccelerationStructuresPropertiesKHR(
                uploadBuildCmd.buffer(),
                pAccelerationStructures,
                VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
                queryPool.get().pool,
                0);

        long buildExecution = context.cmd.submit(asyncQueue, uploadBuildCmdRef);
        context.cmd.hostWaitForExecution(asyncQueue, buildExecution);
        uploadBuildCmdRef.close();

        // Compact and finalize
        long[] compactedSizes = queryPool.get().getResultsLong(jobs.size());
        compactAccelerationStructures(jobs, accelerationStructures, compactedSizes,
                sinlgeUsePoolWorker, stack, priorExecutions);

        accelerationStructures.forEach(VRef::close);
    }

    private void compactAccelerationStructures(
            List<BLASBuildJob> jobs,
            List<VRef<VAccelerationStructure>> accelerationStructures,
            long[] compactedSizes,
            me.cortex.vulkanite.lib.cmd.VCommandPool sinlgeUsePoolWorker,
            MemoryStack stack,
            Deque<Long> priorExecutions) {

        List<BLASBuildResult> results = new ArrayList<>();
        var cmdRef = sinlgeUsePoolWorker.createCommandBuffer();

        for (int idx = 0; idx < compactedSizes.length; idx++) {
            var compact_as = accelerationStructurePool.createAcceleration(compactedSizes[idx],
                    VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            var fat_as = accelerationStructures.get(idx);

            vkCmdCopyAccelerationStructureKHR(cmdRef.get().buffer(),
                    VkCopyAccelerationStructureInfoKHR.calloc(stack).sType$Default()
                            .src(fat_as.get().structure)
                            .dst(compact_as.get().structure)
                            .mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR));

            cmdRef.get().addAccelerationStructureRef(fat_as);
            cmdRef.get().addAccelerationStructureRef(compact_as);
            fat_as.close();

            var job = jobs.get(idx);
            results.add(new BLASBuildResult(compact_as, job.data()));
        }

        long blasExecution = context.cmd.submit(asyncQueue, cmdRef);
        cmdRef.close();

        resultConsumer.accept(new BLASBatchResult(results, blasExecution));

        priorExecutions.add(blasExecution);
        if (priorExecutions.size() > 3) {
            long prior = priorExecutions.poll();
            context.cmd.hostWaitForExecution(asyncQueue, prior);
        }
    }

    /**
     * Context data for a BLAS build batch operation.
     */
    public static class BLASBuildContext {
        public final List<BLASBuildJob> jobs;
        public final MemoryStack stack;
        public final PoolLinearAllocator buildBufferAllocator;
        public final PoolLinearAllocator scratchAllocator;
        public final PoolLinearAllocator initialASBufferAllocator;

        public BLASBuildContext(List<BLASBuildJob> jobs, MemoryStack stack,
                PoolLinearAllocator buildBufferAllocator,
                PoolLinearAllocator scratchAllocator,
                PoolLinearAllocator initialASBufferAllocator) {
            this.jobs = jobs;
            this.stack = stack;
            this.buildBufferAllocator = buildBufferAllocator;
            this.scratchAllocator = scratchAllocator;
            this.initialASBufferAllocator = initialASBufferAllocator;
        }
    }
}
