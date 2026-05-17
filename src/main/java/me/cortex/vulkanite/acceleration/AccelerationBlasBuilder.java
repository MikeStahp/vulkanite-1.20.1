package me.cortex.vulkanite.acceleration;

//Multithreaded acceleration manager, builds blas's in a separate queue,
// then memory copies over to main, while doing compaction

import me.cortex.vulkanite.acceleration.blas.*;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.memory.AccelerationStructurePool;
import me.cortex.vulkanite.lib.other.VQueryPool;
import me.cortex.vulkanite.lib.pipeline.ComputePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import me.cortex.vulkanite.lib.shader.VShader;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;

import java.util.Collection;
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
        this.jobEnqueuer = new BLASJobEnqueuer(context, awaitingJobBatches, batchedJobs);

        // Start worker thread
        var worker = new BLASBuildWorker(
                context,
                asyncQueue,
                resultConsumer,
                queryPool,
                gpuVertexDecodePipeline,
                accelerationStructurePool,
                awaitingJobBatches,
                batchedJobs);
        Thread workerThread = new Thread(worker);
        workerThread.setName("Acceleration blas worker");
        workerThread.start();
    }

    public int getAsyncQueue() {
        return asyncQueue;
    }

    /**
     * Enqueues jobs of section blas builds.
     * NOTE: This is called from a different thread!
     */
    public void enqueue(Collection<ChunkBuildOutput> batch) {
        jobEnqueuer.enqueue(batch);
    }
}
