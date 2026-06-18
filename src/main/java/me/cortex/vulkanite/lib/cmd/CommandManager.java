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
    private static final long HOST_WAIT_SLICE_NS = 500_000_000L;
    private static final long HOST_WAIT_LOG_INTERVAL_NS = 2_000_000_000L;
    private static final long HOST_WAIT_TIMEOUT_NS = 10_000_000_000L;
    /**
     * Thread-safe queue for pending submissions from non-render threads.
     * All submissions are processed on the render thread to ensure thread safety.
     */
    private final ConcurrentLinkedQueue<CommandSubmissionRequest> pendingSubmissions = new ConcurrentLinkedQueue<>();
    private final List<InFlightSubmission> inFlightSubmissions = new ArrayList<>();
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
        LOGGER.trace("submitOnceAndWait called with queueId: {}", queueId);
        long exec = this.submit(queueId, cmdBuff);
        LOGGER.trace("Submitted execution ID: {}", exec);
        this.hostWaitForExecution(queueId, exec);
        LOGGER.trace("hostWaitForExecution completed");
        cmdBuff.close();
    }

    public void executeWait(Consumer<VCmdBuff> cmdbuf) {
        LOGGER.trace("executeWait called");
        var cmd = getSingleUsePool().createCommandBuffer();
        cmdbuf.accept(cmd.get());
        LOGGER.trace("Submitting command buffer in executeWait");
        submitOnceAndWait(0, cmd);
        LOGGER.trace("executeWait completed");
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
        LOGGER.trace("hostWaitForExecution START: queueId={}, execution={}, thread={}",
                waitQueueId, execution, Thread.currentThread().getName());
        LOGGER.trace("Current completedTimestamp={}, current timeline={}",
                waitQueue.completedTimestamp.get(), waitQueue.timeline.get());

        try (var stack = stackPush()) {
            VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack)
                    .sType$Default()
                    .pSemaphores(stack.longs(waitQueue.timelineSema.get().address()))
                    .semaphoreCount(1)
                    .pValues(stack.longs(execution));

            long waitStart = System.nanoTime();
            long lastWaitLog = waitStart;
            while (true) {
                int result = vkWaitSemaphores(device, waitInfo, HOST_WAIT_SLICE_NS);
                if (result == VK_SUCCESS) {
                    break;
                }

                long elapsedNs = System.nanoTime() - waitStart;
                if (result == VK_TIMEOUT) {
                    if (elapsedNs >= HOST_WAIT_TIMEOUT_NS) {
                        Vulkanite.IS_ENABLED = false;
                        throw new DeviceLostException("Timed out waiting for queue " + waitQueueId
                                + " execution " + execution + " after " + (elapsedNs / 1_000_000) + "ms");
                    }
                    long now = System.nanoTime();
                    if (now - lastWaitLog >= HOST_WAIT_LOG_INTERVAL_NS) {
                        LOGGER.warn("Waiting for queue {} execution {} for {}ms",
                                waitQueueId, execution, elapsedNs / 1_000_000);
                        lastWaitLog = now;
                    }
                    continue;
                }

                Vulkanite.IS_ENABLED = false;
                _CHECK_(result);
            }
            long waitDuration = (System.nanoTime() - waitStart) / 1_000_000;
            LOGGER.trace("vkWaitSemaphores completed after {}ms", waitDuration);
        }

        waitQueue.updateCompletedTimestamp(execution);
        LOGGER.trace("Updated completedTimestamp to {}", execution);
        waitQueue.collect();
        LOGGER.trace("hostWaitForExecution END");
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
     * It drains the pending submissions queue and polls earlier submissions for GPU
     * completion without blocking the render thread.
     */
    public void processPendingSubmissions() {
        RenderSystem.assertOnRenderThread();

        completeFinishedSubmissions();

        CommandSubmissionRequest request;
        while ((request = pendingSubmissions.poll()) != null) {
            try {
                long timelineValue = submit(
                        request.getQueueIndex(),
                        request.getCommandBuffer(),
                        request.getWaitSemaphores(),
                        request.getSignalSemaphores(),
                        request.getFence());
                inFlightSubmissions.add(new InFlightSubmission(request, timelineValue));
            } catch (Exception e) {
                request.completeExceptionally(e);
            }
        }

        completeFinishedSubmissions();
    }

    private void completeFinishedSubmissions() {
        if (inFlightSubmissions.isEmpty()) {
            return;
        }

        long[] observedExecutions = new long[queues.length];
        Arrays.fill(observedExecutions, Long.MIN_VALUE);

        inFlightSubmissions.removeIf(submission -> {
            int queueIndex = submission.request().getQueueIndex();
            var queue = queues[queueIndex];
            long completed = queue.completedTimestamp.get();

            if (completed < submission.timelineValue()) {
                if (observedExecutions[queueIndex] == Long.MIN_VALUE) {
                    observedExecutions[queueIndex] = queue.getCurrentExecution();
                    queue.updateCompletedTimestamp(observedExecutions[queueIndex]);
                    queue.collect();
                }
                completed = queue.completedTimestamp.get();
            }

            if (completed >= submission.timelineValue()) {
                submission.request().complete(submission.timelineValue());
                return true;
            }
            return false;
        });
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

        completeFinishedSubmissions();
    }

    private record InFlightSubmission(CommandSubmissionRequest request, long timelineValue) {
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
                LOGGER.trace("Queue.waitForExecution: queueId={}, current={}, new={}, thread={}",
                        execQueue, currentValue, newValue, Thread.currentThread().getName());
                waitingFor.put(execQueue, newValue);
            }
        }

        public void waitForExecutions(int execQueue, List<Long> executions) {
            synchronized (waitingFor) {
                long currentValue = waitingFor.getOrDefault(execQueue, 0);
                long execMax = executions.stream().mapToLong(Long::longValue).max()
                        .orElse(currentValue);
                LOGGER.trace("Queue.waitForExecutions: queueId={}, current={}, max_from_list={}, size={}, thread={}",
                        execQueue, currentValue, execMax, executions.size(), Thread.currentThread().getName());
                waitingFor.put(execQueue, execMax);
            }
        }

        public synchronized long submit(final VRef<VCmdBuff> cmdBuff, Queue[] queues, List<VRef<VSemaphore>> waits,
                List<VRef<VSemaphore>> triggers, VFence fence) {
            LOGGER.trace("Queue.submit called");
            long t = timeline.getAndIncrement();
            LOGGER.trace("Assigned timeline value: {}", t);

            LOGGER.trace("Entering synchronized block for waitingFor");
            synchronized (waitingFor) {
                LOGGER.trace("Processing timelineWaitingEntries, waitingFor size={}, thread={}",
                        waitingFor.size(), Thread.currentThread().getName());
                var timelineWaitingEntries = new ArrayList<>(waitingFor.int2LongEntrySet());
                for (var entry : timelineWaitingEntries) {
                    if (entry.getLongValue() != 0) {
                        LOGGER.trace("Timeline wait entry: queueId={}, value={}",
                                entry.getIntKey(), entry.getLongValue());
                    }
                }
                timelineWaitingEntries.removeIf(e -> e.getLongValue() == 0);
                LOGGER.trace("After filtering: {} timeline waits", timelineWaitingEntries.size());
                waitingFor.clear();

                LOGGER.trace("Creating MemoryStack");
                try (var stack = stackPush()) {
                    LOGGER.trace("Calculating counts");
                    int waitCount = (waits == null ? 0 : waits.size()) + timelineWaitingEntries.size();
                    int triggerCount = (triggers == null ? 0 : triggers.size()) + 1;

                    LOGGER.trace("Allocating buffers: waitCount={}, triggerCount={}", waitCount, triggerCount);
                    LongBuffer waitSemaphores = waitCount > 0 ? stack.mallocLong(waitCount) : null;
                    LongBuffer signalSemaphores = triggerCount > 0 ? stack.mallocLong(triggerCount) : null;
                    LongBuffer waitTimelineValues = waitCount > 0 ? stack.mallocLong(waitCount) : null;
                    LongBuffer signalTimelineValues = triggerCount > 0 ? stack.mallocLong(triggerCount) : null;
                    IntBuffer waitStages = waitCount > 0 ? stack.mallocInt(waitCount) : null;

                    LOGGER.trace("Filling binary waits");
                    if (waits != null) {
                        for (var wait : waits) {
                            waitSemaphores.put(wait.get().address());
                            waitTimelineValues.put(0);
                            waitStages.put(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                            cmdBuff.get().addSemaphoreRef(wait);
                        }
                    }
                    LOGGER.trace("Filling binary triggers");
                    if (triggers != null) {
                        for (var trigger : triggers) {
                            signalSemaphores.put(trigger.get().address());
                            signalTimelineValues.put(0);
                            cmdBuff.get().addSemaphoreRef(trigger);
                        }
                    }
                    LOGGER.trace("Filling timeline waits");
                    for (var entry : timelineWaitingEntries) {
                        var sema = queues[entry.getIntKey()].timelineSema;
                        waitSemaphores.put(sema.get().address());
                        waitTimelineValues.put(entry.getLongValue());
                        waitStages.put(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                        cmdBuff.get().addSemaphoreRef(sema);
                    }
                    LOGGER.trace("Filling fixed timeline signal");
                    signalSemaphores.put(timelineSema.get().address());
                    signalTimelineValues.put(t);
                    cmdBuff.get().addSemaphoreRef(timelineSema);

                    LOGGER.trace("Rewinding buffers");
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

                    LOGGER.trace("Creating VkTimelineSemaphoreSubmitInfo");
                    var timelineSubmitInfo = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                            .sType$Default()
                            .pWaitSemaphoreValues(waitTimelineValues)
                            .waitSemaphoreValueCount(waitCount)
                            .pSignalSemaphoreValues(signalTimelineValues)
                            .signalSemaphoreValueCount(triggerCount);

                    LOGGER.trace("Sealing command buffer");
                    var sealedBuffer = cmdBuff.get().seal();

                    LOGGER.trace("Creating VkSubmitInfo");
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
                    LOGGER.trace("About to call vkQueueSubmit");
                    LOGGER.trace("  Queue address: {}", queue);
                    LOGGER.trace("  Submit info address: {}", submit.address());
                    LOGGER.trace("  pNext address: {}", submit.get(0).pNext());
                    LOGGER.trace("  Fence address: {}", fence == null ? 0 : fence.address());
                    LOGGER.trace("  Command buffer count: {}", submit.get(0).commandBufferCount());
                    LOGGER.trace("  Wait semaphore count: {}", submit.get(0).waitSemaphoreCount());
                    LOGGER.trace("  Signal semaphore count: {}", submit.get(0).signalSemaphoreCount());

                    try {
                        int result = vkQueueSubmit(queue, submit, fence == null ? 0 : fence.address());
                        if (result != VK10.VK_SUCCESS) {
                            LOGGER.error("vkQueueSubmit failed with result: {} ({})",
                                    VUtil.translateVulkanResult(result), result);
                        } else {
                            LOGGER.trace("vkQueueSubmit successful");
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
