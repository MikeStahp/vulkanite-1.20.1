package me.cortex.vulkanite.acceleration.blas;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.base.VRegistry;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.AccelerationStructurePool;
import me.cortex.vulkanite.lib.memory.PoolLinearAllocator;
import me.cortex.vulkanite.lib.other.VQueryPool;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Worker thread that processes queued BLAS build jobs in batches.
 * Orchestrates BLAS building using specialized components:
 * - BLASMemoryManager: Buffer allocator management
 * - BLASBatchProcessor: Batch processing logic
 * - BLASGeometryProcessor: Geometry processing (external)
 * 
 * This class is now a thin orchestrator that delegates to specialized components.
 */
public class BLASBuildWorker implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BLASBuildWorker.class);
    
    // Memory stack size: 16MB reduced from 64MB to prevent MemoryStack exhaustion
    // Large query results are now allocated on heap via MemoryUtil in VQueryPool
    private static final int MEMORY_STACK_SIZE = 16_000_000;
    
    private final VContext context;
    private final int asyncQueue;
    private final Consumer<BLASBatchResult> resultConsumer;
    private final VRef<VQueryPool> queryPool;
    private final VRef<VComputePipeline> gpuVertexDecodePipeline;
    private final AccelerationStructurePool accelerationStructurePool;
    
    private final Semaphore awaitingJobBatches;
    private final ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs;
    
    // Components
    private final BLASMemoryManager memoryManager;
    private final BLASBatchProcessor batchProcessor;
    
    private long totalBatchesProcessed = 0;
    
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
        
        // Initialize components
        this.memoryManager = new BLASMemoryManager(context);
        this.batchProcessor = new BLASBatchProcessor(
            context, asyncQueue, queryPool, gpuVertexDecodePipeline,
            accelerationStructurePool, resultConsumer);
        
        LOGGER.info("[BLAS Worker] Initialized with {} MB stack size", MEMORY_STACK_SIZE / (1024 * 1024));
    }
    
    @Override
    public void run() {
        MemoryStack bigStack = MemoryStack.create(MEMORY_STACK_SIZE);
        List<BLASBuildJob> jobs = new ArrayList<>();
        Deque<Long> priorExecutions = new ArrayDeque<>(3);

        LOGGER.info("[BLAS Worker] Starting worker thread");

        while (true) {
            try {
                collectJobs(jobs);
                VRegistry.INSTANCE.threadLocalCollect();
                var singleUsePoolWorker = context.cmd.getSingleUsePool();

                // Log memory usage before processing
                logMemoryUsage("before batch");

                try (var stack = bigStack.push()) {
                    var buildContext = memoryManager.createBuildContext(jobs, stack);
                    batchProcessor.processBatch(buildContext, singleUsePoolWorker, priorExecutions, stack);
                    totalBatchesProcessed++;
                }

                // Reset allocators for next batch
                memoryManager.reset();

                // Log memory after processing
                logMemoryUsage("after batch");

                // Check and handle memory pressure
                checkAndHandleMemoryPressure();
            } catch (Throwable t) {
                LOGGER.error("[BLAS Worker] Batch failed; dropping batch and keeping worker alive", t);
                memoryManager.reset();
                for (BLASBuildJob job : jobs) {
                    job.data().geometryBuffer().close();
                }
                jobs.clear();
            }
        }
    }
    
    /**
     * Collects jobs from the batched queue.
     * Blocks until at least one job is available, then tries to collect more.
     */
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
        
        // Try to collect more without blocking, but keep batches small enough to avoid
        // long GPU submissions and query-pool pressure.
        while (jobs.size() < BLASBatchProcessor.MAX_BATCH_SIZE && awaitingJobBatches.tryAcquire()) {
            batch = batchedJobs.poll();
            if (batch != null) {
                int remaining = BLASBatchProcessor.MAX_BATCH_SIZE - jobs.size();
                if (batch.size() <= remaining) {
                    jobs.addAll(batch);
                } else {
                    jobs.addAll(batch.subList(0, remaining));
                    batchedJobs.addFirst(new ArrayList<>(batch.subList(remaining, batch.size())));
                    awaitingJobBatches.release();
                }
            }
        }
    }
    
    /**
     * Checks for memory pressure and handles it by forcing garbage collection.
     * Called after each batch to prevent memory accumulation.
     */
    private void checkAndHandleMemoryPressure() {
        long freeMemory = Runtime.getRuntime().freeMemory();
        long maxMemory = Runtime.getRuntime().maxMemory();
        double freePercent = (freeMemory * 100.0) / maxMemory;
        
        // Force cleanup if memory pressure is high (less than 15% free)
        if (freePercent < 15.0) {
            LOGGER.warn("[BLAS Worker] Memory pressure detected ({}% free), forcing cleanup",
                    String.format("%.1f", freePercent));
            System.gc();
        }
    }

    /**
     * Logs current memory usage.
     */
    private void logMemoryUsage(String phase) {
        if (!LOGGER.isInfoEnabled()) return;
        
        long freeMemory = Runtime.getRuntime().freeMemory();
        long totalMemory = Runtime.getRuntime().totalMemory();
        long maxMemory = Runtime.getRuntime().maxMemory();
        
        LOGGER.info("[BLAS Worker] Memory {}: free={}MB, total={}MB, max={}MB",
            phase,
            freeMemory / (1024 * 1024),
            totalMemory / (1024 * 1024),
            maxMemory / (1024 * 1024));
    }
    
    /**
     * Gets the total number of batches processed.
     */
    public long getTotalBatchesProcessed() {
        return totalBatchesProcessed;
    }
    
    /**
     * Gets the batch processor component.
     */
    public BLASBatchProcessor getBatchProcessor() {
        return batchProcessor;
    }
    
    /**
     * Gets the memory manager component.
     */
    public BLASMemoryManager getMemoryManager() {
        return memoryManager;
    }
    
    /**
     * Context data for a BLAS build batch operation.
     * This class is kept for backward compatibility with BLASGeometryProcessor.
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
