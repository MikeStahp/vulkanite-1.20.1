package me.cortex.vulkanite.lib.cmd;

import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.other.sync.VFence;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Encapsulates a queue submission request for thread-safe command submission.
 * This class is used to serialize all vkQueueSubmit calls through the render thread,
 * preventing concurrent access issues from multiple threads.
 */
public class CommandSubmissionRequest {
    private final int queueIndex;
    private final VRef<VCmdBuff> commandBuffer;
    private final List<VRef<VSemaphore>> waitSemaphores;
    private final List<VRef<VSemaphore>> signalSemaphores;
    private final VFence fence;
    private final CompletableFuture<Long> completionFuture;

    /**
     * Creates a new command submission request.
     *
     * @param queueIndex      The index of the queue to submit to
     * @param commandBuffer   The command buffer to submit
     * @param waitSemaphores  Semaphores to wait on before execution (can be null)
     * @param signalSemaphores Semaphores to signal after execution (can be null)
     * @param fence           Fence to signal when submission completes (can be null)
     */
    public CommandSubmissionRequest(
            int queueIndex,
            VRef<VCmdBuff> commandBuffer,
            @Nullable List<VRef<VSemaphore>> waitSemaphores,
            @Nullable List<VRef<VSemaphore>> signalSemaphores,
            @Nullable VFence fence) {
        this.queueIndex = queueIndex;
        this.commandBuffer = commandBuffer;
        this.waitSemaphores = waitSemaphores;
        this.signalSemaphores = signalSemaphores;
        this.fence = fence;
        this.completionFuture = new CompletableFuture<>();
    }

    /**
     * @return The queue index to submit to
     */
    public int getQueueIndex() {
        return queueIndex;
    }

    /**
     * @return The command buffer to submit
     */
    public VRef<VCmdBuff> getCommandBuffer() {
        return commandBuffer;
    }

    /**
     * @return List of wait semaphores (may be null)
     */
    @Nullable
    public List<VRef<VSemaphore>> getWaitSemaphores() {
        return waitSemaphores;
    }

    /**
     * @return List of signal semaphores (may be null)
     */
    @Nullable
    public List<VRef<VSemaphore>> getSignalSemaphores() {
        return signalSemaphores;
    }

    /**
     * @return The fence to signal on completion (may be null)
     */
    @Nullable
    public VFence getFence() {
        return fence;
    }

    /**
     * @return CompletableFuture that will be completed when the submission finishes
     */
    public CompletableFuture<Long> getCompletionFuture() {
        return completionFuture;
    }

    /**
     * Completes the submission future with the timeline value.
     * Should only be called by CommandManager after the submission completes.
     *
     * @param timelineValue The timeline value assigned to this submission
     */
    void complete(long timelineValue) {
        completionFuture.complete(timelineValue);
    }

    /**
     * Completes the submission future exceptionally.
     * Should be called if the submission fails.
     *
     * @param throwable The exception that caused the failure
     */
    void completeExceptionally(Throwable throwable) {
        completionFuture.completeExceptionally(throwable);
    }
}
