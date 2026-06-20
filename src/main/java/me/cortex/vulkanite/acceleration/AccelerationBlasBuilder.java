package me.cortex.vulkanite.acceleration;

//Multithreaded acceleration manager, builds blas's in a separate queue,
// then memory copies over to main, while doing compaction

import me.cortex.vulkanite.acceleration.blas.*;
import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.lib.base.VRegistry;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.AccelerationStructurePool;
import me.cortex.vulkanite.lib.other.VQueryPool;
import me.cortex.vulkanite.lib.pipeline.ComputePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import me.cortex.vulkanite.lib.shader.VShader;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Multithreaded acceleration structure builder for BLAS (Bottom-Level
 * Acceleration Structure).
 * Coordinates building, compaction, and batching of acceleration structures on
 * a dedicated worker thread.
 */
public class AccelerationBlasBuilder {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(AccelerationBlasBuilder.class);
    private static final long WORKER_JOIN_TIMEOUT_MS =
            Long.getLong("vulkanite.blasShutdownJoinMs", 5_000L);

    private final VContext context;
    private final int asyncQueue;
    private final Consumer<BLASBatchResult> resultConsumer;
    private final VRef<VQueryPool> queryPool;
    private final VRef<VComputePipeline> gpuVertexDecodePipeline;
    private final AccelerationStructurePool accelerationStructurePool;

    // Synchronization primitives shared with worker
    private final Semaphore awaitingJobBatches = new Semaphore(0);
    private final ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs = new ConcurrentLinkedDeque<>();

    private final BLASJobEnqueuer jobEnqueuer;
    private final BLASBuildWorker worker;
    private final Thread workerThread;
    private boolean destroyed;

    public AccelerationBlasBuilder(VContext context, int asyncQueue, Consumer<BLASBatchResult> resultConsumer) {
        this.queryPool = VQueryPool.create(context.device, 10000,
                VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR);
        this.context = context;
        this.asyncQueue = asyncQueue;
        this.resultConsumer = resultConsumer;
        this.accelerationStructurePool = new AccelerationStructurePool(context);

        // Create vertex decode compute pipeline
        var decodeShader = VShader.compileLoad(context, VertexDecodeShader.SOURCE, VK_SHADER_STAGE_COMPUTE_BIT);
        var decodePipeBuilder = new ComputePipelineBuilder();
        decodePipeBuilder.addPushConstantRange(8 * 3, 0);
        decodePipeBuilder.set(decodeShader.get().named());
        gpuVertexDecodePipeline = decodePipeBuilder.build(context);

        // Create job enqueuer
        this.jobEnqueuer = new BLASJobEnqueuer(context, asyncQueue, awaitingJobBatches, batchedJobs);

        // Start worker thread
        this.worker = new BLASBuildWorker(
                context,
                asyncQueue,
                resultConsumer,
                queryPool,
                gpuVertexDecodePipeline,
                accelerationStructurePool,
                awaitingJobBatches,
                batchedJobs);
        // Use 8MB stack size for the BLAS worker thread to prevent LWJGL MemoryStack overflow
        this.workerThread = new Thread(null, worker, "Acceleration blas worker", 8 * 1024 * 1024);
        workerThread.start();
    }

    public int getAsyncQueue() {
        return asyncQueue;
    }

    /**
     * Enqueues jobs of section blas builds.
     * NOTE: This is called from a different thread!
     */
    public synchronized void enqueue(List<ChunkBuildOutput> batch) {
        if (destroyed) {
            discardAccelerationGeometry(batch);
            return;
        }
        jobEnqueuer.enqueue(batch);
    }

    public synchronized void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;

        worker.requestShutdown();
        java.util.concurrent.locks.LockSupport.unpark(workerThread);
        boolean workerStopped = joinWorker();
        closeQueuedJobs();

        if (!workerStopped) {
            return;
        }

        context.cmd.waitQueueIdle(asyncQueue);
        queryPool.close();
        gpuVertexDecodePipeline.close();
        accelerationStructurePool.destroy();
        VRegistry.INSTANCE.threadLocalCollect();
    }

    private boolean joinWorker() {
        long deadlineNanos = System.nanoTime() + WORKER_JOIN_TIMEOUT_MS * 1_000_000L;
        boolean interrupted = false;
        while (workerThread.isAlive() && System.nanoTime() < deadlineNanos) {
            context.cmd.processPendingSubmissions();
            context.cmd.waitQueueIdle(asyncQueue);
            context.cmd.processPendingSubmissions();
            try {
                workerThread.join(50L);
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            }
        }

        if (workerThread.isAlive()) {
            LOGGER.warn("[BLAS Builder] Worker did not stop within {} ms; Vulkanite shutdown will continue with resources retained",
                    WORKER_JOIN_TIMEOUT_MS);
        } else {
            LOGGER.info("[BLAS Builder] Worker stopped during shutdown");
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return !workerThread.isAlive();
    }

    private void closeQueuedJobs() {
        List<BLASBuildJob> batch;
        while ((batch = batchedJobs.poll()) != null) {
            BLASBuildWorker.closeJobs(batch);
        }
    }

    private static void discardAccelerationGeometry(List<ChunkBuildOutput> batch) {
        for (ChunkBuildOutput output : batch) {
            ((IAccelerationBuildResult) output).setAccelerationGeometry(null);
        }
    }
}
