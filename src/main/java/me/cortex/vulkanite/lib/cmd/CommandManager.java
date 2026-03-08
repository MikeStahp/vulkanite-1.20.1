package me.cortex.vulkanite.lib.cmd;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.function.Consumer;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.other.DeviceLostException;
import me.cortex.vulkanite.lib.other.VUtil;
import me.cortex.vulkanite.lib.other.sync.VFence;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_TIMEOUT;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkGetSemaphoreCounterValue;
import static org.lwjgl.vulkan.VK12.vkWaitSemaphores;

//Manages multiple command queues and fence synchronizations
public class CommandManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandManager.class);
    /**
     * Thread-safe queue for pending submissions from non-render threads.
     * All submissions are processed on the render thread to ensure thread safety.
     */
    private final ConcurrentLinkedQueue<CommandSubmissionRequest> pendingSubmissions = new ConcurrentLinkedQueue<>();
    // Frame pacing control
    private long lastFrameTime = System.nanoTime();
    private long targetFrameTimeNs = 16666667; // 60 FPS target
    private boolean framePacingEnabled = false;
    private final VkDevice device;
    private final Queue[] queues;
    private final ThreadLocal<VRef<VCommandPool>> threadLocalPool = ThreadLocal.withInitial(() -> {
        var pool = createSingleUsePool();
        pool.get().setDebugUtilsObjectName("Thread-local single use pool");
        return pool;
    });

    public CommandManager(VkDevice device, int queues) {
        this.device = device;
        this.queues = new Queue[queues];
        for (int i = 0; i < queues; i++) {
            this.queues[i] = new Queue(i, device);
        }
    }

    public VRef<VCommandPool> createSingleUsePool() {
        return createPool(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
    }

    public VRef<VCommandPool> createPool(int flags) {
        return new VRef<>(new VCommandPool(device, flags));
    }

    public VCommandPool getSingleUsePool() {
        return threadLocalPool.get().get();
    }

    public VRef<VCommandPool> createSingleUsePool(int queueFamilyIndex) {
        return createPool(queueFamilyIndex, VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
    }

    public VRef<VCommandPool> createPool(int family, int flags) {
        return new VRef<>(new VCommandPool(device, family, flags));
    }

    public void submitOnceAndWait(int queueId, final VRef<VCmdBuff> cmdBuff) {
        LOGGER.debug("submitOnceAndWait called with queueId: {}", queueId);
        long exec = this.submit(queueId, cmdBuff);
        LOGGER.debug("Submitted execution ID: {}", exec);
        this.hostWaitForExecution(queueId, exec);
        LOGGER.debug("hostWaitForExecution completed");
        cmdBuff.close();
    }

    public void executeWait(Consumer<VCmdBuff> cmdbuf) {
        LOGGER.debug("executeWait called");
        var cmd = getSingleUsePool().createCommandBuffer();
        cmdbuf.accept(cmd.get());
        LOGGER.debug("Submitting command buffer in executeWait");
        submitOnceAndWait(0, cmd);
        LOGGER.debug("executeWait completed");
    }

    /**
     * Enqueues a wait for a timeline value on a queue
     *
     * @param waitQueueId      The queue that will wait
     * @param executionQueueId The queue whose timeline value will be waited for
     * @param execution        The timeline value to wait for
     */
    public void queueWaitForExeuction(int waitQueueId, int executionQueueId, long execution) {
        queues[waitQueueId].waitForExecution(executionQueueId, execution);
    }

    /**
     * Enqueues a wait for a timeline value on a queue
     *
     * @param waitQueueId      The queue that will wait
     * @param executionQueueId The queue whose timeline value will be waited for
     * @param executions       The timeline values to wait for
     */
    public void queueWaitForExecutions(int waitQueueId, int executionQueueId, List<Long> executions) {
        queues[waitQueueId].waitForExecutions(executionQueueId, executions);
    }

    /**
     * Wait on the host for a timeline value on a queue
     *
     * @param waitQueueId The queue whose timeline value will be waited for
     * @param execution   The timeline value to wait for
     */
    public void hostWaitForExecution(int waitQueueId, long execution) {
        var waitQueue = queues[waitQueueId];
        LOGGER.debug("hostWaitForExecution START: queueId={}, execution={}, thread={}",
                waitQueueId, execution, Thread.currentThread().getName());
        LOGGER.debug("Current completedTimestamp={}, current timeline={}",
                waitQueue.completedTimestamp.get(), waitQueue.timeline.get());

        try (var stack = stackPush()) {
            VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack)
                    .sType$Default()
                    .pSemaphores(stack.longs(waitQueue.timelineSema.get().address()))
                    .semaphoreCount(1)
                    .pValues(stack.longs(execution));

            LOGGER.debug("About to call vkWaitSemaphores with timeout=-1 (infinite)");
            long waitStart = System.nanoTime();
            _CHECK_(vkWaitSemaphores(device, waitInfo, -1));
            long waitDuration = (System.nanoTime() - waitStart) / 1_000_000;
            LOGGER.debug("vkWaitSemaphores completed after {}ms", waitDuration);
        }

        waitQueue.updateCompletedTimestamp(execution);
        LOGGER.debug("Updated completedTimestamp to {}", execution);
        waitQueue.collect();
        LOGGER.debug("hostWaitForExecution END");
    }

    public long getQueueCurrentExecution(int queueId) {
        return queues[queueId].getCurrentExecution();
    }

    public long submit(int queueId, final VRef<VCmdBuff> cmdBuff) {
        return submit(queueId, cmdBuff, null, null, null);
    }

    public long submit(int queueId, final VRef<VCmdBuff> cmdBuff, List<VRef<VSemaphore>> waits,
            List<VRef<VSemaphore>> triggers, VFence fence) {
        if (queueId == 0) {
            RenderSystem.assertOnRenderThread();
        }
        return queues[queueId].submit(cmdBuff, queues, waits, triggers, fence);
    }

    /**
     * Enqueues a submission request for processing on the render thread.
     * This method is thread-safe and can be called from any thread.
     * The submission will be processed when {@link #processPendingSubmissions()} is
     * called.
     *
     * @param queueId  The queue index to submit to
     * @param cmdBuff  The command buffer to submit
     * @param waits    Wait semaphores (can be null)
     * @param triggers Signal semaphores (can be null)
     * @param fence    Fence to signal on completion (can be null)
     * @return CompletableFuture that completes when the submission finishes
     */
    public CompletableFuture<Long> enqueueSubmission(
            int queueId,
            final VRef<VCmdBuff> cmdBuff,
            @Nullable List<VRef<VSemaphore>> waits,
            @Nullable List<VRef<VSemaphore>> triggers,
            @Nullable VFence fence) {
        var request = new CommandSubmissionRequest(queueId, cmdBuff, waits, triggers, fence);
        pendingSubmissions.add(request);
        return request.getCompletionFuture();
    }

    /**
     * Enqueues a simple submission without semaphores or fence.
     *
     * @param queueId The queue index to submit to
     * @param cmdBuff The command buffer to submit
     * @return CompletableFuture that completes when the submission finishes
     */
    public CompletableFuture<Long> enqueueSubmission(int queueId, final VRef<VCmdBuff> cmdBuff) {
        return enqueueSubmission(queueId, cmdBuff, null, null, null);
    }

    /**
     * Processes all pending submission requests on the render thread.
     * This method must be called from the render thread only.
     * It drains the pending submissions queue and submits each request.
     */
    public void processPendingSubmissions() {
        RenderSystem.assertOnRenderThread();

        CommandSubmissionRequest request;
        while ((request = pendingSubmissions.poll()) != null) {
            try {
                // Submit on the render thread
                long timelineValue = submit(
                        request.getQueueIndex(),
                        request.getCommandBuffer(),
                        request.getWaitSemaphores(),
                        request.getSignalSemaphores(),
                        request.getFence());

                // FIX: Wait for GPU to complete before completing the future.
                // This ensures that any query pool results or other GPU-written data
                // are ready before the waiting thread (e.g., BLASBuildWorker) proceeds.
                // Without this wait, VK_ERROR_DEVICE_LOST can occur when the GPU is
                // still processing heavy workloads (e.g., ray tracing) while the CPU
                // tries to read query results.
                // Use timeout instead of infinite wait to avoid blocking render thread
                // indefinitely
                long timeoutNs = 500_000_000L; // 500ms timeout
                int maxAttempts = 2;
                int attempt = 0;
                int waitResult = VK_SUCCESS;

                try (var stack = stackPush()) {
                    var queue = queues[request.getQueueIndex()];
                    VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack)
                            .sType$Default()
                            .pSemaphores(stack.longs(queue.timelineSema.get().address()))
                            .semaphoreCount(1)
                            .pValues(stack.longs(timelineValue));

                    while (attempt < maxAttempts) {
                        waitResult = vkWaitSemaphores(device, waitInfo, timeoutNs);
                        if (waitResult == VK_SUCCESS) {
                            break;
                        } else if (waitResult == VK_TIMEOUT) {
                            attempt++;
                            LOGGER.warn("[CommandManager] GPU wait timeout (attempt {}/{}), timeline value: {}",
                                    attempt, maxAttempts, timelineValue);
                            if (attempt >= maxAttempts) {
                                LOGGER.error("[CommandManager] GPU wait failed after {} attempts, possible device hang",
                                        maxAttempts);
                                // CRITICAL FIX: Disable Vulkanite and throw DeviceLostException
                                // to prevent cascade of failures from subsequent submissions
                                Vulkanite.IS_ENABLED = false;
                                var deviceLostException = new DeviceLostException(
                                        "GPU hang detected after " + maxAttempts + " timeout attempts");
                                request.completeExceptionally(deviceLostException);
                                throw deviceLostException;
                            }
                        } else {
                            // Actual error - likely device lost
                            LOGGER.error("[CommandManager] vkWaitSemaphores failed with error: {}", waitResult);
                            Vulkanite.IS_ENABLED = false;
                            var deviceLostException = new DeviceLostException(
                                    "vkWaitSemaphores failed with error: " + waitResult);
                            request.completeExceptionally(deviceLostException);
                            throw deviceLostException;
                        }
                    }

                    if (waitResult != VK_SUCCESS) {
                        continue; // Move to next request
                    }
                    queue.updateCompletedTimestamp(timelineValue);
                    queue.collect();
                }

                // Complete the future to notify the waiting thread (GPU work is now done)
                request.complete(timelineValue);
            } catch (Exception e) {
                // Complete exceptionally on error
                request.completeExceptionally(e);
            }
        }
    }

    public void waitQueueIdle(int queue) {
        queues[queue].waitIdle();
        queues[queue].collect();
    }

    public void newFrame() {
        if (framePacingEnabled) {
            // Implement frame pacing to maintain target frame rate
            long currentTime = System.nanoTime();
            long frameTime = currentTime - lastFrameTime;

            if (frameTime < targetFrameTimeNs) {
                long sleepTime = (targetFrameTimeNs - frameTime) / 1000000; // Convert to milliseconds
                if (sleepTime > 1) {
                    try {
                        Thread.sleep(sleepTime - 1); // Sleep slightly less to account for sleep inaccuracies
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                // Spin wait for the remaining time to be more precise
                while (System.nanoTime() - lastFrameTime < targetFrameTimeNs) {
                    Thread.onSpinWait();
                }
            }

            lastFrameTime = System.nanoTime();
        }

        for (var queue : queues) {
            queue.newFrame();
        }
    }

    /**
     * Enable or disable frame pacing
     * 
     * @param enabled Whether frame pacing should be enabled
     */
    public void setFramePacingEnabled(boolean enabled) {
        this.framePacingEnabled = enabled;
        if (enabled) {
            lastFrameTime = System.nanoTime();
        }
    }

    /**
     * Set target frame rate for frame pacing
     * 
     * @param targetFPS Target frames per second
     */
    public void setTargetFPS(int targetFPS) {
        if (targetFPS > 0) {
            this.targetFrameTimeNs = 1000000000L / targetFPS;
        }
    }

    private static class Queue {
        private record Submission(long t, VRef<VCmdBuff> ref) {
        }

        public final VkQueue queue;
        private final Int2LongMap waitingFor = new Int2LongOpenHashMap();
        private final List<Submission> submitted = new ArrayList<>();
        public final VRef<VSemaphore> timelineSema;
        // public final Deque<Long> frameTimestamps = new ArrayDeque<>(3);
        public AtomicLong timeline = new AtomicLong(1);
        public AtomicLong completedTimestamp = new AtomicLong(0);

        public Queue(int queueId, VkDevice device) {
            try (var stack = stackPush()) {
                var pQ = stack.pointers(0);
                vkGetDeviceQueue(device, 0, queueId, pQ);

                this.queue = new VkQueue(pQ.get(0), device);

                var timelineCreateInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                        .sType$Default()
                        .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                        .initialValue(0);

                var semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc(stack)
                        .sType$Default()
                        .pNext(timelineCreateInfo.address());

                var pSemaphore = stack.longs(0);
                vkCreateSemaphore(device, semaphoreCreateInfo, null, pSemaphore);
                this.timelineSema = VSemaphore.create(device, pSemaphore.get(0));
            }
        }

        public void updateCompletedTimestamp(long newTimestamp) {
            // completedTimestamp.atomicMax(newTimestamp);
            long current;
            do {
                current = completedTimestamp.get();
            } while (current < newTimestamp && !completedTimestamp.compareAndSet(current, newTimestamp));
        }

        public void newFrame() {
            // if (frameTimestamps.size() >= 3) {
            // long oldest = frameTimestamps.removeFirst();
            // completedTimestamp = Long.max(completedTimestamp, oldest);
            // }
            // frameTimestamps.addLast(completedTimestamp);

            updateCompletedTimestamp(getCurrentExecution());
            collect();
        }

        public void collect() {
            synchronized (submitted) {
                long completedTimestamp = this.completedTimestamp.get();

                submitted.removeIf((rec) -> {
                    if (rec.t <= completedTimestamp) {
                        rec.ref.close();
                        return true;
                    }
                    return false;
                });
            }
        }

        public void waitIdle() {
            synchronized (waitingFor) {
                vkQueueWaitIdle(queue);
                waitingFor.clear();
            }

            updateCompletedTimestamp(getCurrentExecution());
            collect();
        }

        public void waitForExecution(int execQueue, long execution) {
            synchronized (waitingFor) {
                long currentValue = waitingFor.getOrDefault(execQueue, 0);
                long newValue = Long.max(currentValue, execution);
                LOGGER.debug("Queue.waitForExecution: queueId={}, current={}, new={}, thread={}",
                        execQueue, currentValue, newValue, Thread.currentThread().getName());
                waitingFor.put(execQueue, newValue);
            }
        }

        public void waitForExecutions(int execQueue, List<Long> executions) {
            synchronized (waitingFor) {
                long currentValue = waitingFor.getOrDefault(execQueue, 0);
                long execMax = executions.stream().mapToLong(Long::longValue).max()
                        .orElse(currentValue);
                LOGGER.debug("Queue.waitForExecutions: queueId={}, current={}, max_from_list={}, size={}, thread={}",
                        execQueue, currentValue, execMax, executions.size(), Thread.currentThread().getName());
                waitingFor.put(execQueue, execMax);
            }
        }

        public synchronized long submit(final VRef<VCmdBuff> cmdBuff, Queue[] queues, List<VRef<VSemaphore>> waits,
                List<VRef<VSemaphore>> triggers, VFence fence) {
            LOGGER.debug("Queue.submit called");
            long t = timeline.getAndIncrement();
            LOGGER.debug("Assigned timeline value: {}", t);

            LOGGER.debug("Entering synchronized block for waitingFor");
            synchronized (waitingFor) {
                LOGGER.debug("Processing timelineWaitingEntries, waitingFor size={}, thread={}",
                        waitingFor.size(), Thread.currentThread().getName());
                var timelineWaitingEntries = new ArrayList<>(waitingFor.int2LongEntrySet());
                for (var entry : timelineWaitingEntries) {
                    if (entry.getLongValue() != 0) {
                        LOGGER.debug("Timeline wait entry: queueId={}, value={}",
                                entry.getIntKey(), entry.getLongValue());
                    }
                }
                timelineWaitingEntries.removeIf(e -> e.getLongValue() == 0);
                LOGGER.debug("After filtering: {} timeline waits", timelineWaitingEntries.size());
                waitingFor.clear();

                LOGGER.debug("Creating MemoryStack");
                try (var stack = stackPush()) {
                    LOGGER.debug("Calculating counts");
                    int waitCount = (waits == null ? 0 : waits.size()) + timelineWaitingEntries.size();
                    int triggerCount = (triggers == null ? 0 : triggers.size()) + 1;

                    LOGGER.debug("Allocating buffers: waitCount={}, triggerCount={}", waitCount, triggerCount);
                    LongBuffer waitSemaphores = waitCount > 0 ? stack.mallocLong(waitCount) : null;
                    LongBuffer signalSemaphores = triggerCount > 0 ? stack.mallocLong(triggerCount) : null;
                    LongBuffer waitTimelineValues = waitCount > 0 ? stack.mallocLong(waitCount) : null;
                    LongBuffer signalTimelineValues = triggerCount > 0 ? stack.mallocLong(triggerCount) : null;
                    IntBuffer waitStages = waitCount > 0 ? stack.mallocInt(waitCount) : null;

                    LOGGER.debug("Filling binary waits");
                    if (waits != null) {
                        for (var wait : waits) {
                            waitSemaphores.put(wait.get().address());
                            waitTimelineValues.put(0);
                            waitStages.put(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                            cmdBuff.get().addSemaphoreRef(wait);
                        }
                    }
                    LOGGER.debug("Filling binary triggers");
                    if (triggers != null) {
                        for (var trigger : triggers) {
                            signalSemaphores.put(trigger.get().address());
                            signalTimelineValues.put(0);
                            cmdBuff.get().addSemaphoreRef(trigger);
                        }
                    }
                    LOGGER.debug("Filling timeline waits");
                    for (var entry : timelineWaitingEntries) {
                        var sema = queues[entry.getIntKey()].timelineSema;
                        waitSemaphores.put(sema.get().address());
                        waitTimelineValues.put(entry.getLongValue());
                        waitStages.put(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                        cmdBuff.get().addSemaphoreRef(sema);
                    }
                    LOGGER.debug("Filling fixed timeline signal");
                    signalSemaphores.put(timelineSema.get().address());
                    signalTimelineValues.put(t);
                    cmdBuff.get().addSemaphoreRef(timelineSema);

                    LOGGER.debug("Rewinding buffers");
                    if (waitSemaphores != null)
                        waitSemaphores.rewind();
                    if (signalSemaphores != null)
                        signalSemaphores.rewind();
                    if (waitTimelineValues != null)
                        waitTimelineValues.rewind();
                    if (signalTimelineValues != null)
                        signalTimelineValues.rewind();
                    if (waitStages != null)
                        waitStages.rewind();

                    LOGGER.debug("Creating VkTimelineSemaphoreSubmitInfo");
                    var timelineSubmitInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                            .sType$Default()
                            .pWaitSemaphoreValues(waitTimelineValues)
                            .waitSemaphoreValueCount(waitCount)
                            .pSignalSemaphoreValues(signalTimelineValues)
                            .signalSemaphoreValueCount(triggerCount);

                    LOGGER.debug("Sealing command buffer");
                    var sealedBuffer = cmdBuff.get().seal();

                    LOGGER.debug("Creating VkSubmitInfo");
                    VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack)
                            .sType$Default()
                            .pCommandBuffers(stack.pointers(sealedBuffer))
                            .pWaitSemaphores(waitSemaphores)
                            .pWaitDstStageMask(waitStages)
                            .pSignalSemaphores(signalSemaphores)
                            .pNext(timelineSubmitInfo.address());

                    // Manually set counts because NIO buffer setters might not do it automatically
                    // in all LWJGL versions
                    MemoryUtil.memPutInt(submit.address() + VkSubmitInfo.WAITSEMAPHORECOUNT, waitCount);
                    MemoryUtil.memPutInt(submit.address() + VkSubmitInfo.SIGNALSEMAPHORECOUNT, triggerCount);
                    MemoryUtil.memPutInt(submit.address() + VkSubmitInfo.COMMANDBUFFERCOUNT, 1);

                    // Add debug logging before vkQueueSubmit
                    LOGGER.debug("About to call vkQueueSubmit");
                    LOGGER.debug("  Queue address: {}", queue);
                    LOGGER.debug("  Submit info address: {}", submit.address());
                    LOGGER.debug("  pNext address: {}", submit.get(0).pNext());
                    LOGGER.debug("  Fence address: {}", fence == null ? 0 : fence.address());
                    LOGGER.debug("  Command buffer count: {}", submit.get(0).commandBufferCount());
                    LOGGER.debug("  Wait semaphore count: {}", submit.get(0).waitSemaphoreCount());
                    LOGGER.debug("  Signal semaphore count: {}", submit.get(0).signalSemaphoreCount());

                    try {
                        int result = vkQueueSubmit(queue, submit, fence == null ? 0 : fence.address());
                        if (result != VK10.VK_SUCCESS) {
                            LOGGER.error("vkQueueSubmit failed with result: {} ({})",
                                    VUtil.translateVulkanResult(result), result);
                        } else {
                            LOGGER.debug("vkQueueSubmit successful");
                        }
                        VUtil._CHECK_(result);
                    } catch (DeviceLostException e) {
                        // Log the device loss error and rethrow
                        LOGGER.error("Device lost during command submission: {}", e.getMessage());
                        throw e;
                    } catch (AssertionError e) {
                        // Log other Vulkan errors
                        LOGGER.error("Vulkan error during command submission: {}", e.getMessage());
                        throw e;
                    }
                }
            }

            synchronized (submitted) {
                submitted.add(new Submission(t, cmdBuff.addRef()));
            }

            return t;
        }

        public long getCurrentExecution() {
            try (var stack = stackPush()) {
                var lp = stack.longs(0);
                vkGetSemaphoreCounterValue(queue.getDevice(), timelineSema.get().address(), lp);
                return lp.get(0);
            }
        }
    }
}
