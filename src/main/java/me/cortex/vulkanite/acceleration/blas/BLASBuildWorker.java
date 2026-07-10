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
import java.util.concurrent.locks.LockSupport;
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
    private static final long BLAS_BATCH_COOLDOWN_NANOS =
            Long.getLong("vulkanite.blasCooldownMs", 2L) * 1_000_000L;
    private static final long BLAS_SLOW_BATCH_COOLDOWN_NANOS =
            Long.getLong("vulkanite.blasSlowCooldownMs", 6L) * 1_000_000L;
    private static final long BLAS_SLOW_BATCH_NANOS =
            Long.getLong("vulkanite.blasSlowBatchMs", 40L) * 1_000_000L;
    private static final long MEMORY_PRESSURE_WARN_INTERVAL_NANOS = 10_000_000_000L;
    private static final boolean FORCE_GC_ON_MEMORY_PRESSURE =
            Boolean.getBoolean("vulkanite.blasForceGcOnMemoryPressure");
    private static final double MEMORY_PRESSURE_WARN_PERCENT =
            Double.parseDouble(System.getProperty("vulkanite.blasMemoryPressureWarnPercent", "8.0"));
    
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
    private long lastMemoryPressureWarnNanos;
    private volatile boolean shutdownRequested;
    
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

        try {
            while (true) {
                try {
                    if (!collectJobs(jobs)) {
                        break;
                    }
                    VRegistry.INSTANCE.threadLocalCollect();
                    var singleUsePoolWorker = context.cmd.getSingleUsePool();

                    // Log memory usage before processing
                    logMemoryUsage("before batch");

                    try (var stack = bigStack.push()) {
                        var buildContext = memoryManager.createBuildContext(jobs, stack);
                        long batchStartNanos = System.nanoTime();
                        batchProcessor.processBatch(buildContext, singleUsePoolWorker, priorExecutions, stack);
                        jobs.clear();
                        totalBatchesProcessed++;
                        throttleAfterBatch(System.nanoTime() - batchStartNanos);
                    }

                    // Reset allocators for next batch
                    memoryManager.reset();

                    // Log memory after processing
                    logMemoryUsage("after batch");

                    // Check and handle memory pressure
                    checkAndHandleMemoryPressure();
                } catch (Throwable t) {
                    if (shutdownRequested && t instanceof InterruptedException) {
                        break;
                    }
                    LOGGER.error("[BLAS Worker] Batch failed; dropping batch and keeping worker alive", t);
                    memoryManager.reset();
                    closeJobs(jobs);
                    jobs.clear();
                }
            }
        } finally {
            closeQueuedJobs();
            memoryManager.cleanup();
            batchProcessor.cleanup();
            VRegistry.INSTANCE.threadLocalCollect();
            LOGGER.info("[BLAS Worker] Worker thread stopped after {} batches", totalBatchesProcessed);
        }
    }

    public void requestShutdown() {
        shutdownRequested = true;
        awaitingJobBatches.release();
    }
    
    /**
     * Collects jobs from the batched queue.
     * Blocks until at least one job is available, then tries to collect more.
     */
    private boolean collectJobs(List<BLASBuildJob> jobs) throws InterruptedException {
        jobs.clear();
        if (shutdownRequested) {
            closeQueuedJobs();
            return false;
        }
        awaitingJobBatches.acquire();
        if (shutdownRequested) {
            closeQueuedJobs();
            return false;
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
        return !jobs.isEmpty();
    }
    
    /**
     * Checks for memory pressure and handles it by forcing garbage collection.
     * Called after each batch to prevent memory accumulation.
     */
    private void checkAndHandleMemoryPressure() {
        long freeMemory = Runtime.getRuntime().freeMemory();
        long maxMemory = Runtime.getRuntime().maxMemory();
        double freePercent = (freeMemory * 100.0) / maxMemory;
        
        if (freePercent < MEMORY_PRESSURE_WARN_PERCENT) {
            long now = System.nanoTime();
            if (now - lastMemoryPressureWarnNanos < MEMORY_PRESSURE_WARN_INTERVAL_NANOS) {
                return;
            }
            lastMemoryPressureWarnNanos = now;
            if (FORCE_GC_ON_MEMORY_PRESSURE) {
                LOGGER.warn("[BLAS Worker] Memory pressure detected ({}% free), forcing cleanup",
                        String.format("%.1f", freePercent));
                System.gc();
            } else {
                LOGGER.warn("[BLAS Worker] Memory pressure detected ({}% free); automatic System.gc() is disabled",
                        String.format("%.1f", freePercent));
            }
        }
    }

    private void throttleAfterBatch(long batchNanos) {
        if (batchedJobs.isEmpty()) {
            return;
        }
        long cooldownNanos = batchNanos >= BLAS_SLOW_BATCH_NANOS
                ? BLAS_SLOW_BATCH_COOLDOWN_NANOS
                : BLAS_BATCH_COOLDOWN_NANOS;
        if (cooldownNanos > 0) {
            LockSupport.parkNanos(cooldownNanos);
        }
    }

    /**
     * Logs current memory usage.
     */
    private void logMemoryUsage(String phase) {
        if (!LOGGER.isTraceEnabled()) return;
        
        long freeMemory = Runtime.getRuntime().freeMemory();
        long totalMemory = Runtime.getRuntime().totalMemory();
        long maxMemory = Runtime.getRuntime().maxMemory();
        
        LOGGER.trace("[BLAS Worker] Memory {}: free={}MB, total={}MB, max={}MB",
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

    public static void closeJobs(List<BLASBuildJob> jobs) {
        for (BLASBuildJob job : jobs) {
            job.data().geometryBuffer().close();
            job.proceduralInput().ifPresent(ProceduralBLASInput::close);
        }
    }

    private void closeQueuedJobs() {
        List<BLASBuildJob> batch;
        while ((batch = batchedJobs.poll()) != null) {
            closeJobs(batch);
        }
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
